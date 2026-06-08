package com.colorwalk.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_MORNING = 2001
    private const val REQUEST_EVENING = 2002

    const val EXTRA_SLOT   = "SLOT"
    const val SLOT_MORNING = "MORNING"
    const val SLOT_EVENING = "EVENING"

    /** Schedules whichever slots are individually enabled; cancels the others. */
    fun scheduleBoth(context: Context) {
        if (NotificationPrefs.isMorningEnabled(context)) scheduleMorning(context)
        else cancelMorning(context)
        if (NotificationPrefs.isEveningEnabled(context)) scheduleEvening(context)
        else cancelEvening(context)
    }

    fun scheduleMorning(context: Context) {
        schedule(
            context,
            hour        = NotificationPrefs.getMorningHour(context),
            minute      = NotificationPrefs.getMorningMinute(context),
            requestCode = REQUEST_MORNING,
            slot        = SLOT_MORNING
        )
    }

    fun scheduleEvening(context: Context) {
        schedule(
            context,
            hour        = NotificationPrefs.getEveningHour(context),
            minute      = NotificationPrefs.getEveningMinute(context),
            requestCode = REQUEST_EVENING,
            slot        = SLOT_EVENING
        )
    }

    fun cancelMorning(context: Context) = cancelOne(context, REQUEST_MORNING, SLOT_MORNING)
    fun cancelEvening(context: Context) = cancelOne(context, REQUEST_EVENING, SLOT_EVENING)

    /** Cancels all alarms (used by master toggle off). */
    fun cancel(context: Context) {
        cancelMorning(context)
        cancelEvening(context)
    }

    private fun schedule(context: Context, hour: Int, minute: Int, requestCode: Int, slot: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, StreakReminderReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val trigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, intent)
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger.timeInMillis - 7 * 60 * 1000L, 15 * 60 * 1000L, intent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger.timeInMillis, intent)
        }
    }

    private fun cancelOne(context: Context, requestCode: Int, slot: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, StreakReminderReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
    }
}
