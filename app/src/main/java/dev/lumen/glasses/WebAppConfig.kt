package dev.lumen.glasses

import android.content.Context
import dev.lumen.protocol.AppConfigField
import org.json.JSONObject

/**
 * Web apps' configuration values (server addresses, API keys), set from the phone
 * ([GridApi], `nb.grid` op `config`) and read by the app's page (`window.lumen.config`). Kept
 * in the glasses app's private storage, one JSON object per app; secrets never go back to the
 * phone ([fields] reports only that they're set).
 */
object WebAppConfig {
    private const val PREFS = "lumen_app_config"

    fun interface Listener {
        fun onConfigChanged(appId: String)
    }

    private val listeners = mutableListOf<Listener>()

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** The values an app has, limited to the keys its manifest declares. */
    @JvmStatic
    fun values(context: Context, app: WebApp): JSONObject {
        val stored = stored(context, app.id)
        val out = JSONObject()
        app.configFields.forEach { field ->
            stored.optString(field.key).takeIf { it.isNotEmpty() }?.let { out.put(field.key, it) }
        }
        return out
    }

    /** The app's fields with their values for the phone: a secret shows only whether it's set. */
    @JvmStatic
    fun fields(context: Context, app: WebApp): List<AppConfigField> {
        val stored = stored(context, app.id)
        return app.configFields.map { field ->
            val value = stored.optString(field.key)
            field.copy(value = if (field.secret) "" else value, set = value.isNotEmpty())
        }
    }

    /** Null when set, else why not (English, for the log and the phone's fallback). */
    @JvmStatic
    fun set(context: Context, app: WebApp, key: String, value: String): String? {
        val field = app.configFields.firstOrNull { it.key == key } ?: return "unknown setting $key for ${app.id}"
        val trimmed = value.trim()
        if (field.type == AppConfigField.TYPE_URL && trimmed.isNotEmpty() && !trimmed.startsWith("https://") &&
            !trimmed.startsWith("http://")
        ) {
            return "not a URL: $key"
        }
        val stored = stored(context, app.id)
        if (trimmed.isEmpty()) stored.remove(key) else stored.put(key, trimmed)
        prefs(context).edit().putString(app.id, stored.toString()).apply()
        listeners.toList().forEach { it.onConfigChanged(app.id) }
        return null
    }

    /** Whether any of the app's secret settings has a value. */
    @JvmStatic
    fun hasSecrets(context: Context, app: WebApp): Boolean {
        val stored = stored(context, app.id)
        return app.configFields.any { it.secret && stored.optString(it.key).isNotEmpty() }
    }

    /** Forgets the values of the app's secret settings; the other values stay. */
    @JvmStatic
    fun clearSecrets(context: Context, app: WebApp) {
        val stored = stored(context, app.id)
        val secrets = app.configFields.filter { it.secret && stored.has(it.key) }
        if (secrets.isEmpty()) return
        secrets.forEach { stored.remove(it.key) }
        prefs(context).edit().putString(app.id, stored.toString()).apply()
        listeners.toList().forEach { it.onConfigChanged(app.id) }
    }

    @JvmStatic
    fun clear(context: Context, appId: String) {
        prefs(context).edit().remove(appId).apply()
    }

    private fun stored(context: Context, appId: String): JSONObject =
        runCatching { JSONObject(prefs(context).getString(appId, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
