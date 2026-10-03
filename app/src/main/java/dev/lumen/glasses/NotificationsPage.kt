package dev.lumen.glasses

import android.app.Activity
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.util.Log
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
 * The home's Notifications tab: the phone's notifications in the Meta Ray-Ban Display look
 * ([MetaStyle]), grouped by app (newest app first, with its count); the index tap expands an
 * app, and again opens one notification in full; the middle tap goes back a level. A left swipe
 * on a row shows the bin, a second one dismisses it (a whole app on the first level), here and
 * on the phone. The banners' snooze is the last row of the first level.
 *
 * [view] goes in the home's pager; the home ([LauncherActivity]) passes the band's commands to
 * [onCommand] while the focus is in the page, and gets back what the page leaves to it.
 */
class NotificationsPage(private val activity: Activity) : HomePage, NotificationInbox.Listener {

    private sealed class Level {
        object Apps : Level()
        data class App(val packageName: String) : Level()
        data class Detail(val key: String, val packageName: String) : Level()
    }

    /** One focusable row: its view, what it dismisses, and what the index tap does. */
    private class Row(val frame: FrameLayout, val pill: LinearLayout, val bin: View, val keys: List<String>, val activate: () -> Unit)

    override val view: FrameLayout
    private val scroll: ScrollView
    private val list: LinearLayout
    private val chip: TextView
    private val toast: TextView
    /** Under an open notification: its web app and quick replies ([QuickReplyBar]). */
    private val bar = QuickReplyBar(activity)
    private val barShade: View
    private val hideToast = Runnable { toast.visibility = View.GONE }
    private val replyListener = NotificationReplies.Listener { _, ok ->
        say(activity.getString(if (ok) R.string.quick_reply_sent else R.string.quick_reply_failed))
    }
    private var level: Level = Level.Apps
    /** The level [render] last drew, to tell a redraw of the same level from a move. */
    private var renderedLevel: Level? = null
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
    private val side: Int
    private var shown = false

    /** Whether the focus is in this page (rows show it) or on the home's tabs. */
    override var active = false
        set(value) {
            if (field == value) return
            field = value
            if (!value) conceal()
            bar.active = value && level is Level.Detail
            applyFocus(animate = true)
        }

