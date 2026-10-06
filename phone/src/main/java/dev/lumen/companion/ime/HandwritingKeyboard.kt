package dev.lumen.companion.ime

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import dev.lumen.band.Identity
import dev.lumen.companion.CompanionPrefs
import dev.lumen.companion.PhoneBand
import dev.lumen.companion.R
import org.json.JSONObject

/**
 * Lumen's handwriting keyboard: chosen as the phone's keyboard, opening it on a text field switches
 * the band's handwriting model on (the band must be on this phone, see [PhoneBand]), and what the
 * band reads goes into the field at the cursor. The band's middle tap ends the writing and hides
 * the keyboard; a pause stops the writing (30 s before the first letter, 15 s after). Password
 * fields never get the band. Space, delete, Enter and a switch to the previous keyboard are on
 * the keyboard itself.
 *
 * The band gives no haptic feedback while it writes, so the keyboard shows each letter as it
 * comes. The text is never logged.
 */
class HandwritingKeyboard : InputMethodService(), PhoneBand.HandwritingSink {
    private enum class State { IDLE, PASSWORD, NO_KEY, ELSEWHERE, CONNECTING, WRITING, STOPPED }

    private val main = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private val written = WrittenText()
    private var wrote = false
    private var lastWriting = 0L
    private var preview = ""

    private lateinit var status: TextView
    private lateinit var shown: TextView
    private lateinit var action: TextView
    private lateinit var hint: TextView

    private val bandChanged: () -> Unit = { onBandChanged() }
    private val idleCheck = Runnable { checkIdle() }

    override fun onCreate() {
        super.onCreate()
        PhoneBand.listeners += bandChanged
    }

