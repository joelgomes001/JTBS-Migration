package com.jtbs.box.util

import android.util.Log

object LogUtil {
    const val TAG_PLAYER = "JTBS_PLAYER"
    const val TAG_WATCHDOG = "JTBS_WATCHDOG"
    const val TAG_RECOVERY = "JTBS_RECOVERY"
    const val TAG_STREAM = "JTBS_STREAM"

    fun logI(tag: String, msg: String) {
        Log.i(tag, msg)
    }

    fun logE(tag: String, msg: String, tr: Throwable? = null) {
        if (tr != null) {
            Log.e(tag, msg, tr)
        } else {
            Log.e(tag, msg)
        }
    }

    fun logD(tag: String, msg: String) {
        Log.d(tag, msg)
    }
}
