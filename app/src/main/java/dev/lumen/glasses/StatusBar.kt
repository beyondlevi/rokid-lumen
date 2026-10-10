package dev.lumen.glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The glasses' status bar (the status bar canvas): the time and the glasses' battery in the strip
 * above the app's square, over any app but the Rokid launcher (which has its own), so neither is
 * ever covered by a page. On by default, a Glasses setting turns it off; Lumen's home then shows
 * its own clock again ([addListener]).
 */
object StatusBar {
    /** At or below this, not charging, the battery turns amber (as the band's on the Rokid launcher). */
    const val LOW_PERCENT = 20

    /** The glasses' battery as the bar shows it. */
    data class Battery(val percent: Int, val charging: Boolean) {
        val low: Boolean get() = percent <= LOW_PERCENT && !charging
    }

    /** From ACTION_BATTERY_CHANGED's extras; null when it says nothing usable. */
    @JvmStatic
    fun battery(level: Int, scale: Int, plugged: Int): Battery? {
        if (level < 0 || scale <= 0) return null
        val percent = Math.round(level * 100f / scale).coerceIn(0, 100)
        return Battery(percent, plugged != 0)
    }

    /** The bar shows over [packageName]'s window, the one in front (unknown counts as yes). */
    @JvmStatic
    fun showsOver(packageName: CharSequence?): Boolean = packageName?.toString() != BandBatteryOverlay.ROKID_LAUNCHER

    @JvmStatic
    fun enabled(context: Context): Boolean = GestureMappings.showsStatusBar(context)

    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Told on the main thread when the setting changes. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** The setting changed (from the phone). */
    @JvmStatic
    fun changed() {
        Handler(Looper.getMainLooper()).post { listeners.forEach { it() } }
    }
}

/**
 * The [StatusBar]'s window: an accessibility overlay across the top strip that takes no focus or
 * touch. The time follows the minute (and the clock's format), the battery its broadcasts; which
 * app is in front comes from the window events, as for [BandBatteryOverlay].
 */
