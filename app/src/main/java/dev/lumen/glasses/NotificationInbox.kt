package dev.lumen.glasses

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper

/** A phone notification as the glasses show it. Held in memory only. */
data class PhoneNotification(
    /** The phone's StatusBarNotification key: the same notification updated keeps it. */
    val key: String,
    val appName: String,
    val packageName: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    /** The content was hidden on the phone's side (sensitive, or the user's choice). */
    val redacted: Boolean,
    val icon: Bitmap?,
    /** The phone asks the banner to black out the rest of the HUD, leaving only the notification. */
    val focus: Boolean = false,
    /** It has a reply action on the phone: quick replies go through it ([NotificationReplies]). */
    val replyable: Boolean = false,
    /** The conversation's shortcut id on the phone ("" if none): WhatsApp's is the chat's JID. */
    val shortcut: String = "",
) {
    companion object {
        /** The last [count] non-blank lines of [text] (a chat's last messages), oldest first. */
        @JvmStatic
        fun lastLines(text: String, count: Int): String =
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.takeLast(count).joinToString("\n")
    }
}

/**
 * The notifications the phone companion forwarded, newest first, as Rokid Nexus's Relay keeps
 * them: in memory only (nothing on disk), gone when the phone removes them, and a full sync when
 * the link comes back. Main thread only.
 */
object NotificationInbox {
    const val LIMIT = 50

    fun interface Listener {
        fun onInboxChanged()
    }

    private val items = mutableListOf<PhoneNotification>()
    private val listeners = mutableSetOf<Listener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Arrived live (not in a sync) since the inbox was last opened. */
    private val unreadKeys = mutableSetOf<String>()

    @JvmStatic
    fun all(): List<PhoneNotification> = items.toList()

    @JvmStatic
    fun find(key: String?): PhoneNotification? = items.firstOrNull { it.key == key }

    /** The keys counted in [unread], for the screen to mark before [markSeen] clears them. */
    @JvmStatic
    fun unreadKeys(): Set<String> = unreadKeys.toSet()

    /** New since the inbox was last opened: what arrived live, not what a sync brought back. */
    @JvmStatic
    fun unread(): Int = unreadKeys.size

    @JvmStatic
    fun markSeen() {
        // Only a change is news: the inbox's own tab calls this from onInboxChanged, and an
        // unconditional changed() re-rendered it every ~100 ms, putting the focus back on its
        // first row after each band move (measured on the glasses).
        if (unreadKeys.isEmpty()) return
        unreadKeys.clear()
        changed()
    }

    /**
     * Adds or updates (same key), newest first by the time of what it says ([PhoneNotification.
     * postedAt]: an old chat re-posted by its app stays where it was). [live] is news from the
     * phone (it decided: recent and newer than before), which counts as unread. Returns true when
     * it's new or its text changed.
     */
    @JvmStatic
    @JvmOverloads
    fun put(notification: PhoneNotification, live: Boolean = false): Boolean {
        val index = items.indexOfFirst { it.key == notification.key }
        val old = if (index >= 0) items.removeAt(index) else null
        val at = items.indexOfFirst { it.postedAt < notification.postedAt }.let { if (it < 0) items.size else it }
        items.add(at, notification)
        while (items.size > LIMIT) unreadKeys.remove(items.removeAt(items.size - 1).key)
        val changedText = old == null || old.title != notification.title || old.text != notification.text
        if (live) unreadKeys += notification.key
        changed()
        return changedText
    }

    @JvmStatic
    fun remove(key: String) {
        unreadKeys.remove(key)
        if (items.removeAll { it.key == key }) changed()
    }

    /** The phone's full list after a (re)connection: what's gone there goes here too. */
    @JvmStatic
    fun replaceAll(notifications: List<PhoneNotification>) {
        items.clear()
        items.addAll(notifications.sortedByDescending { it.postedAt }.take(LIMIT))
        unreadKeys.retainAll(items.map { it.key }.toSet())
        changed()
    }

    @JvmStatic
    fun clear() {
        items.clear()
        unreadKeys.clear()
        changed()
    }

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    private fun changed() {
        if (listeners.isEmpty()) return
        listeners.toList().forEach { listener -> main.post { listener.onInboxChanged() } }
    }
}

/** One app's notifications, as the inbox groups them (newest first, as the apps are). */
data class NotificationGroup(val packageName: String, val appName: String, val items: List<PhoneNotification>) {
    val newest: PhoneNotification get() = items.first()
    val keys: List<String> get() = items.map { it.key }

    companion object {
        /** By app, the app with the newest notification first; [items] is newest first. */
        @JvmStatic
        fun of(items: List<PhoneNotification>): List<NotificationGroup> = items
            .groupBy { it.packageName.ifEmpty { it.appName } }
            .map { (pkg, list) -> NotificationGroup(pkg, list.first().appName, list.sortedByDescending { it.postedAt }) }
            .sortedByDescending { it.newest.postedAt }
    }
}
