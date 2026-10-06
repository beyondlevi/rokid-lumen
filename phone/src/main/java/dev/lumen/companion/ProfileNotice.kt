package dev.lumen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/**
 * The phone says which gesture profile is on after a switch: a short heads-up, silent, gone in
 * a few seconds (the band gives no feedback of its own that tells profiles apart).
 */
object ProfileNotice {
    private const val CHANNEL = "band_profile"
    private const val ID = 4207
    private const val SHOWN_MS = 4_000L

    fun show(context: Context, from: String, to: String) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.profile_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.profile_notice_title, to))
            .setContentText(context.getString(R.string.profile_notice_text, from, to))
            .setCategory(Notification.CATEGORY_STATUS)
            .setTimeoutAfter(SHOWN_MS)
            .setAutoCancel(true)
            .build()
        manager.notify(ID, notification)
    }
}
