package dev.lumen.glasses

import android.view.View

/** What a band command did in one of the home's tabs; the `_OUT` ones hand the focus back to the home. */
enum class HomeResult { HANDLED, UP_OUT, LEFT_OUT, RIGHT_OUT, CLOSE, UNHANDLED }

/** One of the home's tabs ([LauncherActivity]): a page in its pager. */
interface HomePage {
    val view: View

    /** Whether the focus is in this page (it shows it) or on the home's tabs. */
    var active: Boolean

    /** The page came into view, or left it. */
    fun onShow()
    fun onHide()

    /** A band command while the focus is in this page. */
    fun onCommand(command: String): HomeResult

    /**
     * The air mouse tapped at [x], [y] (screen pixels): what's there gets the focus and runs, as
     * the index tap would. False when nothing there takes it.
     */
    fun onTap(x: Float, y: Float): Boolean = false
}

/** Whether the point [x], [y] in screen pixels is on this view (shown and laid out). */
fun View.isUnder(x: Float, y: Float): Boolean {
    if (!isShown || width == 0 || height == 0) return false
    val at = IntArray(2)
    getLocationOnScreen(at)
    return x >= at[0] && x < at[0] + width && y >= at[1] && y < at[1] + height
}
