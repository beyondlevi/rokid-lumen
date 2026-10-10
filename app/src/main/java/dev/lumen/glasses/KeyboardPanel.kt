package dev.lumen.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Region
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The window of Lumen's input method ([LumenKeyboard]), in the Meta look ([MetaStyle]) of its
 * approved design canvas (the whole 480x640 HUD in px, scaled here to the display):
 * with the panel closed, only a hint pill in the strip under the web app's square; open, the
 * panel anchored to the bottom over the dimmed app (status, the field, its text, the choices,
 * the Enter action, a hint). It draws what [render] gets and decides nothing; a tap (the air
 * mouse) on a choice goes to [onTap] with its index (the choices, then the action button).
 */
class KeyboardPanel(private val context: Context, private val onTap: (Int) -> Unit) {
    enum class Icon { MIC, PEN, PHONE, SEARCH, LOCK, ENTER }

    /** What the window shows. [panel] false: only [hint] (the pill), when it isn't empty. */
    data class State(
        val panel: Boolean,
        val hint: String = "",
        val hintIcon: Icon? = null,
        val status: String = "",
        val statusIcon: Icon? = null,
        val fieldIcon: Icon? = null,
        val fieldLabel: String = "",
        val fieldAction: String = "",
        val text: String = "",
        val partial: String = "",
        val choices: List<Pair<Icon, String>> = emptyList(),
        val action: String? = null,
        val actionIcon: Icon = Icon.ENTER,
        /** The focused one: an index of [choices], or `choices.size` for the action button; -1 for none. */
        val selected: Int = -1,
        val panelHint: String = "",
    )

