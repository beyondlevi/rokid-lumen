package dev.lumen.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log

/**
 * The test notification's reply action: what the glasses send to it (a quick reply) replaces the
 * notification's text, so the round trip shows on the phone without messaging anyone.
 */
class TestReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString() ?: return
        Log.d("NbNotify", "test notification answered from the glasses")
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            CompanionActivity.TEST_ID,
            Notification.Builder(context, CompanionActivity.TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notifications_test_title, context.getString(R.string.app_name)))
                .setContentText(context.getString(R.string.notifications_test_replied, text))
                .setCategory(NotificationForwarder.CATEGORY_TEST)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val KEY = "reply"

        /** The test notification ("Send a test notification"), with its reply action. */
        fun post(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                android.app.NotificationChannel(CompanionActivity.TEST_CHANNEL, context.getString(R.string.notifications_test_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            manager.notify(
                CompanionActivity.TEST_ID,
                Notification.Builder(context, CompanionActivity.TEST_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(context.getString(R.string.notifications_test_title, context.getString(R.string.app_name)))
                    .setContentText(context.getString(R.string.notifications_test_text))
                    .setCategory(NotificationForwarder.CATEGORY_TEST)
                    .setAutoCancel(true)
                    // A reply action, so the glasses' quick replies can be tried without messaging anyone.
                    .addAction(action(context))
                    .build(),
            )
        }

        fun action(context: Context): Notification.Action {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, 0, Intent(context, TestReplyReceiver::class.java), flags)
            val input = RemoteInput.Builder(KEY).setLabel(context.getString(R.string.notifications_test_reply)).build()
            return Notification.Action.Builder(Icon.createWithResource(context, android.R.drawable.ic_menu_send), context.getString(R.string.notifications_test_reply), pending)
                .addRemoteInput(input)
                .setAllowGeneratedReplies(false)
                .build()
        }
    }
}
