package com.jtbs.box.watchdog

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.video.VideoFrameMetadataListener
import com.google.android.exoplayer2.Format
import android.media.MediaFormat
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil
import java.net.HttpURLConnection
import java.net.URL

class PlayerHealthMonitor(
    private val service: DecoderPlaybackService,
    private val player: ExoPlayer
) : Player.Listener {

    private val context: Context = service.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val persistence = CrashPersistence(context)

    @Volatile
    private var isRunning = false

    @Volatile
    private var lastFrameRenderedTimeMs = 0L

    @Volatile
    private var bufferingStartTimeMs = 0L

    private var lastSwitchTimestamp = 0L
    private var streamUrl: String? = null

    // Runnables for periodic checks
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkHealth()
            handler.postDelayed(this, 500) // check every 500ms
        }
    }

    private val loadTimeoutRunnable = Runnable {
        if (isRunning && player.playbackState != Player.STATE_READY) {
            LogUtil.logE(LogUtil.TAG_WATCHDOG, "Stream load timeout reached!")
            service.triggerRecovery(com.jtbs.box.recovery.RecoveryManager(service)::handleLoadTimeout)
        }
    }

    private val networkCheckRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            checkNetworkConnectivity()
            handler.postDelayed(this, 5000) // check every 5 seconds
        }
    }

    init {
        player.addListener(this)
        
        // VideoFrameMetadataListener to monitor continuous frame rendering
        player.setVideoFrameMetadataListener(object : VideoFrameMetadataListener {
            override fun onVideoFrameAboutToBeRendered(
                presentationTimeUs: Long,
                releaseTimeNs: Long,
                format: Format,
                mediaFormat: MediaFormat?
            ) {
                lastFrameRenderedTimeMs = System.currentTimeMillis()
                // Clear recovery state when we are successfully playing frames
                if (persistence.lastRecoveryStage > 0 || persistence.recoveryCount > 0) {
                    val timeSinceSwitch = System.currentTimeMillis() - lastSwitchTimestamp
                    if (timeSinceSwitch > 10000) {
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Frame rendered successfully after 10s. Clearing recovery state.")
                        persistence.clearRecoveryState()
                        service.notifyPlaying()
                    }
                }
            }
        })
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        lastFrameRenderedTimeMs = System.currentTimeMillis()
        bufferingStartTimeMs = 0L
        handler.post(watchdogRunnable)
        handler.post(networkCheckRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacks(loadTimeoutRunnable)
        handler.removeCallbacks(networkCheckRunnable)
    }

    fun onStreamSwitched(url: String) {
        streamUrl = url
        lastSwitchTimestamp = System.currentTimeMillis()
        lastFrameRenderedTimeMs = System.currentTimeMillis()
        bufferingStartTimeMs = 0L
        
        // Cancel existing load timeout
        handler.removeCallbacks(loadTimeoutRunnable)
        
        // Determine progressive timeout duration
        val timeoutMs = when (persistence.lastRecoveryStage) {
            0 -> 15000L // 15 seconds for first attempt
            1 -> 20000L // 20 seconds for second attempt
            else -> 30000L // 30 seconds for subsequent attempts
        }
        
        LogUtil.logD(LogUtil.TAG_WATCHDOG, "Stream switched. Scheduling load timeout in ${timeoutMs / 1000} seconds.")
        handler.postDelayed(loadTimeoutRunnable, timeoutMs)
    }

    private fun checkHealth() {
        val playWhenReady = player.playWhenReady
        val playbackState = player.playbackState

        // 1. Frame watchdog (runs every 500ms, triggers after 3s)
        if (playWhenReady && playbackState == Player.STATE_READY) {
            val now = System.currentTimeMillis()
            val timeSinceSwitch = now - lastSwitchTimestamp
            
            // Ignore frame watchdog during first 10 seconds after stream switch
            if (timeSinceSwitch > 10000L) {
                val timeSinceLastFrame = now - lastFrameRenderedTimeMs
                if (timeSinceLastFrame > 3000L) {
                    LogUtil.logE(LogUtil.TAG_WATCHDOG, "No frame rendered for $timeSinceLastFrame ms!")
                    stop()
                    service.triggerRecovery { url -> com.jtbs.box.recovery.RecoveryManager(service).handleFrozenFrame(url) }
                    return
                }
            }
        }

        // 2. Buffer watchdog
        if (playbackState == Player.STATE_BUFFERING) {
            if (bufferingStartTimeMs == 0L) {
                bufferingStartTimeMs = System.currentTimeMillis()
            } else {
                val bufferingDuration = System.currentTimeMillis() - bufferingStartTimeMs
                if (bufferingDuration > 15000L) {
                    LogUtil.logE(LogUtil.TAG_WATCHDOG, "Buffer stall detected: buffered for $bufferingDuration ms")
                    stop()
                    service.triggerRecovery { url -> com.jtbs.box.recovery.RecoveryManager(service).handleBufferStall(url) }
                    return
                }
            }
        } else {
            bufferingStartTimeMs = 0L
        }
    }

    private fun checkNetworkConnectivity() {
        Thread {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnected
            
            if (!isConnected) {
                service.notifyOffline("Network disconnected")
                return@Thread
            }

            // Perform HTTP HEAD request to verify actual internet access
            var connection: HttpURLConnection? = null
            var internetAvailable = false
            try {
                // Use JTBS Firestore endpoint as server URL
                val url = URL("https://firestore.googleapis.com")
                connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 4000
                connection.readTimeout = 4000
                val responseCode = connection.responseCode
                internetAvailable = (responseCode >= 200 && responseCode < 400) || responseCode == 405
            } catch (e: Exception) {
                // Fallback to Google if Firestore is down or blocked
                try {
                    val fallbackUrl = URL("https://www.google.com")
                    val fallbackConn = fallbackUrl.openConnection() as HttpURLConnection
                    fallbackConn.requestMethod = "HEAD"
                    fallbackConn.connectTimeout = 4000
                    fallbackConn.readTimeout = 4000
                    val responseCode = fallbackConn.responseCode
                    internetAvailable = (responseCode >= 200 && responseCode < 400)
                } catch (ex: Exception) {
                    internetAvailable = false
                }
            } finally {
                connection?.disconnect()
            }

            if (!internetAvailable) {
                service.notifyOffline("Internet unreachable (HEAD request failed)")
            } else {
                // If we were offline and now back online, we can trigger reload if player is idle/error
                if (player.playbackState == Player.STATE_IDLE && streamUrl != null) {
                    handler.post {
                        LogUtil.logI(LogUtil.TAG_WATCHDOG, "Internet restored. Reconnecting stream...")
                        service.reloadStream(streamUrl!!)
                    }
                }
            }
        }.start()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
            handler.removeCallbacks(loadTimeoutRunnable)
            bufferingStartTimeMs = 0L
            service.notifyPlaying()
            
            // Persist as last successful URL
            streamUrl?.let { url ->
                persistence.lastSuccessfulUrl = url
            }
        }
    }
}

