package dev.lumen.companion

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

/**
 * The test notification's reply action: what the glasses send to it (a quick or typed reply)
 * replaces the notification's text, so the round trip shows on the phone without messaging
 * anyone. The notification is a chat (MessagingStyle) whose last message is a photo, as
 * WhatsApp posts one, so the glasses' pictures can be tried without a real chat.
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
        private const val TAG = "NbNotify"

        /**
         * The test notification ("Send a test notification"), with its reply action and, with
         * [picture], a photo in its last message (a generated JPEG behind the app's own
         * FileProvider, as a chat app shares its media).
         */
        fun post(context: Context, picture: Boolean = true) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                android.app.NotificationChannel(CompanionActivity.TEST_CHANNEL, context.getString(R.string.notifications_test_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val builder = Notification.Builder(context, CompanionActivity.TEST_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(context.getString(R.string.notifications_test_title, context.getString(R.string.app_name)))
                .setContentText(context.getString(R.string.notifications_test_text))
                .setCategory(NotificationForwarder.CATEGORY_TEST)
                .setAutoCancel(true)
                // A reply action, so the glasses' replies can be tried without messaging anyone.
                .addAction(action(context))
            val uri = if (picture) runCatching { testPicture(context) }.onFailure { Log.w(TAG, "test picture", it) }.getOrNull() else null
            if (uri != null) {
                val now = System.currentTimeMillis()
                val me = Person.Builder().setName(context.getString(R.string.notifications_test_you)).build()
                val sender = Person.Builder().setName(context.getString(R.string.notifications_test_sender)).setKey("lumen-test").build()
                builder.setStyle(
                    Notification.MessagingStyle(me)
                        .addMessage(Notification.MessagingStyle.Message(context.getString(R.string.notifications_test_text), now - 1_000, sender))
                        .addMessage(Notification.MessagingStyle.Message(context.getString(R.string.notifications_test_picture), now, sender).setData("image/jpeg", uri)),
                )
            }
            manager.notify(CompanionActivity.TEST_ID, builder.build())
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

        /**
         * A landscape drawn here (sky, sun, two ridges), 960 x 640 so the phone's downscale
         * runs as for a real photo, saved in the cache and shared as a content URI.
         */
        private fun testPicture(context: Context): Uri {
            val width = 960
            val height = 640
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(40, 90, 170), Color.rgb(250, 190, 120), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            paint.shader = null
            paint.color = Color.rgb(255, 240, 200)
            canvas.drawCircle(width * 0.72f, height * 0.38f, 70f, paint)
            fun ridge(color: Int, base: Float, peaks: List<Pair<Float, Float>>) {
                paint.color = color
                val path = Path().apply {
                    moveTo(0f, height.toFloat())
                    lineTo(0f, base)
                    peaks.forEach { (x, y) -> lineTo(x, y) }
                    lineTo(width.toFloat(), base)
                    lineTo(width.toFloat(), height.toFloat())
                    close()
                }
                canvas.drawPath(path, paint)
            }
            ridge(Color.rgb(70, 80, 110), 430f, listOf(180f to 300f, 360f to 400f, 560f to 250f, 760f to 390f))
            ridge(Color.rgb(25, 30, 45), 540f, listOf(140f to 470f, 330f to 520f, 520f to 430f, 700f to 500f, 880f to 450f))
            val dir = File(context.cacheDir, "test").apply { mkdirs() }
            val file = File(dir, "picture.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            bitmap.recycle()
            return FileProvider.getUriForFile(context, context.packageName + ".files", file)
        }
    }
}
