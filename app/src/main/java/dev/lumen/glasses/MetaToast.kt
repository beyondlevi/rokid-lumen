package dev.lumen.glasses

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Meta UI toolkit's toast on the glasses (`ui/Toast.tsx`, `Toast.module.css` in
 * facebook/meta-ray-ban-display-ui-toolkit-web): an elevated chip (icon, message, metadata) in a
 * full-width frame at the top, over a black-to-transparent band, shown 3.5 s with a 0.3 s fade;
 * toasts asked for while one shows wait their turn, 0.7 s apart. Drawn by the accessibility
 * service over any app (not Lumen's notification banner). Main thread.
 */
object MetaToast {
    private const val DISPLAY_MS = 3_500L
    private const val FADE_MS = 300L
    private const val QUEUE_DELAY_MS = 700L

    private class Entry(val icon: Int, val message: String, val metadata: String)

    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Entry>()
    private var service: AccessibilityService? = null
    private var shown: View? = null

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

    private fun next() {
        val service = service ?: return
        val entry = queue.removeFirstOrNull() ?: return
        val view = build(service, entry)
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
        val added = runCatching { service.getSystemService(WindowManager::class.java).addView(view, params) }.isSuccess
        if (!added) {
            main.post { next() }
            return
        }
        shown = view
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
        main.postDelayed({
            view.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(MetaStyle.FOCUS_EASING).withEndAction {
                remove()
                if (queue.isNotEmpty()) main.postDelayed({ next() }, QUEUE_DELAY_MS)
            }.start()
        }, DISPLAY_MS)
    }

    private fun remove() {
        val view = shown ?: return
        shown = null
        runCatching { service?.getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    /** The frame (24 above and below, the band's gradient) around the elevated chip. */
    private fun build(service: AccessibilityService, entry: Entry): View {
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
        chip.addView(ImageView(service).apply {
            setImageResource(entry.icon)
            setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(px(24f), px(24f)).apply { marginEnd = px(8f) })
        chip.addView(text(service, entry.message, MetaStyle.TEXT, MetaStyle.MEDIUM))
        if (entry.metadata.isNotEmpty()) {
            chip.addView(text(service, entry.metadata, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR), LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = px(8f) })
        }
        frame.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        return frame
    }

    private fun text(service: AccessibilityService, value: String, color: Int, face: android.graphics.Typeface) = TextView(service).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(service, 22f))
        typeface = face
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setPadding(0, MetaStyle.px(service, 6f), 0, MetaStyle.px(service, 6f))
    }
}
