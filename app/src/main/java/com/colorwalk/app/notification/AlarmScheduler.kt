package com.colorwalk.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 2001

    fun scheduleDaily(context: Context) {
        val hour = NotificationPrefs.getHour(context)
        val minute = NotificationPrefs.getMinute(context)

        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StreakReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // On API 31+, exact alarms require SCHEDULE_EXACT_ALARM / USE_EXACT_ALARM.
        // On API 33+ with USE_EXACT_ALARM declared, canScheduleExactAlarms() returns true automatically.
        // Pre-API 31: setExactAndAllowWhileIdle works without any permission.
        // setWindow (inexact) gets deferred in Doze mode and can silently miss the window.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    trigger.timeInMillis,
                    intent
                )
            } else {
                alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP,
                    trigger.timeInMillis - 7 * 60 * 1000L,
                    15 * 60 * 1000L,
                    intent
                )
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                trigger.timeInMillis,
                intent
            )
        }
    }

    @Deprecated("Use scheduleDaily which reads user preference", ReplaceWith("scheduleDaily(context)"))
    fun scheduleDailyNoon(context: Context) = scheduleDaily(context)

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StreakReminderReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        intent?.let { alarmManager.cancel(it) }
    }
}
