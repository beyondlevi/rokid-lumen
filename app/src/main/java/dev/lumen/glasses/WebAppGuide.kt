package dev.lumen.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * What the band does in a web app, shown as it opens, the first [TIMES] times per app:
 * - an app whose package declares `lumen_gestures` ([SiteScripts.gestureCard]) gets its gesture
 *   card, which stays until a band gesture closes it; that gesture does nothing else, so a
 *   wearer reading the card doesn't like a post by accident.
 * - any other online app on GeckoView (where the shim's band navigation is turned on) gets a
 *   short hint of it, which takes no gestures and goes after [HINT_MS].
 *
 * Native views over the HUD's square ([side]), in the Meta look ([MetaStyle]): outlines on black.
 */
class WebAppGuide(private val activity: Activity, parent: FrameLayout, private val side: Int) {
    private val layer = FrameLayout(activity)
    private val main = Handler(Looper.getMainLooper())
    private var card: View? = null
    private var hint: View? = null

    init {
        parent.addView(layer, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
    }

    /** Whether the gesture card is up (and takes the next band gesture). */
    val isShowingCard: Boolean get() = card != null

    /**
     * Shows [app]'s card or hint when it has one and hasn't shown it [TIMES] times yet; [gecko]
     * says it runs on GeckoView, the only engine with the band navigation the hint describes.
     */
    fun open(app: WebApp, gecko: Boolean) {
        val language = activity.resources.configuration.locales[0].language
        val gestures = if (app.hasPackage) {
            SiteScripts.gestureCard(WebAppPackages.readManifest(WebAppPackages.dir(activity, app.id)), language)
        } else {
            null
        }
        when {
            gestures != null -> if (countOpen(activity, KIND_CARD, app.id)) showCard(gestures)
            !app.offline && gecko -> if (countOpen(activity, KIND_HINT, app.id)) showHint()
        }
    }

    /** A band command while the card is up closes it and is taken; false otherwise. */
    fun onBandCommand(): Boolean {
        if (card == null) return false
        closeCard()
        return true
    }

    fun closeCard() {
        card?.let { layer.removeView(it) }
        card = null
    }

    fun destroy() {
        main.removeCallbacksAndMessages(null)
    }

    private fun showCard(gestures: SiteScripts.GestureCard) {
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = MetaStyle.outline(activity, 32f, alpha = BORDER_ALPHA)
            setPadding(px(24f), px(20f), px(24f), px(18f))
        }
        if (gestures.title.isNotEmpty()) panel.addView(text(gestures.title, 22f, MetaStyle.TEXT, MetaStyle.MEDIUM, 2))
        gestures.rows.forEachIndexed { index, row ->
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.gestures.forEachIndexed { i, gesture ->
                line.addView(GestureGlyph(activity, gesture), LinearLayout.LayoutParams(px(34f), px(34f)).apply { if (i > 0) marginStart = px(6f) })
            }
            line.addView(
                text(row.text, 20f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(14f) },
            )
            val top = if (index == 0 && gestures.title.isEmpty()) 0 else px(if (index == 0) 16f else 10f)
            panel.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = top })
        }
        panel.addView(
            text(activity.getString(R.string.webapp_gestures_close), 16f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR, 1),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) },
        )
        layer.addView(panel, FrameLayout.LayoutParams(side - 2 * px(MARGIN), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(MARGIN)
        })
        card = panel
    }

    private fun showHint() {
        val pill = text(activity.getString(R.string.webapp_band_hint), 18f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1).apply {
            gravity = Gravity.CENTER
            background = MetaStyle.pill(activity)
            setPadding(px(18f), 0, px(18f), 0)
            maxWidth = side - 2 * px(MARGIN)
        }
        layer.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(MARGIN)
        })
        hint = pill
        main.postDelayed({
            pill.animate().alpha(0f).setDuration(MetaStyle.FOCUS_MS).withEndAction {
                layer.removeView(pill)
                if (hint === pill) hint = null
            }.start()
        }, HINT_MS)
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface, lines: Int) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, size))
        setTextColor(color)
        typeface = face
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    /**
     * A gesture in an outlined circle: an arrow for a swipe, a filled dot for the index tap, a dot
     * in a ring for the middle tap.
     */
    private class GestureGlyph(context: Context, private val gesture: SiteScripts.Gesture) : View(context) {
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = maxOf(1, MetaStyle.px(context, 2f)).toFloat()
            color = Color.argb(BORDER_ALPHA, 255, 255, 255)
        }
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = MetaStyle.px(context, 2.5f).coerceAtLeast(1).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val head = Path()

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) / 2f - ring.strokeWidth / 2
            canvas.drawCircle(cx, cy, radius, ring)
            when (gesture) {
                SiteScripts.Gesture.INDEX -> {
                    ink.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, radius * 0.3f, ink)
                }
                SiteScripts.Gesture.MIDDLE -> {
                    ink.style = Paint.Style.FILL
                    canvas.drawCircle(cx, cy, radius * 0.2f, ink)
                    ink.style = Paint.Style.STROKE
                    canvas.drawCircle(cx, cy, radius * 0.5f, ink)
                }
                else -> {
                    // An arrow pointing up, turned to the swipe's direction.
                    val angle = when (gesture) {
                        SiteScripts.Gesture.RIGHT -> 90f
                        SiteScripts.Gesture.DOWN -> 180f
                        SiteScripts.Gesture.LEFT -> 270f
                        else -> 0f
                    }
                    val length = radius * 0.45f
                    val tip = radius * 0.3f
                    ink.style = Paint.Style.STROKE
                    canvas.save()
                    canvas.rotate(angle, cx, cy)
                    canvas.drawLine(cx, cy + length, cx, cy - length, ink)
                    head.reset()
                    head.moveTo(cx - tip, cy - length + tip)
                    head.lineTo(cx, cy - length)
                    head.lineTo(cx + tip, cy - length + tip)
                    canvas.drawPath(head, ink)
                    canvas.restore()
                }
            }
        }
    }

    companion object {
        /** How many times an app opens with its card or hint. */
        const val TIMES = 3
        private const val HINT_MS = 4_000L
        /** From the sides and the bottom of the HUD's square, in viewport px. */
        private const val MARGIN = 24f
        /** rgba(255, 255, 255, 0.43), the approved border. */
        private const val BORDER_ALPHA = 110
        private const val PREFS = "lumen_webapp_guide"
        private const val KIND_CARD = "card"
        private const val KIND_HINT = "hint"

        /** Counts one more opening of [kind] for [appId]; false once it was shown [TIMES] times. */
        private fun countOpen(context: Context, kind: String, appId: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val key = "$kind:$appId"
            val shown = prefs.getInt(key, 0)
            if (shown >= TIMES) return false
            prefs.edit().putInt(key, shown + 1).apply()
            return true
        }

        /** A removed app's counts go, so installing it again shows its card again. */
        @JvmStatic
        fun forget(context: Context, appId: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("$KIND_CARD:$appId").remove("$KIND_HINT:$appId").apply()
        }
    }
}
