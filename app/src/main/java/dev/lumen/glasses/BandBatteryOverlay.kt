package dev.lumen.glasses

import dev.lumen.band.Phase
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The band's battery on the Rokid launcher's status row, as R08 Access Bridge shows its ring's
 * (RingBatteryLauncherOverlay): an accessibility overlay that takes no focus or touch, only while
 * the Rokid launcher is in front, placed just left of the launcher's right-hand status icons
 * (which move: Wi-Fi and signal come and go), refreshed on every band status change and every
 * 30 s. A `+` means charging; 20% or less turns amber.
 */
class BandBatteryOverlay(private val service: BandAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var label: TextView? = null
    private var icon: ImageView? = null
    private var launcherInFront = false

    private val refreshTick = object : Runnable {
        override fun run() {
            update()
            main.postDelayed(this, REFRESH_MS)
        }
    }
    private val debounced = Runnable { update() }
    private val bandListener = BandRuntime.StateListener { schedule() }

    fun start() {
        BandRuntime.addListener(bandListener)
        main.post(refreshTick)
    }

    fun stop() {
        BandRuntime.removeListener(bandListener)
        main.removeCallbacksAndMessages(null)
        remove()
    }

    /** Window changes tell whether the Rokid launcher is in front. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        schedule()
    }

    private fun schedule() {
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    private fun update() {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        launcherInFront = root?.packageName == ROKID_LAUNCHER
        val battery = BandRuntime.battery()
        val show = launcherInFront && GestureMappings.showsLauncherBattery(service) &&
            BandRuntime.phase == Phase.CONNECTED && battery >= 0
        if (!show || root == null) {
            remove()
            return
        }
        val charging = BandRuntime.status.optBoolean("charging")
        val color = if (battery <= LOW_PERCENT) LOW else NORMAL
        val content = view ?: build().also { view = it }
        label?.text = service.getString(if (charging) R.string.battery_overlay_charging else R.string.battery_overlay, battery)
        label?.setTextColor(color)
        icon?.imageTintList = ColorStateList.valueOf(color)
        content.contentDescription = label?.text
        place(content, statusAnchor(root))
    }

    /** The left edge and vertical center of the launcher's right-hand status icons, if found. */
    private fun statusAnchor(root: AccessibilityNodeInfo): Rect? {
        val width = service.resources.displayMetrics.widthPixels
        var anchor: Rect? = null
        collectStatus(root).forEach { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            if (bounds.left > width / 2 && bounds.height() in 1..MAX_ICON_HEIGHT) {
                if (anchor == null || bounds.left < anchor!!.left) anchor = bounds
            }
        }
        return anchor
    }

    /** The launcher's status views, by id prefix (findAccessibilityNodeInfosByViewId wants a full id). */
    private fun collectStatus(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun walk(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 12) return
            if (node.viewIdResourceName?.startsWith(STATUS_ROW_PREFIX) == true) out += node
            for (i in 0 until node.childCount) walk(node.getChild(i), depth + 1)
        }
        walk(root, 0)
        return out
    }

    private fun place(content: View, anchor: Rect?) {
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = service.resources.displayMetrics.widthPixels
        val gap = dp(4)
        val right = anchor?.left?.minus(gap) ?: (width - FALLBACK_RIGHT_INSET)
        val centerY = anchor?.centerY() ?: FALLBACK_CENTER_Y
        val params = (content.layoutParams as? WindowManager.LayoutParams) ?: WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        params.x = (right - content.measuredWidth).coerceAtLeast(0)
        params.y = (centerY - content.measuredHeight / 2).coerceAtLeast(0)
        if (content.parent == null) {
            runCatching { windows.addView(content, params) }
        } else {
            runCatching { windows.updateViewLayout(content, params) }
        }
    }

    private fun build(): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = dp(15)
        icon = ImageView(service).apply { setImageResource(R.drawable.ic_band) }
        addView(icon, LinearLayout.LayoutParams(size, size))
        label = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, TEXT_PX)
            includeFontPadding = false
            setPadding(dp(2), 0, 0, 0)
        }
        addView(label)
    }

    private fun remove() {
        view?.let { v -> if (v.parent != null) runCatching { windows.removeView(v) } }
        view = null
        label = null
        icon = null
    }

    private fun dp(value: Int) = (value * service.resources.displayMetrics.density).toInt()

    companion object {
        const val ROKID_LAUNCHER = "com.rokid.os.sprite.launcher"
        private const val STATUS_ROW_PREFIX = "com.rokid.os.sprite.launcher:id/status_"
        private const val REFRESH_MS = 30_000L
        private const val DEBOUNCE_MS = 150L
        private const val LOW_PERCENT = 20
        private const val MAX_ICON_HEIGHT = 60
        /** Matches the status row's text (22 px boxes on the 480x640 HUD, measured). */
        private const val TEXT_PX = 17f
        /** Where the row's right-hand icons sit when none is found (measured). */
        private const val FALLBACK_RIGHT_INSET = 44
        private const val FALLBACK_CENTER_Y = 524
        private val NORMAL = Color.rgb(0, 255, 64)
        private val LOW = Color.rgb(238, 190, 92)
    }
}
