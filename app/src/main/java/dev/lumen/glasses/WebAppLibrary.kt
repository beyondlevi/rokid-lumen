package dev.lumen.glasses

import android.content.Context
import android.net.Uri
import dev.lumen.protocol.AppConfigField
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Which browser engine runs a web app: Firefox's GeckoView (current) or the system WebView (Chromium 95). */
enum class WebEngineKind(val label: String) {
    GECKO("GeckoView"),
    SYSTEM("WebView do sistema");

    companion object {
        @JvmStatic
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: GECKO
    }
}

/**
 * A Meta Ray-Ban Display web app the host can open. An online app is an HTTPS URL; an offline
 * one is a package extracted in [WebAppPackages.dir], served by [LocalAppServer] on its own
 * loopback port, so each app keeps its own origin (and localStorage). On GeckoView each app also
 * has its own session context ([WebAppContexts]): cookies and storage apart from the others.
 */
data class WebApp(
    val id: String,
    val name: String,
    val offline: Boolean,
    /** The HTTPS address of an online app; empty for an offline one. */
    val remoteUrl: String,
    /** The loopback port of an offline app; 0 for an online one. */
    val port: Int,
    val engine: WebEngineKind,
    /** A PNG in the app's private storage, or null. */
    val icon: String?,
    val version: String,
    /** The configuration its manifest asks for (`lumen_config`); values live in [WebAppConfig]. */
    val configFields: List<AppConfigField> = emptyList(),
    /** An offline app that reaches the internet (manifest `lumen_internet`): it gets the phone's. */
    val internet: Boolean = false,
    /**
     * Where an offline app's package was downloaded from (the URL's origin), empty when it came
     * from the owner's own channels (adb, the phone). An update from elsewhere loses the secrets.
     */
    val source: String = "",
) {
    /** What the engine loads. */
    val url: String get() = if (offline) LocalAppServer.origin(port) + "/" else remoteUrl
}

/**
 * The web apps added to the glasses, newest last: online ones confirmed on the glasses
 * ([InstallConfirmActivity]: `navigator.install()` inside an app, or `adb shell am start -a
 * android.intent.action.VIEW -n dev.lumen.glasses/.InstallConfirmActivity -d <https url>`) or
 * added from the phone ([GridApi]), offline ones from `.mrbd.zip` packages ([WebAppPackages]).
 */
object WebAppLibrary {
    private const val PREFS = "neuralband_webapps"
    private const val KEY = "apps"
    private const val KEY_NEXT_PORT = "next_port"
    /**
     * Offline apps get ports from here up, one each, never reused, not even after the app is
     * removed: an origin (`http://127.0.0.1:<port>`) keeps its storage in the engines, which a
     * later app on the same port would otherwise inherit.
     */
    const val FIRST_PORT = 47_100
    const val LAST_PORT = 65_535

    @JvmStatic
    fun isAcceptable(url: String?): Boolean = runCatching {
        val uri = Uri.parse(url ?: return false)
        uri.scheme == "https" && !uri.host.isNullOrBlank()
    }.getOrDefault(false)

    @JvmStatic
    fun idForUrl(url: String): String = sha1(url).take(12)

