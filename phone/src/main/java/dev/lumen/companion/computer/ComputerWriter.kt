package dev.lumen.companion.computer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.companion.PhoneBand
import dev.lumen.companion.R
import dev.lumen.companion.ime.WrittenText
import org.json.JSONObject

/**
 * Writing on the computer with the band: its handwriting model turns letters into text, and the
 * phone types it as the computer's keyboard ([ComputerLink]), through the computer's layout. The
 * band reports its whole text each time, deletions included; [WrittenText] turns that into
 * backspaces and new letters at the cursor. The middle tap ends it (the bridge lets only that
 * through while it writes), and so does a pause, as on the handwriting keyboard. The band gives
 * no feedback of its own while it writes: a notification says it's writing and shows the last
 * letters. The text is never logged.
 */
object ComputerWriter : PhoneBand.HandwritingSink {
    private const val TAG = "NbComputer"
    private const val CHANNEL = "computer_writing"
    private const val NOTICE = 4208
    private const val IDLE_MS = 15_000L
    private const val FIRST_LETTER_MS = 30_000L
    private const val IDLE_CHECK_MS = 1_000L
    private const val PREVIEW_CHARS = 40

    private val main = Handler(Looper.getMainLooper())
    private val written = WrittenText()
    private var app: Context? = null
    private var wrote = false
    private var lastWriting = 0L
    private var ready = false

    @Volatile var writing = false
        private set

    val listeners = mutableSetOf<() -> Unit>()

    private val idleCheck = Runnable { checkIdle() }

    /** Starts writing on the connected computer; false when it can't (no computer, no band here). */
    fun start(context: Context): Boolean {
        val app = context.applicationContext.also { this.app = it }
        if (writing) return true
        if (!ComputerLink.connected || !PhoneBand.canWrite) {
            Log.d(TAG, "writing: no computer or no band here")
            return false
        }
        if (!PhoneBand.startHandwriting(app, this)) return false
        writing = true
        ready = false
        wrote = false
        written.reset()
        PhoneBand.resetHandwritingText("")
        lastWriting = System.currentTimeMillis()
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        Log.d(TAG, "writing on the computer")
        notice(app, alert = true)
        changed()
        return true
    }

    /** Ends the writing: the band goes back to the profile's gestures. */
    fun stop() {
        if (!writing) return
        writing = false
        main.removeCallbacks(idleCheck)
        app?.let {
            PhoneBand.stopHandwriting(it, this)
            it.getSystemService(NotificationManager::class.java)?.cancel(NOTICE)
        }
        Log.d(TAG, "writing ends")
        changed()
    }

    override fun onHandwriting(event: JSONObject) {
        if (!writing) return
        val app = app ?: return
        when (event.optString("type")) {
            "state" -> when (event.optString("phase")) {
                "ready" -> {
                    ready = true
                    lastWriting = System.currentTimeMillis()
                    notice(app, alert = false)
                }
                // The band stopped writing on its own (a failure, then its restore).
                "restoring", "finished" -> {
                    val problem = event.optString("problem").takeIf { it.isNotEmpty() && it != "null" }
                    Log.d(TAG, "the band stopped writing: ${problem ?: "done"}")
                    stop()
                }
            }
            "text" -> {
                lastWriting = System.currentTimeMillis()
                wrote = true
                val text = event.optString("text")
                val edit = written.update(text)
                val layout = ComputerProfiles.layout(app)
                val strokes = List(edit.delete) { ComputerKeys.Stroke(ComputerKeys.BACKSPACE) } + ComputerKeys.type(edit.insert, layout)
                if (strokes.isNotEmpty() && !ComputerLink.type(strokes)) Log.d(TAG, "typing failed: the computer isn't connected")
                preview = text.takeLast(PREVIEW_CHARS)
                notice(app, alert = false)
            }
        }
    }

    override fun onExit() {
        Log.d(TAG, "middle tap: done")
        stop()
    }

    private fun checkIdle() {
        if (!writing) return
        val limit = if (wrote) IDLE_MS else FIRST_LETTER_MS
        if (System.currentTimeMillis() - lastWriting >= limit) {
            Log.d(TAG, "writing paused: done")
            stop()
        } else {
            main.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
    }

    private var preview = ""

    private fun notice(context: Context, alert: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.computer_writing_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        if (alert) preview = ""
        val name = ComputerLink.computer?.name ?: context.getString(R.string.computer_generic)
        val title = context.getString(R.string.computer_writing_title, name)
        val hint = context.getString(if (ready) R.string.computer_writing_text else R.string.computer_writing_preparing)
        // On the lock screen: that it writes, not what.
        val public = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(hint)
            .build()
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(if (preview.isEmpty()) hint else "…$preview")
            .setSubText(if (preview.isEmpty()) null else hint)
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
        manager.notify(NOTICE, notification)
    }

    private fun changed() = listeners.toList().forEach { it() }
}
