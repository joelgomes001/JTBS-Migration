package com.jtbs.box.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil

class RecoveryManager(
    private val context: Context,
    private val service: DecoderPlaybackService,
    private val persistence: CrashPersistence
) {
    fun handleFrozenFrame() {
        LogUtil.logI(LogUtil.TAG_RECOVERY, "Watchdog: Frozen frame detected!")
        escalateAndRecover("Frozen frame")
    }

    fun handleBufferStall() {
        LogUtil.logI(LogUtil.TAG_RECOVERY, "Watchdog: Buffer stall detected!")
        escalateAndRecover("Buffer stall")
    }

    fun handleLoadTimeout() {
        LogUtil.logI(LogUtil.TAG_RECOVERY, "Watchdog: Stream load timeout!")
        escalateAndRecover("Load timeout")
    }

    private fun escalateAndRecover(reason: String) {
        val count = persistence.recoveryCount
        val stage = persistence.lastRecoveryStage

        // Surface loss: pause rendering, wait for new surface, do NOT trigger recovery
        if (!service.isSurfaceReady) {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val timestamp = sdf.format(java.util.Date())
            LogUtil.logI(LogUtil.TAG_RECOVERY, "[$timestamp] WAITING_FOR_SURFACE")
            service.notifyOffline("Waiting For Surface")
            return
        }

        // Critical Fix 5: Timestamped RECOVERY_STAGE logging
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_RECOVERY, "[$timestamp] RECOVERY_STAGE (stage=$stage, count=$count, reason=$reason)")

        LogUtil.logI(LogUtil.TAG_RECOVERY, "Escalating recovery. Current Count: $count | Stage: $stage | Reason: $reason")

        if (count >= 5) {
            LogUtil.logE(LogUtil.TAG_RECOVERY, "Maximum recovery attempts reached (5). Staying offline.")
            service.notifyOffline("Recovery failed after 5 attempts")
            return
        }

        service.notifyOffline("Recovering ($reason)...")

        when (stage) {
            0 -> {
                // Stage 1: URL Reload
                persistence.lastRecoveryStage = 1
                persistence.recoveryCount = count + 1
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 1: Reloading URL")
                service.reloadStream()
            }
            1 -> {
                // Stage 2: Player Recreation
                persistence.lastRecoveryStage = 2
                persistence.recoveryCount = count + 1
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 2: Recreating Player")
                service.recreatePlayerAndPlay()
            }
            2 -> {
                // Stage 3: Service Restart (via AlarmManager and self-kill)
                persistence.lastRecoveryStage = 3
                persistence.recoveryCount = count + 1
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 3: Restarting Service via AlarmManager")
                triggerServiceRestart()
            }
            3 -> {
                // Stage 4: Service Restart Loop (up to 5 attempts)
                persistence.recoveryCount = count + 1
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 4: Service restart loop (Count: ${persistence.recoveryCount})")
                triggerServiceRestart()
            }
            else -> {
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Unknown recovery stage: $stage. Resetting.")
                persistence.clearRecoveryState()
                service.recreatePlayerAndPlay()
            }
        }
    }

    private fun triggerServiceRestart() {
        try {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, DecoderPlaybackService::class.java)
            
            val flags = if (android.os.Build.VERSION.SDK_INT >= 23) {
                PendingIntent.FLAG_ONE_SHOT or 0x04000000 // PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_ONE_SHOT
            }
            
            val pendingIntent = PendingIntent.getService(
                context,
                0,
                intent,
                flags
            )
            val triggerAtMs = System.currentTimeMillis() + 3000L
            
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            LogUtil.logI(LogUtil.TAG_RECOVERY, "Scheduled service restart in 3s. Killing current process...")
            
            // Self-kill process to completely release native codecs
            Process.killProcess(Process.myPid())
        } catch (e: Exception) {
            LogUtil.logE(LogUtil.TAG_RECOVERY, "Failed to schedule service restart", e)
            // Fallback: reload stream
            service.recreatePlayerAndPlay()
        }
    }

    fun reset() {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
        val timestamp = sdf.format(java.util.Date())
        LogUtil.logI(LogUtil.TAG_RECOVERY, "[$timestamp] RECOVERY_RESET")
        persistence.clearRecoveryState()
    }
}
