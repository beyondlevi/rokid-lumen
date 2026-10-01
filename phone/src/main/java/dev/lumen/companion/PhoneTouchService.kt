package dev.lumen.companion

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * The accessibility service that turns band gestures into screen gestures on the phone (from the
 * original phone app):
 * a swipe through the middle of the screen, Back / Home / Recents, or a D-pad
 * key (the arrows and the centre, Android 13+). It also
 * opens mapped apps, which Android allows an accessibility service from the
 * background. It performs gestures only; it reads nothing on screen.
 */
class PhoneTouchService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** A finger moving up (content scrolls down) or down, over 40% of the screen's height. */
    fun swipe(up: Boolean) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val x = bounds.exactCenterX()
        val (from, to) = bounds.height() * .7f to bounds.height() * .3f
        stroke(Path().apply {
            moveTo(x, if (up) from else to)
            lineTo(x, if (up) to else from)
        })
    }

    /**
     * A finger moving left (the next page, photo or story) or right, over 50% of
     * the screen's width; it starts away from the edges so it's never a system Back.
     */
    fun swipeSideways(left: Boolean) {
        val bounds = getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.bounds
        val y = bounds.exactCenterY()
        val (from, to) = bounds.width() * .75f to bounds.width() * .25f
        stroke(Path().apply {
            moveTo(if (left) from else to, y)
            lineTo(if (left) to else from, y)
        })
    }

    private fun stroke(path: Path) {
        dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 220)).build(),
            null,
            null,
        )
    }

    /** The D-pad keys the system sends for an accessibility service. */
    enum class Key { UP, DOWN, LEFT, RIGHT, CENTER }

    /**
     * Press a D-pad key in the focused window, as a remote would (Android 13+):
     * the arrows move the focus, the centre clicks what's focused. False when
     * the system refused it.
     */
    @android.annotation.SuppressLint("InlinedApi") // Guarded by keysSupported.
    fun dpad(key: Key): Boolean {
        if (!keysSupported) return false
        return performGlobalAction(
            when (key) {
                Key.UP -> GLOBAL_ACTION_DPAD_UP
                Key.DOWN -> GLOBAL_ACTION_DPAD_DOWN
                Key.LEFT -> GLOBAL_ACTION_DPAD_LEFT
                Key.RIGHT -> GLOBAL_ACTION_DPAD_RIGHT
                Key.CENTER -> GLOBAL_ACTION_DPAD_CENTER
            },
        )
    }

    companion object {
        /** The D-pad global actions arrived in Android 13. */
        val keysSupported get() = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU

        /** Set while the user has the service switched on. */
        var instance: PhoneTouchService? = null
            private set
    }
}
