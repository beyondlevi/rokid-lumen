package dev.lumen.glasses

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The camera canvas's ask (board 1): over a web app, "[App] wants to use the camera" with Allow
 * and Not now, the app dimmed behind. Left and right move between the two, the index tap
 * answers, the middle tap is Not now. In the app's own screen ([WebAppActivity]), over the square,
 * with its hint in the strip under it.
 */
class WebCameraPrompt(private val activity: Activity, root: FrameLayout, side: Int) {
    private var answer: ((Boolean) -> Unit)? = null
    /** 0 Allow, 1 Not now. */
    private var choice = 0
    /** The key pressed down while it's up, whose up acts. */
    private var pressed = NONE

    private val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.argb(179, 0, 0, 0))
        visibility = View.GONE
        // Nothing under it takes a tap meanwhile.
        isClickable = true
    }
    private val title = text(27.5f, MetaStyle.BOLD, MetaStyle.TEXT)
    private val buttons = listOf(R.string.camera_ask_allow, R.string.camera_ask_not_now).map { label ->
        text(22.5f, MetaStyle.MEDIUM, MetaStyle.TEXT).apply {
            setText(label)
            gravity = Gravity.CENTER
        }
    }
    private val hint = text(20f, MetaStyle.REGULAR, MetaStyle.TEXT_SECONDARY).apply {
        setText(R.string.camera_ask_hint)
        gravity = Gravity.CENTER
        setPadding(px(22f), 0, px(22f), 0)
        background = MetaStyle.pill(activity)
        visibility = View.GONE
    }

    /** The question is up: the band's keys are its own. */
    val shown: Boolean get() = answer != null

    init {
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = MetaStyle.outline(activity, radius = 35f, alpha = 110)
            setPadding(px(30f), px(32.5f), px(30f), px(27.5f))
        }
        val icon = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1E1E1E"))
            }
            addView(ImageView(activity).apply {
                setImageResource(R.drawable.ic_camera_outline)
                setColorFilter(MetaStyle.TEXT)
            }, FrameLayout.LayoutParams(px(37.5f), px(37.5f), Gravity.CENTER))
        }
        card.addView(icon, LinearLayout.LayoutParams(px(80f), px(80f)))
        title.gravity = Gravity.CENTER
        card.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(17.5f) })
        val body = text(21.25f, MetaStyle.REGULAR, MetaStyle.TEXT_SECONDARY).apply {
            setText(R.string.camera_ask_body)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.2f)
        }
        card.addView(body, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(17.5f) })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.forEachIndexed { i, button ->
            row.addView(button, LinearLayout.LayoutParams(0, px(75f), 1f).apply { if (i > 0) marginStart = px(12.5f) })
        }
        card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(25f) })
        view.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = px(87.5f)
            marginStart = px(30f)
            marginEnd = px(30f)
        })
        root.addView(view, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        val strip = (activity.resources.displayMetrics.heightPixels - side) / 2
        if (strip >= px(HINT + 10f)) {
            root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(HINT), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (strip - px(HINT)) / 2
            })
        }
    }

    /** Asks for [appName]; [answer] true when the wearer allows. A question still up is Not now. */
    fun ask(appName: String, answer: (Boolean) -> Unit) {
        finish(false)
        title.text = activity.getString(R.string.camera_ask_title, appName)
        choice = 0
        pressed = NONE
        this.answer = answer
        render()
        view.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
    }

    /** The band's keys while it's up: all of them are its own. */
    fun onKey(event: KeyEvent): Boolean {
        if (!shown) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) pressed = event.keyCode
            return true
        }
        // The up of a key pressed before it showed (the tap that opened it) does nothing.
        if (event.action != KeyEvent.ACTION_UP || event.keyCode != pressed) return true
        pressed = NONE
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> move(0)
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> move(1)
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> finish(choice == 0)
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> finish(false)
        }
        return true
    }

    /** The screen goes: the question is Not now. */
    fun dismiss() = finish(false)

    private fun move(to: Int) {
        if (to == choice) return
        choice = to
        render()
    }

    private fun render() {
        buttons.forEachIndexed { i, button ->
            val focused = i == choice
            button.background = if (focused) MetaStyle.focused(activity, 0, radius = 999f) else MetaStyle.outline(activity, radius = 999f)
            button.typeface = if (focused) MetaStyle.BOLD else MetaStyle.MEDIUM
        }
    }

    private fun finish(allowed: Boolean) {
        val done = answer ?: return
        answer = null
        view.visibility = View.GONE
        hint.visibility = View.GONE
        done(allowed)
    }

    private fun text(size: Float, face: android.graphics.Typeface, color: Int) = TextView(activity).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(activity, size))
        typeface = face
        includeFontPadding = false
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    private companion object {
        /** The hint pill's height (the canvas's 40 px). */
        const val HINT = 50f
        const val NONE = -1
    }
}
