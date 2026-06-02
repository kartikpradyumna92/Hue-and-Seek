package com.colorwalk.app.notification

import android.content.Context

object NotificationPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_HOUR = "notification_hour"
    private const val KEY_MINUTE = "notification_minute"

    fun getHour(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOUR, 12)

    fun getMinute(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MINUTE, 0)

    fun set(context: Context, hour: Int, minute: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
    }
}
