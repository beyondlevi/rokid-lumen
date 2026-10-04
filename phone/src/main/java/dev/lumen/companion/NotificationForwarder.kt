package dev.lumen.companion

import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import dev.lumen.companion.relay.AndroidSensitiveNotificationDetector
import dev.lumen.companion.relay.NotificationTextExtractor
import dev.lumen.protocol.NotifyEvent
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Reads the phone's notifications and forwards them to the glasses through [CompanionService]'s
 * CXR link, as Rokid Nexus's Relay does over its own bus; a reply from the glasses goes back
 * through the notification's own reply action ([reply]). Nothing is written to disk: the text
 * goes straight to the link.
 *
 *   post   {key, app, pkg, title, text, when, group, redacted, live, alert, focus, icon}
 *          (when: the time of what it says, [NotificationAlerts.contentTime]; live: news, not a
 *          re-post of something already seen or old; alert: the glasses should show a banner;
 *          focus: the banner blacks out the rest of the HUD)
 *   remove {key}
 *   reset  {} (then a post per active notification, alert false)
 */
class NotificationForwarder : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.d(TAG, "listener connected")
        CompanionService.ensureRunning(this)
        CompanionService.scheduleNotificationSync()
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val message = describe(this, sbn ?: return, alert = true) ?: return
        // Apps re-post a notification to update something the glasses don't show: what says
        // the same and isn't news stays on the phone (a message over the link wakes the glasses).
        val content = contentOf(message)
        if (!message.optBoolean("live") && forwarded.isUnchanged(sbn.key, content)) return
        if (CompanionService.sendNotify(message)) forwarded.add(sbn.key, content)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        // Most removals are of notifications that stayed on the phone (ongoing, summaries).
        if (!forwarded.remove(sbn.key)) return
        if (!CompanionPrefs.notificationsEnabled(this)) return
        CompanionService.sendNotify(NotifyEvent.remove(sbn.key))
    }

    /** The glasses asked (or the link came back): everything active, without banners. */
    fun syncAll() {
        // The glasses empty their inbox: only what's sent from here on can be dismissed there.
        if (CompanionService.sendNotify(NotifyEvent.reset())) forwarded.clear()
        if (!CompanionPrefs.notificationsEnabled(this)) return
        val active = runCatching { activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        // The glasses keep the newest SYNC_LIMIT (their inbox's size); measured on a real phone,
        // 228 active notifications, 186 of them admitted.
        val sent = active.sortedByDescending { it.postTime }.asSequence()
            .mapNotNull { describe(this, it, alert = false) }.take(SYNC_LIMIT).toList()
            .reversed().onEach { if (CompanionService.sendNotify(it)) forwarded.add(it.optString("key"), contentOf(it)) }.size
        Log.d(TAG, "synced $sent of ${active.size} active notifications")
    }

    companion object {
        private const val TAG = "NbNotify"
        private const val ICON_PX = 48
        /** The glasses' inbox size (NotificationInbox.LIMIT there). */
        const val SYNC_LIMIT = 50
        /** Keys remembered as sent to the glasses: several inboxes' worth. */
        const val FORWARDED_LIMIT = 200
        private val iconCache = mutableMapOf<String, String>()
        /** What the glasses were sent: all a dismiss from them may clear (in memory only). */
        private val forwarded = ForwardedKeys(FORWARDED_LIMIT)

        @Volatile var instance: NotificationForwarder? = null
            private set

        /**
         * Notifications dismissed on the glasses, cleared from the phone's shade too (as a swipe
         * there would). Only keys this listener sent to the glasses are cleared: the glasses
         * can't name any other notification of the phone. The listener's removal then tells
         * the glasses, as for any removal.
         */
        fun dismiss(keys: List<String>) {
            val listener = instance ?: run {
                Log.w(TAG, "dismiss: no notification access")
                return
            }
            val sent = forwarded.sentOf(keys)
            if (sent.size < keys.size) Log.w(TAG, "dismiss: ${keys.size - sent.size} key(s) never sent to the glasses, ignored")
            sent.forEach { key -> runCatching { listener.cancelNotification(key) }.onFailure { Log.w(TAG, "dismiss failed", it) } }
            Log.d(TAG, "dismissed ${sent.size} from the glasses")
        }

        /**
         * A reply from the glasses, sent as typing it in the shade would: the notification's
         * first action with a free-form RemoteInput (as Rokid Nexus's Relay picks it) gets [text]
         * in every free-form input. Only notifications this listener sent to the glasses. The
         * outcome goes back as [NotifyEvent.replied]; a reaction is its emoji, as text.
         */
        fun reply(context: Context, key: String, text: String) {
            val ok = runCatching {
                val listener = instance ?: error("no notification access")
                if (forwarded.sentOf(listOf(key)).isEmpty()) error("never sent to the glasses")
                val sbn = listener.activeNotifications.orEmpty().firstOrNull { it.key == key } ?: error("gone from the shade")
                val action = replyAction(sbn.notification) ?: error("no reply action")
                val results = android.os.Bundle()
                val inputs = action.remoteInputs.orEmpty().filter { it.allowFreeFormInput }
                inputs.forEach { results.putCharSequence(it.resultKey, text) }
                val fill = android.content.Intent()
                android.app.RemoteInput.addResultsToIntent(inputs.toTypedArray(), fill, results)
                android.app.RemoteInput.setResultsSource(fill, android.app.RemoteInput.SOURCE_FREE_FORM_INPUT)
                action.actionIntent.send(context, 0, fill)
            }.onFailure { Log.w(TAG, "reply failed: ${it.message}") }.isSuccess
            Log.d(TAG, "reply sent=$ok")
            CompanionService.sendNotify(NotifyEvent.replied(key, ok))
        }

        /** The notification's reply action: the first with a free-form RemoteInput, or null. */
        fun replyAction(notification: Notification): Notification.Action? =
            notification.actions.orEmpty().firstOrNull { action ->
                action.actionIntent != null && action.remoteInputs.orEmpty().any { it.allowFreeFormInput }
            }

        /** What a post says, whether or not it's news ([forwarded] compares it with the last). */
        fun contentOf(message: JSONObject): Int =
            JSONObject(message.toString()).apply { remove("live"); remove("alert") }.toString().hashCode()

        /** The glasses' `sync`: resend all, if notification access is granted. */
        fun requestSync() {
            instance?.let { listener -> runCatching { listener.syncAll() }.onFailure { Log.w(TAG, "sync failed", it) } }
        }

        /** What the glasses get for this notification, or null when it stays on the phone. */
        fun describe(context: Context, sbn: StatusBarNotification, alert: Boolean): JSONObject? {
            if (!CompanionPrefs.notificationsEnabled(context)) return null
            val notification = sbn.notification
            val flags = notification.flags
            if (!admitted(context, sbn.packageName, flags, notification.category, sbn.isClearable, isOurs = sbn.packageName == context.packageName)) return null
            val extras = notification.extras
            val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty().trim()
            val body = NotificationTextExtractor.extract(NotificationTextExtractor.fromExtras(extras), messageLimit = 4)
            if (title.isEmpty() && body.isBlank()) return null
            val sensitive = AndroidSensitiveNotificationDetector.isRedacted(title, body)
            // A private notification shows its public version, as on the lock screen.
            val public = notification.publicVersion?.extras
            val content = NotificationPrivacy.decide(
                Build.VERSION.SDK_INT, notification.visibility, sensitive, CompanionPrefs.hideContent(context),
                publicTitle = public?.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                publicText = public?.let { NotificationTextExtractor.extract(NotificationTextExtractor.fromExtras(it), messageLimit = 4) },
            )
            val shownTitle = if (content is NotificationContent.Public) content.title else title
            val shownText = when (content) {
                is NotificationContent.Public -> content.text
                NotificationContent.Redacted -> ""
                NotificationContent.Full -> body
            }
            val hidden = content == NotificationContent.Redacted
            CompanionPrefs.noteSeen(context, sbn.packageName)
            val screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive == true
            val now = System.currentTimeMillis()
            val messageTimes = NotificationTextExtractor.messagingStyleMessages(extras).map { it.timestamp }
            val time = NotificationAlerts.contentTime(messageTimes, notification.`when`, sbn.postTime, now)
            // A banner only for news: recent content, newer than what this key said before.
            val news = NotificationAlerts.onPosted(context, sbn.key, time, live = alert, now = now)
            return NotifyEvent.post(sbn.key)
                .put("app", appLabel(context, sbn.packageName))
                .put("pkg", sbn.packageName)
                .put("title", shownTitle)
                .put("text", shownText)
                .put("when", time)
                .put("group", sbn.groupKey.orEmpty())
                .put("redacted", hidden)
                // live: news the glasses count as unread; alert: also show the banner.
                .put("live", news)
                .put("alert", news && !(CompanionPrefs.pauseWhileScreenOn(context) && screenOn))
                .put("focus", CompanionPrefs.focusBanner(context))
                .put("icon", appIcon(context, sbn.packageName))
                // Answerable from the glasses (not while its text is hidden from them).
                .put("reply", !hidden && replyAction(notification) != null)
                // The conversation, for a web app that opens it (WhatsApp's is the chat's JID).
                .put("shortcut", notification.shortcutId.orEmpty())
        }

        /**
         * The admission test: user-facing notifications only. Ongoing ones (music, navigation,
         * foreground services), group summaries (their children come on their own), progress and
         * the blocked apps stay on the phone. This app's own notifications pass only on the test
         * channel.
         */
        fun admitted(context: Context, pkg: String, flags: Int, category: String?, clearable: Boolean, isOurs: Boolean): Boolean {
            if (isOurs) return category == CATEGORY_TEST
            if (pkg in CompanionPrefs.blockedApps(context)) return false
            if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
            if (flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return false
            if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
            if (!clearable) return false
            return category !in setOf(Notification.CATEGORY_PROGRESS, Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_SYSTEM)
        }

        const val CATEGORY_TEST = "nb_test"

        fun appLabel(context: Context, pkg: String): String = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        /** The app's icon as a small base64 PNG (a few KB; the CXR link carries it in the message). */
        fun appIcon(context: Context, pkg: String): String = synchronized(iconCache) {
            iconCache.getOrPut(pkg) {
                runCatching {
                    val drawable = context.packageManager.getApplicationIcon(pkg)
                    val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
                    drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                    drawable.draw(Canvas(bitmap))
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }.getOrElse { e ->
                    if (e !is PackageManager.NameNotFoundException) Log.w(TAG, "icon for $pkg", e)
                    ""
                }
            }
        }
    }
}
