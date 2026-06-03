package com.colorwalk.app.notification

import android.content.Context
import com.colorwalk.app.ui.theme.ThemeMode

object NotificationPrefs {
    private const val PREFS = "app_prefs"
    private const val KEY_HOUR = "notification_hour"
    private const val KEY_MINUTE = "notification_minute"
    private const val KEY_ENABLED = "notifications_enabled"
    private const val KEY_THEME = "theme_mode"

    fun getHour(context: Context): Int =
        prefs(context).getInt(KEY_HOUR, 10)

    fun getMinute(context: Context): Int =
        prefs(context).getInt(KEY_MINUTE, 0)

    fun set(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_HOUR, hour).putInt(KEY_MINUTE, minute).apply()
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getThemeMode(context: Context): ThemeMode = try {
        ThemeMode.valueOf(prefs(context).getString(KEY_THEME, ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name)
    } catch (_: IllegalArgumentException) { ThemeMode.SYSTEM }

    fun setThemeMode(context: Context, mode: ThemeMode) {
        prefs(context).edit().putString(KEY_THEME, mode.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