    init {
        val metrics = activity.resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        view = FrameLayout(activity).apply { setBackgroundColor(MetaStyle.WINDOW) }
        scroll = ScrollView(activity).apply {
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
            setPadding(0, px(TOP_PAD), 0, px(64f))
        }
        list = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24f), 0, px(24f), 0)
        }
        scroll.addView(list)
        view.addView(scroll, FrameLayout.LayoutParams(side, side))
        // The toolkit's fading edges: black (see-through on the HUD) over the list's ends.
        view.addView(fade(GradientDrawable.Orientation.TOP_BOTTOM), FrameLayout.LayoutParams(side, px(96f), Gravity.TOP))
        view.addView(fade(GradientDrawable.Orientation.BOTTOM_TOP), FrameLayout.LayoutParams(side, px(64f), Gravity.BOTTOM))
        chip = TextView(activity).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
            typeface = MetaStyle.MEDIUM
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(16f), 0, px(16f), 0)
            background = MetaStyle.pill(context)
            visibility = View.GONE
        }
        view.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(76f) })
        toast = TextView(activity).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 22f))
            gravity = Gravity.CENTER
            minHeight = px(44f)
            setPadding(px(16f), 0, px(16f), 0)
            background = MetaStyle.pill(context)
            visibility = View.GONE
        }
        view.addView(toast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(24f) })
        // Black behind the bar (see-through on the HUD), fading up into the text it covers.
        barShade = View(activity).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT))
            visibility = View.GONE
        }
        view.addView(barShade, FrameLayout.LayoutParams(side, px(BAR_BOTTOM + QuickReplyBar.HEIGHT + 40f), Gravity.BOTTOM))
        view.addView(bar.view, FrameLayout.LayoutParams(side, px(QuickReplyBar.HEIGHT), Gravity.BOTTOM).apply { bottomMargin = px(BAR_BOTTOM) })
        toast.bringToFront()
    }

    private fun fade(orientation: GradientDrawable.Orientation) = View(activity).apply {
        background = GradientDrawable(orientation, intArrayOf(Color.BLACK, Color.TRANSPARENT))
    }

    /** Opens one notification in full (a banner's index tap). */
    fun openKey(key: String) {
        val item = NotificationInbox.find(key) ?: return
        level = Level.Detail(key, item.packageName)
        if (shown) render()
    }

    /** The tab came into view: what's new keeps its accent while it's shown, and is seen. */
    override fun onShow() {
        if (shown) return
        shown = true
        NotificationInbox.addListener(this)
        NotificationSnooze.listeners += snoozeListener
        NotificationReplies.listeners += replyListener
        unread = unread + NotificationInbox.unreadKeys()
        NotificationInbox.markSeen()
        render()
    }

    override fun onHide() {
        if (!shown) return
        shown = false
        NotificationInbox.removeListener(this)
        NotificationSnooze.listeners -= snoozeListener
        NotificationReplies.listeners -= replyListener
        handler.removeCallbacks(rerender)
        conceal()
    }

    override fun onInboxChanged() {
        unread = unread + NotificationInbox.unreadKeys()
        NotificationInbox.markSeen()
        render()
    }

    private fun render() {
        val items = NotificationInbox.all()
        val groups = NotificationGroup.of(items)
        // A level whose content is gone falls back to the one above.
        (level as? Level.Detail)?.let { if (NotificationInbox.find(it.key) == null) level = Level.App(it.packageName) }
        (level as? Level.App)?.let { app -> if (groups.none { it.packageName == app.packageName }) level = Level.Apps }
        // The same level again (the inbox changed under it): the focus stays on its row and an
        // open notification where it was scrolled to.
        val again = level == renderedLevel
        val focusedKeys = if (again) rows.getOrNull(focus)?.keys.orEmpty() else emptyList()
        val wasFocus = focus
        renderedLevel = level
        list.removeAllViews()
        revealed = -1
        // The tab's pill names the first level; deeper ones get their own chip under it.
        chip.visibility = if (level == Level.Apps) View.GONE else View.VISIBLE
        scroll.setPadding(0, px(if (level == Level.Apps) TOP_PAD else TOP_PAD_CHIP), 0, px(64f))
        toast.visibility = View.GONE
        when (val current = level) {
            Level.Apps -> {
                chip.text = if (items.isEmpty()) activity.getString(R.string.inbox_title) else activity.getString(R.string.inbox_chip, activity.getString(R.string.inbox_title), items.size)
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
                focus = if (again) keptFocus(focusedKeys, wasFocus) else appsFocus.coerceIn(0, rows.lastIndex)
            }
            is Level.App -> {
                val group = groups.first { it.packageName == current.packageName }
                chip.text = activity.getString(R.string.inbox_chip, group.appName, group.items.size)
                rows = group.items.map { item ->
                    row(avatar(item.icon, item.title.ifEmpty { item.appName }), item.title.ifEmpty { item.appName }, preview(item), item.postedAt,
                        unread = item.key in unread, badge = null, keys = listOf(item.key)) {
                        appFocus = focus
                        level = Level.Detail(item.key, item.packageName)
                        render()
                    }
                }
                focus = if (again) keptFocus(focusedKeys, wasFocus) else appFocus.coerceIn(0, rows.lastIndex)
            }
            is Level.Detail -> {
                val item = NotificationInbox.find(current.key)!!
                chip.text = item.appName.ifEmpty { activity.getString(R.string.notification_title) }
                rows = emptyList()
                list.addView(detail(item))
                bar.show(quickActions(item), keepFocus = again)
                bar.active = active
                placeBar()
                if (!again) scroll.scrollTo(0, 0)
                return
            }
        }
        bar.hide()
        placeBar()
        rows.forEach { list.addView(it.frame) }
        applyFocus(animate = false)
    }

    /** The web app that takes [item] first, then quick replies when the phone can send them. */
    private fun quickActions(item: PhoneNotification): List<QuickReplyBar.Action> {
        val out = mutableListOf<QuickReplyBar.Action>()
        // Each app that takes it: a personal and a work WhatsApp both offer their icon.
        WebAppNotifications.targets(activity, item).forEach { out += QuickReplyBar.Action.OpenApp(it, WebAppIcons.load(it.app)) }
        if (item.replyable && !item.redacted) {
            activity.resources.getStringArray(R.array.quick_reactions).forEach { out += QuickReplyBar.Action.Reply(it, iconOnly = true) }
            activity.resources.getStringArray(R.array.quick_replies).forEach { out += QuickReplyBar.Action.Reply(it, iconOnly = false) }
        }
        return out
    }

    /** Room under the text for the bar, and the toast above it, while it shows. */
    private fun placeBar() {
        barShade.visibility = if (bar.shown) View.VISIBLE else View.GONE
        scroll.setPadding(0, scroll.paddingTop, 0, px(if (bar.shown) 64f + QuickReplyBar.HEIGHT + BAR_BOTTOM else 64f))
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = px(if (bar.shown) BAR_BOTTOM + QuickReplyBar.HEIGHT + 12f else 24f)
        toast.requestLayout()
    }

    private fun perform(item: PhoneNotification, action: QuickReplyBar.Action) {
        when (action) {
            is QuickReplyBar.Action.OpenApp -> WebAppActivity.open(activity, action.target.app, action.target.path)
            is QuickReplyBar.Action.Reply -> say(
                activity.getString(if (NotificationReplies.send(item.key, action.text)) R.string.quick_reply_sending else R.string.quick_reply_no_phone),
            )
        }
    }

    private fun say(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, 2_500)
    }

    /** The row holding any of [keys] (its group may have gained one), else [index] kept in range. */
    private fun keptFocus(keys: List<String>, index: Int): Int {
        val found = if (keys.isEmpty()) -1 else rows.indexOfFirst { row -> row.keys.any { it in keys } }
        return if (found >= 0) found else index.coerceIn(0, maxOf(0, rows.lastIndex))
    }

    /** The newest [lines] of what it says (its last messages), oldest first. */
    private fun preview(item: PhoneNotification, lines: Int = 2): String =
        if (item.redacted) activity.getString(R.string.notification_hidden) else PhoneNotification.lastLines(item.text, lines)

    private fun empty() = text(activity.getString(R.string.inbox_empty), 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 4).apply {
        setPadding(px(12f), px(8f), px(12f), px(24f))
    }

    private fun snoozeRow(): Row {
        val until = NotificationSnooze.until(activity)
        handler.removeCallbacks(rerender)
        if (until > 0) handler.postDelayed(rerender, until - System.currentTimeMillis() + 500)
        val state = if (until > 0) {
            activity.getString(R.string.inbox_snooze_until, android.text.format.DateFormat.getTimeFormat(activity).format(java.util.Date(until)))
        } else {
            activity.getString(R.string.inbox_snooze_off)
        }
        val icon = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_lock_silent_mode)
            setColorFilter(MetaStyle.TEXT, PorterDuff.Mode.SRC_IN)
            setPadding(px(14f), px(14f), px(14f), px(14f))
            background = MetaStyle.circle(context)
        }
        return row(icon, activity.getString(R.string.inbox_snooze, NotificationSnooze.DURATION_MS / 60_000), state, 0L, unread = false, badge = null, keys = emptyList()) {
            NotificationSnooze.set(activity, !NotificationSnooze.isActive(activity))
        }
    }

    /** An app's icon in the toolkit's round avatar (64), or its initial. */
    private fun avatar(icon: android.graphics.Bitmap?, name: String): View =
        if (icon != null) {
            ImageView(activity).apply {
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
        val frame = FrameLayout(activity).apply { setPadding(0, px(6f), 0, px(6f)) }
        val bin = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_delete)
            setColorFilter(MetaStyle.NEGATIVE, PorterDuff.Mode.SRC_IN)
            setPadding(px(22f), px(22f), px(22f), px(22f))
            background = MetaStyle.pill(context, Color.argb(220, 255, 86, 104))
            alpha = 0f
        }
        frame.addView(bin, FrameLayout.LayoutParams(px(88f), px(88f), Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = px(24f) })
        val pill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(104f)
            setPadding(px(20f), px(8f), px(32f), px(8f))
        }
        pill.addView(avatar, LinearLayout.LayoutParams(px(64f), px(64f)).apply { marginEnd = px(16f) })
        val texts = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 1))
        // Two lines of what it says, at least: one was too little to go by.
        if (subtitle.isNotBlank()) texts.addView(text(subtitle, 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2))
        pill.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val trailing = LinearLayout(activity).apply {
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
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(12f), px(8f), px(12f), px(24f))
        }
        val head = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(avatar(item.icon, item.title.ifEmpty { item.appName }), LinearLayout.LayoutParams(px(56f), px(56f)).apply { marginEnd = px(16f) })
        val who = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        who.addView(text(item.title.ifEmpty { item.appName }, 28f, MetaStyle.TEXT, MetaStyle.BOLD, 2))
        who.addView(text(android.text.format.DateUtils.getRelativeTimeSpanString(item.postedAt, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString(), 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1))
        head.addView(who, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(head)
        val lines = if (item.redacted) listOf(activity.getString(R.string.inbox_hidden_on_phone)) else item.text.lines().filter { it.isNotBlank() }
        lines.forEach { line ->
            card.addView(text(line, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 12).apply {
                setPadding(px(20f), px(12f), px(20f), px(12f))
                background = MetaStyle.outline(context, 24f)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        }
        return card
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface, lines: Int) = TextView(activity).apply {
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
            minutes < 1 -> activity.getString(R.string.inbox_now)
            minutes < 60 -> activity.getString(R.string.inbox_minutes, minutes)
            minutes < 24 * 60 -> activity.getString(R.string.inbox_hours, minutes / 60)
            else -> android.text.format.DateFormat.getDateFormat(activity).format(java.util.Date(time))
        }
    }

    /** The focused row grows to full width with the focus ramp; the others rest inset by 8. */
    private fun applyFocus(animate: Boolean) {
        val width = side - px(48f)
        val rest = (width - px(16f)).toFloat() / width
        rows.forEachIndexed { index, row ->
            // While the tabs have the focus, no row has it.
            val focused = active && index == focus
            row.pill.background = if (focused) MetaStyle.focused(activity, width) else MetaStyle.idle(activity)
            val scale = if (focused) 1f else rest
            if (animate) {
                row.pill.animate().scaleX(scale).scaleY(scale).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
            } else {
                row.pill.scaleX = scale
                row.pill.scaleY = scale
            }
        }
        rows.getOrNull(focus)?.frame?.let { target ->
            scroll.post { scroll.requestChildRectangleOnScreen(target, android.graphics.Rect(0, -scroll.paddingTop, target.width, target.height + px(64f)), !animate) }
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
            toast.text = if (row.keys.size == 1) activity.getString(R.string.inbox_dismiss_confirm)
            else activity.resources.getQuantityString(R.plurals.inbox_dismiss_confirm_all, row.keys.size, row.keys.size)
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

    /**
     * A band command while the focus is in this page. The list keeps the inbox's one axis (as R08
     * Access Bridge navigates, and as the touchpad's forward swipe arrives, as a right): right and
     * down go to the next row, up to the previous one, and up from the first row of the first level
     * hands the focus to the tabs; left shows a row's bin. In a notification opened in full, right
     * and down scroll on, left and up scroll back. The tabs change from the pill, not from here: a
     * right that left the page would take the forward swipe away from the list.
     */
    override fun onCommand(command: String): HomeResult {
        Log.d(TAG, "command=$command level=$level focus=$focus rows=${rows.size} revealed=$revealed active=$active")
        if (level is Level.Detail && bar.shown) {
            // The bar has the focus: sideways along it, up and down through the text.
            val item = NotificationInbox.find((level as Level.Detail).key)
            when (command) {
                BandCommand.RIGHT, BandCommand.FORWARD -> bar.move(1)
                BandCommand.LEFT, BandCommand.BACKWARD -> bar.move(-1)
                BandCommand.DOWN -> step(1)
                BandCommand.UP -> step(-1)
                BandCommand.ACTIVATE -> if (item != null) bar.current()?.let { perform(item, it) }
                BandCommand.BACK -> {
                    level = Level.App((level as Level.Detail).packageName)
                    render()
                }
                else -> return HomeResult.UNHANDLED
            }
            return HomeResult.HANDLED
        }
        if (level is Level.Detail) {
            when (command) {
                BandCommand.RIGHT, BandCommand.DOWN, BandCommand.FORWARD -> step(1)
                BandCommand.LEFT, BandCommand.UP, BandCommand.BACKWARD -> step(-1)
                BandCommand.BACK -> {
                    level = Level.App((level as Level.Detail).packageName)
                    render()
                }
                BandCommand.ACTIVATE -> Unit
                else -> return HomeResult.UNHANDLED
            }
            return HomeResult.HANDLED
        }
        if (command == BandCommand.LEFT) {
            swipeLeft()
            return HomeResult.HANDLED
        }
        // Anything else puts a shown bin away first; Back and right (its way back) do only that.
        if (revealed >= 0) {
            conceal()
            if (command == BandCommand.BACK || command == BandCommand.RIGHT) return HomeResult.HANDLED
        }
        // Right is left's opposite: left dismisses, right goes on to the next tab (the apps).
        if (command == BandCommand.RIGHT) return HomeResult.RIGHT_OUT
        when (command) {
            BandCommand.BACK -> when (level) {
                is Level.App -> { level = Level.Apps; render() }
                else -> return HomeResult.CLOSE
            }
            BandCommand.ACTIVATE -> rows.getOrNull(focus)?.activate?.invoke()
            BandCommand.DOWN, BandCommand.FORWARD -> step(1)
            BandCommand.UP, BandCommand.BACKWARD -> {
                // Above the first row of the first level: the tabs.
                if (level == Level.Apps && focus == 0) return HomeResult.UP_OUT
                step(-1)
            }
            else -> return HomeResult.UNHANDLED
        }
        return HomeResult.HANDLED
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

    private fun px(value: Float) = MetaStyle.px(activity, value)

    companion object {
        private const val TAG = "BandNotifPage"
        /** The quick-reply bar's distance from the bottom. */
        private const val BAR_BOTTOM = 28f
        /** Below the home's tabs (20 + 44), with room. */
        private const val TOP_PAD = 92f
        /** Below the tabs and a level's chip. */
        private const val TOP_PAD_CHIP = 132f
    }
}
