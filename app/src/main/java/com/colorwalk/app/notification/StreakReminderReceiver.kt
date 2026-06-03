package com.colorwalk.app.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.colorwalk.app.data.db.AppDatabase
import com.colorwalk.app.domain.StreakCalculator
import com.colorwalk.app.domain.colorForDay
import com.colorwalk.app.ui.widget.ColorWalkWidget
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Calendar

class StreakReminderReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val pendingResult = goAsync()
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val dao = AppDatabase.getInstance(context).photoDao()

                // Check if already captured today
                val cal = Calendar.getInstance().apply {
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val midnight = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                val tomorrowMidnight = cal.timeInMillis
                val capturedToday = dao.getPhotoForDay(midnight, tomorrowMidnight) != null

                if (!capturedToday) {
                    val colorName = colorForDay(System.currentTimeMillis()).name
                    val streak    = StreakCalculator.compute(dao.getRecentPhotoDates())
                    NotificationHelper.showReminder(context, colorName, streak)
                }

                // Refresh widget for the new day
                ColorWalkWidget.requestUpdate(context)

                // Reschedule for tomorrow only if still enabled
                if (NotificationPrefs.isEnabled(context)) {
                    AlarmScheduler.scheduleDaily(context)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
