package com.jtbs.box.watchdog

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.recovery.RecoveryManager
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class PlayerHealthMonitor(
    private val context: Context,
    private val service: DecoderPlaybackService,
    private val persistence: CrashPersistence,
    private val recoveryManager: RecoveryManager
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private var streamUrl: String? = null
    
    @Volatile
    private var lastFrameRenderedTimeMs = 0L
    
    @Volatile
    private var lastSwitchTimestamp = 0L
    
    @Volatile
    private var bufferingStartTimeMs = 0L
    
    private var loadTimeoutMs = 15000L
    
    private var isMonitoring = false
    private var isNetworkMonitoring = false
    
    @Volatile
    private var isNetworkChecking = false
    
    @Volatile
    private var isOfflineReported = false

    @Volatile
    private var activeInstanceId = 0

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

    fun start(url: String, instanceId: Int) {
        streamUrl = url
        activeInstanceId = instanceId
        lastSwitchTimestamp = System.currentTimeMillis()
        lastFrameRenderedTimeMs = System.currentTimeMillis()
        bufferingStartTimeMs = 0L
        
        // Timeout escalation
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
        val player = service.getPlayer() ?: return
        val now = System.currentTimeMillis()
        
        if (player.isPlaying && player.playbackState == Player.STATE_READY) {
            lastFrameRenderedTimeMs = now
        }

        // Buffer watchdog
        if (player.playbackState == Player.STATE_BUFFERING) {
            if (bufferingStartTimeMs == 0L) {
                bufferingStartTimeMs = now
            } else {
                val duration = now - bufferingStartTimeMs
                if (duration > 15000L) { // Stalled for >15s
                    LogUtil.logI(LogUtil.TAG_WATCHDOG, "Watchdog: Buffer stall detected for $duration ms!")
                    stop()
                    recoveryManager.handleBufferStall()
                }
            }
        } else {
            bufferingStartTimeMs = 0L
        }
    }

    private fun checkNetworkAsync() {
        if (isNetworkChecking) return
        isNetworkChecking = true
        Thread {
            val hasInternet = performDualLayerNetworkCheck()
            handler.post {
                isNetworkChecking = false
                if (!hasInternet) {
                    if (!isOfflineReported) {
                        isOfflineReported = true
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Network check: Offline!")
                        service.notifyOffline("Network disconnected", activeInstanceId)
                    }
                } else {
                    if (isOfflineReported) {
                        isOfflineReported = false
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Network check: Restored! Reloading stream...")
                        service.reloadStream()
                    }
                }
            }
        }.start()
    }

    private fun performDualLayerNetworkCheck(): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            if (activeNetwork == null || !activeNetwork.isConnected) {
                return false
            }

            // HTTP reachability test (bypasses SSL to avoid certificate issues on Android 5.1)
            val testUrl = URL("http://connectivitycheck.gstatic.com/generate_204")
            var conn: HttpURLConnection? = null
            try {
                conn = testUrl.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                val code = conn.responseCode
                return code in 200..399
            } catch (e: IOException) {
                // Try fallback to google
                var fallbackConn: HttpURLConnection? = null
                try {
                    val fallbackUrl = URL("http://www.google.com/generate_204")
                    fallbackConn = fallbackUrl.openConnection() as HttpURLConnection
                    fallbackConn.requestMethod = "GET"
                    fallbackConn.connectTimeout = 3000
                    fallbackConn.readTimeout = 3000
                    val code = fallbackConn.responseCode
                    return code in 200..399
                } catch (fallbackEx: IOException) {
                    return false
                } finally {
                    fallbackConn?.disconnect()
                }
            } finally {
                conn?.disconnect()
            }
        } catch (e: Exception) {
            return false
        }
    }

    fun registerVideoFrameListener(player: com.google.android.exoplayer2.ExoPlayer) {
        player.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long,
                releaseTimeNs: Long,
                format: Format,
                mediaFormat: android.media.MediaFormat?
            ) {
                lastFrameRenderedTimeMs = System.currentTimeMillis()
                
                // Clear recovery states if frames are rendered past 10 seconds of switch
                if (persistence.lastRecoveryStage > 0 || persistence.recoveryCount > 0) {
                    val timeSinceSwitch = System.currentTimeMillis() - lastSwitchTimestamp
                    if (timeSinceSwitch > 10000L) {
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Frame rendered successfully after 10s. Clearing recovery.")
                        persistence.clearRecoveryState()
                        service.notifyPlaying(activeInstanceId)
                    }
                }
            }
        })
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            handler.removeCallbacks(loadTimeoutRunnable)
            bufferingStartTimeMs = 0L
            
            // Audit log for state ready clearance
            LogUtil.logI(LogUtil.TAG_PLAYER, "OFFLINE_CLEARED\nReason=STATE_READY")
            
            // Persist as last successful URL
            streamUrl?.let { url ->
                persistence.lastSuccessfulUrl = url
            }
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Player error inside watchdog: ${error.message}")
        // Trigger timeout runnable immediately to force recovery
        handler.removeCallbacks(loadTimeoutRunnable)
        recoveryManager.handleLoadTimeout()
    }

    override fun onRenderedFirstFrame() {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "[$timestamp] FIRST_FRAME_RENDERED")
        LogUtil.logI(LogUtil.TAG_WATCHDOG, "First video frame rendered.")
        
        // Force recovery state reset on successful render
        persistence.clearRecoveryState()
        isOfflineReported = false
        
        // FIX 7: Cancel pending recovery actions on successful playback
        handler.removeCallbacks(loadTimeoutRunnable)
        recoveryManager.reset()
        
        // Audit log for first frame clearance
        LogUtil.logI(LogUtil.TAG_PLAYER, "OFFLINE_CLEARED\nReason=FIRST_FRAME_RENDERED")
        service.notifyPlaying(activeInstanceId)
    }

    fun reset() {
        isOfflineReported = false
        bufferingStartTimeMs = 0L
        handler.removeCallbacks(loadTimeoutRunnable)
    }
}
