package com.colorwalk.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
                val dao = AppDatabase.getInstance(context).photoDao()

                val midnight = StreakCalculator.todayMidnightMs()
                val tomorrowMidnight = midnight + 24L * 60 * 60 * 1000
                val capturedToday = dao.getPhotoForDay(midnight, tomorrowMidnight) != null

                if (!capturedToday) {
                    val colorName = colorForDay(System.currentTimeMillis()).name
                    val streak    = StreakCalculator.compute(dao.getAllPhotoDates())
                    NotificationHelper.showReminder(context, colorName, streak)
                }

                // Reschedule this specific slot for tomorrow (respects per-slot enabled state)
                if (NotificationPrefs.isEnabled(context)) {
                    if (slot == AlarmScheduler.SLOT_MORNING && NotificationPrefs.isMorningEnabled(context))
                        AlarmScheduler.scheduleMorning(context)
                    else if (slot == AlarmScheduler.SLOT_EVENING && NotificationPrefs.isEveningEnabled(context))
                        AlarmScheduler.scheduleEvening(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
