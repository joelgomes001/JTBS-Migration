package com.jtbs.box.watchdog

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.recovery.RecoveryManager
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil
import tv.danmaku.ijk.media.player.IMediaPlayer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PlayerHealthMonitor(
    private val context: Context,
    private val service: DecoderPlaybackService,
    private val persistence: CrashPersistence,
    private val recoveryManager: RecoveryManager
) {

    private val handler = Handler(Looper.getMainLooper())
    private var streamUrl: String? = null
    
    @Volatile
    private var bufferingStartTimeMs = 0L
    
    @Volatile
    private var lastPlaylistSuccessTimeMs = 0L
    
    private var loadTimeoutMs = 15000L
    private var isMonitoring = false
    private var isNetworkMonitoring = false
    
    @Volatile
    private var isNetworkChecking = false
    
    @Volatile
    private var isOfflineReported = false

    private val healthRunnable = object : Runnable {
        override fun run() {
            checkHealth()
            handler.postDelayed(this, 500)
        }
    }

    private val networkRunnable = object : Runnable {
        override fun run() {
            checkNetworkAsync()
            handler.postDelayed(this, 5000)
        }
    }

    private val loadTimeoutRunnable = Runnable {
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Timeout: Stream load took longer than $loadTimeoutMs ms.")
        recoveryManager.handleLoadTimeout()
    }

    fun start(url: String) {
        streamUrl = url
        val now = System.currentTimeMillis()
        lastPlaylistSuccessTimeMs = now
        bufferingStartTimeMs = 0L
        
        val stage = persistence.lastRecoveryStage
        loadTimeoutMs = when (stage) {
            0 -> 15000L
            1 -> 20000L
            else -> 30000L
        }

        handler.removeCallbacks(loadTimeoutRunnable)
        handler.postDelayed(loadTimeoutRunnable, loadTimeoutMs)

        if (!isMonitoring) {
            isMonitoring = true
            handler.post(healthRunnable)
        }
        if (!isNetworkMonitoring) {
            isNetworkMonitoring = true
            handler.post(networkRunnable)
        }
        
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "[$timestamp] WATCHDOG_STARTED")
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Watchdog started for $url. Timeout set to $loadTimeoutMs ms.")
    }

    fun stop() {
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(healthRunnable)
        handler.removeCallbacks(networkRunnable)
        isMonitoring = false
        isNetworkMonitoring = false
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Watchdog stopped.")
    }

    private fun checkHealth() {
        // Buffer watchdog
        val startMs = bufferingStartTimeMs
        if (startMs > 0L) {
            val duration = System.currentTimeMillis() - startMs
            if (duration > 15000L) { // Stalled for >15s
                LogUtil.logI(LogUtil.TAG_WATCHDOG, "Watchdog: Buffer stall detected for $duration ms!")
                stop()
                recoveryManager.handleBufferStall()
            }
        }
    }

    private fun checkNetworkAsync() {
        if (isNetworkChecking) return
        val activeUrl = streamUrl
        if (activeUrl.isNullOrEmpty() || activeUrl.contains("youtube.com") || activeUrl.contains("youtu.be")) {
            return // Skip network check for youtube streams
        }

        isNetworkChecking = true
        Thread {
            val hasInternet = checkPlaylistReachability(activeUrl)
            handler.post {
                isNetworkChecking = false
                val now = System.currentTimeMillis()
                
                if (hasInternet) {
                    lastPlaylistSuccessTimeMs = now
                    if (isOfflineReported) {
                        isOfflineReported = false
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Network check: Restored! Reloading stream...")
                        service.reloadStream()
                    }
                } else {
                    val offlineDuration = now - lastPlaylistSuccessTimeMs
                    LogUtil.logI(LogUtil.TAG_WATCHDOG, "Playlist download failed. Offline duration: $offlineDuration ms")
                    
                    if (offlineDuration >= 60000L) { // Failed for 60 seconds
                        if (!isOfflineReported) {
                            isOfflineReported = true
                            LogUtil.logI(LogUtil.TAG_WATCHDOG, "Network check: Playlist offline for 60s!")
                            service.notifyOffline("Playlist connectivity lost")
                            stop()
                            recoveryManager.handleLoadTimeout() // Trigger recovery
                        }
                    }
                }
            }
        }.start()
    }

    private fun checkPlaylistReachability(playlistUrl: String): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(playlistUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("Connection", "close")
            val code = conn.responseCode
            code in 200..399
        } catch (e: Exception) {
            false
        } finally {
            conn?.disconnect()
        }
    }

    // Callbacks from IjkMediaPlayer
    fun onPrepared(mp: IMediaPlayer) {
        handler.removeCallbacks(loadTimeoutRunnable)
        bufferingStartTimeMs = 0L
        
        // Reset recovery state
        persistence.clearRecoveryState()
        isOfflineReported = false
        
        LogUtil.logI(LogUtil.TAG_PLAYER, "OFFLINE_CLEARED\nReason=STATE_READY")
        LogUtil.logI(LogUtil.TAG_PLAYER, "OFFLINE_CLEARED\nReason=FIRST_FRAME_RENDERED")
        
        service.notifyPlaying()
        mp.start()
    }

    fun onInfo(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
        when (what) {
            IMediaPlayer.MEDIA_INFO_BUFFERING_START -> {
                LogUtil.logI(LogUtil.TAG_WATCHDOG, "IjkPlayer info: Buffering start")
                if (bufferingStartTimeMs == 0L) {
                    bufferingStartTimeMs = System.currentTimeMillis()
                }
            }
            IMediaPlayer.MEDIA_INFO_BUFFERING_END -> {
                LogUtil.logI(LogUtil.TAG_WATCHDOG, "IjkPlayer info: Buffering end")
                bufferingStartTimeMs = 0L
            }
            IMediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START -> {
                LogUtil.logI(LogUtil.TAG_WATCHDOG, "IjkPlayer info: Video rendering start")
                handler.removeCallbacks(loadTimeoutRunnable)
                bufferingStartTimeMs = 0L
                persistence.clearRecoveryState()
                isOfflineReported = false
                service.notifyPlaying()
            }
        }
        return true
    }

    fun onError(mp: IMediaPlayer, what: Int, extra: Int): Boolean {
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "IjkPlayer error inside watchdog: what=$what, extra=$extra")
        handler.removeCallbacks(loadTimeoutRunnable)
        recoveryManager.handleLoadTimeout() // Force recovery on player errors
        return true
    }

    fun onVideoSizeChanged(mp: IMediaPlayer, width: Int, height: Int) {
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Video size changed: ${width}x${height}")
    }
}

