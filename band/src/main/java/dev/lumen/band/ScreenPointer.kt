package dev.lumen.band

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The air mouse on this device's own screen, the phone's or the glasses': a cursor drawn over
 * everything by the app's accessibility service and moved by the band's forearm aim (the same
 * records as on a computer, see [Bridge.pointer]). Unlike a computer's mouse the app knows where
 * the cursor is, so it starts at the middle of the screen every time and stays inside it.
 *
 * The index pinch is a finger on the screen where the cursor is: down when it closes, up when it
 * opens, moving with the cursor in between (a tap, a long press or a drag that scrolls). The
 * system takes it as one touch made of short strokes, each continuing the last
 * ([GestureDescription.StrokeDescription.continueStroke]). The middle pinch is Back ([Host.back]).
 * Main thread.
 */
class ScreenPointer(private val service: AccessibilityService, private val host: Host, private val style: Style) {
    interface Host {
        /** The band's air mouse on or off with [tuning] ([Tuning.band]); false when the band can't. */
        fun setPointer(on: Boolean, tuning: String): Boolean

        /** The middle pinch. */
        fun back()

        /**
         * A tap ended at [x], [y] (screen pixels), after the touch itself: the app's own screens
         * that move a focus instead of taking touches act on it here.
         */
        fun tapped(x: Float, y: Float) = Unit

        /** The cursor moved or clicked: the person is using the device (at most once a second). */
        fun active() = Unit
    }

    /** The cursor's look: a [ring] with a dot, outlined in [halo] (0 for none), [sizeDp] across. */
    data class Style(val ring: Int, val halo: Int, val sizeDp: Float)

    /** How the cursor follows the arm (the same settings as a computer's air mouse). */
    data class Tuning(val speed: Int = SPEED_DEFAULT, val steadiness: Float = STEADINESS_DEFAULT, val boost: Float = BOOST_DEFAULT) {
        /** The band's part: the speed is applied here. */
        fun band() = "steadiness=$steadiness;boost=$boost"
    }

    private val main = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private val pacer = PointerPacer()
    private val bounds = Rect()
    private var boundsAt = 0L
    private var cursor: Cursor? = null
    private var params: WindowManager.LayoutParams? = null
    private var tuning = Tuning()
    private var framing = false
    private var activeAt = 0L
    private var buttons = 0
    private var x = 0f
    private var y = 0f

    var on = false
        private set

    /** Turns it on with the cursor at the middle of the screen; false when the band can't run it. */
    fun start(tuning: Tuning): Boolean {
        if (on) {
            retune(tuning)
            return true
        }
        if (!host.setPointer(true, tuning.band())) {
            Log.d(TAG, "air mouse: the band can't run it")
            return false
        }
        this.tuning = tuning
        on = true
        refreshBounds(force = true)
        x = bounds.exactCenterX()
        y = bounds.exactCenterY()
        pacer.clear()
        buttons = 0
        show()
        Log.d(TAG, "air mouse on")
        return true
    }

    /** Turns it off: the band goes back to its gestures, a finger down lifts. */
    fun stop() {
        if (!on) return
        on = false
        host.setPointer(false, "")
        if (touching) {
            touching = false
            continueTouch()
        }
        pacer.clear()
        main.removeCallbacks(frame)
        framing = false
        buttons = 0
        hide()
        Log.d(TAG, "air mouse off")
    }

    /** The settings changed while it runs. */
    fun retune(tuning: Tuning) {
        this.tuning = tuning
        if (on) host.setPointer(true, tuning.band())
    }

    /** The band's records (see [Bridge.pointer]): movement to play, buttons at once. */
    fun onBand(records: DoubleArray) {
        if (!on) return
        val scale = pixelsPerDegree()
        var i = 0
        while (i + 3 < records.size) {
            val a = records[i + 2]
            when (records[i]) {
                MOVE -> pacer.add(a * scale, records[i + 3] * scale, records[i + 1])
                CLEAR -> pacer.clear()
                BUTTONS -> buttons(a.toInt())
            }
            i += 4
        }
        if (!framing && !pacer.isEmpty) {
            framing = true
            main.post(frame)
        }
    }

    /** At speed 100 a turn of [FULL_SPEED_DEGREES] crosses the screen's short side. */
    private fun pixelsPerDegree(): Double {
        refreshBounds(force = false)
        val side = minOf(bounds.width(), bounds.height()).toDouble()
        return tuning.speed / 100.0 * side / FULL_SPEED_DEGREES
    }

    private val frame = Runnable { frame() }

    private fun frame() {
        framing = false
        if (!on) return
        pacer.take(SystemClock.elapsedRealtimeNanos() / 1e9)?.let { (dx, dy) -> move(dx.toFloat(), dy.toFloat()) }
        if (!pacer.isEmpty) {
            framing = true
            main.postDelayed(frame, FRAME_MS)
        }
    }

