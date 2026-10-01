package dev.lumen.glasses

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.lumen.protocol.Link
import dev.lumen.protocol.NotifyCommand

/**
 * The phone's notifications on the glasses, in the Meta Ray-Ban Display look ([MetaStyle]):
 * grouped by app (newest app first, with its count), the index tap expands an app, and again
 * opens one notification in full; the middle tap goes back a level. A left swipe on a row shows
 * the bin, a second one dismisses it (a whole app on the first level), here and on the phone.
 * The banners' snooze is the last row of the first level.
 */
class NotificationInboxActivity : Activity(), BandAccessibilityService.InputTarget, NotificationInbox.Listener {
    private sealed class Level {
        object Apps : Level()
        data class App(val packageName: String) : Level()
        data class Detail(val key: String, val packageName: String) : Level()
    }

    /** One focusable row: its view, what it dismisses, and what the index tap does. */
    private class Row(val frame: FrameLayout, val pill: LinearLayout, val bin: View, val keys: List<String>, val activate: () -> Unit)

    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private lateinit var chip: TextView
    private lateinit var toast: TextView
    private var level: Level = Level.Apps
    private var rows: List<Row> = emptyList()
    private var focus = 0
    /** Where the focus was on the first level, for the way back. */
    private var appsFocus = 0
    private var appFocus = 0
    /** The row showing its bin (a second left swipe dismisses it). */
    private var revealed = -1
    private var unread: Set<String> = emptySet()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val snoozeListener: () -> Unit = { render() }
    private val rerender = Runnable { render() }
    private var side = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        val root = FrameLayout(this).apply { setBackgroundColor(MetaStyle.WINDOW) }
        val square = FrameLayout(this).apply { setBackgroundColor(MetaStyle.WINDOW) }
        root.addView(square, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            setBackgroundColor(MetaStyle.WINDOW)
            // The band's commands come through the activity: the list never takes focus. Focused,
            // it got Android's default focus highlight, a 6% white veil over the whole square
            // (measured: black read 16,16,16), which the HUD shows as a green haze.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            defaultFocusHighlightEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            setPadding(0, px(92f), 0, px(64f))
        }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24f), 0, px(24f), 0)
        }
        scroll.addView(list)
        square.addView(scroll, FrameLayout.LayoutParams(side, side))
        // The toolkit's fading edges: black (see-through on the HUD) over the list's ends.
        square.addView(fade(GradientDrawable.Orientation.TOP_BOTTOM), FrameLayout.LayoutParams(side, px(96f), Gravity.TOP))
        square.addView(fade(GradientDrawable.Orientation.BOTTOM_TOP), FrameLayout.LayoutParams(side, px(64f), Gravity.BOTTOM))
        chip = TextView(this).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
            typeface = MetaStyle.MEDIUM
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(16f), 0, px(16f), 0)
            background = MetaStyle.pill(context)
        }
        square.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(24f) })
        toast = TextView(this).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(16f), 0, px(16f), 0)
            background = MetaStyle.pill(context)
            visibility = View.GONE
        }
        square.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(24f) })
        setContentView(root)
        openFromIntent(intent)
    }

    private fun fade(orientation: GradientDrawable.Orientation) = View(this).apply {
        background = GradientDrawable(orientation, intArrayOf(Color.BLACK, Color.TRANSPARENT))
    }

    private fun openFromIntent(intent: android.content.Intent?) {
        val key = intent?.getStringExtra(EXTRA_KEY) ?: return
        val item = NotificationInbox.find(key) ?: return
        level = Level.Detail(key, item.packageName)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
        render()
    }

    override fun onResume() {
        super.onResume()
        BandAccessibilityService.setInputTarget(this)
        NotificationInbox.addListener(this)
        NotificationSnooze.listeners += snoozeListener
        // What was new when the inbox opened keeps its accent while it's open.
        unread = unread + NotificationInbox.unreadKeys()
        NotificationInbox.markSeen()
        render()
    }

    override fun onPause() {
        BandAccessibilityService.clearInputTarget(this)
        NotificationInbox.removeListener(this)
        NotificationSnooze.listeners -= snoozeListener
        handler.removeCallbacks(rerender)
        super.onPause()
    }

    override fun onInboxChanged() {
        unread = unread + NotificationInbox.unreadKeys()
        render()
    }

    private fun render() {
        val items = NotificationInbox.all()
        val groups = NotificationGroup.of(items)
        // A level whose content is gone falls back to the one above.
        (level as? Level.Detail)?.let { if (NotificationInbox.find(it.key) == null) level = Level.App(it.packageName) }
        (level as? Level.App)?.let { app -> if (groups.none { it.packageName == app.packageName }) level = Level.Apps }
        list.removeAllViews()
        revealed = -1
        toast.visibility = View.GONE
        when (val current = level) {
            Level.Apps -> {
                chip.text = if (items.isEmpty()) getString(R.string.inbox_title) else getString(R.string.inbox_chip, getString(R.string.inbox_title), items.size)
                rows = groups.map { group ->
                    val newest = group.newest
                    val subtitle = listOf(newest.title, preview(newest, lines = 1)).filter { it.isNotBlank() }.joinToString(": ")
                    val count = group.items.size
                    row(avatar(newest.icon, group.appName), group.appName, subtitle, newest.postedAt,
                        unread = group.keys.any { it in unread }, badge = count.takeIf { it > 1 }, keys = group.keys) {
                        appsFocus = focus
                        appFocus = 0
                        level = Level.App(group.packageName)
                        render()
                    }
                } + snoozeRow()
                if (items.isEmpty()) list.addView(empty(), 0)
                focus = appsFocus.coerceIn(0, rows.lastIndex)
            }
            is Level.App -> {
                val group = groups.first { it.packageName == current.packageName }
                chip.text = getString(R.string.inbox_chip, group.appName, group.items.size)
                rows = group.items.map { item ->
                    row(avatar(item.icon, item.title.ifEmpty { item.appName }), item.title.ifEmpty { item.appName }, preview(item), item.postedAt,
                        unread = item.key in unread, badge = null, keys = listOf(item.key)) {
                        appFocus = focus
                        level = Level.Detail(item.key, item.packageName)
                        render()
                    }
                }
                focus = appFocus.coerceIn(0, rows.lastIndex)
            }
            is Level.Detail -> {
                val item = NotificationInbox.find(current.key)!!
                chip.text = item.appName.ifEmpty { getString(R.string.notification_title) }
                rows = emptyList()
                list.addView(detail(item))
                scroll.scrollTo(0, 0)
                return
            }
        }
        rows.forEach { list.addView(it.frame) }
        applyFocus(animate = false)
    }

    /** The newest [lines] of what it says (its last messages), oldest first. */
    private fun preview(item: PhoneNotification, lines: Int = 2): String =
        if (item.redacted) getString(R.string.notification_hidden) else PhoneNotification.lastLines(item.text, lines)

    private fun empty() = text(getString(R.string.inbox_empty), 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 4).apply {
        setPadding(px(12f), px(8f), px(12f), px(24f))
    }

    private fun snoozeRow(): Row {
        val until = NotificationSnooze.until(this)
        handler.removeCallbacks(rerender)
        if (until > 0) handler.postDelayed(rerender, until - System.currentTimeMillis() + 500)
        val state = if (until > 0) {
            getString(R.string.inbox_snooze_until, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(until)))
        } else {
            getString(R.string.inbox_snooze_off)
        }
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode)
            setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
            setPadding(px(14f), px(14f), px(14f), px(14f))
            background = MetaStyle.circle(context)
        }
        return row(icon, getString(R.string.inbox_snooze, NotificationSnooze.DURATION_MS / 60_000), state, 0L, unread = false, badge = null, keys = emptyList()) {
            NotificationSnooze.set(this, !NotificationSnooze.isActive(this))
        }
    }

    /** An app's icon in the toolkit's round avatar (64), or its initial. */
    private fun avatar(icon: android.graphics.Bitmap?, name: String): View =
        if (icon != null) {
            ImageView(this).apply {
                setImageBitmap(icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                // The app's own icon, as it is: no plate behind it (it would light up).
                setPadding(px(6f), px(6f), px(6f), px(6f))
            }
        } else {
            text(name.take(1).uppercase(), 28f, MetaStyle.TEXT, MetaStyle.BOLD, 1).apply {
                gravity = Gravity.CENTER
                background = MetaStyle.circle(context)
            }
        }

    /** A ListItem: avatar, title, subtitle, time (accent when new) and the count badge. */
    private fun row(avatar: View, title: String, subtitle: String, time: Long, unread: Boolean, badge: Int?, keys: List<String>, activate: () -> Unit): Row {
        val frame = FrameLayout(this).apply { setPadding(0, px(6f), 0, px(6f)) }
        val bin = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(MetaStyle.NEGATIVE, PorterDuff.Mode.SRC_IN)
            setPadding(px(22f), px(22f), px(22f), px(22f))
            background = MetaStyle.pill(context, Color.argb(220, 255, 86, 104))
            alpha = 0f
        }
        frame.addView(bin, FrameLayout.LayoutParams(px(88f), px(88f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = px(24f) })
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(104f)
            setPadding(px(20f), px(8f), px(32f), px(8f))
        }
        pill.addView(avatar, LinearLayout.LayoutParams(px(64f), px(64f)).apply { marginEnd = px(16f) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 1))
        // Two lines of what it says, at least: one was too little to go by.
        if (subtitle.isNotBlank()) texts.addView(text(subtitle, 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2))
        pill.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumWidth = px(88f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        // New: bright and bold (the toolkit's accent blue is dim in the HUD's green).
        if (time > 0) trailing.addView(text(shortAgo(time), 22f, if (unread) MetaStyle.TEXT else MetaStyle.TEXT_SECONDARY, if (unread) MetaStyle.BOLD else MetaStyle.REGULAR, 1).apply {
            ellipsize = null
            isSingleLine = true
            // Wide enough for "10 min" beside a count badge (it was cut to "min" there).
            minWidth = px(88f)
            gravity = Gravity.END
        })
        if (badge != null) {
            trailing.addView(text(badge.toString(), 22f, MetaStyle.TEXT, MetaStyle.MEDIUM, 1).apply {
                gravity = Gravity.CENTER
                minWidth = px(32f)
                minHeight = px(32f)
                setPadding(px(8f), 0, px(8f), 0)
                background = MetaStyle.pill(context, Color.argb(170, 255, 255, 255))
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(32f)).apply { topMargin = px(4f) })
        }
        pill.addView(trailing, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(16f) })
        frame.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        return Row(frame, pill, bin, keys, activate)
    }

    /** One notification in full: who and when, then its lines as message bubbles. */
    private fun detail(item: PhoneNotification): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12f), px(8f), px(12f), px(24f))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(avatar(item.icon, item.title.ifEmpty { item.appName }), LinearLayout.LayoutParams(px(56f), px(56f)).apply { marginEnd = px(16f) })
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        who.addView(text(item.title.ifEmpty { item.appName }, 28f, MetaStyle.TEXT, MetaStyle.BOLD, 2))
        who.addView(text(android.text.format.DateUtils.getRelativeTimeSpanString(item.postedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(), 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1))
        head.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val lines = if (item.redacted) listOf(getString(R.string.inbox_hidden_on_phone)) else item.text.lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            card.addView(text(line, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 12).apply {
                setPadding(px(20f), px(12f), px(20f), px(12f))
                background = MetaStyle.outline(context, 24f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        }
        return card
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface, lines: Int) = TextView(this).apply {
        text = value
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, size))
        setTextColor(color)
        typeface = face
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(MetaStyle.textPx(context, size * 0.3f), 1f)
    }

    /** "Now", "4m", "2h", or the date: the toolkit's short timestamps. */
    private fun shortAgo(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 1 -> getString(R.string.inbox_now)
            minutes < 60 -> getString(R.string.inbox_minutes, minutes)
            minutes < 24 * 60 -> getString(R.string.inbox_hours, minutes / 60)
            else -> android.text.format.DateFormat.getDateFormat(this).format(java.util.Date(time))
        }
    }

    /** The focused row grows to full width with the focus ramp; the others rest inset by 8. */
    private fun applyFocus(animate: Boolean) {
        val width = side - px(48f)
        val rest = (width - px(16f)).toFloat() / width
        rows.forEachIndexed { index, row ->
            val focused = index == focus
            row.pill.background = if (focused) MetaStyle.focused(this, width) else MetaStyle.idle(this)
            val scale = if (focused) 1f else rest
            if (animate) {
                row.pill.animate().scaleX(scale).scaleY(scale).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            } else {
                row.pill.scaleX = scale
                row.pill.scaleY = scale
            }
        }
        rows.getOrNull(focus)?.frame?.let { target ->
            scroll.post { scroll.requestChildRectangleOnScreen(target, android.graphics.Rect(0, -px(92f), target.width, target.height + px(64f)), !animate) }
        }
    }

    private fun conceal() {
        val row = rows.getOrNull(revealed) ?: return
        revealed = -1
        row.pill.animate().translationX(0f).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
        row.bin.animate().alpha(0f).setDuration(150).start()
        toast.visibility = View.GONE
    }

    /** First left swipe: the bin shows; the second dismisses (here and on the phone). */
    private fun swipeLeft() {
        val row = rows.getOrNull(focus) ?: return
        if (row.keys.isEmpty()) return
        if (revealed != focus) {
            conceal()
            revealed = focus
            row.pill.animate().translationX(-px(148f).toFloat()).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            row.bin.animate().alpha(1f).setDuration(MetaStyle.FOCUS_MS).start()
            toast.text = if (row.keys.size == 1) getString(R.string.inbox_dismiss_confirm)
            else resources.getQuantityString(R.plurals.inbox_dismiss_confirm_all, row.keys.size, row.keys.size)
            toast.visibility = View.VISIBLE
            return
        }
        revealed = -1
        dismiss(row.keys)
    }

    private fun dismiss(keys: List<String>) {
        PhoneLink.send(Link.NOTIFY, NotifyCommand.dismiss(keys))
        keys.forEach { NotificationInbox.remove(it) }
    }

    override fun onBandCommand(command: String): Boolean {
        if (command == BandCommand.LEFT) {
            if (level !is Level.Detail) swipeLeft()
            return true
        }
        // Anything else puts a shown bin away first; Back does only that.
        if (revealed >= 0) {
            conceal()
            if (command == BandCommand.BACK) return true
        }
        when (command) {
            BandCommand.BACK -> when (val current = level) {
                is Level.Detail -> { level = Level.App(current.packageName); render() }
                is Level.App -> { level = Level.Apps; render() }
                Level.Apps -> finish()
            }
            BandCommand.ACTIVATE -> rows.getOrNull(focus)?.activate?.invoke()
            BandCommand.DOWN, BandCommand.RIGHT, BandCommand.FORWARD -> step(1)
            BandCommand.UP, BandCommand.BACKWARD -> step(-1)
            else -> return false
        }
        return true
    }

    private fun step(delta: Int) {
        if (level is Level.Detail) {
            scroll.smoothScrollBy(0, delta * px(120f))
            return
        }
        val next = (focus + delta).coerceIn(0, maxOf(0, rows.lastIndex))
        if (next != focus) {
            focus = next
            applyFocus(animate = true)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BandCommand.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> BandCommand.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BandCommand.LEFT
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> BandCommand.ACTIVATE
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> BandCommand.BACK
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP) onBandCommand(command)
        return true
    }

    private fun px(value: Float) = MetaStyle.px(this, value)

    companion object {
        const val EXTRA_KEY = "key"
    }
}
