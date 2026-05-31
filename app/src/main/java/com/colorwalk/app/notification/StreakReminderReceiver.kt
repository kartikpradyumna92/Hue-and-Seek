package com.colorwalk.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class StreakReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).photoDao()

                // Check if already captured today
                val midnight = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                val capturedToday = dao.getPhotoForDay(midnight) != null

                if (!capturedToday) {
                    val colorName = colorForDay(System.currentTimeMillis()).name
                    val streak    = StreakCalculator.compute(dao.getRecentPhotoDates())
                    NotificationHelper.showReminder(context, colorName, streak)
                }

                // Reschedule for tomorrow
                AlarmScheduler.scheduleDailyNoon(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
