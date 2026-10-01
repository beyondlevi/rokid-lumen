package dev.lumen.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.animation.PathInterpolator

/**
 * The Meta Ray-Ban Display look (facebook/meta-ray-ban-display-ui-toolkit-web, the web apps'
 * UI Toolkit) for the host's native screens: its tokens are CSS px on MRBD's 600x600 viewport,
 * scaled here to the HUD's square ([px]).
 *
 * The Rokid's display is additive and monochrome: anything not black lights up, in green. The
 * toolkit's grey surfaces (#27282D and its focus ramp), subtle on a colour screen, became solid
 * green blocks there (measured on the glasses). So this keeps the toolkit's type, spacing,
 * radii, layout and focus motion, and draws surfaces as outlines on black: a row at rest has no
 * background, the focused one a bright edge over a barely lit fill, chips and badges an outline.
 */
object MetaStyle {
    // theme.css
    val WINDOW = Color.BLACK
    val SURFACE = Color.parseColor("#27282D")
    val ELEVATION1 = Color.parseColor("#30333A")
    val ELEVATION2 = Color.parseColor("#41454E")
    val TEXT = Color.WHITE
    val TEXT_SECONDARY = Color.parseColor("#CFD3D9")
    val TEXT_PLACEHOLDER = Color.parseColor("#AAAFB9")
    val ACCENT = Color.parseColor("#2694FE")
    val NEGATIVE = Color.parseColor("#FF5668")
    /** The container focus ramp (secondary), from the top-left. */
    private val FOCUS_RAMP = intArrayOf(
        Color.parseColor("#585E6A"), Color.parseColor("#30333A"), Color.parseColor("#1E1E21"), Color.parseColor("#111113"),
    )
    private val FOCUS_STOPS = floatArrayOf(0f, 0.33f, 0.66f, 1f)

    /** 300 ms, cubic-bezier(0.68, 0, 0.29, 1): default and focused states (Animations.ts). */
    const val FOCUS_MS = 300L
    val FOCUS_EASING = PathInterpolator(0.68f, 0f, 0.29f, 1f)

    /** A viewport px (MRBD's 600-wide square) in the HUD's pixels. */
    fun px(context: Context, value: Float): Int {
        val metrics = context.resources.displayMetrics
        return Math.round(value * minOf(metrics.widthPixels, metrics.heightPixels) / 600f)
    }

    /** Text size for a viewport px size (setTextSize in raw pixels). */
    fun textPx(context: Context, value: Float): Float = value * minOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels) / 600f

    val REGULAR: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val BOLD: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    /** A row at rest: nothing behind it (black is see-through on the HUD). */
    fun idle(context: Context, radius: Float = 32f): GradientDrawable = GradientDrawable().apply {
        setColor(Color.TRANSPARENT)
        cornerRadius = px(context, radius).toFloat()
    }

    /** A card or bubble that isn't focusable: a faint outline. */
    fun outline(context: Context, radius: Float = 32f, alpha: Int = 70): GradientDrawable = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = px(context, radius).toFloat()
        setStroke(maxOf(1, px(context, 2f)), Color.argb(alpha, 255, 255, 255))
    }

    /** Focused: the toolkit's bright 3 px edge over a barely lit fill (its ramp's darkest end). */
    @Suppress("UNUSED_PARAMETER")
    fun focused(context: Context, width: Int, radius: Float = 32f): GradientDrawable = GradientDrawable().apply {
        setColor(FOCUS_RAMP[3])
        cornerRadius = px(context, radius).toFloat()
        setStroke(px(context, 3f), Color.argb(220, 255, 255, 255))
    }

    /** A chip, toast or badge: an outlined pill on black. */
    fun pill(context: Context, stroke: Int = Color.argb(110, 255, 255, 255)): GradientDrawable = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = 9_999f
        setStroke(maxOf(1, px(context, 2f)), stroke)
    }

    /** An avatar without a picture (its initial): an outlined circle. */
    fun circle(context: Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.BLACK)
        setStroke(px(context, 2f), Color.argb(110, 255, 255, 255))
    }
}
