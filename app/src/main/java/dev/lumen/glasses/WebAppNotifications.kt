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
 * `{shortcut}` (the conversation's shortcut id: WhatsApp's is the chat's JID), `{title}`,
 * `{package}`. When a placeholder it uses is empty, or there's no `open`, the app opens on its
 * start page. Offline packages only for now (their manifest is on the glasses).
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

    /** The first installed app that takes [notification]'s package, or null. */
    @JvmStatic
    fun target(context: Context, notification: PhoneNotification): Target? {
        for (app in WebAppLibrary.all(context)) {
            if (!app.offline) continue
            val manifest = WebAppPackages.readManifest(WebAppPackages.dir(context, app.id))
            val intent = parse(manifest).firstOrNull { notification.packageName in it.packages } ?: continue
            return Target(app, path(intent.open, notification))
        }
        return null
    }

    /**
     * [template] filled from [notification]: a same-app path ("/…", never "//host"), or "/" when
     * it isn't one or a placeholder it uses is empty.
     */
    @JvmStatic
    fun path(template: String, notification: PhoneNotification): String {
        if (!template.startsWith("/") || template.startsWith("//")) return "/"
        val values = mapOf("shortcut" to notification.shortcut, "title" to notification.title, "package" to notification.packageName)
        var missing = false
        val filled = Regex("""\{(\w+)\}""").replace(template) { match ->
            val value = values[match.groupValues[1]].orEmpty()
            if (value.isEmpty()) missing = true
            Uri.encode(value)
        }
        return if (missing) "/" else filled
    }
}
