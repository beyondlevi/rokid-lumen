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
}
