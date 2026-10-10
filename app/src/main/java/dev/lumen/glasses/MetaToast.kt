package dev.lumen.glasses

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Meta UI toolkit's toast on the glasses (`ui/Toast.tsx`, `Toast.module.css` in
 * facebook/meta-ray-ban-display-ui-toolkit-web): an elevated chip (icon, message, metadata) in a
 * full-width frame at the top, over a black-to-transparent band, shown 3.5 s with a 0.3 s fade;
 * toasts asked for while one shows wait their turn, 0.7 s apart. Drawn by the accessibility
 * service over any app (not Lumen's notification banner).
 *
 * A step-by-step operation ([progress]) uses the same chip in the toolkit's loading state (the
 * Chip's `isLoading`: an IndeterminateLoader in place of the icon). It stays up, updated in place,
 * until [settle] turns it into an ordinary toast; it takes the place of a toast showing then.
 * Main thread.
 */
object MetaToast {
    private const val DISPLAY_MS = 3_500L
    private const val FADE_MS = 300L
    private const val QUEUE_DELAY_MS = 700L

    /** A progress toast nobody settles goes away after this. */
    private const val PROGRESS_LIMIT_MS = 120_000L

    private class Entry(val icon: Int, val message: String, val metadata: String)

    private class Shown(val view: View, val icon: ImageView, val loader: Loader, val message: TextView, val metadata: TextView) {
        fun set(icon: Int?, message: String, metadata: String) {
            if (icon == null) {
                this.icon.visibility = View.GONE
                loader.visibility = View.VISIBLE
            } else {
                this.icon.setImageResource(icon)
                this.icon.visibility = View.VISIBLE
                loader.visibility = View.GONE
            }
            this.message.text = message
            this.metadata.text = metadata
            this.metadata.visibility = if (metadata.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Entry>()
    private var service: AccessibilityService? = null
    private var shown: Shown? = null

    /** The toast up now is a progress one: it stays until settled. */
    private var live = false

    private val hide = Runnable { fadeOut() }

    @JvmStatic
    fun attach(service: AccessibilityService) {
        this.service = service
    }

    @JvmStatic
    fun detach(service: AccessibilityService) {
        if (this.service !== service) return
        main.removeCallbacksAndMessages(null)
        queue.clear()
        remove()
        live = false
        this.service = null
    }

    /** Queues a toast; [metadata] is the secondary text ("" for none). */
    @JvmStatic
    @JvmOverloads
    fun show(icon: Int, message: String, metadata: String = "") {
        main.post {
            if (service == null) return@post
            queue.addLast(Entry(icon, message, metadata))
            if (shown == null && queue.size == 1) next()
        }
    }

    /** Shows or updates the progress toast (the loader, [message], [metadata]). */
    @JvmStatic
    fun progress(message: String, metadata: String = "") {
        main.post {
            if (service == null) return@post
            val current = shown ?: add(null, message, metadata) ?: return@post
            current.set(null, message, metadata)
            current.view.animate().cancel()
            current.view.alpha = 1f
            live = true
            main.removeCallbacks(hide)
            main.postDelayed(hide, PROGRESS_LIMIT_MS)
        }
    }

    /** Ends the progress toast as an ordinary one ([icon], [message]); a plain toast if none is up. */
    @JvmStatic
    fun settle(icon: Int, message: String, metadata: String = "") {
        main.post {
            val current = shown
            if (!live || current == null) {
                show(icon, message, metadata)
                return@post
            }
            live = false
            current.set(icon, message, metadata)
            main.removeCallbacks(hide)
            main.postDelayed(hide, DISPLAY_MS)
        }
    }

    /** Takes the progress toast down, if one is up. */
    @JvmStatic
    fun dismissProgress() {
        main.post {
            if (!live) return@post
            main.removeCallbacks(hide)
            fadeOut()
        }
    }

    private fun next() {
        val entry = queue.removeFirstOrNull() ?: return
        if (add(entry.icon, entry.message, entry.metadata) == null) {
            main.post { next() }
            return
        }
        main.postDelayed(hide, DISPLAY_MS)
    }

    private fun add(icon: Int?, message: String, metadata: String): Shown? {
        val service = service ?: return null
        val built = build(service)
        built.set(icon, message, metadata)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP
            title = "Lumen toast"
        }
        val added = runCatching { service.getSystemService(WindowManager::class.java).addView(built.view, params) }.isSuccess
        if (!added) return null
        shown = built
        built.view.alpha = 0f
        built.view.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
        return built
    }

    private fun fadeOut() {
        val current = shown ?: return
        live = false
        current.view.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(MetaStyle.FOCUS_EASING).withEndAction {
            if (shown !== current) return@withEndAction
            remove()
            if (queue.isNotEmpty()) main.postDelayed({ if (shown == null) next() }, QUEUE_DELAY_MS)
        }.start()
    }

    private fun remove() {
        val current = shown ?: return
        shown = null
        current.loader.stop()
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeView(current.view) }
    }

    /** The frame (24 above and below, the band's gradient) around the elevated chip. */
    private fun build(service: AccessibilityService): Shown {
        fun px(value: Float) = MetaStyle.px(service, value)
        val frame = FrameLayout(service).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.TRANSPARENT))
            setPadding(0, px(24f), 0, px(24f))
        }
        val chip = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(44f)
            setPadding(px(16f), 0, px(16f), 0)
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#3E4148"), Color.parseColor("#26282D"))).apply {
                cornerRadius = px(22f).toFloat()
                setStroke(maxOf(1, px(1f)), Color.argb(40, 255, 255, 255))
            }
        }
        val icon = ImageView(service).apply { setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN) }
        chip.addView(icon, LinearLayout.LayoutParams(px(24f), px(24f)).apply { marginEnd = px(8f) })
        val loader = Loader(service)
        chip.addView(loader, LinearLayout.LayoutParams(px(24f), px(24f)).apply { marginEnd = px(8f) })
        val message = text(service, MetaStyle.TEXT, MetaStyle.MEDIUM)
        chip.addView(message)
        val metadata = text(service, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR)
        chip.addView(metadata, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginStart = px(8f) })
        frame.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        return Shown(frame, icon, loader, message, metadata)
    }

    private fun text(service: AccessibilityService, color: Int, face: android.graphics.Typeface) = TextView(service).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(service, 22f))
        typeface = face
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, MetaStyle.px(service, 6f), 0, MetaStyle.px(service, 6f))
    }

    /**
     * The toolkit's IndeterminateLoader at its XSMALL size (24, stroke 3, 1 from the edge): one
     * 1667 ms loop moves the arc (-40 to 105 degrees) while its end grows 30 to 90 % of the circle
     * over the first 80 % and its start follows from 48 %, drawn in the control-active white.
     */
    private class Loader(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            color = Color.argb(191, 255, 255, 255)
        }
        private val oval = RectF()
        private val ease = AccelerateDecelerateInterpolator()
        private var t = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1_667L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                t = it.animatedValue as Float
                invalidate()
            }
        }

        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (isShown) start() else animator.cancel()
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            if (isShown) start()
        }

        override fun onDetachedFromWindow() {
            stop()
            super.onDetachedFromWindow()
        }

        private fun start() {
            if (!animator.isStarted) animator.start()
        }

        fun stop() = animator.cancel()

        override fun onDraw(canvas: Canvas) {
            val stroke = width * 3f / 24f
            val inset = stroke / 2f + width / 24f
            paint.strokeWidth = stroke
            oval.set(inset, inset, width - inset, height - inset)
            val end = 30f + 60f * ease.getInterpolation((t / 0.8f).coerceAtMost(1f))
            val start = if (t < 0.48f) 0f else 52f * ease.getInterpolation((t - 0.48f) / 0.52f)
            val rotation = -40f + 145f * t
            canvas.drawArc(oval, start * 3.6f + rotation - 14f - 90f, (end - start) * 3.6f, false, paint)
        }
    }
}
