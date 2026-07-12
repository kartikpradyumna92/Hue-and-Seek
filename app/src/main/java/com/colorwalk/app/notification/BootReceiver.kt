package com.colorwalk.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the one-shot reminder alarms whenever the system drops or invalidates
 * them: device reboot, app update (alarms are cancelled on package replace), and
 * clock/timezone changes (RTC alarms keep their old wall-clock millis and would
 * fire at the wrong local time).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,        // manifest action android.intent.action.TIME_SET
            Intent.ACTION_TIMEZONE_CHANGED,
            // L-10: user granted SCHEDULE_EXACT_ALARM (API 31+) — re-arm now so
            // reminders upgrade from the inexact setWindow fallback immediately.
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" -> {
                if (NotificationPrefs.isEnabled(context)) {
                    AlarmScheduler.scheduleBoth(context)
                }
            }
        }
    }
}