    private val scale = minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) / CANVAS_SIDE

    val root: FrameLayout = Layer(context)
    private val dim = View(context).apply { setBackgroundColor(DIM) }
    private val pill = LinearLayout(context)
    private val pillIcon = Glyph(context)
    private val pillText = text(16f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR)
    private val panel = LinearLayout(context)
    private val statusRow = LinearLayout(context)
    private val statusIcon = Glyph(context)
    private val statusText = text(18f, MetaStyle.TEXT, MetaStyle.MEDIUM)
    private val fieldRow = LinearLayout(context)
    private val fieldIcon = Glyph(context)
    private val fieldLabel = text(15f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR)
    private val fieldAction = text(15f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR)
    private val textBox = CaretText(context)
    private val choiceRow = LinearLayout(context)
    private val actionButton = LinearLayout(context)
    private val actionIcon = Glyph(context)
    private val actionText = text(19f, MetaStyle.TEXT, MetaStyle.MEDIUM)
    private val hint = text(14f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR)
    private var shownChoices: List<Pair<Icon, String>> = emptyList()

    init {
        root.addView(dim, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        pill.orientation = LinearLayout.HORIZONTAL
        pill.gravity = Gravity.CENTER_VERTICAL
        pill.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 9_999f
            setStroke(px(2f), PILL)
        }
        pill.setPadding(px(18f), 0, px(18f), 0)
        pill.addView(pillIcon, LinearLayout.LayoutParams(px(18f), px(18f)).apply { marginEnd = px(8f) })
        pill.addView(pillText)
        root.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(40f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(PILL_BOTTOM)
        })

        panel.orientation = LinearLayout.VERTICAL
        panel.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = px(28f).toFloat()
            setStroke(px(2f), PILL)
        }
        panel.setPadding(px(20f), px(18f), px(20f), px(18f))
        panel.isClickable = true

        statusRow.orientation = LinearLayout.HORIZONTAL
        statusRow.gravity = Gravity.CENTER_VERTICAL
        statusRow.addView(statusIcon, LinearLayout.LayoutParams(px(20f), px(20f)).apply { marginEnd = px(8f) })
        statusRow.addView(statusText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        statusText.maxLines = 2

        fieldRow.orientation = LinearLayout.HORIZONTAL
        fieldRow.gravity = Gravity.CENTER_VERTICAL
        fieldRow.addView(fieldIcon, LinearLayout.LayoutParams(px(16f), px(16f)).apply { marginEnd = px(8f) })
        fieldRow.addView(fieldLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        fieldRow.addView(fieldAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })

        textBox.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20f * scale)
        textBox.setTextColor(MetaStyle.TEXT)
        textBox.typeface = MetaStyle.REGULAR
        textBox.setLineSpacing(0f, 1.2f)
        textBox.includeFontPadding = false
        textBox.minHeight = px(56f)
        textBox.maxLines = 4
        // At the top, so the caret's place is the layout's (no gravity offset).
        textBox.gravity = Gravity.TOP or Gravity.START
        textBox.setPadding(px(16f), px(12f), px(16f), px(12f))
        textBox.background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = px(18f).toFloat()
            setStroke(px(2f), LINE)
        }

        choiceRow.orientation = LinearLayout.HORIZONTAL

        actionButton.orientation = LinearLayout.HORIZONTAL
        actionButton.gravity = Gravity.CENTER
        actionButton.addView(actionIcon, LinearLayout.LayoutParams(px(20f), px(20f)).apply { marginEnd = px(8f) })
        actionButton.addView(actionText)
        actionButton.setOnClickListener { onTap(shownChoices.size) }

        hint.gravity = Gravity.CENTER
        hint.maxLines = 2

        val gap = px(GAP)
        panel.addView(statusRow, rowParams(0))
        panel.addView(fieldRow, rowParams(gap))
        panel.addView(textBox, rowParams(gap))
        panel.addView(choiceRow, rowParams(gap))
        panel.addView(actionButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56f)).apply { topMargin = gap })
        panel.addView(hint, rowParams(gap))
        root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            setMargins(px(12f), 0, px(12f), px(12f))
        })
        render(State(panel = false))
    }

    fun render(state: State) {
        dim.visibility = if (state.panel) View.VISIBLE else View.GONE
        pill.visibility = if (!state.panel && state.hint.isNotEmpty()) View.VISIBLE else View.GONE
        panel.visibility = if (state.panel) View.VISIBLE else View.GONE
        pillText.text = state.hint
        pillIcon.show(state.hintIcon, MetaStyle.TEXT_SECONDARY, 2f)
        if (!state.panel) return

        statusText.text = state.status
        statusIcon.show(state.statusIcon, MetaStyle.TEXT, 2.2f)
        fieldLabel.text = state.fieldLabel
        fieldAction.text = state.fieldAction
        fieldAction.visibility = if (state.fieldAction.isEmpty()) View.GONE else View.VISIBLE
        fieldIcon.show(state.fieldIcon, MetaStyle.TEXT_PLACEHOLDER, 2f)
        val shown = SpannableStringBuilder(state.text)
        if (state.partial.isNotBlank()) {
            if (shown.isNotEmpty()) shown.append(' ')
            val start = shown.length
            shown.append(state.partial)
            shown.setSpan(ForegroundColorSpan(MetaStyle.TEXT_PLACEHOLDER), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        textBox.text = shown

        if (state.choices != shownChoices) {
            shownChoices = state.choices
            choiceRow.removeAllViews()
            state.choices.forEachIndexed { index, (icon, label) ->
                choiceRow.addView(choiceView(icon, label, index), LinearLayout.LayoutParams(0, px(64f), 1f).apply { if (index > 0) marginStart = px(10f) })
            }
        }
        choiceRow.visibility = if (state.choices.isEmpty()) View.GONE else View.VISIBLE
        for (index in 0 until choiceRow.childCount) styleChoice(choiceRow.getChildAt(index) as LinearLayout, index == state.selected, 32f)

        actionButton.visibility = if (state.action == null) View.GONE else View.VISIBLE
        actionText.text = state.action.orEmpty()
        actionIcon.show(state.actionIcon, MetaStyle.TEXT, 2.2f)
        styleChoice(actionButton, state.selected == state.choices.size && state.action != null, 28f)

        hint.text = state.panelHint
        hint.visibility = if (state.panelHint.isEmpty()) View.GONE else View.VISIBLE
    }

    /**
     * Where a tap lands on the window: the whole window with the panel open (the app behind takes
     * none), only the pill otherwise (taps elsewhere reach the app).
     */
    fun touchable(region: Region) {
        region.setEmpty()
        val target = when {
            panel.visibility == View.VISIBLE -> root
            pill.visibility == View.VISIBLE -> pill
            else -> return
        }
        val location = IntArray(2)
        target.getLocationInWindow(location)
        region.set(Rect(location[0], location[1], location[0] + target.width, location[1] + target.height))
    }

    private fun choiceView(icon: Icon, label: String, index: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        addView(Glyph(context).apply { show(icon, MetaStyle.TEXT, 2.2f) }, LinearLayout.LayoutParams(px(22f), px(22f)).apply { marginEnd = px(8f) })
        addView(text(19f, MetaStyle.TEXT, MetaStyle.MEDIUM).apply { text = label })
        setOnClickListener { onTap(index) }
    }

    /** Focused: the bright 3 px edge over the barely lit fill; at rest a 2 px outline on black. */
    private fun styleChoice(view: LinearLayout, focused: Boolean, radius: Float) {
        view.background = GradientDrawable().apply {
            setColor(if (focused) FILL else Color.BLACK)
            cornerRadius = px(radius).toFloat()
            setStroke(px(if (focused) 3f else 2f), if (focused) FOCUS else LINE)
        }
        for (i in 0 until view.childCount) {
            (view.getChildAt(i) as? TextView)?.typeface = if (focused) MetaStyle.BOLD else MetaStyle.MEDIUM
        }
    }

    private fun rowParams(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun text(size: Float, color: Int, face: android.graphics.Typeface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale)
        setTextColor(color)
        typeface = face
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    /** A canvas px (the 480-wide HUD) in the display's pixels. */
    private fun px(value: Float) = maxOf(1, Math.round(value * scale))

    /**
     * The window's root: the whole height it's offered, so the panel's dim covers the app and the
     * hint sits at the bottom of the screen (the input method's window wraps its content).
     */
    private class Layer(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) {
                resources.displayMetrics.heightPixels
            } else {
                MeasureSpec.getSize(heightMeasureSpec)
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }

    /** The field's text with the canvas' caret after it: a 2 px white bar, 22 px high. */
    private inner class CaretText(context: Context) : TextView(context) {
        private val caret = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val layout = layout ?: return
            val end = text.length
            val line = layout.getLineForOffset(end)
            val x = compoundPaddingLeft + layout.getPrimaryHorizontal(end) + if (end > 0) px(2f) else 0
            val middle = extendedPaddingTop + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
            val half = px(22f) / 2f
            canvas.drawRect(x, middle - half, x + px(2f), middle + half, caret)
        }
    }

    /** One of the canvas' line icons (a 24-unit grid, as its SVGs), drawn in [show]'s colour. */
    private class Glyph(context: Context) : View(context) {
        private var icon: Icon? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private var strokeUnits = 2f

        fun show(next: Icon?, color: Int, stroke: Float) {
            if (next != icon) {
                icon = next
                next?.let { build(it) }
            }
            paint.color = color
            strokeUnits = stroke
            visibility = if (next == null) View.GONE else View.VISIBLE
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (icon == null) return
            val unit = minOf(width, height) / 24f
            canvas.save()
            canvas.scale(unit, unit)
            // In grid units: the canvas is scaled to the view.
            paint.strokeWidth = strokeUnits
            canvas.drawPath(path, paint)
            canvas.restore()
        }

        /** The icon's path on the 24-unit grid, built once per icon (not while drawing). */
        private fun build(icon: Icon) {
            path.reset()
            when (icon) {
                Icon.MIC -> {
                    path.addRoundRect(RectF(9f, 3.5f, 15f, 14.5f), 3f, 3f, Path.Direction.CW)
                    path.moveTo(6f, 11f)
                    path.arcTo(RectF(6f, 5f, 18f, 17f), 180f, -180f)
                    path.moveTo(12f, 17f)
                    path.lineTo(12f, 20.5f)
                }
                Icon.PEN -> {
                    path.moveTo(4f, 20f)
                    path.lineTo(8f, 19f)
                    path.lineTo(19f, 8f)
                    path.lineTo(16f, 5f)
                    path.lineTo(5f, 16f)
                    path.close()
                    path.moveTo(14f, 6f)
                    path.lineTo(17f, 9f)
                }
                Icon.PHONE -> {
                    path.addRoundRect(RectF(7f, 3f, 17f, 21f), 2.5f, 2.5f, Path.Direction.CW)
                    path.moveTo(11f, 18f)
                    path.lineTo(13f, 18f)
                }
                Icon.SEARCH -> {
                    path.addCircle(11f, 11f, 6.5f, Path.Direction.CW)
                    path.moveTo(20f, 20f)
                    path.lineTo(15.8f, 15.8f)
                }
                Icon.LOCK -> {
                    path.addRoundRect(RectF(5f, 11f, 19f, 20f), 2f, 2f, Path.Direction.CW)
                    path.moveTo(8f, 11f)
                    path.lineTo(8f, 8f)
                    path.arcTo(RectF(8f, 4f, 16f, 12f), 180f, 180f)
                    path.lineTo(16f, 11f)
                }
                Icon.ENTER -> {
                    path.moveTo(5f, 12f)
                    path.lineTo(19f, 12f)
                    path.moveTo(13f, 6f)
                    path.lineTo(19f, 12f)
                    path.lineTo(13f, 18f)
                }
            }
        }
    }

    companion object {
        /** The canvas' HUD is 480 px wide. */
        private const val CANVAS_SIDE = 480f
        /** The pill's distance from the bottom: it sits in the strip under the web app's square. */
        private const val PILL_BOTTOM = 22f
        private const val GAP = 12f
        private val PILL = Color.argb(110, 255, 255, 255)
        private val LINE = Color.argb(69, 255, 255, 255)
        private val FOCUS = Color.argb(219, 255, 255, 255)
        private val FILL = Color.parseColor("#111113")
        /** The app behind the open panel: rgba(0, 0, 0, 0.55). */
        private val DIM = Color.argb(140, 0, 0, 0)
    }
}