    private fun move(dx: Float, dy: Float) {
        refreshBounds(force = false)
        x = (x + dx).coerceIn(bounds.left.toFloat(), bounds.right - 1f)
        y = (y + dy).coerceIn(bounds.top.toFloat(), bounds.bottom - 1f)
        if (touching && Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) > slop()) dragged = true
        place()
        active()
    }

    private fun buttons(state: Int) {
        val before = buttons
        buttons = state
        val left = state and LEFT != 0
        val wasLeft = before and LEFT != 0
        if (left && !wasLeft) press()
        if (!left && wasLeft) release()
        if (state and RIGHT != 0 && before and RIGHT == 0) host.back()
        active()
    }

    private fun active() {
        val now = SystemClock.uptimeMillis()
        if (now - activeAt < ACTIVE_MS) return
        activeAt = now
        host.active()
    }

    // ---- The finger ----

    private var touching = false
    private var stroke: GestureDescription.StrokeDescription? = null
    private var inFlight = false
    private var strokeX = 0f
    private var strokeY = 0f
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var dragged = false

    private fun press() {
        touching = true
        dragged = false
        downAt = SystemClock.uptimeMillis()
        downX = x
        downY = y
        cursor?.down = true
        if (!inFlight) begin()
    }

    private fun release() {
        touching = false
        cursor?.down = false
        val tap = !dragged && SystemClock.uptimeMillis() - downAt < TAP_MS
        if (!inFlight) continueTouch()
        if (tap) host.tapped(downX, downY)
    }

    private fun begin() {
        val path = android.graphics.Path().apply { moveTo(x, y) }
        send(GestureDescription.StrokeDescription(path, 0, SEGMENT_MS, true), x, y)
    }

    private fun send(next: GestureDescription.StrokeDescription, toX: Float, toY: Float) {
        stroke = next
        strokeX = toX
        strokeY = toY
        inFlight = true
        if (!service.dispatchGesture(GestureDescription.Builder().addStroke(next).build(), strokeDone, main)) {
            inFlight = false
            stroke = null
            Log.d(TAG, "air mouse: the system refused the touch")
        }
    }

    private val strokeDone = object : AccessibilityService.GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            inFlight = false
            continueTouch()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            // Another gesture took over (a swipe action): this touch is over.
            inFlight = false
            stroke = null
        }
    }

    /** The touch's next stroke to where the cursor is: held, or the last one lifting the finger. */
    private fun continueTouch() {
        val current = stroke ?: run {
            // The touch ended while the button was still down: a new one starts at the cursor.
            if (touching && on) begin()
            return
        }
        val path = android.graphics.Path().apply {
            moveTo(strokeX, strokeY)
            if (x != strokeX || y != strokeY) lineTo(x, y)
        }
        if (!touching) {
            stroke = null
            service.dispatchGesture(GestureDescription.Builder().addStroke(current.continueStroke(path, 0, SEGMENT_MS, false)).build(), null, null)
            return
        }
        send(current.continueStroke(path, 0, SEGMENT_MS, true), x, y)
    }

    private fun slop() = TAP_SLOP_DP * service.resources.displayMetrics.density

    // ---- The cursor ----

    private fun refreshBounds(force: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (!force && now - boundsAt < BOUNDS_MS) return
        boundsAt = now
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bounds.set(windows.maximumWindowMetrics.bounds)
        } else {
            val size = Point()
            @Suppress("DEPRECATION")
            windows.defaultDisplay.getRealSize(size)
            bounds.set(0, 0, size.x, size.y)
        }
    }

    private fun size() = Math.round(style.sizeDp * service.resources.displayMetrics.density)

    private fun show() {
        val view = Cursor(service, style)
        val size = size()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Screen pixels, as the touches: no shift for a camera cutout or the system bars.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            } else {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            title = "Air Mouse"
        }
        this.params = params
        cursor = view
        place()
        runCatching { windows.addView(view, params) }.onFailure {
            Log.d(TAG, "air mouse: no cursor (${it.message})")
            cursor = null
        }
    }

    private fun place() {
        val view = cursor ?: return
        val params = params ?: return
        val half = params.width / 2
        params.x = Math.round(x) - half
        params.y = Math.round(y) - half
        if (view.isAttachedToWindow) runCatching { windows.updateViewLayout(view, params) }
    }

    private fun hide() {
        cursor?.let { view -> runCatching { windows.removeView(view) } }
        cursor = null
        params = null
    }

    /** A ring with a dot; filled while the index pinch holds the finger down. */
    private class Cursor(context: Context, private val look: Style) : View(context) {
        private val density = context.resources.displayMetrics.density
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * density
            color = look.ring
        }
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f * density
            color = look.halo
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = look.ring }

        var down = false
            set(value) {
                if (field == value) return
                field = value
                invalidate()
            }

        override fun onDraw(canvas: Canvas) {
            val center = width / 2f
            val radius = center - 4f * density
            if (look.halo != 0) canvas.drawCircle(center, center, radius, halo)
            if (down) {
                fill.alpha = 110
                canvas.drawCircle(center, center, radius, fill)
            }
            canvas.drawCircle(center, center, radius, ring)
            fill.alpha = 255
            canvas.drawCircle(center, center, 3.5f * density, fill)
        }
    }

    companion object {
        private const val TAG = "NbPointer"

        /** The action that turns the air mouse on (rust/bridge `POINTER_TOGGLE`; named for the computer, where it began). */
        const val TOGGLE = "pc.pointer"

        /** The band's action when it turns the air mouse off (rust/bridge `POINTER_OFF`). */
        const val OFF = "pointer.off"

        val SPEEDS = 10..100
        const val SPEED_DEFAULT = 50
        const val STEADINESS_DEFAULT = 0.5f
        val BOOSTS = 1f..2.5f

        /** No pointer acceleration in the system here (a computer has its own), so kinesis' default. */
        const val BOOST_DEFAULT = 1.6f

        private const val FULL_SPEED_DEGREES = 15.0
        private const val FRAME_MS = 8L
        private const val SEGMENT_MS = 30L
        private const val TAP_MS = 400L
        private const val TAP_SLOP_DP = 8f
        private const val ACTIVE_MS = 1000L
        private const val BOUNDS_MS = 1000L
        private const val MOVE = 0.0
        private const val CLEAR = 1.0
        private const val BUTTONS = 2.0
        private const val LEFT = 1
        private const val RIGHT = 2
    }
}
