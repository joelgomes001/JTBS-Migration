package com.example.jtbs

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import android.view.SurfaceView

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
    private var libVLC: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null
    private var videoSurface: SurfaceView? = null
    private var vlcVideoWidth = 0
    private var vlcVideoHeight = 0
    private var webView: WebView? = null
    private var splashLayout: LinearLayout? = null
    private var offAirLayout: android.view.ViewGroup? = null
    private var offAirImageView: ImageView? = null
    private var loadingText: TextView? = null

    private val handler = Handler(Looper.getMainLooper())
    private var currentUrl: String? = null
    private var currentIsLive: Boolean = false
    private var currentLoadedEmbedUrl: String? = null

    // Resiliency tracking
    private var consecutiveErrors = 0
    private var hasPlayedOnce = false
    private var isRetrying = false
    private var lastState: StreamState? = null
    private var lastPlayerError: String? = null

    private val vlcLayoutListener = object : IVLCVout.OnNewVideoLayoutListener {
        override fun onNewVideoLayout(
            vlcVout: IVLCVout,
            width: Int,
            height: Int,
            visibleWidth: Int,
            visibleHeight: Int,
            sarNum: Int,
            sarDen: Int
        ) {
            vlcVideoWidth = width
            vlcVideoHeight = height
        }
    }

    private fun stopVlc() {
        vlcPlayer?.let { p ->
            p.stop()
        }
    }

    private fun setupMediaPlayer(player: MediaPlayer) {
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    bufferingStartTimeMs = 0L
                    streamStartTimeMs = System.currentTimeMillis()
                    handler.removeCallbacks(bufferingWatchdogRunnable)
                    hideOffAirFallback()
                    consecutiveErrors = 0
                    hasPlayedOnce = true
                    lastPlayerError = null
                    hideSplashSmoothly()
                }
                MediaPlayer.Event.EndReached -> {
                    bufferingStartTimeMs = 0L
                    handler.removeCallbacks(bufferingWatchdogRunnable)
                    stopVlc()
                    currentUrl?.let { retryUrl ->
                        lastState?.let { retryState ->
                            playStream(retryUrl, retryState)
                        }
                    }
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering < 100f) {
                        if (bufferingStartTimeMs == 0L) {
                            bufferingStartTimeMs = System.currentTimeMillis()
                            handler.postDelayed(bufferingWatchdogRunnable, 5000)
                        }
                        if (!hasPlayedOnce) {
                            runOnUiThread {
                                loadingText?.text = "CONNECTING"
                                splashLayout?.visibility = View.VISIBLE
                                splashLayout?.alpha = 1f
                                offAirLayout?.visibility = View.GONE
                            }
                        }
                    } else {
                        bufferingStartTimeMs = 0L
                        handler.removeCallbacks(bufferingWatchdogRunnable)
                    }
                }
                MediaPlayer.Event.EncounteredError -> {
                    consecutiveErrors++
                    lastPlayerError = "VLC playback error"
                    streamStartTimeMs = 0L
                    
                    val failUrl = currentUrl
                    if (failUrl != null && failUrl.startsWith("https://", ignoreCase = true)) {
                        val fallbackUrl = "http://" + failUrl.substring(8)
                        android.util.Log.w("JTBS_DEBUG", "VLC HTTPS playback failed. Retrying with HTTP fallback: $fallbackUrl")
                        currentUrl = fallbackUrl
                        runOnUiThread {
                            showOffAirFallback("Retrying over HTTP...")
                        }
                        triggerRetryWithDelay()
                    } else {
                        runOnUiThread {
                            showOffAirFallback("VLC error")
                        }
                        triggerRetryWithDelay()
                    }
                }
            }
        }
        
        videoSurface?.let { surface ->
            if (surface.holder.surface.isValid) {
                val vout = player.vlcVout
                vout.setVideoView(surface)
                vout.attachViews(vlcLayoutListener)
            }
        }
    }

    @Volatile
    private var isStatusSending = false

    @Volatile
    private var isConfigFetching = false

    private var bufferingStartTimeMs: Long = 0
    private val bufferingWatchdogRunnable = Runnable {
        if (bufferingStartTimeMs > 0) {
            showOffAirFallback("Stream buffering timeout")
        }
    }

    private var streamStartTimeMs: Long = 0

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
                fetchBrandingConfig()
                val delay = if (!hasPlayedOnce) 4000L else 5000L
                handler.postDelayed(this, delay)
            } else {
                runOnUiThread {
                    stopVlc()
                    showOffAirFallback("No network connection")
                }
                handler.postDelayed(this, 2000)
            }
        }
    }

    private val statusRunnable = object : Runnable {
        override fun run() {
            if (isNetworkConnected()) {
                sendStatusUpdate()
            }
            handler.postDelayed(this, 5000) // update status every 5 seconds
        }
    }

    private val retryRunnable = Runnable {
        isRetrying = false
        retryPlay()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Copy CA cert bundle and set SSL_CERT_FILE env var for GnuTLS
        try {
            val certFile = java.io.File(filesDir, "cacert.pem")
            if (!certFile.exists()) {
                assets.open("cacert.pem").use { input ->
                    certFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                android.util.Log.d("JTBS_DEBUG", "Copied cacert.pem to internal storage")
            } else {
                android.util.Log.d("JTBS_DEBUG", "cacert.pem already exists")
            }
            android.system.Os.setenv("SSL_CERT_FILE", certFile.absolutePath, true)
            android.util.Log.d("JTBS_DEBUG", "Set SSL_CERT_FILE successfully")
        } catch (e: Exception) {
            android.util.Log.e("JTBS_DEBUG", "Failed to set up CA cert bundle: ${e.message}", e)
        }

        // Initialize Conscrypt security provider
        try {
            java.security.Security.insertProviderAt(org.conscrypt.Conscrypt.newProvider(), 1)
            android.util.Log.d("JTBS_DEBUG", "Conscrypt provider inserted successfully")
        } catch (e: Exception) {
            android.util.Log.e("JTBS_DEBUG", "Failed to insert Conscrypt provider: ${e.message}", e)
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
            setBackgroundColor(0xFE000000.toInt())
        }

        // SurfaceView (VLC container)
        val surface = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        videoSurface = surface
        surface.holder.addCallback(object : android.view.SurfaceHolder.Callback {
            override fun surfaceCreated(holder: android.view.SurfaceHolder) {
                android.util.Log.d("JTBS_DEBUG", "Android SurfaceHolder surfaceCreated called")
                vlcPlayer?.vlcVout?.let { vout ->
                    vout.setVideoView(surface)
                    vout.attachViews(vlcLayoutListener)
                }
            }
            override fun surfaceChanged(holder: android.view.SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: android.view.SurfaceHolder) {
                android.util.Log.d("JTBS_DEBUG", "Android SurfaceHolder surfaceDestroyed called")
                vlcPlayer?.vlcVout?.detachViews()
            }
        })
        root.addView(surface)

        // WebView (YouTube fallback container)
        val wv = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            
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
        try {
            val vlcArgs = arrayListOf(
                "--network-caching=6000",      // 6s buffer
                "--live-caching=3000",         // 3s for live streams
                "--http-reconnect",            // Auto-reconnect on network dropout
                "--http-continuous",           // Keep connection alive
                "--no-drop-late-frames",       // Don't drop frames on slow decode
                "--no-skip-frames",            // Don't skip frames
                "--avcodec-skiploopfilter=4",   // Speed up decoding on weak hardware
                "--vout=android-display"       // Ensure compatibility with Android surface display
            )
            libVLC = LibVLC(this, vlcArgs)
            val initialPlayer = MediaPlayer(libVLC!!)
            vlcPlayer = initialPlayer
            setupMediaPlayer(initialPlayer)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Off-Air Layout
        val offAir = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(0xFE000000.toInt())
            visibility = View.GONE
        }
        val iv = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.CENTER_CROP
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
            setBackgroundColor(0xFE000000.toInt())
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
                val url = URL("https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/streamState/main?key=AIzaSyAA4izkWkMyHdrJ56HNoQcVBX_hJO8EH3s")
                val conn = url.openConnection() as HttpURLConnection
                
                conn.requestMethod = "GET"
                conn.setRequestProperty("Connection", "close")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                
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
                        val controlUrl = URL("https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/decoderControl/$devId?key=AIzaSyAA4izkWkMyHdrJ56HNoQcVBX_hJO8EH3s")
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
                        if (!hasPlayedOnce || consecutiveErrors >= 3) {
                            loadingText?.text = "CONNECTING"
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!hasPlayedOnce || consecutiveErrors >= 3) {
                        loadingText?.text = "CONNECTING"
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
        android.util.Log.d("JTBS_DEBUG", "handleConfigResult - url: $url, isLive: $isLive, deviceEnabled: $deviceEnabled, isOffline: $isOffline, currentUrl: $currentUrl")
        
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
            
            stopVlc()
            runOnUiThread {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.visibility = View.GONE
                loadLocalOfflineImage()
                offAirLayout?.visibility = View.VISIBLE
            }
            hideSplashSmoothly()
        } else {
            offAirLayout?.visibility = View.GONE
            val isYouTube = url?.contains("youtube.com") == true || url?.contains("youtu.be") == true
            val shouldPlay = if (isYouTube) {
                url != currentUrl || !currentIsLive || lastState != state
            } else {
                url != currentUrl || !currentIsLive
            }
            if (shouldPlay) {
                // Stop any existing playback and reset player state before switching streams
                stopVlc()
                currentUrl = url
                currentIsLive = true
                lastState = state
                playStream(url!!, state)
            }
        }
    }

    private fun playStream(url: String, state: StreamState) {
        var processedUrl = url
        if (processedUrl.contains("mayapur.tv")) {
            processedUrl = processedUrl.replace("https://video4.mayapur.tv", "http://video1.mayapur.tv")
            processedUrl = processedUrl.replace("https://video1.mayapur.tv", "http://video1.mayapur.tv")
            processedUrl = processedUrl.replace("http://video4.mayapur.tv", "http://video1.mayapur.tv")
            android.util.Log.d("JTBS_DEBUG", "Rewrote mayapur.tv URL to: $processedUrl")
        }
        streamStartTimeMs = System.currentTimeMillis()
        val isYouTube = processedUrl.contains("youtube.com") || processedUrl.contains("youtu.be")
        
        if (isYouTube) {
            stopVlc()
            runOnUiThread {
                webView?.visibility = View.VISIBLE
            }
            
            val videoId = extractYTId(processedUrl)
            val playlistId = extractYTPlaylistId(processedUrl)
            
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
            runOnUiThread {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.visibility = View.GONE
            }
            android.util.Log.d("JTBS_DEBUG", "playStream HLS. Re-using MediaPlayer for URL: $processedUrl")
            vlcPlayer?.let { p ->
                try {
                    p.stop()
                } catch (e: Exception) {
                    android.util.Log.e("JTBS_DEBUG", "Error stopping player: ${e.message}")
                }
                
                handler.postDelayed({
                    if (isFinishing) return@postDelayed
                    try {
                        val media = Media(libVLC!!, Uri.parse(processedUrl))
                        media.setHWDecoderEnabled(false, false)
                        media.addOption(":network-caching=6000")
                        p.media = media
                        media.release()
                        p.play()
                        android.util.Log.d("JTBS_DEBUG", "playStream HLS - play() called after transition delay")
                    } catch (e: Exception) {
                        android.util.Log.e("JTBS_DEBUG", "Error starting playback after delay: ${e.message}", e)
                        runOnUiThread {
                            showOffAirFallback("VLC error")
                        }
                        triggerRetryWithDelay()
                    }
                }, 200)
            }
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
        
        val playbackState = if (webView?.visibility == View.VISIBLE) {
            "playing (Web)"
        } else {
            if (vlcPlayer?.isPlaying == true) {
                "playing"
            } else if (bufferingStartTimeMs > 0L) {
                "buffering"
            } else {
                "idle"
            }
        }
        
        val resolution = if (webView?.visibility == View.VISIBLE) {
            "Web Player"
        } else if (vlcVideoWidth > 0 && vlcVideoHeight > 0) {
            "${vlcVideoWidth}x${vlcVideoHeight}"
        } else {
            "Unknown"
        }
        
        val bitrate = if (webView?.visibility == View.VISIBLE) {
            "Adaptive"
        } else {
            "Adaptive (VLC)"
        }
        
        val mi = android.app.ActivityManager.MemoryInfo()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        activityManager.getMemoryInfo(mi)
        val freeRamMb = mi.availMem / (1024 * 1024)
        
        val uptimeSeconds = android.os.SystemClock.elapsedRealtime() / 1000
        
        val sourceUptimeSeconds = if (streamStartTimeMs > 0 && currentIsLive && !currentUrl.isNullOrEmpty()) {
            (System.currentTimeMillis() - streamStartTimeMs) / 1000
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

                val url = URL("https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/decoderStatus/$devId" +
                        "?key=AIzaSyAA4izkWkMyHdrJ56HNoQcVBX_hJO8EH3s" +
                        "&updateMask.fieldPaths=deviceId" +
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
            } catch (e: java.lang.Exception) {
                e.printStackTrace()
            } finally {
                isStatusSending = false
            }
        }.start()
    }

    private fun fetchBrandingConfig() {
        Thread {
            try {
                val url = URL("https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/config/main?key=AIzaSyAA4izkWkMyHdrJ56HNoQcVBX_hJO8EH3s")
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showOffAirFallback(reason: String) {
        runOnUiThread {
            loadLocalOfflineImage()
            offAirLayout?.visibility = View.VISIBLE
        }
    }

    private fun hideOffAirFallback() {
        val isOffline = !currentIsLive || currentUrl.isNullOrEmpty()
        if (isOffline) return

        runOnUiThread {
            offAirLayout?.visibility = View.GONE
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
        
        vlcPlayer?.let {
            it.stop()
            it.vlcVout.detachViews()
            it.release()
        }
        libVLC?.release()
        vlcPlayer = null
        libVLC = null
        
        webView?.destroy()
        super.onDestroy()
    }
}
