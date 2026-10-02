package dev.lumen.glasses

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The home's Controls tab, right of Apps: quick settings in the toolkit's tile look. A row of
 * AppControlTiles (two icon-only, one titled) for the Rokid launcher's camera, gallery and music
 * screens; ControlTiles two across for volume and brightness (a circular progress ring: the index
 * tap starts adjusting, the swipes change it, the index or middle tap ends), do not disturb (the
 * banners' snooze, checked when on) and the Rokid's settings; last, full width, the Rokid
 * launcher: the only way out of Lumen, whose Back stays in the home.
 *
 * The toolkit's metrics (ControlTile, AppControlTile): tiles 120 high with a 32 corner, an 8 gap,
 * a 72 icon circle, the 24 px bold label 24 from it; ring 56 across, 6 thick, a 260-degree arc
 * open at the bottom. Surfaces are outlines on black, as [MetaStyle] explains for this display.
 *
 * Focus: right and left walk the tiles in order (left from the first is the Apps tab); up and
 * down change rows, keeping to the column (up from the first row is the tabs).
 */
class ControlsPage(private val activity: Activity, private val say: (String) -> Unit) : HomePage {
    private enum class Kind { CAMERA, GALLERY, MUSIC, VOLUME, BRIGHTNESS, DND, SETTINGS, ROKID }

    /** A tile: its row and its horizontal span (fractions of the row), for up and down. */
    private class Tile(
        val kind: Kind, val row: Int, val start: Float, val end: Float,
        val frame: FrameLayout, val iconCircle: View?, val icon: ImageView, val label: TextView?, val ring: Ring?,
    )

    private val side: Int
    override val view: FrameLayout
    private val tiles: List<Tile>
    private var focus = 0
    private var adjusting: Tile? = null
    private var volume = 0f
    private var brightness = 0f
    private val snoozeListener: () -> Unit = { refresh() }

    override var active = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) adjusting = null
            applyFocus(animate = true)
        }

    init {
        val metrics = activity.resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        view = FrameLayout(activity).apply { setBackgroundColor(MetaStyle.WINDOW) }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(px(PAD_X), px(TOP_PAD), px(PAD_X), 0)
        }
        view.addView(content, FrameLayout.LayoutParams(side, side))
        val width = side - 2 * px(PAD_X)
        val gap = px(GAP)
        val quarter = (width - 3 * gap) / 4
        val half = (width - gap) / 2

        val built = mutableListOf<Tile>()
        fun row(vararg cells: Pair<Tile, Int>) {
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            cells.forEachIndexed { i, (tile, w) ->
                line.addView(tile.frame, LinearLayout.LayoutParams(w, px(HEIGHT)).apply { if (i > 0) marginStart = gap })
                built += tile
            }
            content.addView(line, LinearLayout.LayoutParams(width, px(HEIGHT)).apply { if (content.childCount > 0) topMargin = gap })
        }
        row(
            appTile(Kind.CAMERA, 0, 0f, .25f, R.drawable.ic_ctl_camera, R.string.controls_camera, CAMERA_COLORS, titled = false) to quarter,
            appTile(Kind.GALLERY, 0, .25f, .5f, R.drawable.ic_ctl_gallery, R.string.controls_gallery, GALLERY_COLORS, titled = false) to quarter,
            appTile(Kind.MUSIC, 0, .5f, 1f, R.drawable.ic_ctl_music, R.string.controls_music, MUSIC_COLORS, titled = true) to half,
        )
        row(
            controlTile(Kind.VOLUME, 1, 0f, .5f, R.drawable.ic_ctl_volume_high, R.string.controls_volume, progress = true) to half,
            controlTile(Kind.BRIGHTNESS, 1, .5f, 1f, R.drawable.ic_ctl_brightness_high, R.string.controls_brightness, progress = true) to half,
        )
        row(
            controlTile(Kind.DND, 2, 0f, .5f, R.drawable.ic_ctl_dnd, R.string.controls_dnd, progress = false) to half,
            controlTile(Kind.SETTINGS, 2, .5f, 1f, R.drawable.ic_ctl_settings, R.string.controls_settings, progress = false) to half,
        )
        row(appTile(Kind.ROKID, 3, 0f, 1f, R.drawable.ic_ctl_rokid, R.string.controls_rokid, ROKID_COLORS, titled = true) to width)
        tiles = built
        applyFocus(animate = false)
    }

    override fun onShow() {
        NotificationSnooze.listeners += snoozeListener
        refresh()
    }

    /** Coming from another tab, the focus starts on the first tile (back from a screen, it stays). */
    fun resetFocus() {
        focus = 0
        adjusting = null
        applyFocus(animate = false)
    }

    override fun onHide() {
        NotificationSnooze.listeners -= snoozeListener
        adjusting = null
        applyFocus(animate = false)
    }

    /** The values shown: volume, brightness and whether do not disturb is on (they change elsewhere too). */
    private fun refresh() {
        volume = SystemControls.volume(activity)
        brightness = SystemControls.brightness(activity)
        tiles.forEach { tile ->
            when (tile.kind) {
                Kind.VOLUME -> showLevel(tile, volume)
                Kind.BRIGHTNESS -> showLevel(tile, brightness)
                Kind.DND -> showChecked(tile, NotificationSnooze.isActive(activity))
                else -> Unit
            }
        }
    }

    override fun onCommand(command: String): HomeResult {
        adjusting?.let { tile -> return adjust(tile, command) }
        when (command) {
            BandCommand.ACTIVATE -> tiles.getOrNull(focus)?.let { activate(it) }
            BandCommand.BACK -> return HomeResult.CLOSE
            BandCommand.RIGHT, BandCommand.FORWARD -> move(focus + 1)
            BandCommand.LEFT, BandCommand.BACKWARD -> {
                if (focus == 0) return if (command == BandCommand.LEFT) HomeResult.LEFT_OUT else HomeResult.HANDLED
                move(focus - 1)
            }
            BandCommand.UP -> {
                val row = tiles[focus].row
                if (row == 0) return HomeResult.UP_OUT
                move(nearest(row - 1))
            }
            BandCommand.DOWN -> {
                val row = tiles[focus].row
                if (row < tiles.last().row) move(nearest(row + 1))
            }
            else -> return HomeResult.UNHANDLED
        }
        return HomeResult.HANDLED
    }

    /** The tile of [row] under the focused one's middle. */
    private fun nearest(row: Int): Int {
        val middle = tiles[focus].let { (it.start + it.end) / 2 }
        return tiles.indices.filter { tiles[it].row == row }
            .minByOrNull { abs((tiles[it].start + tiles[it].end) / 2 - middle) } ?: focus
    }

    private fun move(to: Int) {
        val next = to.coerceIn(0, tiles.lastIndex)
        if (next == focus) return
        focus = next
        applyFocus(animate = true)
    }

    private fun activate(tile: Tile) {
        when (tile.kind) {
            Kind.CAMERA -> openRokid(SystemControls.RokidScreen.CAMERA)
            Kind.GALLERY -> openRokid(SystemControls.RokidScreen.GALLERY)
            Kind.MUSIC -> openRokid(SystemControls.RokidScreen.MUSIC)
            Kind.SETTINGS -> openRokid(SystemControls.RokidScreen.SETTINGS)
            Kind.ROKID -> SystemControls.openRokidLauncher(activity)
            Kind.VOLUME, Kind.BRIGHTNESS -> {
                refresh()
                adjusting = tile
                applyFocus(animate = true)
            }
            Kind.DND -> {
                NotificationSnooze.set(activity, !NotificationSnooze.isActive(activity))
                val on = NotificationSnooze.isActive(activity)
                showChecked(tile, on)
                say(activity.getString(if (on) R.string.controls_dnd_on else R.string.controls_dnd_off))
            }
        }
    }

    private fun openRokid(screen: SystemControls.RokidScreen) {
        if (!SystemControls.open(activity, screen)) say(activity.getString(R.string.controls_unavailable))
    }

    /** While adjusting: up, right and forward raise; down, left and backward lower; a tap ends. */
    private fun adjust(tile: Tile, command: String): HomeResult {
        val up = when (command) {
            BandCommand.UP, BandCommand.RIGHT, BandCommand.FORWARD -> true
            BandCommand.DOWN, BandCommand.LEFT, BandCommand.BACKWARD -> false
            BandCommand.ACTIVATE, BandCommand.BACK -> {
                adjusting = null
                applyFocus(animate = true)
                return HomeResult.HANDLED
            }
            else -> return HomeResult.UNHANDLED
        }
        if (tile.kind == Kind.VOLUME) {
            volume = SystemControls.stepVolume(activity, up)
            showLevel(tile, volume)
        } else {
            brightness = SystemControls.stepBrightness(activity, brightness, up) {
                // No way to write it (no self-arm): the Rokid's brightness screen does it.
                adjusting = null
                applyFocus(animate = true)
                openRokid(SystemControls.RokidScreen.BRIGHTNESS)
            }
            showLevel(tile, brightness)
        }
        return HomeResult.HANDLED
    }

    // ---- Views ----

    /** The toolkit's ControlTile: icon circle (with a ring when [progress]) and a label. */
    private fun controlTile(kind: Kind, row: Int, start: Float, end: Float, iconRes: Int, labelRes: Int, progress: Boolean): Tile {
        val frame = tileFrame()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(24f), px(16f), px(24f), px(16f))
        }
        val circle = FrameLayout(activity).apply { background = circleIdle() }
        val ring = if (progress) Ring(activity).also { circle.addView(it, FrameLayout.LayoutParams(px(56f), px(56f), Gravity.CENTER)) } else null
        val icon = glyph(iconRes)
        val iconSize = px(if (progress) 24f else 32f)
        circle.addView(icon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER))
        content.addView(circle, LinearLayout.LayoutParams(px(72f), px(72f)))
        val label = label(labelRes)
        content.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(24f) })
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return Tile(kind, row, start, end, frame, circle, icon, label, ring)
    }

    /** The toolkit's AppControlTile: the app's icon on its material, with a title when [titled]. */
    private fun appTile(kind: Kind, row: Int, start: Float, end: Float, iconRes: Int, labelRes: Int, colors: IntArray, titled: Boolean): Tile {
        val frame = tileFrame()
        val media = FrameLayout(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                gradientType = GradientDrawable.RADIAL_GRADIENT
                setGradientCenter(0.2256f, 0.1333f)
                gradientRadius = px(72f) * 1.3f
                setColors(colors, floatArrayOf(0f, 1f / 3, 2f / 3, 1f))
            }
        }
        val icon = glyph(iconRes)
        media.addView(icon, FrameLayout.LayoutParams(px(40f), px(40f), Gravity.CENTER))
        var label: TextView? = null
        if (titled) {
            val content = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(20f), px(20f), px(20f), px(20f))
            }
            content.addView(media, LinearLayout.LayoutParams(px(72f), px(72f)))
            label = label(labelRes).apply { maxLines = 1 }
            content.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(16f) })
            frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        } else {
            frame.addView(media, FrameLayout.LayoutParams(px(72f), px(72f), Gravity.CENTER))
            frame.contentDescription = activity.getString(labelRes)
        }
        return Tile(kind, row, start, end, frame, null, icon, label, null)
    }

    private fun tileFrame() = FrameLayout(activity).apply { clipChildren = false }

    private fun glyph(res: Int) = ImageView(activity).apply {
        setImageResource(res)
        setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
    }

    private fun label(res: Int) = TextView(activity).apply {
        text = activity.getString(res)
        setTextColor(MetaStyle.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 24f))
        typeface = MetaStyle.BOLD
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    /** The icon circle at rest: the toolkit's inset dark disc, an outline on this display. */
    private fun circleIdle() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.BLACK)
        setStroke(maxOf(1, px(2f)), Color.argb(90, 255, 255, 255))
    }

    /** Checked: the toolkit's white disc with a dark glyph. */
    private fun circleChecked() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.WHITE)
    }

    private fun showLevel(tile: Tile, level: Float) {
        tile.ring?.level = level
        tile.icon.setImageResource(
            when (tile.kind) {
                Kind.VOLUME -> when {
                    level <= 0f -> R.drawable.ic_ctl_volume_off
                    level <= 1f / 3 -> R.drawable.ic_ctl_volume_low
                    level <= 2f / 3 -> R.drawable.ic_ctl_volume_mid
                    else -> R.drawable.ic_ctl_volume_high
                }
                else -> when {
                    level <= 1f / 3 -> R.drawable.ic_ctl_brightness_low
                    level <= 2f / 3 -> R.drawable.ic_ctl_brightness_mid
                    else -> R.drawable.ic_ctl_brightness_high
                }
            },
        )
        updateLabel(tile)
    }

    private fun showChecked(tile: Tile, checked: Boolean) {
        tile.iconCircle?.background = if (checked) circleChecked() else circleIdle()
        tile.icon.setColorFilter(if (checked) Color.BLACK else MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
    }

    /** While adjusting, the label is the level ("60%"); otherwise the setting's name. */
    private fun updateLabel(tile: Tile) {
        val label = tile.label ?: return
        val level = if (tile.kind == Kind.VOLUME) volume else brightness
        label.text = if (adjusting === tile) activity.getString(R.string.controls_level, (level * 100).roundToInt())
        else activity.getString(if (tile.kind == Kind.VOLUME) R.string.controls_volume else R.string.controls_brightness)
    }

    /**
     * The focused tile full size on the focused material; the others inset 8 each side (the
     * toolkit's (w-16)/w) on a faint outline. While adjusting, the tile keeps the focus.
     */
    private fun applyFocus(animate: Boolean) {
        tiles.forEachIndexed { index, tile ->
            val focused = active && index == focus
            val width = tile.frame.layoutParams?.width ?: px(HEIGHT)
            tile.frame.background = if (focused) MetaStyle.focused(activity, width) else MetaStyle.outline(activity, alpha = 70)
            val scale = if (focused) 1f else (width - px(16f)).toFloat() / width
            if (animate) {
                tile.frame.animate().scaleX(scale).scaleY(scale).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            } else {
                tile.frame.animate().cancel()
                tile.frame.scaleX = scale
                tile.frame.scaleY = scale
            }
            if (tile.kind == Kind.VOLUME || tile.kind == Kind.BRIGHTNESS) {
                tile.ring?.adjusting = adjusting === tile
                updateLabel(tile)
            }
        }
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    /** The toolkit's circular progress: a 260-degree arc open at the bottom, track at 20%. */
    private class Ring(context: Context) : View(context) {
        var level = 0f
            set(value) {
                field = value.coerceIn(0f, 1f)
                invalidate()
            }
        /** Adjusting: the track a little brighter, so the tile reads as taken. */
        var adjusting = false
            set(value) {
                field = value
                invalidate()
            }
        private val stroke = MetaStyle.px(context, 6f).toFloat()
        private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.WHITE
        }
        private val value = Paint(track).apply { alpha = 255 }
        private val box = RectF()

        override fun onDraw(canvas: Canvas) {
            box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            track.alpha = if (adjusting) 90 else 51
            canvas.drawArc(box, START, SWEEP, false, track)
            if (level > 0f) canvas.drawArc(box, START, SWEEP * level, false, value)
        }

        companion object {
            private const val START = 140f
            private const val SWEEP = 260f
        }
    }

    companion object {
        private const val PAD_X = 32f
        /** Below the home's tabs (20 + 44), with room. */
        private const val TOP_PAD = 84f
        private const val HEIGHT = 120f
        private const val GAP = 8f
        /** The toolkit gallery's AppControlTile materials: Captions, Camera, Audio. */
        private val GALLERY_COLORS = intArrayOf(Color.parseColor("#FFB23F"), Color.parseColor("#D97100"), Color.parseColor("#713400"), Color.parseColor("#341600"))
        private val CAMERA_COLORS = intArrayOf(Color.parseColor("#F28BB9"), Color.parseColor("#BD4C81"), Color.parseColor("#662040"), Color.parseColor("#310E1E"))
        /** The toolkit's fallback material (WebAppIconMaterial), as the grid's apps without artwork. */
        private val ROKID_COLORS = intArrayOf(Color.parseColor("#7F93B5"), Color.parseColor("#495E84"), Color.parseColor("#27344A"), Color.parseColor("#181F2D"))
        private val MUSIC_COLORS = intArrayOf(Color.parseColor("#A98BE8"), Color.parseColor("#7051B4"), Color.parseColor("#38245F"), Color.parseColor("#1C102F"))
    }
}
