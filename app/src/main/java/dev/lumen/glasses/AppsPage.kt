package dev.lumen.glasses

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The home's Apps tab: the grid's apps, three across, in rows that scroll vertically. Each is
 * the toolkit's WebAppIcon look, a squircle (its 32-in-112 corner) with the app's own artwork,
 * and its name under it. The focused app is the one that's bigger: its icon grows from the
 * baseline over 300 ms with the toolkit's container easing, no outline (the toolkit leaves the
 * grow to the host, and its focus rim is left out here). An app without artwork gets the
 * toolkit's fallback material (its four-stop radial) with its initial.
 *
 * Sizes are the toolkit's viewport px ([MetaStyle.px]).
 */
class AppsPage(private val activity: Activity, private val onOpen: (Entry) -> Unit) : HomePage {
    sealed class Entry {
        data class App(val app: WebApp) : Entry()
        /** One of the glasses' Android apps, added to the grid from the phone. */
        data class Native(val pkg: String, val label: String) : Entry()
        /** The glasses' own settings (MainActivity): pairing, self-arm, the band's key and log. */
        object Settings : Entry()
    }

    private class Cell(val frame: LinearLayout, val icon: View)

    private val grid = GridNavigator(COLUMNS)
    private val side: Int
    override val view: FrameLayout
    private val scroll: ScrollView
    private val rows: LinearLayout
    private var entries: List<Entry> = emptyList()
    private var cells: List<Cell> = emptyList()
    private var focus = 0

    override var active = false
        set(value) {
            if (field == value) return
            field = value
            applyFocus(animate = true)
        }

