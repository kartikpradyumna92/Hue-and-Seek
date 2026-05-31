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
    const val NOTIFICATION_ID      = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Reminds you to complete today's color walk at noon"
        }
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
}
