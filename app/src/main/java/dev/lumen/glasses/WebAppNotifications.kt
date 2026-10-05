package dev.lumen.glasses

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Which web app opens a phone notification, from the apps' manifests (`lumen_notifications`):
 *
 * ```json
 * "lumen_notifications": [
 *   {"packages": ["com.whatsapp", "com.whatsapp.w4b"], "open": "/chat/{shortcut}"}
 * ]
 * ```
 *
 * [Intent.packages] are the phone apps whose notifications it takes; [Intent.open] the page to
 * open, a path of the app with placeholders filled from the notification, URL-encoded:
 * `{shortcut}` (the conversation's shortcut id: WhatsApp's is the chat's JID, Telegram's
 * `ndid_<dialog id>`), `{tag}` (the notification's tag on the phone: Reddit's names the post),
 * `{title}`, `{package}`. When a placeholder it uses is empty, or there's no `open`, the app
 * opens on its start page. Offline packages only for now (their manifest is on the glasses).
 */
object WebAppNotifications {
    data class Intent(val packages: Set<String>, val open: String)

    /** The app to open for [notification] and the path to open it at ("/" for its start page). */
    data class Target(val app: WebApp, val path: String)

    @JvmStatic
    fun parse(manifest: JSONObject?): List<Intent> {
        val array = manifest?.optJSONArray("lumen_notifications") ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val entry = array.optJSONObject(i) ?: return@mapNotNull null
            val packages = entry.optJSONArray("packages") ?: return@mapNotNull null
            val names = (0 until packages.length()).map { packages.optString(it) }.filter { it.isNotBlank() }.toSet()
            if (names.isEmpty()) null else Intent(names, entry.optString("open"))
        }
    }

    /** Every installed app that takes [notification]'s package (copies included), in the library's order. */
    @JvmStatic
    fun targets(context: Context, notification: PhoneNotification): List<Target> =
        WebAppLibrary.all(context).filter { it.offline }.mapNotNull { app ->
            val manifest = WebAppPackages.readManifest(WebAppPackages.dir(context, app.id))
            parse(manifest).firstOrNull { notification.packageName in it.packages }?.let { Target(app, path(it.open, notification)) }
        }

    /**
     * [template] filled from [notification]: a same-app path ("/…", never "//host"), or "/" when
     * it isn't one or a placeholder it uses is empty.
     */
    @JvmStatic
    fun path(template: String, notification: PhoneNotification): String {
        if (!template.startsWith("/") || template.startsWith("//")) return "/"
        val values = mapOf(
            "shortcut" to notification.shortcut,
            "tag" to tag(notification.key),
            "title" to notification.title,
            "package" to notification.packageName,
        )
        var missing = false
        val filled = Regex("""\{(\w+)\}""").replace(template) { match ->
            val value = values[match.groupValues[1]].orEmpty()
            if (value.isEmpty()) missing = true
            Uri.encode(value)
        }
        return if (missing) "/" else filled
    }

    /**
     * The tag in a phone notification's key (`user|package|id|tag|uid`, Android's
     * StatusBarNotification key; a tag may itself hold `|`), or "" when it has none ("null").
     */
    @JvmStatic
    fun tag(key: String): String {
        val parts = key.split("|")
        if (parts.size < 5) return ""
        val tag = parts.subList(3, parts.size - 1).joinToString("|")
        return if (tag == "null") "" else tag
    }
}
