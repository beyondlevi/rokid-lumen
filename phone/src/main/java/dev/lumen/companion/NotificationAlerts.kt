package dev.lumen.companion

import android.content.Context
import org.json.JSONObject

/**
 * When a notification deserves a banner on the glasses. Messaging apps (WhatsApp, Telegram)
 * re-post every chat's notification again and again (a new message elsewhere, a reconnect, a
 * re-sort), each time with a fresh `postTime`: that used to bring old chats back as new. What
 * counts is the time of what it says ([contentTime]: its newest message, else the app's `when`),
 * and a banner goes out only when that is recent and newer than anything already seen for the
 * key. The memory survives restarts (a small LRU in the preferences).
 */
object NotificationAlerts {
    /** A notification whose newest content is older than this is not news. */
    const val FRESH_MS = 3 * 60_000L
    /** A time this far ahead of the clock is the app's mistake, not the future. */
    private const val FUTURE_SLACK_MS = 60_000L
    private const val CAPACITY = 400
    private const val PREFS = "notification_alerts"
    private const val KEY = "seen"

    /**
     * The time of the notification's content: its newest MessagingStyle message, else `when`
     * (what the app says it's about), else when it was posted. A time in the future or before
     * 2001 is ignored.
     */
    fun contentTime(messageTimes: List<Long>, whenMs: Long, postTime: Long, now: Long): Long {
        val plausible = { t: Long -> t > 978_307_200_000L && t <= now + FUTURE_SLACK_MS }
        return messageTimes.filter(plausible).maxOrNull()
            ?: whenMs.takeIf(plausible)
            ?: postTime
    }

    /** Pure decision, for tests: [last] is the newest content time seen for the key before. */
    fun decide(contentTime: Long, last: Long?, now: Long): Boolean =
        now - contentTime <= FRESH_MS && (last == null || contentTime > last)

    private val memory = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > CAPACITY
    }
    private var loaded = false

    /** Records the key's content time and says whether it's news (a live post only). */
    @Synchronized
    fun onPosted(context: Context, key: String, contentTime: Long, live: Boolean, now: Long = System.currentTimeMillis()): Boolean {
        load(context)
        val last = memory[key]
        val alert = live && decide(contentTime, last, now)
        if (last == null || contentTime > last) {
            memory[key] = contentTime
            save(context)
        }
        return alert
    }

    private fun load(context: Context) {
        if (loaded) return
        loaded = true
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        runCatching {
            val json = JSONObject(raw)
            json.keys().forEach { memory[it] = json.getLong(it) }
        }
    }

    private fun save(context: Context) {
        val json = JSONObject()
        memory.forEach { (key, time) -> json.put(key, time) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, json.toString()).apply()
    }
}
