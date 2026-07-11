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
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                // M-5: with notifications revoked at the OS level, notify() is
                // silently dropped — skip the DB reads entirely, but still fall
                // through to the reschedule below so reminders resume the day the
                // user re-enables notifications.
                if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
                    val dao = AppDatabase.getInstance(context).photoDao()

                    val midnight = StreakCalculator.todayMidnightMs()
                    val tomorrowMidnight = midnight + 24L * 60 * 60 * 1000
                    val capturedToday = dao.getPhotoForDay(midnight, tomorrowMidnight) != null

                    if (!capturedToday) {
                        val colorName = colorForDay(System.currentTimeMillis()).name
                        val streak    = StreakCalculator.compute(dao.getAllPhotoDates())
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
            } finally {
                pendingResult.finish()
            }
        }
    }
}
