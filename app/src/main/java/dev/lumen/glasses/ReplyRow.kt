package dev.lumen.glasses

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * The reply row under an open notification (the notifications canvas): a real Android text
 * field, so the glasses' input method (Lumen's keyboard: dictation, the band's handwriting, the
 * phone) types into it, and a round send button. The band's focus moves between them ([move]);
 * Android's focus goes to the field only when the index tap asks ([activate]) and leaves it
 * when the band does, so the field never takes the keyboard by itself.
 *
 * The toolkit's metrics, as the quick bar's: 72 high, fully rounded, a 2 px outline at 27 %
 * white at rest, focused 3 px at 86 % over #111113 ([MetaStyle.focused]).
 */
class ReplyRow(private val activity: Activity, private val onSend: (String) -> Unit, private val onChange: () -> Unit) {
    val view = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(24f), 0, px(24f), 0)
        visibility = View.GONE
    }

    /** The field the input method types into. */
    val input: EditText = EditText(activity).apply {
        setTextColor(MetaStyle.TEXT)
        setHintTextColor(MetaStyle.TEXT_PLACEHOLDER)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
        typeface = MetaStyle.REGULAR
        includeFontPadding = false
        isSingleLine = true
        setHorizontallyScrolling(true)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        setPadding(px(24f), 0, px(24f), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
        // Enter sends (the keyboard's "Enter: send"); no full-screen editor over the HUD.
        imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        highlightColor = Color.argb(90, 255, 255, 255)
        textCursorDrawable = GradientDrawable().apply {
            setColor(Color.WHITE)
            setSize(maxOf(2, px(2.5f)), px(26f))
        }
        defaultFocusHighlightEnabled = false
        // Not focusable until the index tap: Android would otherwise give it the window's first focus.
        isFocusable = false
        isFocusableInTouchMode = false
    }

    private val send = FrameLayout(activity).apply { contentDescription = activity.getString(R.string.notification_reply_send) }
    private val sendIcon = ImageView(activity).apply { setImageResource(R.drawable.ic_send_arrow) }

    /** What's typed and how its sending went. */
    var state = ReplyState()
        private set

    /** Where the band's focus is in the row, when it's here ([active]). */
    var part = ReplyPart.FIELD
        private set

    /** The band's focus is in the row (the page shows it there). */
    var active = false
        set(value) {
            field = value
            if (!value) releaseField()
            applyFocus()
        }

    private var key: String? = null
    private val imm get() = activity.getSystemService(InputMethodManager::class.java)

    init {
        view.addView(input, LinearLayout.LayoutParams(0, px(HEIGHT), 1f))
        send.addView(sendIcon, FrameLayout.LayoutParams(px(30f), px(30f), Gravity.CENTER))
        view.addView(send, LinearLayout.LayoutParams(px(HEIGHT), px(HEIGHT)).apply { marginStart = px(12f) })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                state = state.edited(s?.toString().orEmpty())
                applyFocus()
                onChange()
            }
        })
        input.setOnEditorActionListener { _, actionId, event ->
            val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER || event?.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
            if (actionId == EditorInfo.IME_ACTION_SEND || (enter && event?.action == KeyEvent.ACTION_DOWN)) sendNow()
            // Every Enter is the field's: a new line has no place in a one-line reply.
            actionId == EditorInfo.IME_ACTION_SEND || enter
        }
        input.setOnFocusChangeListener { _, focused ->
            if (focused) focusedRow = java.lang.ref.WeakReference(this)
            else if (focusedRow?.get() === this) focusedRow = null
            applyFocus()
            onChange()
        }
        applyFocus()
    }

    /** The row for notification [key] (from [name]): the same one keeps what's typed, another starts empty. */
    fun show(key: String, name: String) {
        if (key != this.key) {
            releaseField()
            this.key = key
            state = ReplyState()
            input.setText("")
            part = ReplyPart.FIELD
        }
        input.hint = activity.getString(R.string.notification_reply_placeholder, name)
        view.visibility = View.VISIBLE
        applyFocus()
    }

    fun hide() {
        releaseField()
        view.visibility = View.GONE
    }

    val shown: Boolean get() = view.visibility == View.VISIBLE

    /** The field has Android's focus (the input method may be up). */
    val fieldFocused: Boolean get() = input.hasFocus()

    /**
     * The field has Android's focus and an input method serves it: the index tap is the input
     * method's then (Lumen's keyboard opens its panel on it), not the page's.
     */
    val typing: Boolean get() = input.hasFocus() && imm?.isActive(input) == true

    /** Left or right: the field or the send button (leaving the field takes Android's focus off it). */
    fun move(delta: Int) {
        val next = NotificationDetail.side(delta)
        if (next == part) return
        part = next
        if (part != ReplyPart.FIELD) releaseField()
        applyFocus()
        onChange()
    }

    /** Back to the field (the band came into the row). */
    fun reset() {
        part = ReplyPart.FIELD
        applyFocus()
    }

    /** The index tap on the row: the field takes the focus and the keyboard, the send button sends. */
    fun activate() {
        when (part) {
            ReplyPart.FIELD -> focusField()
            ReplyPart.SEND -> sendNow()
        }
    }

    /** Android's focus into the field and the input method up, as a tap on it would. */
    fun focusField() {
        input.isFocusable = true
        input.isFocusableInTouchMode = true
        input.requestFocus()
        input.setSelection(input.text.length)
        // Once the field is the input method's: right after requestFocus it may not be yet.
        input.post {
            if (!input.hasFocus()) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) input.windowInsetsController?.show(android.view.WindowInsets.Type.ime())
            imm?.showSoftInput(input, 0)
        }
    }

    /** Android's focus off the field and the input method down; what's typed stays. */
    fun releaseField() {
        val had = input.hasFocus()
        // Android keeps serving a field until another view takes the focus, even unfocused: a
        // later setText (the field cleared after a reply) would start the keyboard on it again.
        val served = imm?.isActive(input) == true
        if (had || served) imm?.hideSoftInputFromWindow(input.windowToken, 0)
        input.isFocusableInTouchMode = false
        input.isFocusable = false
        if (had) input.clearFocus()
        if (had || served) {
            // The row takes the focus (no text field there), and Lumen's keyboard lets the index
            // tap be the page's again (the send button's).
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.requestFocus()
            LumenKeyboard.fieldReleased()
        }
    }

    private fun sendNow() {
        if (!state.canSend) return
        onSend(state.text.trim())
    }

    /** The reply is on its way to the phone. */
    fun sending() {
        state = state.sent()
        applyFocus()
        onChange()
    }

    /** How it went: sent clears the field, a failure keeps the text. */
    fun replied(ok: Boolean) {
        state = state.replied(ok)
        if (ok) input.setText("")
        applyFocus()
        onChange()
    }

    private fun applyFocus() {
        val onField = active && part == ReplyPart.FIELD
        val onSend = active && part == ReplyPart.SEND
        input.background = if (onField || input.hasFocus()) MetaStyle.focused(activity, 0, radius = 999f) else MetaStyle.outline(activity, radius = 999f)
        send.background = if (onSend) MetaStyle.focused(activity, 0, radius = 999f) else MetaStyle.pill(activity)
        // Bright when there's something to send (or the band is on it); dim otherwise.
        sendIcon.setColorFilter(if (state.canSend || onSend) MetaStyle.TEXT else SEND_DIM, PorterDuff.Mode.SRC_IN)
        send.alpha = if (state.sending) 0.5f else 1f
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    companion object {
        const val HEIGHT = 72f
        private val SEND_DIM = Color.parseColor("#6B6F76")

        /** The row whose field has Android's focus, if any (weakly: its screen may go). */
        private var focusedRow: java.lang.ref.WeakReference<ReplyRow>? = null

        /**
         * A notification's reply field has the focus and an input method serves it: Lumen's
         * keyboard takes the band's index tap then ([typing]). Main thread.
         */
        @JvmStatic
        fun isTyping(): Boolean = focusedRow?.get()?.typing == true
    }
}
