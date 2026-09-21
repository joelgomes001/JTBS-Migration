package com.jtbs.box.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Debug
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.view.Surface
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.recovery.RecoveryManager
import com.jtbs.box.util.LogUtil
import com.jtbs.box.watchdog.PlayerHealthMonitor
import tv.danmaku.ijk.media.player.IjkMediaPlayer
import tv.danmaku.ijk.media.player.IMediaPlayer

class DecoderPlaybackService : Service() {

    companion object {
        const val ACTION_PLAYBACK_STATE = "com.jtbs.box.ACTION_PLAYBACK_STATE"
        const val ACTION_PLAYER_RECREATED = "com.jtbs.box.ACTION_PLAYER_RECREATED"
        const val EXTRA_STATE = "state"
        const val STATE_PLAYING = "playing"
        const val STATE_OFFLINE = "offline"
        const val EXTRA_REASON = "reason"
    }

    private val binder = LocalBinder()
    
    private var player: IjkMediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var persistence: CrashPersistence
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var watchdog: PlayerHealthMonitor
    
    private var currentUrl: String? = null
    private var lastSurface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())

    var pendingStartupUrl: String? = null
        private set
    
    @Volatile
    var isSurfaceReady: Boolean = false
        private set

    // Telemetry: Heartbeat every 10 seconds
    private val statsRunnable = object : Runnable {
        override fun run() {
            logStats()
            handler.postDelayed(this, 10000)
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): DecoderPlaybackService = this@DecoderPlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        logStartup("SERVICE_CREATED")
        persistence = CrashPersistence(this)
        persistence.clearRecoveryState()
        logStartup("RECOVERY_STATE_CLEARED")
        
        recoveryManager = RecoveryManager(this, this, persistence)
        watchdog = PlayerHealthMonitor(this, this, persistence, recoveryManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logStartup("SERVICE_ON_START_COMMAND")
        
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JTBS:PlaybackWakeLock").apply {
                acquire()
            }
            logStartup("WAKELOCK_ACQUIRED")
        }

        if (player == null && currentUrl == null && pendingStartupUrl == null) {
            val urlToRestore = persistence.lastSuccessfulUrl
            if (!urlToRestore.isNullOrEmpty()) {
                pendingStartupUrl = urlToRestore
                logStartup("PENDING_URL_STORED: $urlToRestore")
            } else {
                logStartup("NO_URL_TO_RESTORE")
                notifyOffline("No stream URL set")
            }
        }

        handler.removeCallbacks(statsRunnable)
        handler.postDelayed(statsRunnable, 10000)

        return START_STICKY
    }

    fun getPlayer(): IjkMediaPlayer? = player

    fun setSurface(surface: Surface?) {
        lastSurface = surface
        isSurfaceReady = surface != null && surface.isValid
        
        if (surface != null && surface.isValid) {
            player?.setSurface(surface)
            logStartup("SURFACE_SET_VALID")
        } else {
            // Surface destroyed: pause rendering, wait for new surface
            player?.setSurface(null)
            logStartup("SURFACE_SET_NULL")
        }
    }

    fun consumePendingStartupUrl(): String? {
        val url = pendingStartupUrl
        pendingStartupUrl = null
        return url
    }

    fun switchStream(url: String) {
        if (url == currentUrl && player != null) return
        
        // Switch procedure: pause watchdog, stop player, clear state, load incomingUrl
        LogUtil.logI(LogUtil.TAG_PLAYER, "SWITCH_STREAM: Incoming URL = $url")
        watchdog.stop()
        
        currentUrl = url
        persistence.lastAttemptedUrl = url
        pendingStartupUrl = null
        
        recreatePlayerAndPlay()
    }

    fun recreatePlayerAndPlay() {
        val url = currentUrl
        if (url.isNullOrEmpty()) {
            notifyOffline("Empty URL")
            return
        }

        val surface = lastSurface
        if (surface == null || !surface.isValid) {
            logStartup("PLAYER_CREATE_DEFERRED: No valid surface")
            return
        }

        watchdog.stop()

        player?.let { oldPlayer ->
            oldPlayer.stop()
            oldPlayer.release()
            player = null
            logStartup("OLD_PLAYER_RELEASED")
        }

        try {
            // IjkPlayer setup for low memory and mature IPTV usage
            val ijkPlayer = IjkMediaPlayer().apply {
                // Hardened native codec Options for Old Android 5.1 (Meson8 platform)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec", 1) // hardware decoding
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-auto-rotate", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "mediacodec-handle-resolution-change", 1)
                
                // Keep low buffer target and fast recovery parameters
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "dns_cache_clear", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "analyzeduration", 1000000)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "probesize", 1024 * 1024)
                setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "flush_packets", 1)
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "packet-buffering", 1)
                
                // old S805 boxes use standard audio track output
                setOption(IjkMediaPlayer.OPT_CATEGORY_PLAYER, "opensles", 0)
            }

            player = ijkPlayer
            logStartup("PLAYER_CREATED")
            
            sendBroadcast(Intent(ACTION_PLAYER_RECREATED))
            
            ijkPlayer.setSurface(surface)
            logStartup("SURFACE_ATTACHED")

            // Register Watchdog/Health Monitor callbacks
            ijkPlayer.setOnPreparedListener { mp ->
                watchdog.onPrepared(mp)
            }
            ijkPlayer.setOnInfoListener { mp, what, extra ->
                watchdog.onInfo(mp, what, extra)
            }
            ijkPlayer.setOnErrorListener { mp, what, extra ->
                watchdog.onError(mp, what, extra)
            }
            ijkPlayer.setOnVideoSizeChangedListener { mp, width, height, sarNum, sarDen ->
                watchdog.onVideoSizeChanged(mp, width, height)
            }

            ijkPlayer.dataSource = url
            logStartup("MEDIA_ITEM_SET: $url")
            
            ijkPlayer.prepareAsync()
            logStartup("PLAYER_PREPARED")
            
            // Watchdog starts waiting for ready
            watchdog.start(url)
            logStartup("WATCHDOG_STARTED")
            
        } catch (e: Exception) {
            LogUtil.logE(LogUtil.TAG_PLAYER, "Failed to recreate player", e)
            notifyOffline("Player creation failure: ${e.message}")
        }
    }

    fun reloadStream() {
        recreatePlayerAndPlay()
    }

    fun play() {
        player?.start()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        watchdog.stop()
        player?.stop()
        currentUrl = null
    }

    fun notifyPlaying() {
        logStartup("NOTIFY_PLAYING_BROADCAST")
        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_PLAYING)
        }
        sendBroadcast(intent)
    }

    fun notifyOffline(reason: String) {
        logStartup("NOTIFY_OFFLINE: $reason")
        
        val stateStr = getPlayerStateString()
        val stage = persistence.lastRecoveryStage
        val count = persistence.recoveryCount
        LogUtil.logI(
            LogUtil.TAG_RECOVERY,
            "OFFLINE_TRIGGER\nReason=$reason\nState=$stateStr\nUrl=${currentUrl ?: "none"}\nStage=$stage\nCount=$count"
        )
        
        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_OFFLINE)
            putExtra(EXTRA_REASON, reason)
        }
        sendBroadcast(intent)
    }

    private fun getPlayerStateString(): String {
        val p = player ?: return "STATE_NULL"
        return if (p.isPlaying) "STATE_PLAYING" else "STATE_PAUSED"
    }

    private fun logStartup(event: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] $event")
    }

    private fun logStats() {
        val p = player
        val resolution = if (p != null) "${p.videoWidth}x${p.videoHeight}" else "Unknown"
        val ramUsage = Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
        val recoveryCount = persistence.recoveryCount
        val recoveryStage = persistence.lastRecoveryStage
        
        LogUtil.logI(
            LogUtil.TAG_STREAM,
            "Telemetry - URL: $currentUrl | Res: $resolution | RAM: ${ramUsage}MB | State: ${getPlayerStateString()} | RecCount: $recoveryCount | RecStage: $recoveryStage"
        )
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        logStartup("SERVICE_DESTROY")
        handler.removeCallbacks(statsRunnable)
        watchdog.stop()
        
        player?.let {
            it.stop()
            it.release()
            player = null
        }
        
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
            wakeLock = null
            logStartup("WAKELOCK_RELEASED")
        }
        
        super.onDestroy()
    }
}

