package com.jtbs.box.recovery

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.jtbs.box.persistence.CrashPersistence
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil

class RecoveryManager(private val service: DecoderPlaybackService) {
    private val context: Context = service.applicationContext
    private val persistence = CrashPersistence(context)
    private val handler = Handler(Looper.getMainLooper())

    fun handleFrozenFrame(currentUrl: String?) {
        LogUtil.logE(LogUtil.TAG_RECOVERY, "Watchdog: Frozen frame detected!")
        escalateAndRecover(currentUrl)
    }

    fun handleBufferStall(currentUrl: String?) {
        LogUtil.logE(LogUtil.TAG_RECOVERY, "Watchdog: Buffer stall detected (> 15 seconds)!")
        escalateAndRecover(currentUrl)
    }

    fun handleLoadTimeout(currentUrl: String?) {
        LogUtil.logE(LogUtil.TAG_RECOVERY, "Watchdog: Stream load timeout!")
        escalateAndRecover(currentUrl)
    }

    private fun escalateAndRecover(currentUrl: String?) {
        val nextStage = persistence.lastRecoveryStage + 1
        LogUtil.logI(LogUtil.TAG_RECOVERY, "Escalating recovery to Stage $nextStage")

        when (nextStage) {
            1 -> {
                persistence.lastRecoveryStage = 1
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 1: Reloading current URL: $currentUrl")
                if (currentUrl != null) {
                    service.reloadStream(currentUrl)
                } else {
                    escalateAndRecover(currentUrl)
                }
            }
            2 -> {
                persistence.lastRecoveryStage = 2
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 2: Recreating player...")
                service.recreatePlayerAndPlay()
            }
            3 -> {
                persistence.lastRecoveryStage = 3
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 3: Restarting service...")
                restartServiceProcess()
            }
            else -> {
                // Stage 4: Service restart loop (Nuclear recovery)
                val count = persistence.recoveryCount + 1
                persistence.recoveryCount = count
                persistence.lastRecoveryStage = 4
                LogUtil.logI(LogUtil.TAG_RECOVERY, "Stage 4: Nuclear service restart loop (Attempt $count / 5)")
                
                if (count <= 5) {
                    restartServiceProcess()
                } else {
                    LogUtil.logE(LogUtil.TAG_RECOVERY, "Nuclear recovery failed 5 times. Staying offline.")
                    service.notifyOffline("Nuclear recovery failed 5 times")
                }
            }
        }
    }

    private fun restartServiceProcess() {
        try {
            val intent = Intent(context, DecoderPlaybackService::class.java)
            // API 22 compatibility: do NOT use FLAG_IMMUTABLE, use FLAG_ONE_SHOT
            val pendingIntent = PendingIntent.getService(
                context,
                0,
                intent,
                PendingIntent.FLAG_ONE_SHOT
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerTime = System.currentTimeMillis() + 3000 // 3 seconds delay
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
            
            LogUtil.logI(LogUtil.TAG_RECOVERY, "Scheduled service restart in 3 seconds. Killing current process...")
            
            // Post death to let things clean up or exit immediately
            handler.postDelayed({
                service.stopSelf()
                Process.killProcess(Process.myPid())
                System.exit(0)
            }, 500)
        } catch (e: Exception) {
            LogUtil.logE(LogUtil.TAG_RECOVERY, "Failed to schedule service restart", e)
            // Fallback: stopSelf
            service.stopSelf()
        }
    }
}

