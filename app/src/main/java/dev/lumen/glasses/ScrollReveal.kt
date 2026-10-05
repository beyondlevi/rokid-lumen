package dev.lumen.glasses

import android.view.View
import android.widget.ScrollView

/**
 * Brings a page's focused row into view, clear of the fading edges ([topPad] above, [bottomPad]
 * below). ScrollView's own requestChildRectangleOnScreen caps the scroll at its child's bottom
 * without the bottom padding, so the last row stopped under the bottom fade with its labels
 * hidden (measured on the Apps tab); this scrolls within the range ScrollView itself allows.
 */
object ScrollReveal {
    fun reveal(scroll: ScrollView, target: View, topPad: Int, bottomPad: Int, animate: Boolean) {
        val content = scroll.getChildAt(0) ?: return
        var top = 0
        var view: View = target
        while (view !== content) {
            top += view.top
            view = view.parent as? View ?: return
        }
        top += content.top
        val bottom = top + target.height
        val range = maxOf(0, content.height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
        val y = scroll.scrollY
            .coerceAtMost(top - topPad)
            .coerceAtLeast(bottom + bottomPad - scroll.height)
            .coerceIn(0, range)
        if (animate) scroll.smoothScrollTo(0, y) else scroll.scrollTo(0, y)
    }
}
