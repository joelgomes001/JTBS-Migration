package com.example.jtbs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jtbs.box.service.DecoderPlaybackService
import com.jtbs.box.util.LogUtil

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        LogUtil.logI(LogUtil.TAG_PLAYER, "BootReceiver received action: $action")
        if (Intent.ACTION_BOOT_COMPLETED == action || Intent.ACTION_MY_PACKAGE_REPLACED == action) {
            try {
                val serviceIntent = Intent(context, DecoderPlaybackService::class.java)
                context?.startService(serviceIntent)
                LogUtil.logI(LogUtil.TAG_PLAYER, "BootReceiver started DecoderPlaybackService successfully")
            } catch (e: Exception) {
                LogUtil.logE(LogUtil.TAG_PLAYER, "BootReceiver failed to start DecoderPlaybackService", e)
            }
        }
    }
}
