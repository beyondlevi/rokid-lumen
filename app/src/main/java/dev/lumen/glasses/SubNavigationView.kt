package dev.lumen.glasses

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The toolkit's SubNavigation, natively: a pill of tabs, 44 high, each a 44x44 icon cell (a
 * 24 glyph); the active tab at full alpha, the others at half. While the pill has the focus the
 * active tab also shows its label (meta2, 22) after its icon, the cell widening over 300 ms;
 * with the focus in the page every tab is its icon alone. Sizes are the toolkit's viewport px
 * ([MetaStyle.px]).
 *
 * The toolkit fills the pill with #27282D and lights it when focused; on the Rokid's additive
 * display that is a solid green block (see [MetaStyle]), so the pill is drawn as its rim: a
 * faint edge at rest, the bright 3 px edge when focused.
 */
class SubNavigationView(context: Context, private val tabs: List<Tab>) : LinearLayout(context) {
    class Tab(val icon: Int, val label: String)

    private class Item(val cell: FrameLayout, val content: LinearLayout, val label: TextView, val dot: View)

    private val items = mutableListOf<Item>()
    private var active = 0
    private var focusedPill = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        tabs.forEach { tab ->
            val content = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val iconCell = FrameLayout(context)
            iconCell.addView(ImageView(context).apply {
                setImageResource(tab.icon)
                setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
            }, FrameLayout.LayoutParams(px(ICON), px(ICON), Gravity.CENTER))
            val dot = View(context).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(MetaStyle.TEXT)
                }
                visibility = View.GONE
            }
            iconCell.addView(dot, FrameLayout.LayoutParams(px(9f), px(9f), Gravity.TOP or Gravity.END).apply {
                topMargin = px(8f)
                marginEnd = px(8f)
            })
            content.addView(iconCell, LayoutParams(px(ITEM), px(ITEM)))
            val label = TextView(context).apply {
                text = tab.label
                setTextColor(MetaStyle.TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
                typeface = MetaStyle.REGULAR
                isSingleLine = true
                includeFontPadding = false
                setPadding(0, 0, px(12f), 0)
            }
            content.addView(label, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // The cell clips the label: its width is the icon's alone, or the icon's and the label's.
            val cell = FrameLayout(context).apply { clipChildren = true }
            cell.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(ITEM)))
            addView(cell, LayoutParams(px(ITEM), px(ITEM)))
            items += Item(cell, content, label, dot)
        }
        applyState(animate = false)
    }

    /** The tab shown in the page under the pill. */
    fun setActive(index: Int, animate: Boolean = true) {
        if (index == active) return
        active = index
        applyState(animate)
    }

    /** Whether the pill has the focus (the active tab's label shows) or the page has it. */
    fun setPillFocused(focused: Boolean, animate: Boolean = true) {
        if (focused == focusedPill) return
        focusedPill = focused
        applyState(animate)
    }

    /** A dot on a tab's icon (unread notifications). */
    fun setDot(index: Int, on: Boolean) {
        items.getOrNull(index)?.dot?.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun applyState(animate: Boolean) {
        background = GradientDrawable().apply {
            setColor(Color.BLACK)
            cornerRadius = 9_999f
            if (focusedPill) setStroke(px(3f), Color.argb(220, 255, 255, 255))
            else setStroke(maxOf(1, px(1.5f)), Color.argb(110, 255, 255, 255))
        }
        items.forEachIndexed { index, item ->
            val expanded = focusedPill && index == active
            item.label.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED)
            val width = px(ITEM) + if (expanded) item.label.measuredWidth else 0
            val alpha = if (index == active) 1f else ALPHA_INACTIVE
            val params = item.cell.layoutParams
            if (!animate || !isLaidOut) {
                params.width = width
                item.cell.layoutParams = params
                item.content.alpha = alpha
                return@forEachIndexed
            }
            ValueAnimator.ofInt(params.width, width).apply {
                duration = MetaStyle.FOCUS_MS
                interpolator = MetaStyle.FOCUS_EASING
                addUpdateListener {
                    params.width = it.animatedValue as Int
                    item.cell.layoutParams = params
                }
                start()
            }
            item.content.animate().alpha(alpha).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
        }
    }

    private fun px(value: Float) = MetaStyle.px(context, value)

    companion object {
        private const val ITEM = 44f
        private const val ICON = 24f
        private const val ALPHA_INACTIVE = 0.5f
    }
}
