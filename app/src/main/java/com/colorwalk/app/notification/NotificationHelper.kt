package com.colorwalk.app.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.colorwalk.app.MainActivity
import com.colorwalk.app.R
import com.colorwalk.app.ui.components.colorDisplayName

object NotificationHelper {

    private const val CHANNEL_ID   = "streak_reminder"
    // Separate LOW-importance channel for the end-of-day nudge: no sound, no
    // vibration, no heads-up banner — it just waits in the shade. A separate
    // channel also lets the user silence ONLY the nudge in system settings.
    private const val LAST_CHANCE_CHANNEL_ID   = "streak_last_chance"
    const val NOTIFICATION_ID      = 1001

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_reminder_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_channel_reminder_desc)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(
                LAST_CHANCE_CHANNEL_ID,
                context.getString(R.string.notif_channel_last_chance_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notif_channel_last_chance_desc)
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
        val color = context.colorDisplayName(colorName)
        val res = context.resources

        val nudge = when {
            streak >= 7  -> res.getQuantityString(R.plurals.notif_nudge_dont_break, streak, streak)
            streak >= 3  -> res.getQuantityString(R.plurals.notif_nudge_strong, streak, streak)
            streak == 1  -> context.getString(R.string.notif_nudge_started_yesterday)
            else         -> context.getString(R.string.notif_nudge_start_today)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.notif_reminder_title, color))
            .setContentText(nudge)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(context.getString(R.string.notif_reminder_body, color, nudge)))
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
        val color = context.colorDisplayName(colorName)

        val body = if (streak > 0) {
            context.resources.getQuantityString(R.plurals.notif_last_chance_body_streak, streak, color, streak)
        } else {
            context.getString(R.string.notif_last_chance_body, color)
        }

        val notification = NotificationCompat.Builder(context, LAST_CHANCE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notification)
            .setContentTitle(context.getString(R.string.notif_last_chance_title, color))
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