    override fun onDestroy() {
        stopWriting()
        PhoneBand.listeners -= bandChanged
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = Math.round(value * density)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17, 19, 21))
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        status = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
        }
        shown = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            maxLines = 2
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
        }
        hint = TextView(this).apply {
            setTextColor(Color.rgb(160, 170, 178))
            textSize = 12f
            text = getString(R.string.ime_hint)
        }
        action = key(R.string.ime_write_again, primary = true) { onAction() }
        val keys = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, 0)
        }
        fun add(view: View, weight: Float) =
            keys.addView(view, LinearLayout.LayoutParams(0, dp(48), weight).apply { setMargins(dp(3), 0, dp(3), 0) })
        add(key(R.string.ime_switch) { switchKeyboard() }, 1f)
        add(key(R.string.ime_space) { type(" ") }, 2f)
        add(key(R.string.ime_delete) { delete() }, 1f)
        add(key(R.string.ime_enter) { enter() }, 1f)
        panel.addView(status)
        panel.addView(shown)
        panel.addView(hint)
        panel.addView(action, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(8) })
        panel.addView(keys)
        render()
        return panel
    }

    private fun key(label: Int, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = getString(label)
        gravity = Gravity.CENTER
        textSize = 15f
        setTextColor(if (primary) Color.rgb(8, 20, 14) else Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = 20f * resources.displayMetrics.density
            setColor(if (primary) ACCENT else Color.rgb(45, 49, 54))
        }
        setOnClickListener { onClick() }
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        written.reset()
        preview = ""
        state = when {
            isPassword(info.inputType) -> State.PASSWORD
            !Identity.present(this) -> State.NO_KEY
            else -> State.CONNECTING
        }
        if (state == State.CONNECTING) begin()
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopWriting()
        state = State.IDLE
        super.onFinishInputView(finishingInput)
    }

    /** Start writing if the band is here; otherwise say where it is. */
    private fun begin() {
        if (!CompanionPrefs.bandOnPhone(this)) {
            state = State.ELSEWHERE
            render()
            return
        }
        // The keyboard may come up before the companion's service: start the link here.
        if (!PhoneBand.isRunning) PhoneBand.resume(this)
        if (!PhoneBand.canWrite) {
            state = State.CONNECTING
            render()
            return
        }
        if (!PhoneBand.startHandwriting(this, this)) {
            state = State.CONNECTING
            render()
            return
        }
        state = State.WRITING
        written.reset()
        PhoneBand.resetHandwritingText("")
        wrote = false
        lastWriting = System.currentTimeMillis()
        status.text = getString(R.string.ime_status_preparing)
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, IDLE_CHECK_MS)
        render()
    }

    private fun stopWriting() {
        main.removeCallbacks(idleCheck)
        PhoneBand.stopHandwriting(this, this)
    }

    private fun onBandChanged() {
        if (state == State.CONNECTING && PhoneBand.canWrite) begin()
        else if (state == State.CONNECTING || state == State.ELSEWHERE) render()
    }

    override fun onHandwriting(event: JSONObject) {
        if (state != State.WRITING) return
        when (event.optString("type")) {
            "state" -> when (event.optString("phase")) {
                "ready" -> {
                    lastWriting = System.currentTimeMillis()
                    status.text = getString(R.string.ime_status_ready)
                }
                "restoring", "finished" -> {
                    // The band stopped on its own (a failure, then its restore).
                    val problem = event.optString("problem").takeIf { it.isNotEmpty() && it != "null" }
                    stopWriting()
                    state = State.STOPPED
                    render()
                    if (problem != null) status.text = getString(R.string.ime_status_failed, problem)
                }
            }
            "text" -> {
                lastWriting = System.currentTimeMillis()
                wrote = true
                val text = event.optString("text")
                val edit = written.update(text)
                val connection = currentInputConnection ?: return
                if (edit.delete > 0 || edit.insert.isNotEmpty()) {
                    connection.beginBatchEdit()
                    if (edit.delete > 0) connection.deleteSurroundingText(edit.delete, 0)
                    if (edit.insert.isNotEmpty()) connection.commitText(edit.insert, 1)
                    connection.endBatchEdit()
                }
                preview = text.takeLast(PREVIEW_CHARS)
                shown.text = preview
            }
        }
    }

    /** The band's middle tap: the writing ends and the keyboard steps aside. */
    override fun onExit() {
        Log.d(TAG, "middle tap: done")
        stopWriting()
        state = State.STOPPED
        requestHideSelf(0)
    }

    private fun checkIdle() {
        if (state != State.WRITING) return
        val limit = if (wrote) IDLE_MS else FIRST_LETTER_MS
        if (System.currentTimeMillis() - lastWriting >= limit) {
            Log.d(TAG, "writing paused: stopped")
            stopWriting()
            state = State.STOPPED
            render()
        } else {
            main.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
    }

    private fun onAction() {
        when (state) {
            State.ELSEWHERE -> {
                PhoneBand.useHere(this)
                state = State.CONNECTING
                render()
            }
            State.STOPPED -> begin()
            else -> {}
        }
    }

    /** A key on the keyboard: typed as it is, and the band's text counts from here on. */
    private fun type(text: String) {
        currentInputConnection?.commitText(text, 1)
        afterKey()
    }

    private fun delete() {
        val connection = currentInputConnection ?: return
        if (connection.getSelectedText(0).isNullOrEmpty()) connection.deleteSurroundingText(1, 0)
        else connection.commitText("", 1)
        afterKey()
    }

    private fun enter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val multiline = info != null && info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (!multiline && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            currentInputConnection?.commitText("\n", 1)
        }
        afterKey()
    }

    private fun afterKey() {
        written.reset()
        if (state == State.WRITING) PhoneBand.resetHandwritingText("")
    }

    private fun switchKeyboard() {
        stopWriting()
        if (!switchToPreviousInputMethod()) {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
        }
    }

    private fun render() {
        if (!::status.isInitialized) return
        status.text = getString(
            when (state) {
                State.PASSWORD -> R.string.ime_status_password
                State.NO_KEY -> R.string.ime_status_no_key
                State.ELSEWHERE -> R.string.ime_status_elsewhere
                State.CONNECTING -> R.string.ime_status_connecting
                State.WRITING -> R.string.ime_status_preparing
                State.STOPPED, State.IDLE -> R.string.ime_status_stopped
            },
        )
        shown.text = preview
        shown.visibility = if (state == State.WRITING || preview.isNotEmpty()) View.VISIBLE else View.GONE
        hint.visibility = if (state == State.WRITING) View.VISIBLE else View.GONE
        action.visibility = if (state == State.ELSEWHERE || state == State.STOPPED) View.VISIBLE else View.GONE
        action.text = getString(if (state == State.ELSEWHERE) R.string.ime_use_here else R.string.ime_write_again)
    }

    companion object {
        private const val TAG = "NbHandwriting"
        private val ACCENT = Color.rgb(102, 242, 165)
        private const val IDLE_MS = 15_000L
        private const val FIRST_LETTER_MS = 30_000L
        private const val IDLE_CHECK_MS = 1_000L
        private const val PREVIEW_CHARS = 60

        /** Password fields, of any kind: the band never writes in them. */
        fun isPassword(inputType: Int): Boolean {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (inputType and InputType.TYPE_MASK_CLASS) {
                InputType.TYPE_CLASS_TEXT -> variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                    variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
                else -> false
            }
        }
    }
}
