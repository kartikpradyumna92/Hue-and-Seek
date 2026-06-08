package com.colorwalk.app.notification

import android.content.Context
import com.colorwalk.app.ui.theme.ThemeMode

object NotificationPrefs {
    private const val PREFS                = "app_prefs"
    private const val KEY_ENABLED          = "notifications_enabled"
    private const val KEY_MORNING_ENABLED  = "morning_enabled"
    private const val KEY_EVENING_ENABLED  = "evening_enabled"
    private const val KEY_MORNING_HOUR     = "morning_hour"
    private const val KEY_MORNING_MINUTE   = "morning_minute"
    private const val KEY_EVENING_HOUR     = "evening_hour"
    private const val KEY_EVENING_MINUTE   = "evening_minute"
    private const val KEY_THEME            = "theme_mode"

    // Master toggle
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    // Per-slot toggles
    fun isMorningEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MORNING_ENABLED, true)
    fun setMorningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_MORNING_ENABLED, enabled).apply()
    }
    fun isEveningEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EVENING_ENABLED, true)
    fun setEveningEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EVENING_ENABLED, enabled).apply()
    }

    // Per-slot times
    fun getMorningHour(context: Context): Int   = prefs(context).getInt(KEY_MORNING_HOUR, 10)
    fun getMorningMinute(context: Context): Int = prefs(context).getInt(KEY_MORNING_MINUTE, 0)
    fun setMorning(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_MORNING_HOUR, hour).putInt(KEY_MORNING_MINUTE, minute).apply()
    }
    fun getEveningHour(context: Context): Int   = prefs(context).getInt(KEY_EVENING_HOUR, 17)
    fun getEveningMinute(context: Context): Int = prefs(context).getInt(KEY_EVENING_MINUTE, 0)
    fun setEvening(context: Context, hour: Int, minute: Int) {
        prefs(context).edit().putInt(KEY_EVENING_HOUR, hour).putInt(KEY_EVENING_MINUTE, minute).apply()
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
