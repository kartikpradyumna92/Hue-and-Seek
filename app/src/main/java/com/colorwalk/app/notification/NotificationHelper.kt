package com.colorwalk.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.colorwalk.app.MainActivity
import com.colorwalk.app.R

object NotificationHelper {

    private const val CHANNEL_ID   = "streak_reminder"
    private const val CHANNEL_NAME = "Daily Streak Reminder"
    // Separate LOW-importance channel for the end-of-day nudge: no sound, no
    // vibration, no heads-up banner — it just waits in the shade. A separate
    // channel also lets the user silence ONLY the nudge in system settings.
    private const val LAST_CHANCE_CHANNEL_ID   = "streak_last_chance"
    private const val LAST_CHANCE_CHANNEL_NAME = "End-of-Day Gentle Nudge"
    const val NOTIFICATION_ID      = 1001

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminds you to complete today's color walk at your chosen morning and evening times"
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LAST_CHANCE_CHANNEL_ID,
                LAST_CHANCE_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "A silent last-call reminder on days both regular reminders were missed"
            }
        )
    }

    fun showReminder(context: Context, colorName: String, streak: Int) {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val nudge = when {
            streak >= 7  -> "Don't break your $streak day streak! 🔥"
            streak >= 3  -> "$streak days strong — keep it going!"
            streak == 1  -> "You started yesterday — don't stop now!"
            else         -> "Start your streak today!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("Find $colorName today 🎨")
            .setContentText(nudge)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("Today's color is $colorName. $nudge Head outside and snap it before the day ends!"))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }

    /**
     * End-of-day gentle nudge — fires only when both user-set reminders came and
     * went with the walk unfinished. Delivered on the silent LOW channel and under
     * the SAME notification id as the regular reminder, so it REPLACES an unread
     * morning/evening reminder instead of stacking a third entry in the shade.
     */
    fun showLastChanceNudge(context: Context, colorName: String, streak: Int) {
        val tapIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val body = if (streak > 0) {
            "A gentle nudge — one $colorName photo before midnight keeps your $streak-day streak going. Still time."
        } else {
            "A gentle nudge — today's $colorName walk is still open. No pressure, just a little time left."
        }

        val notification = NotificationCompat.Builder(context, LAST_CHANCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle("Today's $colorName is still out there")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setSilent(true) // belt-and-suspenders with the LOW channel: never a sound
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