    init {
        val metrics = activity.resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        view = FrameLayout(activity).apply { setBackgroundColor(MetaStyle.WINDOW) }
        scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            // The band's commands come through the activity (see NotificationsPage).
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            defaultFocusHighlightEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(px(PAD_X), px(TOP_PAD), px(PAD_X), px(BOTTOM_PAD))
        }
        rows = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        scroll.addView(rows)
        view.addView(scroll, FrameLayout.LayoutParams(side, side))
        // The toolkit ScrollView's fading edges (64): black, see-through on the HUD.
        view.addView(fade(GradientDrawable.Orientation.TOP_BOTTOM), FrameLayout.LayoutParams(side, px(TOP_PAD), Gravity.TOP))
        view.addView(fade(GradientDrawable.Orientation.BOTTOM_TOP), FrameLayout.LayoutParams(side, px(BOTTOM_PAD), Gravity.BOTTOM))
    }

    private fun fade(orientation: GradientDrawable.Orientation) = View(activity).apply {
        background = GradientDrawable(orientation, intArrayOf(Color.BLACK, Color.TRANSPARENT))
    }

    override fun onShow() = Unit

    override fun onHide() = Unit

    /** The grid's entries, in order; the focus stays on the same position (or the last one). */
    fun setEntries(next: List<Entry>) {
        entries = next
        focus = focus.coerceIn(0, maxOf(0, entries.size - 1))
        rows.removeAllViews()
        val column = (side - 2 * px(PAD_X)) / COLUMNS
        val built = entries.map { cell(it) }
        built.chunked(COLUMNS).forEach { line ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            line.forEach { row.addView(it.frame, LinearLayout.LayoutParams(column, LinearLayout.LayoutParams.WRAP_CONTENT)) }
            rows.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = px(ROW_GAP)
            })
        }
        cells = built
        applyFocus(animate = false)
    }

    private fun cell(entry: Entry): Cell {
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
        }
        // Room for the grown icon, so growing moves nothing; the icon sits on its baseline.
        val box = FrameLayout(activity).apply { clipChildren = false }
        val icon = icon(entry)
        box.addView(icon, FrameLayout.LayoutParams(px(REST), px(REST), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        frame.addView(box, LinearLayout.LayoutParams(px(FOCUSED), px(FOCUSED)))
        frame.addView(TextView(activity).apply {
            text = label(entry)
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
            typeface = MetaStyle.REGULAR
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setPadding(px(4f), px(LABEL_GAP), px(4f), 0)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return Cell(frame, icon)
    }

    private fun label(entry: Entry) = when (entry) {
        is Entry.App -> entry.app.name
        is Entry.Native -> entry.label
        Entry.Settings -> activity.getString(R.string.launcher_settings)
    }

    /** The app's artwork in the WebAppIcon squircle, or the fallback material. */
    private fun icon(entry: Entry): View {
        val size = px(REST)
        val view: View = when (entry) {
            is Entry.App -> WebAppIcons.load(entry.app)?.let { bitmap ->
                // Artwork without its own background (a glyph) goes on the toolkit's material.
                if (isGlyph(bitmap)) fallback(artwork = bitmap) else image(bitmap)
            } ?: fallback(initial = entry.app.name)
            is Entry.Native -> runCatching { activity.packageManager.getApplicationIcon(entry.pkg) }.getOrNull()?.let { image(it) }
                ?: fallback(initial = entry.label)
            Entry.Settings -> fallback(glyph = R.drawable.ic_settings)
        }
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setRoundRect(0, 0, v.width, v.height, v.width * CORNER)
        }
        view.clipToOutline = true
        view.pivotX = size / 2f
        view.pivotY = size.toFloat()
        return view
    }

    private fun image(bitmap: Bitmap) = ImageView(activity).apply {
        setImageBitmap(bitmap)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }

    private fun image(drawable: Drawable) = ImageView(activity).apply {
        setImageDrawable(drawable)
        scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /**
     * The toolkit's fallback material (WebAppIconMaterial): its radial from the top-left, with
     * [artwork] (a transparent glyph, in the toolkit's 64-in-112 box), a [glyph] drawable, or
     * the [initial].
     */
    private fun fallback(initial: String? = null, glyph: Int = 0, artwork: Bitmap? = null): View {
        val size = px(REST)
        val frame = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.2256f, 0.1333f)
                gradientRadius = size * 1.3f
                setColors(FALLBACK_COLORS, floatArrayOf(0f, 1f / 3, 2f / 3, 1f))
            }
        }
        if (artwork != null) {
            frame.addView(ImageView(activity).apply {
                setImageBitmap(artwork)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, FrameLayout.LayoutParams(size * 64 / 112, size * 64 / 112, Gravity.CENTER))
        } else if (glyph != 0) {
            frame.addView(ImageView(activity).apply {
                setImageResource(glyph)
                setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
            }, FrameLayout.LayoutParams(size * 64 / 112, size * 64 / 112, Gravity.CENTER))
        } else {
            frame.addView(TextView(activity).apply {
                text = initial.orEmpty().trim().take(1).uppercase()
                setTextColor(MetaStyle.TEXT)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size * 0.42f)
                typeface = MetaStyle.BOLD
                gravity = Gravity.CENTER
                includeFontPadding = false
            }, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        }
        return frame
    }

    /**
     * Whether [bitmap] is a glyph on transparency rather than a full icon: more than
     * [GLYPH_TRANSPARENCY] of it is see-through (a full icon's rounded corners are far less).
     */
    private fun isGlyph(bitmap: Bitmap): Boolean {
        val sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true)
        var clear = 0
        for (y in 0 until 24) for (x in 0 until 24) if (Color.alpha(sample.getPixel(x, y)) < 128) clear++
        return clear > 24 * 24 * GLYPH_TRANSPARENCY
    }

    private fun applyFocus(animate: Boolean) {
        val grown = FOCUSED / REST
        cells.forEachIndexed { index, cell ->
            val scale = if (active && index == focus) grown else 1f
            if (animate) {
                cell.icon.animate().scaleX(scale).scaleY(scale).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            } else {
                cell.icon.animate().cancel()
                cell.icon.scaleX = scale
                cell.icon.scaleY = scale
            }
        }
        if (active) reveal(animate)
    }

    /** The focused row into view, clear of the fading edges. */
    private fun reveal(animate: Boolean) {
        val row = cells.getOrNull(focus)?.frame?.parent as? View ?: return
        scroll.post {
            val rect = Rect(0, -px(TOP_PAD), row.width, row.height + px(BOTTOM_PAD))
            scroll.requestChildRectangleOnScreen(row, rect, !animate)
        }
    }

    override fun onCommand(command: String): HomeResult {
        when (command) {
            BandCommand.ACTIVATE -> entries.getOrNull(focus)?.let(onOpen)
            BandCommand.BACK -> return HomeResult.CLOSE
            BandCommand.UP, BandCommand.DOWN, BandCommand.LEFT, BandCommand.RIGHT, BandCommand.FORWARD, BandCommand.BACKWARD -> {
                when (val next = grid.move(focus, entries.size, command)) {
                    GridNavigator.OUT_UP -> return HomeResult.UP_OUT
                    GridNavigator.OUT_LEFT -> return HomeResult.LEFT_OUT
                    GridNavigator.OUT_RIGHT -> return HomeResult.RIGHT_OUT
                    focus -> Unit
                    else -> {
                        focus = next
                        applyFocus(animate = true)
                    }
                }
            }
            else -> return HomeResult.UNHANDLED
        }
        return HomeResult.HANDLED
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    companion object {
        private const val COLUMNS = 3
        /** The resting icon and the focused one: the toolkit gallery's focused icon is 1.27x. */
        private const val REST = 104f
        private const val FOCUSED = 132f
        /** WebAppIcon's corner: 32 on its 112 side. */
        private const val CORNER = 32f / 112f
        private const val PAD_X = 24f
        private const val TOP_PAD = 84f
        private const val BOTTOM_PAD = 64f
        private const val LABEL_GAP = 8f
        private const val ROW_GAP = 16f
        private const val GLYPH_TRANSPARENCY = 0.3f
        private val FALLBACK_COLORS = intArrayOf(
            Color.parseColor("#7F93B5"), Color.parseColor("#495E84"), Color.parseColor("#27344A"), Color.parseColor("#181F2D"),
        )
    }
}
