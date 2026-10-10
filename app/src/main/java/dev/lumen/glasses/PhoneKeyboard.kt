package dev.lumen.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.protocol.KeyboardCommand
import dev.lumen.protocol.KeyboardField
import dev.lumen.protocol.Link
import org.json.JSONObject

/**
 * The companion's keyboard, typing into the field focused on the glasses through Lumen's input
 * method ([LumenKeyboard], the [Target] while a text field has the input, in any app): the phone
 * hears which field it is ([KeyboardField]) and sends the field's whole text as it's typed
 * ([KeyboardCommand]). The field's text goes to the phone only while its keyboard is open (or
 * the wearer asks for it, [ask]); otherwise the phone hears the field's name and type only. A
 * keyboard the phone stops confirming (its screen gone without a word, the companion killed) is
 * forgotten after [KeyboardCommand.EXPIRY_MS]. Main thread.
 */
object PhoneKeyboard {
    private const val TAG = "BandKeyboard"

    /** What types for the phone: the input method's focused field. */
    interface Target {
        fun keyboardOpen(open: Boolean)
        fun keyboardText(text: String)
        fun keyboardEnter()
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var target: Target? = null
    private var field = KeyboardField(false)
    private var lastSeq = 0L
    private var heardAt = 0L

    /** Whether the phone's keyboard is open now. */
    @JvmStatic
    var open = false
        private set

    private val expire = Runnable {
        if (open && SystemClock.elapsedRealtime() - heardAt >= KeyboardCommand.EXPIRY_MS) {
            Log.d(TAG, "the phone's keyboard went quiet; closed")
            setOpen(false)
        }
    }

    /** [target] has a text field now: it hears the keyboard's state. */
    @JvmStatic
    fun attach(target: Target) {
        this.target = target
        target.keyboardOpen(open)
    }

    /** [target] lost its field: no field is focused for the phone any more. */
    @JvmStatic
    fun detach(target: Target) {
        if (this.target !== target) return
        this.target = null
        blur()
    }

    /** The focused field in [from], or its text again ([KeyboardField.SYNC]); only the attached target counts. */
    @JvmStatic
    fun focus(from: Target, app: String, label: String, type: String, multiline: Boolean, value: String, reason: String) {
        if (from !== target) return
        report(KeyboardField(true, app, label, type.ifEmpty { "text" }, multiline, value, reason))
    }

    /** The wearer chose the phone's keyboard for the field: the phone offers to open it ([KeyboardField.ASK]). */
    @JvmStatic
    fun ask(from: Target, app: String, label: String, type: String, multiline: Boolean, value: String) {
        if (from !== target) return
        report(KeyboardField(true, app, label, type.ifEmpty { "text" }, multiline, value, KeyboardField.ASK), force = true)
    }

    @JvmStatic
    fun blur(from: Target) {
        if (from === target) blur()
    }

    private fun blur() {
        if (field.focused) report(KeyboardField(false, field.app))
    }

    fun onPhoneMessage(json: JSONObject) {
        val command = KeyboardCommand.from(json)
        when (command.action) {
            KeyboardCommand.OPEN -> {
                heardAt = SystemClock.elapsedRealtime()
                main.removeCallbacks(expire)
                main.postDelayed(expire, KeyboardCommand.EXPIRY_MS)
                if (open) return
                setOpen(true)
                // The phone fills its box with what the field holds.
                report(field.copy(reason = if (field.focused) KeyboardField.FOCUS else KeyboardField.BLUR), force = true)
            }
            KeyboardCommand.CLOSE -> setOpen(false)
            KeyboardCommand.TEXT -> {
                // The phone's seq only grows (across its keyboard's openings): a late one never wins.
                if (command.seq <= lastSeq) return
                lastSeq = command.seq
                if (field.focused) target?.keyboardText(command.text)
            }
            KeyboardCommand.ENTER -> if (field.focused) target?.keyboardEnter()
        }
    }

    private fun setOpen(next: Boolean) {
        if (open == next) return
        open = next
        if (!next) main.removeCallbacks(expire)
        Log.d(TAG, "phone keyboard ${if (next) "open" else "closed"}")
        target?.keyboardOpen(next)
    }

    /** Keeps the field whole here; what goes out leaves its text behind unless the phone types into it. */
    private fun report(next: KeyboardField, force: Boolean = false) {
        val sent = sendable(next)
        val same = sent == sendable(field)
        field = next
        if (same && !force) return
        PhoneLink.send(Link.KEYBOARD_FIELD, sent.toJson())
    }

    private fun sendable(field: KeyboardField): KeyboardField =
        if (open || field.reason == KeyboardField.ASK) field else field.copy(value = "")
}
