package com.example.jtbs

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.PlayerView
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.InetAddress
import java.net.Socket
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

class TLSSocketFactory : SSLSocketFactory() {
    private val delegate: SSLSocketFactory

    init {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        })

        val context = SSLContext.getInstance("TLS")
        context.init(null, trustAllCerts, SecureRandom())
        delegate = context.socketFactory
    }

    override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
    override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites

    private fun enableTLSOnSocket(socket: Socket): Socket {
        if (socket is SSLSocket) {
            socket.enabledProtocols = arrayOf("TLSv1.1", "TLSv1.2")
        }
        return socket
    }

    override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket {
        return enableTLSOnSocket(delegate.createSocket(s, host, port, autoClose))
    }

    override fun createSocket(host: String, port: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port, localHost, localPort))
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(host, port))
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return enableTLSOnSocket(delegate.createSocket(address, port, localAddress, localPort))
    }
}

class MainActivity : Activity() {
    @Volatile
    private var playbackService: DecoderPlaybackService? = null
    @Volatile
    private var isBound = false

    private var playerView: PlayerView? = null
    private var webView: WebView? = null
    private var splashLayout: LinearLayout? = null
    private var offAirLayout: android.view.ViewGroup? = null
    private var offAirImageView: ImageView? = null
    private var loadingText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    @Volatile
    private var currentUrl: String? = null
    @Volatile
    private var currentIsLive: Boolean = false
    @Volatile
    private var currentLoadedEmbedUrl: String? = null

    // Resiliency tracking
    @Volatile
    private var consecutiveErrors = 0
    @Volatile
    private var hasPlayedOnce = false
    @Volatile
    private var isRetrying = false
    @Volatile
    private var lastState: StreamState? = null
    @Volatile
    private var lastPlayerError: String? = null
    private var lastRxBytes: Long = 0L
    private var lastRxTimestamp: Long = 0L

    @Volatile
    private var isStatusSending = false

    @Volatile
    private var isConfigFetching = false

    @Volatile
    private var streamStartTimeMs: Long = 0

    // Critical Fix 2: Surface-First synchronization flags
    // Playback can ONLY start when BOTH conditions are true.
    private var surfaceReady = false
    private var serviceBound = false
    private var pendingStartupUrl: String? = null

    // Fixes 1, 3, 5, 9: Generation, session IDs, video validation, switch debounce
    private var currentGeneration = 0
    private var activePlaybackSessionId = 0L
    @Volatile
    private var videoValidated = false
    private var lastSwitchTimestamp = 0L

    // Saved surface for handoff: surfaceCreated may fire before onServiceConnected,
    // so we save it here to pass reliably when the service bind completes.
    private var pendingSurface: android.view.Surface? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DecoderPlaybackService.LocalBinder
            playbackService = binder.getService()
            isBound = true
            serviceBound = true
            logStartup("SERVICE_BOUND")

