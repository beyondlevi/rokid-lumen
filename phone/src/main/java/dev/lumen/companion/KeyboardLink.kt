package dev.lumen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.KeyboardCommand
import dev.lumen.protocol.KeyboardField
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The companion's keyboard for the glasses: the field focused there ([field], from the glasses'
 * input method, in any app) and the text typed here, sent live as the field's whole value (a lost
 * message heals with the next). Typing bursts go out at most every [THROTTLE_MS], the last text
 * always. When the wearer chooses the phone on the glasses ([KeyboardField.ASK]) and the keyboard
 * screen isn't open, a heads-up notification opens it. Main thread.
 */
object KeyboardLink {
    private const val TAG = "NbKeyboard"
    private const val THROTTLE_MS = 80L
    private const val CHANNEL = "glasses_keyboard"
    private const val NOTICE_ID = 4213
    /** The glasses' ask goes stale: their panel closes long before this. */
    private const val NOTICE_MS = 2 * 60_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Grows across openings, so the glasses never take a late text over a newer one. */
    private val seq = AtomicLong(System.currentTimeMillis())
    private var pending: String? = null
    private var lastSent = 0L
    private var app: Context? = null
    /** The keyboard screen is open (between [open] and [close]). */
    private var screenOpen = false

    /** The glasses' focused field; not focused when none, or nothing heard yet. */
    var field = KeyboardField(false)
        private set

    val listeners = mutableSetOf<() -> Unit>()

    /** The process starts ([CompanionApplication]): the notice needs a context, any time the glasses ask. */
    fun init(context: Context) {
        app = context.applicationContext
    }

    fun onGlassesField(json: JSONObject) {
        field = KeyboardField.from(json)
        val context = app
        if (context != null) {
            if (field.focused && field.reason == KeyboardField.ASK && !screenOpen) notify(context, field)
            if (!field.focused) cancelNotice(context)
        }
        listeners.toList().forEach { it() }
    }

    /** The keyboard's screen shows (and again every [KeyboardCommand.HEARTBEAT_MS] while it does). */
    fun open() {
        screenOpen = true
        app?.let { cancelNotice(it) }
        send(KeyboardCommand(KeyboardCommand.OPEN))
    }

    fun close() {
        screenOpen = false
        flush()
        send(KeyboardCommand(KeyboardCommand.CLOSE))
    }

    fun text(text: String) {
        pending = text
        main.removeCallbacks(flushRunnable)
        val wait = lastSent + THROTTLE_MS - android.os.SystemClock.uptimeMillis()
        if (wait <= 0) flush() else main.postDelayed(flushRunnable, wait)
    }

    /** Enter after the text it follows: the field's own action on the glasses (search, send). */
    fun enter() {
        flush()
        send(KeyboardCommand(KeyboardCommand.ENTER))
    }

    private val flushRunnable = Runnable { flush() }

    private fun flush() {
        main.removeCallbacks(flushRunnable)
        val text = pending ?: return
        pending = null
        lastSent = android.os.SystemClock.uptimeMillis()
        send(KeyboardCommand(KeyboardCommand.TEXT, text, seq.incrementAndGet()))
    }

    private fun send(command: KeyboardCommand) {
        CompanionService.requestKeyboard(command.toJson())
    }

    /**
     * A silent heads-up that opens the keyboard screen on the field. Its text names the field and
     * the app, never what the field holds.
     */
    private fun notify(context: Context, field: KeyboardField) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) {
            Log.d(TAG, "the glasses ask for the keyboard; notifications are off")
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.keyboard_notice_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val name = field.label.ifEmpty {
            context.getString(if (field.type == "password") R.string.keyboard_field_password else R.string.keyboard_field_text)
        }
        val open = PendingIntent.getActivity(
            context, NOTICE_ID,
            Intent(context, CompanionActivity::class.java).putExtra(CompanionActivity.EXTRA_PAGE, CompanionActivity.PAGE_KEYBOARD)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.keyboard_notice_title))
            .setContentText(
                if (field.app.isEmpty()) context.getString(R.string.keyboard_notice_text_field, name)
                else context.getString(R.string.keyboard_notice_text, name, field.app),
            )
            .setCategory(Notification.CATEGORY_STATUS)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(NOTICE_MS)
            .build()
        runCatching { manager.notify(NOTICE_ID, notification) }
            .onSuccess { Log.d(TAG, "the glasses ask for the keyboard (${field.type}): notified") }
            .onFailure { Log.w(TAG, "couldn't show the keyboard notice", it) }
    }

    private fun cancelNotice(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTICE_ID)
    }
}
