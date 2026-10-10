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
 * An open notification shows its text as bubbles and its pictures in their place (asked for
 * from the phone as it opens, [NotificationPictures]); under it, when the phone can answer it, a
 * reply row (a real text field and a send button, [ReplyRow]) above the quick bar. The band goes
 * down from the pictures to the reply row and the bar ([NotificationDetail]); the index tap on a
 * picture shows it full screen.
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
    /** An open notification's toasts: in the chip's place, filled (the canvas's). */
    private val detailToast: TextView
    /** Under an open notification: its web app and quick replies ([QuickReplyBar]). */
    private val bar = QuickReplyBar(activity)
    /** Above the bar: the free-text reply ([ReplyRow]). */
    private val reply = ReplyRow(activity, onSend = { text -> sendReply(text) }, onChange = { updateHint() })
    private val barShade: View
    private val hideToast = Runnable {
        toast.visibility = View.GONE
        detailToast.visibility = View.GONE
        chip.alpha = 1f
    }
    private val replyListener = NotificationReplies.Listener { key, ok -> onReplied(key, ok) }
    private val pictureListener = NotificationPictures.Listener { key, index, state -> onPicture(key, index, state) }

    /** The pictures' bubbles of the open notification, in order, to update as their bytes come. */
    private class PhotoBubble(val index: Int, val frame: LinearLayout, val image: ImageView, val status: TextView, val detail: TextView)
    private var photos: List<PhotoBubble> = emptyList()
    /** The open notification's band focus: which zone, and which picture in the content. */
    private var zone: DetailZone? = null
    private var photoFocus = 0
    /** The free-text reply on its way: its notification and text (the outgoing bubble once it went). */
    private var replyKey: String? = null
    private var replyText = ""
    /** Replies that went from here, per notification, while it's in the inbox (memory only). */
    private val sentReplies = HashMap<String, MutableList<String>>()
    /** A picture full screen ([fullIndex] of the open notification), or -1. */
    private var fullIndex = -1
    private val full: FrameLayout
    private val fullImage: ImageView
    private val fullStatus: TextView
    private val fullChip: TextView

    /**
     * What the band does here, under the HUD's square (the canvas's strip): the home places it
     * ([LauncherActivity]); hidden while there's nothing to say.
     */
    val hint: TextView

    /** A picture went full screen (true) or back: the home hides its tabs and clock meanwhile. */
    var onFullScreen: ((Boolean) -> Unit)? = null
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
            if (level is Level.Detail) applyDetailFocus() else bar.active = false
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
        view.addView(reply.view, FrameLayout.LayoutParams(side, px(ReplyRow.HEIGHT), Gravity.BOTTOM).apply { bottomMargin = px(REPLY_BOTTOM) })
        toast.bringToFront()
        detailToast = TextView(activity).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 21f))
            typeface = MetaStyle.MEDIUM
            gravity = Gravity.CENTER
            includeFontPadding = false
            compoundDrawablePadding = px(10f)
            setPadding(px(22f), 0, px(22f), 0)
            background = GradientDrawable().apply {
                setColor(TOAST_FILL)
                cornerRadius = 9_999f
            }
            visibility = View.GONE
        }
        view.addView(detailToast, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(50f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(73f) })
        // A picture full screen: the square, black around it, who and which in a chip.
        full = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        fullImage = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        full.addView(fullImage, FrameLayout.LayoutParams(side, side))
        fullStatus = text("", 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2).apply { gravity = Gravity.CENTER }
        full.addView(fullStatus, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        fullChip = text("", 20f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1).apply {
            gravity = Gravity.CENTER
            setPadding(px(22f), 0, px(22f), 0)
            background = MetaStyle.pill(context)
        }
        full.addView(fullChip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(50f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(25f) })
        view.addView(full, FrameLayout.LayoutParams(side, side))
        hint = TextView(activity).apply {
            setTextColor(MetaStyle.TEXT_SECONDARY)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 20f))
            typeface = MetaStyle.REGULAR
            gravity = Gravity.CENTER
            includeFontPadding = false
            isSingleLine = true
            compoundDrawablePadding = px(10f)
            setPadding(px(22f), 0, px(22f), 0)
            background = MetaStyle.pill(context)
            visibility = View.GONE
        }
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
        NotificationPictures.listeners += pictureListener
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
        NotificationPictures.listeners -= pictureListener
        handler.removeCallbacks(rerender)
        conceal()
        // Out of view: no keyboard, no transfer, no full screen (shown again, it asks again).
        reply.releaseField()
        NotificationPictures.stop()
        closeFullScreen()
        hint.visibility = View.GONE
    }

    override fun onInboxChanged() {
        unread = unread + NotificationInbox.unreadKeys()
        NotificationInbox.markSeen()
        render()
    }

    private fun render() {
        val items = NotificationInbox.all()
        val groups = NotificationGroup.of(items)
        sentReplies.keys.retainAll(items.map { it.key }.toSet())
        // A level whose content is gone falls back to the one above.
        (level as? Level.Detail)?.let { if (NotificationInbox.find(it.key) == null) level = Level.App(it.packageName) }
        (level as? Level.App)?.let { app -> if (groups.none { it.packageName == app.packageName }) level = Level.Apps }
        // The same level again (the inbox changed under it): the focus stays on its row and an
        // open notification where it was scrolled to.
        val again = level == renderedLevel
        val focusedKeys = if (again) rows.getOrNull(focus)?.keys.orEmpty() else emptyList()
        val wasFocus = focus
        if (renderedLevel is Level.Detail && !again) leaveDetail()
        renderedLevel = level
        list.removeAllViews()
        revealed = -1
        // The tab's pill names the first level; deeper ones get their own chip under it.
        chip.visibility = if (level == Level.Apps) View.GONE else View.VISIBLE
        scroll.setPadding(0, px(if (level == Level.Apps) TOP_PAD else TOP_PAD_CHIP), 0, px(64f))
        toast.visibility = View.GONE
        if (!again) detailToast.visibility = View.GONE
        when (val current = level) {
            Level.Apps -> {
                chip.text = if (items.isEmpty()) activity.getString(R.string.inbox_title) else activity.getString(R.string.inbox_chip, activity.getString(R.string.inbox_title), items.size)
                rows = groups.map { group ->
                    val newest = group.newest
                    val subtitle = listOf(newest.title, preview(newest, lines = 1)).filter { it.isNotBlank() }.joinToString(": ")
                    val count = group.items.size
                    row(avatar(newest.icon, group.appName), group.appName, subtitle, newest.postedAt,
                        unread = group.keys.any { it in unread }, badge = count.takeIf { it > 1 }, keys = group.keys, pictures = hasPictures(newest)) {
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
                        unread = item.key in unread, badge = null, keys = listOf(item.key), pictures = hasPictures(item)) {
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
                if (replyable(item)) reply.show(item.key, item.title.ifEmpty { item.appName }) else reply.hide()
                val zones = zones()
                if (!again) {
                    photoFocus = 0
                    zone = NotificationDetail.initial(zones)
                    if (zone == DetailZone.REPLY) reply.reset()
                } else if (zone !in zones) {
                    zone = NotificationDetail.initial(zones)
                }
                photoFocus = photoFocus.coerceIn(0, maxOf(0, photos.lastIndex))
                applyDetailFocus()
                placeBar()
                if (!again) scroll.scrollTo(0, 0)
                // Opened on a picture: it shows above the reply row and the bar, not under them.
                if (zone == DetailZone.CONTENT) revealPhoto()
                if (fullIndex >= 0) showFullScreen(item, fullIndex)
                NotificationPictures.want(activity, item, first = photos.getOrNull(photoFocus)?.index ?: 0)
                return
            }
        }
        bar.hide()
        reply.hide()
        placeBar()
        rows.forEach { list.addView(it.frame) }
        applyFocus(animate = false)
    }

    /** The web app that takes [item] first, then quick replies when the phone can send them. */
    private fun quickActions(item: PhoneNotification): List<QuickReplyBar.Action> {
        val out = mutableListOf<QuickReplyBar.Action>()
        // Each app that takes it: a personal and a work WhatsApp both offer their icon, and then
        // their names too, since a copy has the original's icon.
        val targets = WebAppNotifications.targets(activity, item)
        targets.forEach { out += QuickReplyBar.Action.OpenApp(it, WebAppIcons.load(it.app), named = targets.size > 1) }
        if (item.replyable && !item.redacted) {
            activity.resources.getStringArray(R.array.quick_reactions).forEach { out += QuickReplyBar.Action.Reply(it, iconOnly = true) }
            activity.resources.getStringArray(R.array.quick_replies).forEach { out += QuickReplyBar.Action.Reply(it, iconOnly = false) }
        }
        return out
    }

    /** Room under the text for the bar and the reply row, and the toast above them, while they show. */
    private fun placeBar() {
        val shown = bar.shown || reply.shown
        // The reply row sits on the bar, or where the bar would be without one.
        (reply.view.layoutParams as FrameLayout.LayoutParams).bottomMargin = px(if (bar.shown) REPLY_BOTTOM else BAR_BOTTOM)
        reply.view.requestLayout()
        val bottom = when {
            reply.shown && bar.shown -> REPLY_BOTTOM + ReplyRow.HEIGHT
            reply.shown -> BAR_BOTTOM + ReplyRow.HEIGHT
            bar.shown -> BAR_BOTTOM + QuickReplyBar.HEIGHT
            else -> 0f
        }
        barShade.visibility = if (shown) View.VISIBLE else View.GONE
        (barShade.layoutParams as FrameLayout.LayoutParams).height = px(bottom + 40f)
        barShade.requestLayout()
        scroll.setPadding(0, scroll.paddingTop, 0, px(if (shown) 64f + bottom else 64f))
        (toast.layoutParams as FrameLayout.LayoutParams).bottomMargin = px(if (shown) bottom + 12f else 24f)
        toast.requestLayout()
    }

    private fun perform(item: PhoneNotification, action: QuickReplyBar.Action) {
        when (action) {
            is QuickReplyBar.Action.OpenApp -> WebAppActivity.open(activity, action.target.app, action.target.path)
            is QuickReplyBar.Action.Reply -> if (NotificationReplies.send(item.key, action.text)) {
                say(activity.getString(R.string.quick_reply_sending))
            } else {
                say(activity.getString(R.string.quick_reply_no_phone), R.drawable.ic_alert)
            }
        }
    }

    /** A toast: in an open notification in the chip's place, with [icon] (the canvas's), else under the list. */
    private fun say(text: String, icon: Int = 0) {
        if (level is Level.Detail) {
            detailToast.text = text
            val drawable = if (icon == 0) null else activity.getDrawable(icon)?.mutate()?.apply {
                setTint(MetaStyle.TEXT)
                setBounds(0, 0, px(25f), px(25f))
            }
            detailToast.setCompoundDrawablesRelative(drawable, null, null, null)
            detailToast.visibility = View.VISIBLE
            chip.alpha = 0f
        } else {
            toast.text = text
            toast.visibility = View.VISIBLE
        }
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

    /** A ListItem: avatar, title, subtitle (a camera before it for [pictures]), time (accent when new) and the count badge. */
    private fun row(avatar: View, title: String, subtitle: String, time: Long, unread: Boolean, badge: Int?, keys: List<String>, pictures: Boolean = false, activate: () -> Unit): Row {
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
        if (subtitle.isNotBlank() || pictures) texts.addView(text(subtitle, 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2).apply {
            if (pictures) {
                setCompoundDrawablesRelative(icon(R.drawable.ic_camera_outline, 22f, MetaStyle.TEXT_SECONDARY), null, null, null)
                compoundDrawablePadding = px(8f)
            }
        })
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

    /**
     * One notification in full: who and when, then its lines as message bubbles, its pictures
     * in their place (a bubble with the photo and its caption), then the replies sent from here.
     */
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
        val bubbles = if (item.redacted) lines.map { DetailBubble.Text(it) } else NotificationDetail.bubbles(lines, item.pictures)
        val built = mutableListOf<PhotoBubble>()
        bubbles.forEach { bubble ->
            val view = when (bubble) {
                is DetailBubble.Text -> text(bubble.line, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 12).apply {
                    setPadding(px(20f), px(12f), px(20f), px(12f))
                    background = MetaStyle.outline(context, 24f)
                }
                is DetailBubble.Photo -> photoBubble(item, bubble).also { built += it }.frame
            }
            card.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })
        }
        photos = built
        sentReplies[item.key].orEmpty().forEach { sent -> addOutgoing(card, sent) }
        return card
    }

    /** A picture's bubble: the photo fitted and rounded (or its state while it comes), the caption under it. */
    private fun photoBubble(item: PhoneNotification, bubble: DetailBubble.Photo): PhotoBubble {
        val frame = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(14f), px(20f), px(14f))
            background = MetaStyle.outline(context, 24f)
        }
        val image = ImageView(activity).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, v.width, v.height, px(15f).toFloat())
            }
            clipToOutline = true
            contentDescription = activity.getString(R.string.notification_picture_description, item.title.ifEmpty { item.appName })
        }
        frame.addView(image, LinearLayout.LayoutParams(px(PHOTO_W), px(PHOTO_H)))
        // While it comes, or when it can't: a dashed placeholder's text, as the canvas's.
        val status = text("", 19f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 2).apply {
            gravity = Gravity.CENTER
            compoundDrawablePadding = px(10f)
            setPadding(px(16f), 0, px(16f), 0)
            background = GradientDrawable().apply {
                setColor(PLACEHOLDER_FILL)
                cornerRadius = px(15f).toFloat()
                setStroke(maxOf(1, px(1.25f)), PLACEHOLDER_LINE, px(5f).toFloat(), px(4f).toFloat())
            }
        }
        frame.addView(status, LinearLayout.LayoutParams(px(PLACEHOLDER_W), px(PLACEHOLDER_H)))
        val detail = text("", 18f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1).apply { visibility = View.GONE }
        frame.addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(6f) })
        if (bubble.caption.isNotBlank()) {
            frame.addView(text(bubble.caption, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 6), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })
        }
        return PhotoBubble(bubble.index, frame, image, status, detail).also { showPicture(it, NotificationPictures.state(item, bubble.index)) }
    }

    /** A picture's state in its bubble: the photo fitted in the bubble's box, or the placeholder saying why not. */
    private fun showPicture(photo: PhotoBubble, state: NotificationPictures.State) {
        when (state) {
            is NotificationPictures.State.Ready -> {
                val (width, height) = NotificationDetail.fitInto(state.bitmap.width, state.bitmap.height, px(PHOTO_W), px(PHOTO_H))
                photo.image.layoutParams = (photo.image.layoutParams as LinearLayout.LayoutParams).apply {
                    this.width = width
                    this.height = height
                }
                photo.image.setImageBitmap(state.bitmap)
                photo.image.visibility = View.VISIBLE
                photo.status.visibility = View.GONE
                photo.detail.visibility = View.GONE
            }
            NotificationPictures.State.Loading -> {
                photo.image.visibility = View.GONE
                photo.status.visibility = View.VISIBLE
                photo.status.setTextColor(PLACEHOLDER_TEXT)
                photo.status.text = activity.getString(R.string.notification_picture_loading)
                photo.status.setCompoundDrawablesRelative(icon(R.drawable.ic_camera_outline, 25f, PLACEHOLDER_TEXT), null, null, null)
                photo.detail.visibility = View.GONE
            }
            is NotificationPictures.State.Failed -> {
                photo.image.visibility = View.GONE
                photo.status.visibility = View.VISIBLE
                photo.status.setTextColor(MetaStyle.TEXT_SECONDARY)
                photo.status.text = activity.getString(R.string.notification_picture)
                photo.status.setCompoundDrawablesRelative(icon(R.drawable.ic_camera_outline, 25f, MetaStyle.TEXT_SECONDARY), null, null, null)
                // The phone's app keeps it to itself: no use asking again. Else the index tap retries.
                photo.detail.text = activity.getString(if (state.reason == dev.lumen.protocol.PictureOps.REASON_DENIED) R.string.notification_picture_denied else R.string.notification_picture_retry)
                photo.detail.visibility = View.VISIBLE
            }
        }
    }

    /** A reply sent from here: right-aligned and filled, "Via the phone" under it. */
    private fun addOutgoing(card: LinearLayout, sent: String) {
        val bubble = text(sent, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 12).apply {
            setPadding(px(20f), px(12f), px(20f), px(12f))
            background = GradientDrawable().apply {
                setColor(OUTGOING_FILL)
                cornerRadius = px(24f).toFloat()
            }
        }
        card.addView(bubble, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = px(12f)
            gravity = Gravity.END
            marginStart = px(64f)
        })
        card.addView(text(activity.getString(R.string.notification_reply_via_phone), 18f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR, 1),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = px(4f)
                gravity = Gravity.END
                marginEnd = px(5f)
            })
    }

    /** A drawable tinted [color], [size] viewport px square, for a TextView's side. */
    private fun icon(res: Int, size: Float, color: Int) = activity.getDrawable(res)?.mutate()?.apply {
        setTint(color)
        setBounds(0, 0, px(size), px(size))
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
            scroll.post { ScrollReveal.reveal(scroll, target, scroll.paddingTop, px(64f), animate) }
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
     * hands the focus to the tabs; left shows a row's bin. A notification opened in full is
     * [detailCommand]'s. The tabs change from the pill, not from here: a right that left the page
     * would take the forward swipe away from the list.
     */
    override fun onCommand(command: String): HomeResult {
        Log.d(TAG, "command=$command level=$level focus=$focus rows=${rows.size} revealed=$revealed active=$active")
        if (level is Level.Detail) return detailCommand(command)
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

    override fun onTap(x: Float, y: Float): Boolean {
        // The detail reads by scrolling (the cursor drags it); a picture, the field and send take a tap.
        if (level is Level.Detail) return detailTap(x, y)
        val index = rows.indexOfFirst { it.frame.isUnder(x, y) }
        if (index < 0) return false
        if (index != focus) {
            focus = index
            applyFocus(animate = true)
        }
        rows[index].activate()
        return true
    }

    // ---- An open notification: its pictures, the reply row, the bar ----

    private fun hasPictures(item: PhoneNotification) = !item.redacted && item.pictures.isNotEmpty()

    /** The phone can answer it and its text goes to the glasses: the reply row shows. */
    private fun replyable(item: PhoneNotification) = item.replyable && !item.redacted

    private fun zones(): List<DetailZone> = NotificationDetail.zones(photos.isNotEmpty(), reply.shown, bar.shown)

    private fun openItem(): PhoneNotification? = (level as? Level.Detail)?.let { NotificationInbox.find(it.key) }

    /**
     * A band command in an open notification. Full screen: left and right go through its
     * pictures, the middle tap (or the index) comes back. Otherwise up and down go from the
     * content (scrolled to its end) to the reply row and the bar ([NotificationDetail.vertical]);
     * left and right move along the bar or the row, or between pictures; the index tap acts on
     * what has the focus. While the reply field is typed in, the index tap is the input method's.
     */
    private fun detailCommand(command: String): HomeResult {
        val item = openItem() ?: return HomeResult.HANDLED
        if (fullIndex >= 0) {
            when (command) {
                BandCommand.RIGHT, BandCommand.FORWARD -> showFullScreen(item, (fullIndex + 1).coerceAtMost(item.pictures.lastIndex))
                BandCommand.LEFT, BandCommand.BACKWARD -> showFullScreen(item, (fullIndex - 1).coerceAtLeast(0))
                BandCommand.BACK, BandCommand.ACTIVATE -> closeFullScreen()
                BandCommand.UP, BandCommand.DOWN -> Unit
                else -> return HomeResult.UNHANDLED
            }
            return HomeResult.HANDLED
        }
        // Lumen's keyboard opens its panel on the index tap while the field is focused.
        if (command == BandCommand.ACTIVATE && zone == DetailZone.REPLY && reply.part == ReplyPart.FIELD && reply.typing) return HomeResult.UNHANDLED
        when (command) {
            BandCommand.RIGHT, BandCommand.FORWARD -> sideways(1)
            BandCommand.LEFT, BandCommand.BACKWARD -> sideways(-1)
            BandCommand.DOWN -> vertical(down = true)
            BandCommand.UP -> vertical(down = false)
            BandCommand.ACTIVATE -> when (zone) {
                DetailZone.CONTENT -> photos.getOrNull(photoFocus)?.let { activatePhoto(item, it.index) }
                DetailZone.REPLY -> reply.activate()
                DetailZone.BAR -> bar.current()?.let { perform(item, it) }
                null -> Unit
            }
            BandCommand.BACK -> if (reply.fieldFocused) {
                // Out of the field first (the keyboard down); the next middle tap goes up a level.
                reply.releaseField()
                updateHint()
            } else {
                level = Level.App((level as Level.Detail).packageName)
                render()
            }
            else -> return HomeResult.UNHANDLED
        }
        return HomeResult.HANDLED
    }

    private fun sideways(delta: Int) {
        when (zone) {
            DetailZone.BAR -> bar.move(delta)
            DetailZone.REPLY -> reply.move(delta)
            DetailZone.CONTENT -> {
                val next = (photoFocus + delta).coerceIn(0, maxOf(0, photos.lastIndex))
                if (next == photoFocus) return
                photoFocus = next
                applyDetailFocus()
                revealPhoto()
            }
            // Nothing to move along: the text scrolls, as before the bar.
            null -> step(delta)
        }
    }

    private fun vertical(down: Boolean) {
        when (val move = NotificationDetail.vertical(zones(), zone, down, scroll.canScrollVertically(1))) {
            NotificationDetail.Step.Stay -> Unit
            is NotificationDetail.Step.Scroll -> step(move.delta)
            is NotificationDetail.Step.Zone -> {
                if (zone == DetailZone.REPLY) reply.releaseField()
                zone = move.zone
                if (move.zone == DetailZone.REPLY) reply.reset()
                applyDetailFocus()
                if (move.zone == DetailZone.CONTENT) revealPhoto()
            }
        }
    }

    private fun revealPhoto() {
        val target = photos.getOrNull(photoFocus)?.frame ?: return
        val bottom = scroll.paddingBottom
        scroll.post { ScrollReveal.reveal(scroll, target, scroll.paddingTop, bottom, true) }
    }

    /** The index tap on a picture: full screen once it's here, again when it didn't come. */
    private fun activatePhoto(item: PhoneNotification, index: Int) {
        when (val state = NotificationPictures.state(item, index)) {
            is NotificationPictures.State.Failed -> if (state.reason != dev.lumen.protocol.PictureOps.REASON_DENIED) NotificationPictures.retry(activity, item, index)
            else -> showFullScreen(item, index)
        }
    }

    /** The air mouse in an open notification: a picture, the reply field or send. */
    private fun detailTap(x: Float, y: Float): Boolean {
        val item = openItem() ?: return false
        if (fullIndex >= 0) {
            closeFullScreen()
            return true
        }
        photos.indexOfFirst { it.frame.isUnder(x, y) }.takeIf { it >= 0 }?.let { at ->
            zone = DetailZone.CONTENT
            photoFocus = at
            applyDetailFocus()
            activatePhoto(item, photos[at].index)
            return true
        }
        if (reply.shown && reply.view.isUnder(x, y)) {
            if (zone != DetailZone.REPLY) reply.reset()
            zone = DetailZone.REPLY
            if (reply.part == ReplyPart.FIELD && !reply.input.isUnder(x, y)) reply.move(1)
            else if (reply.part == ReplyPart.SEND && reply.input.isUnder(x, y)) reply.move(-1)
            applyDetailFocus()
            reply.activate()
            return true
        }
        return false
    }

    /** Where the band is in the open notification, drawn: one ring at a time, none while the tabs have it. */
    private fun applyDetailFocus() {
        bar.active = active && zone == DetailZone.BAR
        reply.active = active && zone == DetailZone.REPLY
        photos.forEachIndexed { i, photo ->
            val focused = active && zone == DetailZone.CONTENT && i == photoFocus
            photo.frame.background = if (focused) MetaStyle.focused(activity, 0, radius = 24f) else MetaStyle.outline(activity, 24f)
        }
        updateHint()
    }

    /** The strip under the square: what the index does on the focused reply part, or in full screen. */
    private fun updateHint() {
        val item = openItem()
        val (textRes, iconRes) = when {
            item == null -> null to 0
            fullIndex >= 0 -> (if (item.pictures.size > 1) R.string.notification_picture_hint else R.string.notification_picture_hint_single) to 0
            active && zone == DetailZone.REPLY -> when (reply.state.hint(reply.part, typing = reply.fieldFocused)) {
                ReplyHint.FIELD -> R.string.notification_reply_field_hint to R.drawable.ic_mic
                ReplyHint.SEND -> R.string.notification_reply_send_hint to R.drawable.ic_send_arrow
                ReplyHint.RETRY -> R.string.notification_reply_retry_hint to R.drawable.ic_send_arrow
                null -> null to 0
            }
            else -> null to 0
        }
        if (textRes == null || !shown) {
            hint.visibility = View.GONE
            return
        }
        hint.text = activity.getString(textRes)
        hint.setCompoundDrawablesRelative(if (iconRes == 0) null else icon(iconRes, 22f, MetaStyle.TEXT_SECONDARY), null, null, null)
        hint.visibility = View.VISIBLE
    }

    private fun onPicture(key: String, index: Int, state: NotificationPictures.State) {
        val item = openItem()?.takeIf { it.key == key } ?: return
        photos.filter { it.index == index }.forEach { showPicture(it, state) }
        if (fullIndex == index) showFullScreen(item, index)
        // The bubble grew with the picture: the focused one stays in view.
        if (zone == DetailZone.CONTENT && photos.getOrNull(photoFocus)?.index == index) revealPhoto()
    }

    /** Sends the field's text through the notification's reply action on the phone. */
    private fun sendReply(text: String) {
        val item = openItem() ?: return
        if (!reply.state.canSend) return
        if (!NotificationReplies.send(item.key, text)) {
            say(activity.getString(R.string.quick_reply_no_phone), R.drawable.ic_alert)
            return
        }
        replyKey = item.key
        replyText = text
        reply.sending()
        say(activity.getString(R.string.quick_reply_sending))
    }

    /** How a reply went (a quick one or the field's): the toast, and for the field's the bubble or the text kept. */
    private fun onReplied(key: String, ok: Boolean) {
        say(activity.getString(if (ok) R.string.quick_reply_sent else R.string.quick_reply_failed), if (ok) R.drawable.ic_check else R.drawable.ic_alert)
        if (key != replyKey) return
        replyKey = null
        reply.replied(ok)
        if (!ok) return
        sentReplies.getOrPut(key) { mutableListOf() } += replyText
        if (openItem()?.key == key) {
            render()
            // The reply where it can be read, as the canvas's "Sent".
            scroll.post { scroll.smoothScrollTo(0, list.height) }
        }
    }

    /** The picture [index] of [item] over the whole square, with its chip; asked for if it isn't here. */
    private fun showFullScreen(item: PhoneNotification, index: Int) {
        if (index !in item.pictures.indices) return closeFullScreen()
        val entering = fullIndex < 0
        fullIndex = index
        when (val state = NotificationPictures.state(item, index)) {
            is NotificationPictures.State.Ready -> {
                fullImage.setImageBitmap(state.bitmap)
                fullStatus.visibility = View.GONE
            }
            NotificationPictures.State.Loading -> {
                fullImage.setImageDrawable(null)
                fullStatus.text = activity.getString(R.string.notification_picture_loading)
                fullStatus.visibility = View.VISIBLE
                NotificationPictures.want(activity, item, first = index)
            }
            is NotificationPictures.State.Failed -> {
                fullImage.setImageDrawable(null)
                fullStatus.text = activity.getString(R.string.notification_picture)
                fullStatus.visibility = View.VISIBLE
            }
        }
        fullChip.text = activity.getString(R.string.notification_picture_position, item.title.ifEmpty { item.appName }, index + 1, item.pictures.size)
        full.visibility = View.VISIBLE
        full.bringToFront()
        if (entering) {
            reply.releaseField()
            onFullScreen?.invoke(true)
        }
        updateHint()
    }

    private fun closeFullScreen() {
        if (fullIndex < 0) return
        fullIndex = -1
        full.visibility = View.GONE
        fullImage.setImageDrawable(null)
        onFullScreen?.invoke(false)
        updateHint()
    }

    /** The open notification closed (or another opened): no keyboard, no transfer, no full screen. */
    private fun leaveDetail() {
        reply.hide()
        NotificationPictures.stop()
        closeFullScreen()
        photos = emptyList()
        zone = null
        detailToast.visibility = View.GONE
        chip.alpha = 1f
        updateHint()
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
        /** The reply row's, on the bar (12 between them). */
        private const val REPLY_BOTTOM = BAR_BOTTOM + QuickReplyBar.HEIGHT + 12f
        /** A picture's box in its bubble (the canvas's 220 x 104 placeholder is its loading state). */
        private const val PHOTO_W = 380f
        private const val PHOTO_H = 260f
        private const val PLACEHOLDER_W = 275f
        private const val PLACEHOLDER_H = 130f
        /** The canvas's surfaces: #1E1E1E (placeholder, toast), #3A3A3A (its dashes), #2C2C2C (a reply sent). */
        private val PLACEHOLDER_FILL = Color.parseColor("#1E1E1E")
        private val PLACEHOLDER_LINE = Color.parseColor("#3A3A3A")
        private val PLACEHOLDER_TEXT = Color.parseColor("#8A8F98")
        private val TOAST_FILL = Color.parseColor("#1E1E1E")
        private val OUTGOING_FILL = Color.parseColor("#2C2C2C")

        /** Below the home's tabs (20 + 44), with room. */
        private const val TOP_PAD = 92f
        /** Below the tabs and a level's chip. */
        private const val TOP_PAD_CHIP = 132f
    }
}
