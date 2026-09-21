package com.jtbs.box.persistence

import android.content.Context
import android.content.SharedPreferences

class CrashPersistence(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jtbs_crash_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_LAST_SUCCESSFUL_URL = "last_successful_url"
        private const val KEY_LAST_ATTEMPTED_URL = "last_attempted_url"
        private const val KEY_LAST_RECOVERY_STAGE = "last_recovery_stage"
        private const val KEY_RECOVERY_COUNT = "recovery_count"
        private const val KEY_LAST_SUCCESS_TIMESTAMP = "last_success_timestamp"
    }

    var lastSuccessfulUrl: String?
        get() = prefs.getString(KEY_LAST_SUCCESSFUL_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_SUCCESSFUL_URL, value).apply()

    var lastAttemptedUrl: String?
        get() = prefs.getString(KEY_LAST_ATTEMPTED_URL, null)
        set(value) = prefs.edit().putString(KEY_LAST_ATTEMPTED_URL, value).apply()

    var lastRecoveryStage: Int
        get() = prefs.getInt(KEY_LAST_RECOVERY_STAGE, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_RECOVERY_STAGE, value).apply()

    var recoveryCount: Int
        get() = prefs.getInt(KEY_RECOVERY_COUNT, 0)
        set(value) = prefs.edit().putInt(KEY_RECOVERY_COUNT, value).apply()

    var lastSuccessTimestamp: Long
        get() = prefs.getLong(KEY_LAST_SUCCESS_TIMESTAMP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SUCCESS_TIMESTAMP, value).apply()

    fun clearRecoveryState() {
        prefs.edit()
            .putInt(KEY_LAST_RECOVERY_STAGE, 0)
            .putInt(KEY_RECOVERY_COUNT, 0)
            .apply()
    }
}
