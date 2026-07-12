package com.colorwalk.app.notification

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    private const val REQUEST_MORNING     = 2001
    private const val REQUEST_EVENING     = 2002
    private const val REQUEST_LAST_CHANCE = 2003

    const val EXTRA_SLOT       = "SLOT"
    const val SLOT_MORNING     = "MORNING"
    const val SLOT_EVENING     = "EVENING"
    const val SLOT_LAST_CHANCE = "LAST_CHANCE"

    // ── Last-chance nudge timing ─────────────────────────────────────────────
    // A quiet third reminder for days when the user-set reminders came and went
    // with the walk still unfinished. 9 PM is late enough that the day is clearly
    // slipping away but early enough to act on; if the user's own evening slot is
    // late, the nudge yields 90 minutes of breathing room so the two never feel
    // like a double-tap, and it never lands past 23:30 — a nudge at midnight
    // isn't gentle.
    internal const val LAST_CHANCE_DEFAULT_MINUTE = 21 * 60         // 21:00
    internal const val LAST_CHANCE_GAP_MINUTES    = 90              // after a late evening slot
    internal const val LAST_CHANCE_LATEST_MINUTE  = 23 * 60 + 30    // 23:30 hard cap
    internal const val LAST_CHANCE_MIN_GAP        = 30              // below this, evening IS the last call
    private  const val LATE_EVENING_MINUTE        = 19 * 60 + 30    // evening ≥ 19:30 pushes the nudge

    /**
     * Minute-of-day for the last-chance nudge, or null when it shouldn't exist
     * (the user's evening reminder is already so late it serves as the last call).
     * Pure — JVM-tested.
     */
    internal fun lastChanceMinuteOfDay(eveningEnabled: Boolean, eveningMinuteOfDay: Int): Int? {
        var candidate = LAST_CHANCE_DEFAULT_MINUTE
        if (eveningEnabled && eveningMinuteOfDay >= LATE_EVENING_MINUTE) {
            candidate = eveningMinuteOfDay + LAST_CHANCE_GAP_MINUTES
        }
        candidate = minOf(candidate, LAST_CHANCE_LATEST_MINUTE)
        if (eveningEnabled && candidate < eveningMinuteOfDay + LAST_CHANCE_MIN_GAP) return null
        return candidate
    }

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
        refreshLastChance(context)
    }

    fun scheduleEvening(context: Context) {
        schedule(
            context,
            hour        = NotificationPrefs.getEveningHour(context),
            minute      = NotificationPrefs.getEveningMinute(context),
            requestCode = REQUEST_EVENING,
            slot        = SLOT_EVENING
        )
        refreshLastChance(context)
    }

    fun cancelMorning(context: Context) {
        cancelOne(context, REQUEST_MORNING, SLOT_MORNING)
        refreshLastChance(context)
    }

    fun cancelEvening(context: Context) {
        cancelOne(context, REQUEST_EVENING, SLOT_EVENING)
        refreshLastChance(context)
    }

    /**
     * Arms or disarms the last-chance nudge from current prefs. Armed only while
     * the master toggle is on AND at least one regular slot is enabled — the nudge
     * backs up the user's own reminders; it is not an independent third slot.
     * Every slot mutation above routes through here, so the nudge tracks evening
     * time changes automatically.
     */
    fun refreshLastChance(context: Context) {
        val anySlot = NotificationPrefs.isEnabled(context) &&
            (NotificationPrefs.isMorningEnabled(context) || NotificationPrefs.isEveningEnabled(context))
        val minuteOfDay = if (anySlot) {
            lastChanceMinuteOfDay(
                eveningEnabled = NotificationPrefs.isEveningEnabled(context),
                eveningMinuteOfDay = NotificationPrefs.getEveningHour(context) * 60 +
                    NotificationPrefs.getEveningMinute(context)
            )
        } else null
        if (minuteOfDay == null) {
            cancelOne(context, REQUEST_LAST_CHANCE, SLOT_LAST_CHANCE)
        } else {
            schedule(
                context,
                hour        = minuteOfDay / 60,
                minute      = minuteOfDay % 60,
                requestCode = REQUEST_LAST_CHANCE,
                slot        = SLOT_LAST_CHANCE
            )
        }
    }

    /** Cancels all alarms (used by master toggle off). */
    fun cancel(context: Context) {
        cancelOne(context, REQUEST_MORNING, SLOT_MORNING)
        cancelOne(context, REQUEST_EVENING, SLOT_EVENING)
        cancelOne(context, REQUEST_LAST_CHANCE, SLOT_LAST_CHANCE)
    }

    private fun schedule(context: Context, hour: Int, minute: Int, requestCode: Int, slot: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)

        val intent = PendingIntent.getBroadcast(
            context,
            requestCode,
            Intent(context, StreakReminderReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val triggerMillis = nextTriggerMillis(hour, minute)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (alarmManager.canScheduleExactAlarms()) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, intent)
            } else {
                alarmManager.setWindow(AlarmManager.RTC_WAKEUP, triggerMillis - 7 * 60 * 1000L, 15 * 60 * 1000L, intent)
            }
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, intent)
        }
    }

    /**
     * Next occurrence of the local wall-clock time [hour]:[minute] strictly after
     * [nowMillis] — today if still ahead, otherwise the same time tomorrow.
     * Pure (I-6: JVM-tested) — the Calendar handles month/year/DST rollover.
     */
    internal fun nextTriggerMillis(hour: Int, minute: Int, nowMillis: Long = System.currentTimeMillis()): Long =
        Calendar.getInstance().apply {
            timeInMillis = nowMillis
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= nowMillis) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

    private fun cancelOne(context: Context, requestCode: Int, slot: String) {
        val alarmManager = context.getSystemService(AlarmManager::class.java)
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, StreakReminderReceiver::class.java).putExtra(EXTRA_SLOT, slot),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )?.let { alarmManager.cancel(it) }
    }
}