    @JvmStatic
    fun all(context: Context): List<WebApp> =
        parse(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))

    @JvmStatic
    fun find(context: Context, id: String?): WebApp? = all(context).firstOrNull { it.id == id }

    @JvmStatic
    fun findByUrl(context: Context, url: String?): WebApp? = all(context).firstOrNull { !it.offline && it.remoteUrl == url }

    /** Adds an online app, or renames it when the URL is already there. Returns null for a non-HTTPS URL. */
    @JvmStatic
    fun add(context: Context, url: String, name: String?): WebApp? {
        if (!isAcceptable(url)) return null
        val label = name?.trim()?.takeIf { it.isNotEmpty() } ?: Uri.parse(url).host ?: url
        val existing = findByUrl(context, url)
        val app = existing?.copy(name = label)
            ?: WebApp(idForUrl(url), label, false, url, 0, WebEngineKind.GECKO, null, "")
        put(context, app)
        return app
    }

    /** Adds or replaces an app by id (the port of an offline app it replaces is kept). */
    @JvmStatic
    fun put(context: Context, app: WebApp) {
        val apps = all(context)
        val index = apps.indexOfFirst { it.id == app.id }
        save(context, if (index < 0) apps + app else apps.toMutableList().also { it[index] = app })
    }

    @JvmStatic
    fun setEngine(context: Context, id: String, engine: WebEngineKind) {
        find(context, id)?.let { put(context, it.copy(engine = engine)) }
    }

    @JvmStatic
    fun setIcon(context: Context, id: String, icon: String?) {
        find(context, id)?.let { put(context, it.copy(icon = icon)) }
    }

    /**
     * Removes the app, its package files and its icon, stops its server and deletes its
     * cookies and storage (its GeckoView context; the system WebView's cookies are shared, see
     * [SystemWebEngine]). Its port is never given out again.
     */
    @JvmStatic
    fun remove(context: Context, id: String) {
        val app = find(context, id) ?: return
        save(context, all(context).filter { it.id != id })
        if (app.offline) {
            LocalAppServer.stop(app.port)
            WebAppPackages.dir(context, id).deleteRecursively()
        }
        app.icon?.let { File(it).delete() }
        WebAppConfig.clear(context, id)
        WebAppContexts.clear(context, id)
    }

    /**
     * The port for a new offline app: the stored counter ([counter]), past every port an
     * installed app holds (libraries from before the counter), never below [FIRST_PORT].
     */
    @JvmStatic
    fun nextPort(counter: Int, apps: List<WebApp>): Int {
        val highest = apps.filter { it.offline }.maxOfOrNull { it.port } ?: (FIRST_PORT - 1)
        return maxOf(FIRST_PORT, counter, highest + 1)
    }

    /** Gives out the next port for good ([nextPort]); throws once the ports run out (18 435 installs). */
    @JvmStatic
    @Synchronized
    fun allocatePort(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val port = nextPort(prefs.getInt(KEY_NEXT_PORT, FIRST_PORT), all(context))
        check(port <= LAST_PORT) { "no loopback port left for another offline app" }
        prefs.edit().putInt(KEY_NEXT_PORT, port + 1).apply()
        return port
    }

    /** Reads the stored list; the POC's entries ({url, name}) become online Gecko apps. */
    @JvmStatic
    fun parse(raw: String?): List<WebApp> = runCatching {
        val array = JSONArray(raw ?: return emptyList())
        (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull { json ->
            val offline = json.optBoolean("offline")
            val url = json.optString("url")
            if (!offline && url.isEmpty()) return@mapNotNull null
            WebApp(
                id = json.optString("id").ifEmpty { idForUrl(url) },
                name = json.optString("name").ifEmpty { Uri.parse(url).host ?: url },
                offline = offline,
                remoteUrl = if (offline) "" else url,
                port = json.optInt("port"),
                engine = WebEngineKind.of(json.optString("engine")),
                icon = json.optString("icon").ifEmpty { null },
                version = json.optString("version"),
                configFields = AppConfigField.list(json.optJSONArray("config")),
                internet = json.optBoolean("internet"),
                source = json.optString("source"),
            )
        }
    }.getOrDefault(emptyList())

    @JvmStatic
    fun serialize(apps: List<WebApp>): String {
        val array = JSONArray()
        apps.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("offline", it.offline)
                    .put("url", it.remoteUrl)
                    .put("port", it.port)
                    .put("engine", it.engine.name)
                    .put("icon", it.icon ?: "")
                    .put("version", it.version)
                    .put("config", JSONArray().apply { it.configFields.forEach { field -> put(field.toJson()) } })
                    .put("internet", it.internet)
                    .put("source", it.source),
            )
        }
        return array.toString()
    }

    private fun save(context: Context, apps: List<WebApp>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, serialize(apps)).apply()
    }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
