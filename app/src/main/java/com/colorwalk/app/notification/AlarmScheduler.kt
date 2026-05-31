package com.colorwalk.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_CODE = 2001

    fun scheduleDailyNoon(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, StreakReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Set trigger for today's noon; if noon already passed, schedule for tomorrow
        val noon = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 12)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        // setWindow fires within a 15-min window around noon — no special permission needed
        alarmManager.setWindow(
            AlarmManager.RTC_WAKEUP,
            noon.timeInMillis - 7 * 60 * 1000L,   // start: 11:53 AM
            15 * 60 * 1000L,                        // window: 15 minutes
            intent
        )
    }

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
