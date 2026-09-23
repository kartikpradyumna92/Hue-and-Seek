package com.colorwalk.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class StreakReminderReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val slot = intent.getStringExtra(AlarmScheduler.EXTRA_SLOT) ?: AlarmScheduler.SLOT_MORNING
        val nowMillis = System.currentTimeMillis()
        // Alarms armed by older versions carry no trigger time; "now" is the best proxy.
        val triggerAt = intent.getLongExtra(AlarmScheduler.EXTRA_TRIGGER_AT, -1L)
            .takeIf { it > 0L } ?: nowMillis
        val slotDay = StreakCalculator.epochMillisToDayIndex(triggerAt)
        val today = StreakCalculator.epochMillisToDayIndex(nowMillis)
        // Mark this slot's day as done BEFORE rescheduling, so the reschedule below
        // (and any scheduleBoth() later today) skips to tomorrow (BUG-004).
        NotificationPrefs.setLastFiredDay(context, slot, slotDay)
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            // L-7: an uncaught exception here would leak the pending result until the
            // ~10s ANR window; catch, log, and always finish() — worst case one
            // reminder is skipped and the reschedule below still re-arms tomorrow.
            try {
                // M-5: with notifications revoked at the OS level, notify() is
                // silently dropped — skip the DB reads entirely, but still fall
                // through to the reschedule below so reminders resume the day the
                // user re-enables notifications.
                // A slot for a PAST day (inexact/Doze delivery slipped past midnight)
                // would announce the wrong day's color — drop it, just reschedule.
                if (slotDay >= today &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                ) {
                    val dao = AppDatabase.getInstance(context).photoDao()

                    // BUG-025: same "today" as Home and the streak (frozen dayIndex).
                    val capturedToday = dao.hasPhotoOnDay(today)

                    if (!capturedToday) {
                        val colorName = colorForDay(System.currentTimeMillis()).name
                        val streak    = StreakCalculator.computeFromDayIndices(dao.getAllPhotoDayIndices())
                        if (slot == AlarmScheduler.SLOT_LAST_CHANCE) {
                            // Silent end-of-day nudge — the user missed the regular
                            // reminder(s) and the walk is still open.
                            NotificationHelper.showLastChanceNudge(context, colorName, streak)
                        } else {
                            NotificationHelper.showReminder(context, colorName, streak)
                        }
                    }
                }

                // Reschedule this specific slot for tomorrow (respects per-slot enabled state)
                if (NotificationPrefs.isEnabled(context)) {
                    when (slot) {
                        AlarmScheduler.SLOT_MORNING ->
                            if (NotificationPrefs.isMorningEnabled(context)) AlarmScheduler.scheduleMorning(context)
                        AlarmScheduler.SLOT_EVENING ->
                            if (NotificationPrefs.isEveningEnabled(context)) AlarmScheduler.scheduleEvening(context)
                        AlarmScheduler.SLOT_LAST_CHANCE ->
                            AlarmScheduler.refreshLastChance(context) // self-gates on prefs
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("StreakReminderReceiver", "Reminder handling failed for slot $slot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
