package dev.lumen.glasses

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** The HUD's palette and outlines, as MainActivity draws them (after R08 Access Bridge). */
object HudStyle {
    val TEXT = Color.rgb(248, 250, 249)
    val DETAIL = Color.rgb(161, 183, 172)
    val MUTED = Color.rgb(117, 142, 130)
    val ACCENT = Color.rgb(102, 242, 165)
    val WARN = Color.rgb(238, 190, 92)
    val PANEL = Color.rgb(16, 22, 19)
    val FOCUS_FILL = Color.rgb(18, 24, 21)

    fun dp(context: Context, value: Float) = Math.round(value * context.resources.displayMetrics.density)

    fun outline(context: Context, focused: Boolean, radius: Float = 12f): GradientDrawable = GradientDrawable().apply {
        setColor(if (focused) FOCUS_FILL else Color.TRANSPARENT)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(if (focused) dp(context, 2.5f) else dp(context, 1f), if (focused) ACCENT else Color.argb(0, 0, 0, 0))
    }

    fun panel(context: Context, radius: Float = 14f, stroke: Int = ACCENT): GradientDrawable = GradientDrawable().apply {
        setColor(PANEL)
        cornerRadius = dp(context, radius).toFloat()
        setStroke(dp(context, 2f), stroke)
    }

    fun badge(context: Context, color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.BLACK)
        cornerRadius = dp(context, 5f).toFloat()
        setStroke(dp(context, 1f), color)
    }

    /** A letter tile for an app without an icon; the hue comes from its name. */
    fun letterTile(context: Context, name: String): GradientDrawable = GradientDrawable().apply {
        val hue = (name.hashCode() and 0x7fffffff) % 360
        setColor(Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.55f, 0.45f)))
        cornerRadius = dp(context, 14f).toFloat()
    }
}