class StatusBarOverlay(private val service: BandAccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: LinearLayout? = null
    private var time: TextView? = null
    private var percent: TextView? = null
    private var glyph: BatteryGlyph? = null
    private var battery: StatusBar.Battery? = null
    private var overFront = true
    private var started = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) readBattery(intent)
            update()
        }
    }
    private val debounced = Runnable { checkFront() }
    private val settingListener: () -> Unit = { update() }

    fun start() {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        // The battery's broadcast is sticky: the current reading comes back at once.
        runCatching { service.registerReceiver(receiver, filter) }.getOrNull()?.let(::readBattery)
        StatusBar.addListener(settingListener)
        checkFront()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { service.unregisterReceiver(receiver) }
        StatusBar.removeListener(settingListener)
        main.removeCallbacksAndMessages(null)
        remove()
    }

    /** Window changes tell which app is in front. */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) return
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    private fun checkFront() {
        val root = runCatching { service.rootInActiveWindow }.getOrNull()
        // No window to read (between two apps): as it was.
        if (root != null) overFront = StatusBar.showsOver(root.packageName)
        update()
    }

    private fun readBattery(intent: Intent) {
        battery = StatusBar.battery(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
            intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
        )
    }

    private fun update() {
        val metrics = service.resources.displayMetrics
        val strip = (metrics.heightPixels - minOf(metrics.widthPixels, metrics.heightPixels)) / 2
        if (!started || !overFront || !StatusBar.enabled(service) || strip < MetaStyle.px(service, MIN_STRIP)) {
            remove()
            return
        }
        val content = view ?: build().also { view = it }
        time?.text = android.text.format.DateFormat.getTimeFormat(service).format(java.util.Date())
        val reading = battery
        val color = if (reading?.low == true) LOW else MetaStyle.TEXT_PLACEHOLDER
        percent?.text = reading?.let { service.getString(R.string.battery_overlay, it.percent) }.orEmpty()
        percent?.setTextColor(color)
        glyph?.apply {
            visibility = if (reading == null) View.GONE else View.VISIBLE
            show(reading?.percent ?: 0, reading?.charging == true, color)
        }
        content.contentDescription = if (reading == null) time?.text else service.getString(
            if (reading.charging) R.string.status_bar_description_charging else R.string.status_bar_description,
            time?.text, reading.percent,
        )
        if (content.parent == null) {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                strip,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                y = 0
            }
            runCatching { windows.addView(content, params) }
                .onFailure { view = null }
        }
    }

    private fun build(): LinearLayout = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val side = MetaStyle.px(service, SIDE)
        setPadding(side, 0, side, 0)
        time = label().also { addView(it) }
        addView(View(service), LinearLayout.LayoutParams(0, 1, 1f))
        glyph = BatteryGlyph(service).also {
            addView(it, LinearLayout.LayoutParams(MetaStyle.px(service, 34f), MetaStyle.px(service, 17.5f)).apply {
                marginEnd = MetaStyle.px(service, 9f)
            })
        }
        percent = label().also { addView(it) }
    }

    private fun label() = TextView(service).apply {
        setTextColor(MetaStyle.TEXT_PLACEHOLDER)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(service, TEXT))
        typeface = MetaStyle.MEDIUM
        includeFontPadding = false
        isSingleLine = true
    }

    private fun remove() {
        view?.let { v -> if (v.parent != null) runCatching { windows.removeView(v) } }
        view = null
        time = null
        percent = null
        glyph = null
    }

    /** The battery's outline with its level, and a bolt while plugged in (the canvas's 27 x 14). */
    @SuppressLint("ViewConstructor")
    private class BatteryGlyph(context: Context) : View(context) {
        private var level = 0
        private var charging = false
        private var color = Color.WHITE
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val rect = RectF()
        private val bolt = Path()

        fun show(level: Int, charging: Boolean, color: Int) {
            if (level == this.level && charging == this.charging && color == this.color) return
            this.level = level
            this.charging = charging
            this.color = color
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val unit = width / 27f
            canvas.save()
            canvas.translate(0f, (height - 14f * unit) / 2)
            canvas.scale(unit, unit)
            stroke.color = color
            stroke.strokeWidth = 1.6f
            fill.color = color
            rect.set(1f, 1f, 23f, 13f)
            canvas.drawRoundRect(rect, 3.2f, 3.2f, stroke)
            rect.set(24.2f, 4.6f, 26.2f, 9.4f)
            canvas.drawRoundRect(rect, 1f, 1f, fill)
            rect.set(3.4f, 3.4f, 3.4f + maxOf(2f, 17f * level / 100f), 10.6f)
            canvas.drawRoundRect(rect, 1.4f, 1.4f, fill)
            if (charging) {
                bolt.reset()
                bolt.moveTo(12.6f, 2.6f)
                bolt.lineTo(8.6f, 7.6f)
                bolt.lineTo(12.2f, 7.6f)
                bolt.lineTo(11f, 11.4f)
                bolt.lineTo(15.2f, 6.2f)
                bolt.lineTo(11.6f, 6.2f)
                bolt.close()
                // A black edge keeps the bolt apart from the level behind it (black is see-through).
                stroke.color = Color.BLACK
                stroke.strokeWidth = 2.2f
                stroke.strokeJoin = Paint.Join.ROUND
                canvas.drawPath(bolt, stroke)
                canvas.drawPath(bolt, fill)
            }
            canvas.restore()
        }
    }

    companion object {
        private const val DEBOUNCE_MS = 150L
        /** The canvas's 17 px text and 28 px sides, in viewport px (x 0.8 on the glasses). */
        private const val TEXT = 21f
        private const val SIDE = 35f
        /** Below this strip (a display about as tall as wide) there's no room for it. */
        private const val MIN_STRIP = 40f
        private val LOW = Color.rgb(238, 190, 92)
    }
}
