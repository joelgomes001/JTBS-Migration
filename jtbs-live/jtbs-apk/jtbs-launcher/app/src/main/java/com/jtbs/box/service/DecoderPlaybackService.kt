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
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.Player
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.player.PlayerFactory
import com.jtbs.box.recovery.RecoveryManager
import com.jtbs.box.util.LogUtil
import com.jtbs.box.watchdog.PlayerHealthMonitor

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
    
    private var player: ExoPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    private lateinit var persistence: CrashPersistence
    private lateinit var recoveryManager: RecoveryManager
    private lateinit var watchdog: PlayerHealthMonitor
    
    private var currentUrl: String? = null
    private var lastSurface: Surface? = null
    private val handler = Handler(Looper.getMainLooper())
    private val playbackGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var activePlaybackSessionId = 0L
    private var currentPlayerInstanceId = 0
    private var streamUpdatedAtMs = 0L
    private var streamDurationSec = 0
    private var streamPlayMode = "live"

    // Surface-First Architecture: URL stored on startup but playback deferred until surface arrives
    var pendingStartupUrl: String? = null
        private set
    
    // Track whether a valid surface is currently attached
    @Volatile
    var isSurfaceReady: Boolean = false
        private set
    
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
        
        // Critical Fix 4: Clear stale recovery state from any previous session
        // to prevent immediate escalation to service-restart/process-kill on startup
        persistence.clearRecoveryState()
        logStartup("RECOVERY_STATE_CLEARED")
        
        recoveryManager = RecoveryManager(this, this, persistence)
        watchdog = PlayerHealthMonitor(this, this, persistence, recoveryManager)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        logStartup("SERVICE_ON_START_COMMAND")
        
        // Acquire WakeLock
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "JTBS:PlaybackWakeLock").apply {
                acquire()
            }
            logStartup("WAKELOCK_ACQUIRED")
        }

        // Critical Fix 1: Do NOT auto-play. Store URL for deferred playback.
        // Playback will ONLY start when MainActivity calls tryStartPendingPlayback()
        // after BOTH service is bound AND surface is ready.
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

        // Start stats loop
        handler.removeCallbacks(statsRunnable)
        handler.postDelayed(statsRunnable, 10000)

        return START_STICKY
    }

    fun getPlayer(): ExoPlayer? = player

    fun getPlaybackGeneration(): Int = playbackGeneration.get()

    fun getActivePlaybackSessionId(): Long = activePlaybackSessionId

    fun setSurface(surface: Surface?) {
        lastSurface = surface
        isSurfaceReady = surface != null && surface.isValid
    }

    /**
     * Called by MainActivity.tryStartPendingPlayback() when BOTH service is bound AND surface is ready.
     * Consumes the pending startup URL and starts playback.
     */
    fun consumePendingStartupUrl(): String? {
        val url = pendingStartupUrl
        pendingStartupUrl = null
        return url
    }

    fun switchStream(url: String, updatedAtMs: Long = 0L, durationSec: Int = 0, playMode: String = "live") {
        if (url == currentUrl && player != null) return
        
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] SOURCE_SWITCH_START (url=$url)")

        // FIX 1 & 3: Increment generation and session ID
        val gen = playbackGeneration.incrementAndGet()
        activePlaybackSessionId = System.currentTimeMillis()

        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] GENERATION_CREATED (gen=$gen)")
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] SESSION_CREATED (session=$activePlaybackSessionId)")

        // FIX 2: Reset recovery state
        LogUtil.logI(LogUtil.TAG_RECOVERY, "[$timestamp] SOURCE_SWITCH_RESET")
        persistence.clearRecoveryState()
        recoveryManager.reset()
        watchdog.reset()

        currentUrl = url
        streamUpdatedAtMs = updatedAtMs
        streamDurationSec = durationSec
        streamPlayMode = playMode
        
        // Save attempted URL
        persistence.lastAttemptedUrl = url
        
        // Clear pending startup since we're now actively switching
        pendingStartupUrl = null
        
        recreatePlayerAndPlay()
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] SOURCE_SWITCH_COMPLETE")
    }

    fun recreatePlayerAndPlay() {
        val url = currentUrl
        if (url.isNullOrEmpty()) {
            notifyOffline("Empty URL")
            return
        }

        // Stop watchdog
        watchdog.stop()

        // Release old player with removal of listeners
        player?.let { oldPlayer ->
            oldPlayer.removeListener(watchdog)
            oldPlayer.stop()
            oldPlayer.clearMediaItems()
            oldPlayer.release()
            player = null
            logStartup("OLD_PLAYER_RELEASED")
        }

        // Build new player
        currentPlayerInstanceId++
        val instId = currentPlayerInstanceId
        try {
            val newPlayer = PlayerFactory.create(this)
            player = newPlayer
            logStartup("PLAYER_CREATED")
            
            // Broadcast player recreation to MainActivity immediately
            val intent = Intent(ACTION_PLAYER_RECREATED).apply {
                putExtra("generation", playbackGeneration.get())
                putExtra("session_id", activePlaybackSessionId)
            }
            sendBroadcast(intent)

            // Register frame listeners
            watchdog.registerVideoFrameListener(newPlayer)
            newPlayer.addListener(watchdog)

            // Load media item
            val mediaItem = buildMediaItem(url)
            newPlayer.setMediaItem(mediaItem)
            logStartup("MEDIA_ITEM_SET: $url")
            
            newPlayer.prepare()
            logStartup("PLAYER_PREPARED")
            
            // IPTV Synchronization Seek: seek to the correct elapsed frame based on stream start time
            if (streamDurationSec > 0 && streamUpdatedAtMs > 0L) {
                val now = System.currentTimeMillis()
                val elapsedMs = Math.max(0L, now - streamUpdatedAtMs)
                val durationMs = streamDurationSec * 1000L
                val seekPosMs = if (streamPlayMode == "loop" || streamPlayMode == "repeat") {
                    elapsedMs % durationMs
                } else {
                    if (elapsedMs < durationMs) elapsedMs else -1L
                }
                
                if (seekPosMs >= 0L) {
                    newPlayer.seekTo(seekPosMs)
                    logStartup("IPTV_SYNC_SEEK: ${seekPosMs}ms (elapsed=${elapsedMs}ms, duration=${durationMs}ms)")
                }
            }
            
            newPlayer.playWhenReady = true
            logStartup("PLAYER_STARTED")
            
            // Critical Fix 6: Watchdog starts AFTER surface attached + player prepared
            watchdog.start(url, instId)
            logStartup("WATCHDOG_STARTED")
            
        } catch (e: Exception) {
            LogUtil.logE(LogUtil.TAG_PLAYER, "Failed to recreate player", e)
            notifyOffline("Player creation failure: ${e.message}", instId)
        }
    }

    private fun buildMediaItem(url: String): MediaItem {
        val lowerUrl = url.lowercase(java.util.Locale.US)
        val mimeType = when {
            lowerUrl.endsWith(".m3u8") || lowerUrl.endsWith(".mpd") -> null
            lowerUrl.contains("/m3u8") || 
            lowerUrl.contains("m3u8?") || 
            lowerUrl.contains(".m3u8?") ||
            lowerUrl.contains("format=m3u8") ||
            lowerUrl.contains("stream.m3u8") -> "application/x-mpegURL"
            lowerUrl.contains("/mpd") || 
            lowerUrl.contains("mpd?") ||
            lowerUrl.contains(".mpd?") ||
            lowerUrl.contains("format=mpd") -> "application/dash+xml"
            lowerUrl.contains(".mp4") || 
            lowerUrl.contains("/mp4") || 
            lowerUrl.contains("mp4?") ||
            lowerUrl.contains("format=mp4") -> "video/mp4"
            lowerUrl.contains(".mkv") || 
            lowerUrl.contains("/mkv") || 
            lowerUrl.contains("mkv?") ||
            lowerUrl.contains("format=mkv") -> "video/x-matroska"
            lowerUrl.contains(".webm") || 
            lowerUrl.contains("/webm") || 
            lowerUrl.contains("webm?") ||
            lowerUrl.contains("format=webm") -> "video/webm"
            lowerUrl.contains(".avi") || 
            lowerUrl.contains("/avi") || 
            lowerUrl.contains("avi?") ||
            lowerUrl.contains("format=avi") -> "video/x-msvideo"
            lowerUrl.contains(".mov") || 
            lowerUrl.contains("/mov") || 
            lowerUrl.contains("mov?") ||
            lowerUrl.contains("format=mov") -> "video/quicktime"
            lowerUrl.contains(".ts") || 
            lowerUrl.contains("/ts") || 
            lowerUrl.contains("ts?") ||
            lowerUrl.contains("format=ts") -> "video/mp2t"
            else -> null
        }
        return if (mimeType != null) {
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(mimeType)
                .build()
        } else {
            MediaItem.fromUri(url)
        }
    }

    fun reloadStream() {
        recreatePlayerAndPlay()
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun stop() {
        watchdog.stop()
        player?.stop()
        currentUrl = null
    }

    fun notifyPlaying(instanceId: Int = currentPlayerInstanceId) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())

        if (instanceId != currentPlayerInstanceId) {
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] PLAYING_BROADCAST_IGNORED (incoming $instanceId != current $currentPlayerInstanceId)")
            return
        }

        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] PLAYING_BROADCAST_SENT (gen=${playbackGeneration.get()}, session=$activePlaybackSessionId)")
        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_PLAYING)
            putExtra("generation", playbackGeneration.get())
            putExtra("session_id", activePlaybackSessionId)
        }
        sendBroadcast(intent)
    }

    fun notifyOffline(reason: String, instanceId: Int = currentPlayerInstanceId) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())

        if (instanceId != currentPlayerInstanceId) {
            LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OFFLINE_BROADCAST_IGNORED (incoming $instanceId != current $currentPlayerInstanceId)")
            return
        }

        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] OFFLINE_BROADCAST_SENT (reason=$reason, gen=${playbackGeneration.get()}, session=$activePlaybackSessionId)")
        val intent = Intent(ACTION_PLAYBACK_STATE).apply {
            putExtra(EXTRA_STATE, STATE_OFFLINE)
            putExtra(EXTRA_REASON, reason)
            putExtra("generation", playbackGeneration.get())
            putExtra("session_id", activePlaybackSessionId)
        }
        sendBroadcast(intent)
    }

    private fun logStartup(event: String) {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_PLAYER, "[$timestamp] $event")
    }

    private fun logStats() {
        val p = player
        val format = p?.videoFormat
        val resolution = if (format != null) "${format.width}x${format.height}" else "Unknown"
        val bitrate = format?.bitrate ?: 0
        val decoderType = format?.sampleMimeType ?: "Unknown"
        
        val counters = p?.videoDecoderCounters
        val dropped = counters?.droppedBufferCount ?: 0
        
        val bufferPercent = p?.bufferedPercentage ?: 0
        val position = p?.currentPosition ?: 0L
        val bufferDuration = p?.totalBufferedDuration ?: 0L
        
        val stateString = when (p?.playbackState) {
            Player.STATE_IDLE -> "STATE_IDLE"
            Player.STATE_BUFFERING -> "STATE_BUFFERING"
            Player.STATE_READY -> if (p.playWhenReady) "STATE_PLAYING" else "STATE_PAUSED"
            Player.STATE_ENDED -> "STATE_ENDED"
            else -> "STATE_UNKNOWN"
        }
        
        val ramUsage = Debug.getNativeHeapAllocatedSize() / (1024 * 1024) // MB
        
        val recoveryCount = persistence.recoveryCount
        val recoveryStage = persistence.lastRecoveryStage
        
        LogUtil.logI(
            LogUtil.TAG_STREAM,
            "Telemetry - URL: $currentUrl | Res: $resolution | Bitrate: $bitrate | Dropped: $dropped | " +
            "Buffer: $bufferPercent% | Decoder: $decoderType | RAM: ${ramUsage}MB | State: $stateString | " +
            "Pos: ${position}ms | BufDur: ${bufferDuration}ms | RecCount: $recoveryCount | RecStage: $recoveryStage"
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
            it.removeListener(watchdog)
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