            currentGeneration = playbackService?.getPlaybackGeneration() ?: 0
            activePlaybackSessionId = playbackService?.getActivePlaybackSessionId() ?: 0L
            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val timestamp = sdf.format(java.util.Date())
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] SERVICE_BOUND_SYNC (gen=$currentGeneration, session=$activePlaybackSessionId)")

            val surf = pendingSurface
            if (surf != null && surf.isValid) {
                logStartup("PASSING_SAVED_SURFACE")
                playbackService?.setSurface(surf)
            }

            if (pendingStartupUrl == null) {
                pendingStartupUrl = playbackService?.consumePendingStartupUrl()
            }

            val p = playbackService?.getPlayer()
            if (p != null) {
                playerView?.player = p
                p.removeListener(mainPlayerListener)
                p.addListener(mainPlayerListener)
                if (p.isPlaying || p.playbackState == Player.STATE_READY) {
                    runOnUiThread {
                        hideOffAirFallback()
                        hideSplashSmoothly()
                    }
                }
            }

            tryStartPendingPlayback()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            isBound = false
            serviceBound = false
            logStartup("SERVICE_DISCONNECTED")
        }
    }

    @Volatile
    private var initialStartupComplete = false

    private val mainPlayerListener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val timestamp = sdf.format(java.util.Date())
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] FIRST_FRAME_VALIDATED")
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] VIDEO_VALIDATED")
            
            videoValidated = true
            initialStartupComplete = true
            streamStartTimeMs = System.currentTimeMillis()
            runOnUiThread {
                hideOffAirFallback()
                offAirLayout?.visibility = View.GONE
                playerView?.visibility = View.VISIBLE
                playerView?.bringToFront()
                hideSplashSmoothly()
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                val timestamp = sdf.format(java.util.Date())
                LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] STATE_READY_CLEARING_OFFLINE")
                videoValidated = true
                initialStartupComplete = true
                if (streamStartTimeMs <= 0L) {
                    streamStartTimeMs = System.currentTimeMillis()
                }
                runOnUiThread {
                    hideOffAirFallback()
                    offAirLayout?.visibility = View.GONE
                    playerView?.visibility = View.VISIBLE
                    playerView?.bringToFront()
                    hideSplashSmoothly()
                }
            }
        }
    }

    private val playbackStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action
            val incomingGeneration = intent?.getIntExtra("generation", -1) ?: -1
            val incomingSessionId = intent?.getLongExtra("session_id", -1L) ?: -1L

            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val timestamp = sdf.format(java.util.Date())

            if (action == DecoderPlaybackService.ACTION_PLAYER_RECREATED) {
                val p = playbackService?.getPlayer()
                playerView?.player = p
                p?.removeListener(mainPlayerListener)
                p?.addListener(mainPlayerListener)
                LogUtil.logI(LogUtil.TAG_PLAYER, "MainActivity: Player re-bound after recreation")
            } else {
                val state = intent?.getStringExtra(DecoderPlaybackService.EXTRA_STATE)
                if (state == DecoderPlaybackService.STATE_OFFLINE) {
                    if (incomingGeneration != currentGeneration || incomingSessionId != activePlaybackSessionId) {
                        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OFFLINE_BROADCAST_IGNORED (incoming gen $incomingGeneration != current $currentGeneration, session $incomingSessionId != current $activePlaybackSessionId)")
                        return
                    }
                    val reason = intent.getStringExtra(DecoderPlaybackService.EXTRA_REASON) ?: "Unknown"
                    showOffAirFallback(reason)
                } else if (state == DecoderPlaybackService.STATE_PLAYING) {
                    handler.removeCallbacks(retryRunnable)
                    isRetrying = false
                    
                    initialStartupComplete = true
                    hideOffAirFallback()
                    runOnUiThread {
                        offAirLayout?.visibility = View.GONE
                        playerView?.visibility = View.VISIBLE
                        hideSplashSmoothly()
                    }
                    hasPlayedOnce = true
                    consecutiveErrors = 0
                    lastPlayerError = null
                }
            }
        }
    }

    data class StreamState(
        val streamUrl: String?,
        val isLive: Boolean,
        val playMode: String?,
        val duration: Int?,
        val updatedAtMs: Long?,
        val selectedVideos: List<String>,
        val videoDurations: List<Int>
    )

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (isNetworkConnected()) {
                fetchConfig()
            } else {
                if (!initialStartupComplete) {
                    initialStartupComplete = true
                    runOnUiThread {
                        loadLocalOfflineImage()
                        offAirLayout?.visibility = View.VISIBLE
                        hideSplashSmoothly()
                    }
                } else {
                    runOnUiThread {
                        loadLocalOfflineImage()
                        offAirLayout?.visibility = View.VISIBLE
                        playerView?.visibility = View.GONE
                        webView?.visibility = View.GONE
                    }
                }
            }
            handler.postDelayed(this, 15000L)
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            if (isNetworkConnected()) {
                sendStatusUpdate()
            }
            handler.postDelayed(this, 30000)
        }
    }

    private val retryRunnable = Runnable {
        isRetrying = false
        retryPlay()
    }

    override fun onStart() {
        super.onStart()
        logStartup("APP_START")
        
        val intent = Intent(this, DecoderPlaybackService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, BIND_AUTO_CREATE)
        val filter = IntentFilter().apply {
            addAction(DecoderPlaybackService.ACTION_PLAYBACK_STATE)
            addAction(DecoderPlaybackService.ACTION_PLAYER_RECREATED)
        }
        registerReceiver(playbackStateReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        surfaceReady = false
        serviceBound = false
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        unregisterReceiver(playbackStateReceiver)
    }

    /**
     * Critical Fix 2: Surface-First playback coordinator.
     * Called from BOTH onServiceConnected() and surfaceCreated().
     * Playback starts ONLY when both service is bound AND surface is ready.
     */
    private fun tryStartPendingPlayback() {
        if (
            surfaceReady &&
            serviceBound &&
            pendingStartupUrl != null
        ) {
            playbackService?.switchStream(pendingStartupUrl!!)
            pendingStartupUrl = null

            // Sync generation and session ID
            currentGeneration = playbackService?.getPlaybackGeneration() ?: 0
            activePlaybackSessionId = playbackService?.getActivePlaybackSessionId() ?: 0L

            // Re-link player to PlayerView after creation
            val p = playbackService?.getPlayer()
            playerView?.player = p
            p?.addListener(mainPlayerListener)
        }
    }

    /**
     * Critical Fix 5: Timestamped startup event logging
     */
    private fun logStartup(event: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] $event")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Critical Fix 4: Clear recovery state at application startup
        try {
            com.jtbs.box.persistence.CrashPersistence(this).clearRecoveryState()
            logStartup("RECOVERY_STATE_CLEARED")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Set default SSL socket factory globally for all HTTPS connections (including ExoPlayer)
        try {
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(TLSSocketFactory())
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            
            // Configure DNS cache TTL to prevent stale or failed DNS caching
            java.security.Security.setProperty("networkaddress.cache.ttl", "5")
            java.security.Security.setProperty("networkaddress.cache.negative.ttl", "0")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Force Landscape
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Hide Title bar
        requestWindowFeature(Window.FEATURE_NO_TITLE)

        // Fullscreen flags
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= 19) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }

        // Keep Screen Awake
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Root View
        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
        }

        // PlayerView (ExoPlayer container)
        val pv = PlayerView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            useController = false
            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            setKeepContentOnPlayerReset(true)
        }
        val shutter = pv.findViewById<View>(com.google.android.exoplayer2.ui.R.id.exo_shutter)
        shutter?.visibility = View.GONE
        shutter?.alpha = 0f
        
        playerView = pv
        root.addView(pv)

        // WebView (YouTube fallback container)
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setBackgroundColor(0xFF000000.toInt())
            
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.allowFileAccess = true
            
            // Set database path for Android 5.1 databases/DOM storage support
            try {
                settings.setDatabasePath(applicationContext.filesDir.path + "/databases")
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // Set modern User Agent so YouTube doesn't block playback on old webview version
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
            
            // Allow mixed content on Lollipop
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            
            // Enable WebView remote contents debugging for Chrome DevTools inspection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
            
            webChromeClient = android.webkit.WebChromeClient()
            
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url != null && !url.contains("about:blank")) {
                        hideSplashSmoothly()
                    }
                }
                
                override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed() // Bypass SSL certificate expiration checks on Lollipop
                }
            }
            visibility = View.GONE
        }
        webView = wv
        root.addView(wv)

        // Surface Callback to notify the service
        val surfaceView = pv.videoSurfaceView as? SurfaceView
        surfaceView?.holder?.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                logStartup("SURFACE_CREATED")
                pendingSurface = holder.surface
                surfaceReady = true
                playbackService?.setSurface(holder.surface)
                
                // Critical Fix 2: Now that surface is ready, try starting pending playback
                tryStartPendingPlayback()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                logStartup("SURFACE_DESTROYED")
                pendingSurface = null
                surfaceReady = false
                playbackService?.setSurface(null)
            }
        })

        // Off-Air Layout
        val offAir = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFF000000.toInt())
            visibility = View.GONE
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_XY
        }
        offAirImageView = iv
        offAir.addView(iv)
        offAirLayout = offAir
        root.addView(offAir)
        loadLocalOfflineImage()

        // Splash Layout
        val splash = LinearLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(0xFF000000.toInt())
        }
        
        // Logo ImageView
        val logoView = ImageView(this).apply {
            val logoResId = resources.getIdentifier("logo", "drawable", packageName)
            if (logoResId != 0) {
                setImageResource(logoResId)
            }
            layoutParams = LinearLayout.LayoutParams(180, 180).apply {
                bottomMargin = 32
            }
        }
        
        // Title: JTBS CLASSIC CHANNEL
        val titleText = TextView(this).apply {
            text = "JTBS CLASSIC CHANNEL"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 24f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
        }
        
        // Subtitle: CONNECTING
        val subText = TextView(this).apply {
            text = "CONNECTING"
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 16f
            gravity = android.view.Gravity.CENTER
        }
        loadingText = subText
        
        // Custom Rotating Bluish Retro Spinner
        val density = resources.displayMetrics.density
        val spinnerSize = (48 * density).toInt()
        val spinnerView = ImageView(this).apply {
            val spinnerResId = resources.getIdentifier("spinner", "drawable", packageName)
            if (spinnerResId != 0) {
                setImageResource(spinnerResId)
                setColorFilter(0xFF00E5FF.toInt(), android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            layoutParams = LinearLayout.LayoutParams(spinnerSize, spinnerSize).apply {
                topMargin = (24 * density).toInt()
            }
        }
        
        // Start continuous rotation animation
        val rotateAnimation = android.view.animation.RotateAnimation(
            0f, 360f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
            android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1200
            repeatCount = android.view.animation.Animation.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        spinnerView.startAnimation(rotateAnimation)
        
        splash.addView(logoView)
        splash.addView(titleText)
        splash.addView(subText)
        splash.addView(spinnerView)
        
        splashLayout = splash
        root.addView(splash)

        setContentView(root)

        // Start Fetch loops
        handler.post(checkRunnable)
        handler.post(statusRunnable)
    }

    private fun triggerRetryWithDelay() {
        if (!isRetrying) {
            isRetrying = true
            handler.removeCallbacks(retryRunnable)
            handler.postDelayed(retryRunnable, 5000)
        }
    }

    private fun fetchConfig() {
        if (isConfigFetching) return
        isConfigFetching = true
        Thread {
            try {
                val url = URL("https://jtbsclassic.dpdns.org/api/streamState/main")
                val conn = url.openConnection() as HttpURLConnection
                
                conn.requestMethod = "GET"
                conn.setRequestProperty("Connection", "close")
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    
                    val mode = parseJsonString(response, "mode") ?: "same"
                    
                    val streamUrl: String?
                    val isLive: Boolean
                    val playMode: String?
                    val duration: Int?
                    val updatedAtMs: Long?
                    val selectedVideos: List<String>
                    val videoDurations: List<Int>
                    
                    if (mode == "different") {
                        streamUrl = parseJsonString(response, "decoderStreamUrl")
                        isLive = parseJsonBoolean(response, "decoderIsLive") ?: false
                        playMode = parseJsonString(response, "decoderPlayMode") ?: "live"
                        duration = parseJsonInt(response, "decoderDuration")
                        updatedAtMs = parseJsonTimestamp(response, "decoderUpdatedAt")
                        selectedVideos = parseJsonStringArray(response, "decoderSelectedVideos")
                        videoDurations = parseJsonIntArray(response, "decoderVideoDurations")
                    } else {
                        streamUrl = parseJsonString(response, "streamUrl")
                        isLive = parseJsonBoolean(response, "isLive") ?: false
                        playMode = parseJsonString(response, "playMode") ?: "live"
                        duration = parseJsonInt(response, "duration")
                        updatedAtMs = parseJsonTimestamp(response, "updatedAt")
                        selectedVideos = parseJsonStringArray(response, "selectedVideos")
                        videoDurations = parseJsonIntArray(response, "videoDurations")
                    }
                    
                    val remoteOfflineImg = parseJsonString(response, "decoderOfflineImageUrl") ?: parseJsonString(response, "offlineImageUrl")
                    if (!remoteOfflineImg.isNullOrEmpty()) {
                        checkAndDownloadOfflineImage(remoteOfflineImg)
                    }
                    
                    val state = StreamState(
                        streamUrl = streamUrl,
                        isLive = isLive,
                        playMode = playMode,
                        duration = duration,
                        updatedAtMs = updatedAtMs,
                        selectedVideos = selectedVideos,
                        videoDurations = videoDurations
                    )
                    
                    var deviceEnabled = false
                    var fetchSuccess = false
                    try {
                        val devId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown_device"
                        val controlUrl = URL("https://jtbsclassic.dpdns.org/api/decoderControl/$devId")
                        val controlConn = controlUrl.openConnection() as HttpURLConnection
                        controlConn.requestMethod = "GET"
                        controlConn.setRequestProperty("Connection", "close")
                        controlConn.connectTimeout = 5000
                        controlConn.readTimeout = 5000
                        if (controlConn.responseCode == 200) {
                            val controlResponse = controlConn.inputStream.bufferedReader().use { it.readText() }
                            val enabledVal = parseJsonBoolean(controlResponse, "enabled")
                            deviceEnabled = enabledVal != false
                            fetchSuccess = true
                        } else if (controlConn.responseCode == 404) {
                            deviceEnabled = true
                            fetchSuccess = true
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    
                    runOnUiThread {
                        handleConfigResult(state, deviceEnabled, fetchSuccess)
                    }
                } else {
                    runOnUiThread {
                        if (!initialStartupComplete) {
                            loadingText?.text = "CONNECTING"
                        } else {
                            loadLocalOfflineImage()
                            offAirLayout?.visibility = View.VISIBLE
                            playerView?.visibility = View.GONE
                            webView?.visibility = View.GONE
                            splashLayout?.visibility = View.GONE
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!initialStartupComplete) {
                        loadingText?.text = "CONNECTING"
                    } else {
                        loadLocalOfflineImage()
                        offAirLayout?.visibility = View.VISIBLE
                        playerView?.visibility = View.GONE
                        webView?.visibility = View.GONE
                        splashLayout?.visibility = View.GONE
                    }
                }
            } finally {
                isConfigFetching = false
            }
        }.start()
    }

    private fun handleConfigResult(state: StreamState, deviceEnabled: Boolean, fetchSuccess: Boolean) {
        if (isFinishing) return

        if (!fetchSuccess) {
            if (!hasPlayedOnce) {
                triggerRetryWithDelay()
            }
            return
        }

        val url = state.streamUrl
        val isLive = state.isLive
        val mode = state.playMode ?: "live"

        var isOffline = !isLive || url.isNullOrEmpty() || !deviceEnabled
        
        if (!isOffline && url != null) {
            val isYouTube = url.contains("youtube.com") || url.contains("youtu.be")
            if (isYouTube && mode == "offair") {
                val now = System.currentTimeMillis()
                val updatedAtMs = state.updatedAtMs ?: now
                val elapsed = Math.max(0L, (now - updatedAtMs) / 1000)
                
                val hasCustomPlaylist = state.selectedVideos.isNotEmpty() && state.videoDurations.isNotEmpty()
                if (hasCustomPlaylist) {
                    val totalD = state.videoDurations.sum()
                    if (elapsed >= totalD) {
                        isOffline = true
                    }
                } else {
                    val duration = state.duration ?: 0
                    if (duration > 0 && elapsed >= duration) {
                        isOffline = true
                    }
                }
            }
        }

        if (isOffline) {
            currentUrl = null
            currentIsLive = false
            lastState = null
            currentLoadedEmbedUrl = null
            streamStartTimeMs = 0L
            videoValidated = false
            
            playbackService?.stop()
            runOnUiThread {
                playerView?.player = null
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.visibility = View.GONE
                splashLayout?.visibility = View.GONE
                splashLayout?.alpha = 0f
                loadLocalOfflineImage()
                offAirLayout?.visibility = View.VISIBLE
                offAirLayout?.bringToFront()
            }
        } else {
            val p = playbackService?.getPlayer()
            val isCurrentlyPlaying = p != null && (p.isPlaying || p.playbackState == Player.STATE_READY)
            if (isCurrentlyPlaying) {
                runOnUiThread {
                    hideOffAirFallback()
                    hideSplashSmoothly()
                }
            }

            val isCurrentlyShowingOffline = offAirLayout?.visibility == View.VISIBLE || !isCurrentlyPlaying
            if (url != currentUrl || !currentIsLive || lastState != state || isCurrentlyShowingOffline) {
                currentUrl = url
                currentIsLive = true
                lastState = state
                playStream(url!!, state)
            }
        }
    }

    private fun playStream(url: String, state: StreamState) {
        val now = System.currentTimeMillis()
        if (now - lastSwitchTimestamp < 200) {
            return
        }
        lastSwitchTimestamp = now
        videoValidated = false

        // Cancel any pending retry callbacks to prevent concurrent retry conflicts
        handler.removeCallbacks(retryRunnable)
        isRetrying = false

        streamStartTimeMs = System.currentTimeMillis()
        val isYouTube = url.contains("youtube.com") || url.contains("youtu.be")
        
        if (isYouTube) {
            playbackService?.stop()
            runOnUiThread {
                playerView?.player = null // Detach player to completely destroy hardware SurfaceView punch-through overlay
                playerView?.visibility = View.GONE
                webView?.visibility = View.VISIBLE
                offAirLayout?.visibility = View.GONE // Hide offline layout immediately for YouTube
            }
            
            val videoId = extractYTId(url)
            val playlistId = extractYTPlaylistId(url)
            
            val hasCustomPlaylist = state.selectedVideos.isNotEmpty() && state.videoDurations.isNotEmpty()
            var targetVideoId = videoId ?: ""
            var startSeconds = 0
            var playlistIdsString = ""
            
            val mode = state.playMode ?: "live"
            val now = System.currentTimeMillis()
            val updatedAtMs = state.updatedAtMs ?: now
            val elapsed = Math.max(0L, (now - updatedAtMs) / 1000)
            
            if (hasCustomPlaylist) {
                val totalD = state.videoDurations.sum()
                if (totalD > 0) {
                    val loopElapsed = if (mode == "loop") (elapsed % totalD) else elapsed
                    
                    if (mode == "repeat" && elapsed >= totalD) {
                        val elapsedRepeat = elapsed % totalD
                        var accumulatedTime = 0
                        var currentIndex = 0
                        for (i in state.videoDurations.indices) {
                            val d = state.videoDurations[i]
                            if (elapsedRepeat < accumulatedTime + d) {
                                currentIndex = i
                                startSeconds = (elapsedRepeat - accumulatedTime).toInt()
                                break
                            }
                            accumulatedTime += d
                        }
                        val rotated = state.selectedVideos.subList(currentIndex, state.selectedVideos.size) +
                                state.selectedVideos.subList(0, currentIndex)
                        targetVideoId = rotated.firstOrNull() ?: ""
                        playlistIdsString = rotated.joinToString(",")
                    } else {
                        var accumulatedTime = 0
                        var currentIndex = 0
                        for (i in state.videoDurations.indices) {
                            val d = state.videoDurations[i]
                            if (loopElapsed < accumulatedTime + d) {
                                currentIndex = i
                                startSeconds = (loopElapsed - accumulatedTime).toInt()
                                break
                            }
                            accumulatedTime += d
                        }
                        val rotated = state.selectedVideos.subList(currentIndex, state.selectedVideos.size) +
                                state.selectedVideos.subList(0, currentIndex)
                        targetVideoId = rotated.firstOrNull() ?: ""
                        playlistIdsString = rotated.joinToString(",")
                    }
                }
            } else if (!playlistId.isNullOrEmpty()) {
                playlistIdsString = ""
            } else if (!videoId.isNullOrEmpty()) {
                targetVideoId = videoId
                val duration = state.duration ?: 0
                if (duration > 0) {
                    if (mode == "loop" || mode == "repeat") {
                        startSeconds = (elapsed % duration).toInt()
                    } else {
                        startSeconds = elapsed.toInt()
                    }
                } else {
                    startSeconds = 0
                }
            }
            
            // Use privacy-enhanced youtube-nocookie.com domain to bypass cookie consent redirects
            val sb = java.lang.StringBuilder("https://www.youtube-nocookie.com/embed/")
            sb.append(targetVideoId)
            sb.append("?autoplay=1&controls=0&mute=0&rel=0&modestbranding=1")
            sb.append("&start=").append(startSeconds)
            
            if (playlistIdsString.isNotEmpty()) {
                sb.append("&playlist=").append(playlistIdsString)
                if (mode == "loop" || mode == "repeat") {
                    sb.append("&loop=1")
                }
            } else if (!playlistId.isNullOrEmpty()) {
                sb.append("&listType=playlist&list=").append(playlistId)
                if (mode == "loop" || mode == "repeat") {
                    sb.append("&loop=1&playlist=").append(targetVideoId)
                }
            } else if (!videoId.isNullOrEmpty()) {
                if (mode == "loop" || mode == "repeat") {
                    sb.append("&loop=1&playlist=").append(videoId)
                }
            }
            
            val embedUrl = sb.toString()
            
            runOnUiThread {
                if (currentLoadedEmbedUrl != embedUrl) {
                    currentLoadedEmbedUrl = embedUrl
                    webView?.loadUrl(embedUrl)
                }
            }
        } else {
            currentLoadedEmbedUrl = null
            webView?.stopLoading()
            webView?.loadUrl("about:blank")
            webView?.visibility = View.GONE
            val p = playbackService?.getPlayer()
            playerView?.player = p
            playerView?.visibility = View.VISIBLE
            
            if (!initialStartupComplete) {
                loadingText?.text = "CONNECTING"
                splashLayout?.visibility = View.VISIBLE
                splashLayout?.alpha = 1f
                offAirLayout?.visibility = View.GONE
            } else {
                splashLayout?.visibility = View.GONE
            }
            
            playbackService?.switchStream(
                url = url,
                updatedAtMs = state.updatedAtMs ?: 0L,
                durationSec = state.duration ?: 0,
                playMode = state.playMode ?: "live"
            )
            currentGeneration = playbackService?.getPlaybackGeneration() ?: 0
            activePlaybackSessionId = playbackService?.getActivePlaybackSessionId() ?: 0L
        }
    }

    private fun retryPlay() {
        currentUrl?.let { url ->
            lastState?.let { state ->
                playStream(url, state)
            }
        }
    }

    private fun hideSplashSmoothly() {
        splashLayout?.let { splash ->
            if (splash.visibility == View.VISIBLE) {
                splash.animate()
                    .alpha(0f)
                    .setDuration(800)
                    .withEndAction {
                        splash.visibility = View.GONE
                        splash.alpha = 1f
                    }
            }
        }
    }

    private fun extractYTPlaylistId(url: String): String? {
        val m = url.indexOf("list=")
        if (m == -1) return null
        val start = m + 5
        var end = url.indexOf("&", start)
        if (end == -1) end = url.length
        return url.substring(start, end)
    }

    private fun extractYTId(url: String): String? {
        val pattern = "(?:v=|youtu\\.be/|embed/|live/)([A-Za-z0-9_-]{11})"
        val regex = java.util.regex.Pattern.compile(pattern)
        val matcher = regex.matcher(url)
        return if (matcher.find()) matcher.group(1) else null
    }

    private fun parseJsonString(json: String, key: String): String? {
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return null
            
            val stringValueIndex = json.indexOf("\"stringValue\"", keyIndex)
            if (stringValueIndex == -1) return null
            
            val colonIndex = json.indexOf(":", stringValueIndex)
            if (colonIndex == -1) return null
            
            val startQuote = json.indexOf("\"", colonIndex + 1)
            if (startQuote == -1) return null
            
            val endQuote = json.indexOf("\"", startQuote + 1)
            if (endQuote == -1) return null
            
            return json.substring(startQuote + 1, endQuote)
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseJsonInt(json: String, key: String): Int? {
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return null
            
            val integerValueIndex = json.indexOf("\"integerValue\"", keyIndex)
            if (integerValueIndex == -1) return null
            
            val colonIndex = json.indexOf(":", integerValueIndex)
            if (colonIndex == -1) return null
            
            val startQuote = json.indexOf("\"", colonIndex + 1)
            if (startQuote == -1) return null
            
            val endQuote = json.indexOf("\"", startQuote + 1)
            if (endQuote == -1) return null
            
            return json.substring(startQuote + 1, endQuote).toIntOrNull()
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseJsonBoolean(json: String, key: String): Boolean? {
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return null
            
            val booleanValueIndex = json.indexOf("\"booleanValue\"", keyIndex)
            if (booleanValueIndex == -1) return null
            
            val colonIndex = json.indexOf(":", booleanValueIndex)
            if (colonIndex == -1) return null
            
            val trueIndex = json.indexOf("true", colonIndex)
            val falseIndex = json.indexOf("false", colonIndex)
            
            if (trueIndex != -1 && (falseIndex == -1 || trueIndex < falseIndex)) {
                return true
            }
            if (falseIndex != -1 && (trueIndex == -1 || falseIndex < trueIndex)) {
                return false
            }
            return null
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseJsonStringArray(json: String, key: String): List<String> {
        val list = mutableListOf<String>()
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return list
            
            val arrayIndex = json.indexOf("\"arrayValue\"", keyIndex)
            if (arrayIndex == -1) return list
            
            val valuesIndex = json.indexOf("\"values\"", arrayIndex)
            if (valuesIndex == -1) return list
            
            val endArrayBracket = json.indexOf("]", valuesIndex)
            if (endArrayBracket == -1) return list
            
            var searchIndex = valuesIndex
            while (true) {
                val stringValIndex = json.indexOf("\"stringValue\"", searchIndex)
                if (stringValIndex == -1 || stringValIndex > endArrayBracket) break
                
                val colonIndex = json.indexOf(":", stringValIndex)
                val startQuote = json.indexOf("\"", colonIndex + 1)
                val endQuote = json.indexOf("\"", startQuote + 1)
                if (startQuote != -1 && endQuote != -1) {
                    list.add(json.substring(startQuote + 1, endQuote))
                }
                searchIndex = endQuote + 1
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun parseJsonIntArray(json: String, key: String): List<Int> {
        val list = mutableListOf<Int>()
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return list
            
            val arrayIndex = json.indexOf("\"arrayValue\"", keyIndex)
            if (arrayIndex == -1) return list
            
            val valuesIndex = json.indexOf("\"values\"", arrayIndex)
            if (valuesIndex == -1) return list
            
            val endArrayBracket = json.indexOf("]", valuesIndex)
            if (endArrayBracket == -1) return list
            
            var searchIndex = valuesIndex
            while (true) {
                val integerValIndex = json.indexOf("\"integerValue\"", searchIndex)
                if (integerValIndex == -1 || integerValIndex > endArrayBracket) break
                
                val colonIndex = json.indexOf(":", integerValIndex)
                val startQuote = json.indexOf("\"", colonIndex + 1)
                val endQuote = json.indexOf("\"", startQuote + 1)
                if (startQuote != -1 && endQuote != -1) {
                    val numStr = json.substring(startQuote + 1, endQuote)
                    numStr.toIntOrNull()?.let { list.add(it) }
                }
                searchIndex = endQuote + 1
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    private fun parseJsonTimestamp(json: String, key: String): Long? {
        try {
            val keyIndex = json.indexOf("\"$key\"")
            if (keyIndex == -1) return null
            
            val timestampValueIndex = json.indexOf("\"timestampValue\"", keyIndex)
            if (timestampValueIndex == -1) return null
            
            val colonIndex = json.indexOf(":", timestampValueIndex)
            val startQuote = json.indexOf("\"", colonIndex + 1)
            val endQuote = json.indexOf("\"", startQuote + 1)
            if (startQuote == -1 || endQuote == -1) return null
            
            val timestampStr = json.substring(startQuote + 1, endQuote)
            val normalized = timestampStr.replace("Z", "+0000").replace(Regex("\\.\\d+"), "")
            val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US)
            return sdf.parse(normalized)?.time
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    // Intercept all physical remote button presses (Kiosk Lock)
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val keyCode = event.keyCode
        
        // Pressing 0 launches Android System Settings
        if (keyCode == android.view.KeyEvent.KEYCODE_0) {
            if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                try {
                    val intent = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return true
        }
        
        // Allow volume controls to pass to the OS
        if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event)
        }
        
        return true
    }

    private fun sendStatusUpdate() {
        val devId = android.provider.Settings.Secure.getString(contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown_device"
        val devName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
        
        val p = playbackService?.getPlayer()
        
        val playbackState = if (webView?.visibility == View.VISIBLE) {
            "playing (Web)"
        } else {
            if (p != null) {
                when (p.playbackState) {
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> if (p.playWhenReady == true) "playing" else "paused"
                    Player.STATE_ENDED -> "ended"
                    Player.STATE_IDLE -> "idle"
                    else -> "unknown"
                }
            } else {
                "offline"
            }
        }
        
        val format = p?.videoFormat
        val resolution = if (webView?.visibility == View.VISIBLE) {
            "Web Player"
        } else if (format != null) {
            "${format.width}x${format.height}"
        } else {
            "Unknown"
        }
        
        var bitrate = if (webView?.visibility == View.VISIBLE) {
            "Adaptive"
        } else if (format != null && format.bitrate != com.google.android.exoplayer2.Format.NO_VALUE) {
            "${format.bitrate / 1000} kbps"
        } else {
            "Unknown"
        }
        
        val now = System.currentTimeMillis()
        val uid = android.os.Process.myUid()
        val rxBytes = android.net.TrafficStats.getUidRxBytes(uid)
        if (lastRxBytes > 0L && now > lastRxTimestamp) {
            val bytesDiff = rxBytes - lastRxBytes
            val timeDiffMs = now - lastRxTimestamp
            if (bytesDiff > 0L && timeDiffMs > 0L) {
                val calculatedBitrateKbps = (bytesDiff * 8) / timeDiffMs
                if (bitrate == "Unknown" || bitrate == "0 kbps") {
                    if (calculatedBitrateKbps > 50L) {
                        bitrate = "$calculatedBitrateKbps kbps"
                    }
                }
            }
        }
        lastRxBytes = rxBytes
        lastRxTimestamp = now
        
        val mi = android.app.ActivityManager.MemoryInfo()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        activityManager.getMemoryInfo(mi)
        val freeRamMb = mi.availMem / (1024 * 1024)
        
        val uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000
        
        val isOfflineOverlayVisible = offAirLayout?.visibility == View.VISIBLE
        val sourceUptimeSeconds = if (streamStartTimeMs > 1000000000000L && currentIsLive && !currentUrl.isNullOrEmpty() && !isOfflineOverlayVisible) {
            Math.max(0L, (System.currentTimeMillis() - streamStartTimeMs) / 1000)
        } else {
            0L
        }
        
        val playingUrl = currentUrl ?: "none"
        val errorMsg = lastPlayerError ?: ""
        
        val appVersion = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "1.0.0"
        }

        if (isStatusSending) return
        isStatusSending = true

        Thread {
            try {
                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
                sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
                val timestampStr = sdf.format(java.util.Date())

                val url = URL("https://jtbsclassic.dpdns.org/api/decoderStatus/$devId" +
                        "?updateMask.fieldPaths=deviceId" +
                        "&updateMask.fieldPaths=deviceName" +
                        "&updateMask.fieldPaths=lastSeen" +
                        "&updateMask.fieldPaths=playbackState" +
                        "&updateMask.fieldPaths=streamUrl" +
                        "&updateMask.fieldPaths=bitrate" +
                        "&updateMask.fieldPaths=resolution" +
                        "&updateMask.fieldPaths=freeRamMb" +
                        "&updateMask.fieldPaths=uptimeSeconds" +
                        "&updateMask.fieldPaths=sourceUptimeSeconds" +
                        "&updateMask.fieldPaths=appVersion" +
                        "&updateMask.fieldPaths=lastError")
                        
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-HTTP-Method-Override", "PATCH")
                conn.setRequestProperty("Connection", "close")
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")

                val jsonBody = """
                {
                  "fields": {
                    "deviceId": { "stringValue": "$devId" },
                    "deviceName": { "stringValue": "$devName" },
                    "lastSeen": { "timestampValue": "$timestampStr" },
                    "playbackState": { "stringValue": "$playbackState" },
                    "streamUrl": { "stringValue": "$playingUrl" },
                    "bitrate": { "stringValue": "$bitrate" },
                    "resolution": { "stringValue": "$resolution" },
                    "freeRamMb": { "integerValue": "$freeRamMb" },
                    "uptimeSeconds": { "integerValue": "$uptimeSeconds" },
                    "sourceUptimeSeconds": { "integerValue": "$sourceUptimeSeconds" },
                    "appVersion": { "stringValue": "$appVersion" },
                    "lastError": { "stringValue": "$errorMsg" }
                  }
                }
                """.trimIndent()

                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                }
                
                val code = conn.responseCode
                LogUtil.logI(LogUtil.TAG_PLAYER, "sendStatusUpdate successfully sent. HTTP Response: $code")
            } catch (e: java.lang.Exception) {
                LogUtil.logE(LogUtil.TAG_PLAYER, "sendStatusUpdate failed: ${e.message}", e)
            } finally {
                isStatusSending = false
            }
        }.start()
    }

    private fun fetchBrandingConfig() {
        Thread {
            try {
                val url = URL("https://jtbsclassic.dpdns.org/api/config/main")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Connection", "close")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                
                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    val offlineImageUrl = parseJsonString(response, "offlineImageUrl")
                    if (!offlineImageUrl.isNullOrEmpty()) {
                        checkAndDownloadOfflineImage(offlineImageUrl)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun checkAndDownloadOfflineImage(imageUrl: String) {
        val prefs = getSharedPreferences("jtbs_prefs", MODE_PRIVATE)
        val savedUrl = prefs.getString("offline_image_url", "")
        val localFile = java.io.File(filesDir, "offline_image.jpg")
        
        if (savedUrl == imageUrl && localFile.exists()) {
            runOnUiThread {
                loadLocalOfflineImage()
            }
            return
        }
        
        Thread {
            try {
                val url = URL(imageUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                conn.readTimeout = 15000
                conn.requestMethod = "GET"
                
                if (conn.responseCode == 200) {
                    val tempFile = java.io.File(filesDir, "offline_image.jpg.tmp")
                    conn.inputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (tempFile.renameTo(localFile)) {
                        prefs.edit().putString("offline_image_url", imageUrl).apply()
                        runOnUiThread {
                            loadLocalOfflineImage()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.start()
    }

    private fun loadLocalOfflineImage() {
        val localFile = java.io.File(filesDir, "offline_image.jpg")
        if (localFile.exists()) {
            try {
                val bitmap = android.graphics.BitmapFactory.decodeFile(localFile.absolutePath)
                if (bitmap != null) {
                    offAirImageView?.setImageBitmap(bitmap)
                    return
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        val resId = resources.getIdentifier("offline_image", "drawable", packageName)
        if (resId != 0) {
            offAirImageView?.setImageResource(resId)
        }
    }

    private fun showOffAirFallback(reason: String) {
        initialStartupComplete = true
        val persistence = com.jtbs.box.persistence.CrashPersistence(this)
        val stage = persistence.lastRecoveryStage
        val count = persistence.recoveryCount

        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())

        val p = playbackService?.getPlayer()
        if (p != null && p.isPlaying) {
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OFFLINE_TRIGGER_IGNORED (player is actively playing)")
            return
        }
        videoValidated = false

        LogUtil.logI(
            LogUtil.TAG_RECOVERY,
            "OFFLINE_TRIGGER\nReason=$reason\nState=STATE_UNKNOWN\nUrl=${currentUrl ?: "none"}\nStage=$stage\nCount=$count"
        )
        
        runOnUiThread {
            loadLocalOfflineImage()
            offAirLayout?.visibility = View.VISIBLE
            offAirLayout?.bringToFront()
            playerView?.visibility = View.GONE
            webView?.visibility = View.GONE
            splashLayout?.visibility = View.GONE
            splashLayout?.alpha = 0f
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OVERLAY_SHOWN (reason=$reason)")
        }

        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, 3000)
    }

    private fun hideOffAirFallback() {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())

        runOnUiThread {
            if (offAirLayout?.visibility == View.VISIBLE) {
                LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OVERLAY_HIDDEN")
            }
            offAirLayout?.visibility = View.GONE
            splashLayout?.visibility = View.GONE
            if (webView?.visibility != View.VISIBLE) {
                playerView?.visibility = View.VISIBLE
                playerView?.bringToFront()
            }
        }
    }

    private fun isNetworkConnected(): Boolean {
        return try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            activeNetwork != null && activeNetwork.isConnected
        } catch (e: Exception) {
            true
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(retryRunnable)
        webView?.destroy()
        super.onDestroy()
    }
}
