package dev.lumen.glasses

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A row of the toolkit's QuickReplyButtons under an open notification: the web app that takes
 * it (its icon only, first; icon and name when several do) and quick replies (reactions as
 * icon-only buttons, then short texts).
 * The toolkit's metrics: 72 high, fully rounded, 24 padding, a 24 px label, icon-only buttons a
 * 72 circle, at rest inset 8; the focused one full size on the focused material, its label
 * primary. Outlines on black, as [MetaStyle] explains for this display. The band's commands come
 * through [move] and [current]; it never takes Android's focus.
 */
class QuickReplyBar(private val activity: Activity) {
    sealed class Action {
        /** Opens [target]'s web app (at its page for the notification); [named]: its name by the icon. */
        data class OpenApp(val target: WebAppNotifications.Target, val icon: Bitmap?, val named: Boolean = false) : Action()

        /** Sends [text] through the notification's reply action; [iconOnly] for a reaction. */
        data class Reply(val text: String, val iconOnly: Boolean) : Action()
    }

    private class Button(val frame: FrameLayout, val label: TextView?, val width: Int)

    val view: HorizontalScrollView = HorizontalScrollView(activity).apply {
        isHorizontalScrollBarEnabled = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        defaultFocusHighlightEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        clipToPadding = false
        visibility = View.GONE
    }
    private val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        clipChildren = false
    }
    private var actions: List<Action> = emptyList()
    private var buttons: List<Button> = emptyList()
    var focus = 0
        private set

    /** Whether the focus is in the bar (the page is active); off, no button looks focused. */
    var active = false
        set(value) {
            field = value
            applyFocus(animate = true)
        }

    val shown: Boolean get() = actions.isNotEmpty()

    init {
        view.addView(row)
        view.setPadding(px(24f), 0, px(24f), 0)
    }

    /**
     * Shows [next] (hides the bar when empty). [keepFocus]: the same notification redrawn, the
     * focus stays where it was; another one starts on the first button.
     */
    fun show(next: List<Action>, keepFocus: Boolean = false) {
        if (!keepFocus) focus = 0
        val same = next == actions
        actions = next
        if (!same) {
            row.removeAllViews()
            buttons = next.mapIndexed { i, action ->
                build(action).also { button ->
                    row.addView(button.frame, LinearLayout.LayoutParams(button.width, px(HEIGHT)).apply { if (i > 0) marginStart = px(GAP) })
                }
            }
            focus = focus.coerceIn(0, maxOf(0, next.size - 1))
        }
        view.visibility = if (next.isEmpty()) View.GONE else View.VISIBLE
        applyFocus(animate = false)
    }

    fun hide() = show(emptyList())

    /** One step along the row; false at its end (nothing moved). */
    fun move(delta: Int): Boolean {
        val next = (focus + delta).coerceIn(0, maxOf(0, actions.size - 1))
        if (next == focus) return false
        focus = next
        applyFocus(animate = true)
        return true
    }

    fun current(): Action? = actions.getOrNull(focus)

    private fun build(action: Action): Button {
        val frame = FrameLayout(activity).apply { clipChildren = false }
        return when (action) {
            is Action.OpenApp -> {
                val icon = ImageView(activity).apply {
                    if (action.icon != null) {
                        setImageBitmap(action.icon)
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    } else {
                        setImageResource(R.drawable.ic_apps)
                        setColorFilter(MetaStyle.TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    outlineProvider = object : ViewOutlineProvider() {
                        override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
                    }
                    clipToOutline = true
                    contentDescription = action.target.app.name
                }
                if (!action.named) {
                    frame.addView(icon, FrameLayout.LayoutParams(px(APP_ICON), px(APP_ICON), Gravity.CENTER))
                    return Button(frame, null, px(HEIGHT))
                }
                // Several apps take the notification (a copy and its original share the icon).
                val label = label(action.target.app.name, 24f)
                val content = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(icon, LinearLayout.LayoutParams(px(APP_ICON), px(APP_ICON)))
                    addView(label, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(GAP) })
                }
                // The icon sits as far from the left edge as from the top: (72 - 52) / 2.
                val inset = (px(HEIGHT) - px(APP_ICON)) / 2
                frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = inset })
                label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                Button(frame, label, inset + px(APP_ICON) + px(GAP) + label.measuredWidth + px(PADDING))
            }
            is Action.Reply -> {
                val label = label(action.text, if (action.iconOnly) 30f else 24f)
                frame.addView(label, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                val width = if (action.iconOnly) px(HEIGHT) else {
                    label.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
                    maxOf(px(HEIGHT), label.measuredWidth + 2 * px(PADDING))
                }
                Button(frame, label, width)
            }
        }
    }

    private fun label(text: String, size: Float) = TextView(activity).apply {
        this.text = text
        setTextColor(MetaStyle.TEXT_SECONDARY)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, size))
        typeface = MetaStyle.REGULAR
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        gravity = Gravity.CENTER
    }

    private fun applyFocus(animate: Boolean) {
        buttons.forEachIndexed { index, button ->
            val focused = active && index == focus
            button.frame.background = if (focused) MetaStyle.focused(activity, button.width, radius = 999f) else MetaStyle.pill(activity, Color.argb(90, 255, 255, 255))
            button.label?.setTextColor(if (focused) MetaStyle.TEXT else MetaStyle.TEXT_SECONDARY)
            // At rest the toolkit insets the button by 8 (its height's scale).
            val scale = if (focused) 1f else (px(HEIGHT) - px(8f)).toFloat() / px(HEIGHT)
            if (animate) {
                button.frame.animate().scaleX(scale).scaleY(scale).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            } else {
                button.frame.animate().cancel()
                button.frame.scaleX = scale
                button.frame.scaleY = scale
            }
        }
        buttons.getOrNull(focus)?.frame?.let { target ->
            view.post { view.requestChildRectangleOnScreen(target, android.graphics.Rect(-px(24f), 0, target.width + px(24f), target.height), !animate) }
        }
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    companion object {
        const val HEIGHT = 72f
        private const val GAP = 8f
        private const val PADDING = 24f
        private const val APP_ICON = 52f
    }
}
