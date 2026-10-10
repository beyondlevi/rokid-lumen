package dev.lumen.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The glasses' apps grid, arranged from the phone. The glasses own it and answer with its
 * whole state after every change; icons travel one per message, for the items the phone lacks
 * (a state with every icon inline would risk the link's message size).
 *
 *   phone → glasses   [Link.GRID]        {op: describe} | {op: set, order, hidden} | {op: add_web, url, name}
 *                                        | {op: add_package, url} | {op: remove, id} | {op: engine, id, engine}
 *                                        | {op: install_file, token, name, size, sha256, replace}
 *                                        | {op: config, id, key, value} | {op: rename, id, name}
 *                                        | {op: copy, id, name} (a second install of a web app, see below)
 *                                        | {op: icons, ids}; each a request (id)
 *   glasses → phone   [Link.GRID_EVENT]  {type: state, items, available} (after describe and every change)
 *                                        {type: result, ok, subject, error} | {type: icon, id, png (base64)}
 *
 * Item ids: `notifications`, `settings`, `web:<web app id>`, `app:<package>`.
 *
 * Rokid's link delays phone → glasses messages by minutes and loses some, so the phone sends a
 * request again, with the same request id, until its answer (`re`, on the result or on the state
 * that answers `describe`) comes back. The glasses do each request once: a repeat gets the first
 * answer. The request id travels as `req` ([GridOps.requestId]): the ops about one item use `id`
 * for the item (older glasses read it there), which overwrites the envelope's numeric `id`.
 *
 * `install_file` hands over an offline package picked on the phone: the glasses join the phone's
 * network ([Link.NET]) and download it from the phone's proxy at `/lumen/package/<token>`
 * ([PACKAGE_PATH]), checking `size` and `sha256`. With `replace` (an item id) it updates that app
 * only, keeping its settings and storage.
 *
 * A web app's configuration ([AppConfigField]) is declared by its package's manifest
 * (`lumen_config`), set from the phone and read by the page (`window.lumen.config`). The state
 * carries a secret field's presence only, never its value.
 *
 * `copy` installs a web app a second time under another name (a personal and a work WhatsApp):
 * its own id, so its own cookies, storage and settings (empty at first), and offline its own
 * origin. Updating the original's package updates its copies. A copy's item names the original
 * (`copy_of`). `rename` gives any web app a name that updates keep.
 */
object GridOps {
    /** A grid request: the envelope's id kept as `req` too, since item ops reuse `id` for the item. */
    private fun request(): JSONObject = Link.request().let { it.put("req", it.getLong("id")) }

    /** The id of a grid request: `req`, or the envelope's `id` from a phone too old to send `req`. */
    @JvmStatic
    fun requestId(request: JSONObject): Long = request.optLong("req").takeIf { it != 0L } ?: request.optLong("id")

    /** The grid item a request is about (`remove`, `config`, `rename`, `engine`, `copy`), or "". */
    @JvmStatic
    fun item(request: JSONObject): String =
        if (request.optString("op") in setOf(REMOVE, CONFIG, RENAME, ENGINE, COPY)) request.optString("id") else ""

    const val DESCRIBE = "describe"
    const val SET = "set"
    const val ADD_WEB = "add_web"
    const val ADD_PACKAGE = "add_package"
    const val REMOVE = "remove"
    const val ENGINE = "engine"
    const val ICONS = "icons"
    const val CONFIG = "config"
    const val INSTALL_FILE = "install_file"
    const val RENAME = "rename"
    const val COPY = "copy"

    @JvmStatic
    fun rename(id: String, name: String): JSONObject = request().put("op", RENAME).put("id", id).put("name", name)

    @JvmStatic
    fun copy(id: String, name: String): JSONObject = request().put("op", COPY).put("id", id).put("name", name)

    /** Where the phone's proxy serves a package handed over with [installFile]: this + token. */
    const val PACKAGE_PATH = "/lumen/package/"

    @JvmStatic
    fun describe(): JSONObject = request().put("op", DESCRIBE)

    /** The grid as it should be: [order] shown, in order; [hidden] kept out (web apps included). */
    @JvmStatic
    fun set(order: List<String>, hidden: List<String>): JSONObject = request().put("op", SET)
        .put("order", JSONArray(order)).put("hidden", JSONArray(hidden))

    @JvmStatic
    fun addWeb(url: String, name: String = ""): JSONObject = request().put("op", ADD_WEB).put("url", url).put("name", name)

    @JvmStatic
    fun addPackage(url: String): JSONObject = request().put("op", ADD_PACKAGE).put("url", url)

    /** Deletes a web app from the glasses (its package too, if offline). */
    @JvmStatic
    fun remove(id: String): JSONObject = request().put("op", REMOVE).put("id", id)

    @JvmStatic
    fun engine(id: String, engine: String): JSONObject = request().put("op", ENGINE).put("id", id).put("engine", engine)

    /** Sets one configuration value of a web app; an empty [value] clears it. */
    @JvmStatic
    fun config(id: String, key: String, value: String): JSONObject = request().put("op", CONFIG).put("id", id)
        .put("key", key).put("value", value)

    /**
     * An offline package the phone serves at [PACKAGE_PATH] + [token]; [replace] is the grid id of
     * the app it updates, or empty for a new one.
     */
    @JvmStatic
    @JvmOverloads
    fun installFile(token: String, name: String, size: Long, sha256: String, replace: String = ""): JSONObject =
        request().put("op", INSTALL_FILE).put("token", token).put("name", name).put("size", size)
            .put("sha256", sha256).put("replace", replace)

    @JvmStatic
    fun icons(ids: List<String>): JSONObject = request().put("op", ICONS).put("ids", JSONArray(ids))

    @JvmStatic
    fun strings(json: JSONObject, key: String): List<String> {
        val array = json.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }
}

/**
 * One configuration value a web app asks for (its manifest's `lumen_config`): [type] is `text`,
 * `url` or `secret`. [value] is empty for a secret (only [set] says whether it has one). An
 * [optional] field (`"optional": true`) is not required for the app to work, so the phone doesn't
 * ask for it.
 */
data class AppConfigField(
    val key: String,
    val label: String,
    val type: String = TYPE_TEXT,
    val value: String = "",
    val set: Boolean = false,
    val optional: Boolean = false,
) {
    val secret: Boolean get() = type == TYPE_SECRET

    /** Required and still empty: what the phone lists as missing. */
    val missing: Boolean get() = !set && !optional

    fun toJson(): JSONObject = JSONObject().put("key", key).put("label", label).put("type", type).put("value", value)
        .put("set", set).apply { if (optional) put("optional", true) }

    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_URL = "url"
        const val TYPE_SECRET = "secret"

        fun from(json: JSONObject) = AppConfigField(
            json.optString("key"),
            json.optString("label"),
            json.optString("type", TYPE_TEXT),
            json.optString("value"),
            json.optBoolean("set"),
            json.optBoolean("optional"),
        )

        fun list(array: JSONArray?): List<AppConfigField> =
            (0 until (array?.length() ?: 0)).mapNotNull { array!!.optJSONObject(it)?.let(::from) }.filter { it.key.isNotEmpty() }
    }
}

/** One thing the grid can show. [detail] is the web app's URL or the native app's package. */
data class GridItem(
    val id: String,
    val kind: Kind,
    val name: String,
    val detail: String = "",
    val offline: Boolean = false,
    val engine: String = "",
    /** Whether the phone can take it off the grid (Settings can't: it would lock the glasses out). */
    val removable: Boolean = true,
    /** A web app's configuration fields, as its manifest declares them, with their values. */
    val config: List<AppConfigField> = emptyList(),
    /** An offline web app's package version (its manifest's `version`), or empty. */
    val version: String = "",
    /** When the item's icon last changed (0: unknown, or none): the phone asks again on a change. */
    val iconStamp: Long = 0,
    /** A copy's original (its item id), "" otherwise. */
    val copyOf: String = "",
) {
    enum class Kind(val id: String) {
        NOTIFICATIONS("notifications"), SETTINGS("settings"), WEB("web"), NATIVE("native");

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id } ?: WEB
        }
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.id).put("name", name).put("detail", detail)
        .put("offline", offline).put("engine", engine).put("removable", removable)
        .put("config", JSONArray().apply { config.forEach { put(it.toJson()) } })
        .apply { if (version.isNotEmpty()) put("version", version) }
        .apply { if (iconStamp > 0) put("icon", iconStamp) }
        .apply { if (copyOf.isNotEmpty()) put("copy_of", copyOf) }

    companion object {
        const val NOTIFICATIONS_ID = "notifications"
        const val SETTINGS_ID = "settings"
        const val WEB_PREFIX = "web:"
        const val APP_PREFIX = "app:"

        fun from(json: JSONObject) = GridItem(
            json.optString("id"),
            Kind.of(json.optString("kind")),
            json.optString("name"),
            json.optString("detail"),
            json.optBoolean("offline"),
            json.optString("engine"),
            json.optBoolean("removable", true),
            AppConfigField.list(json.optJSONArray("config")),
            json.optString("version"),
            json.optLong("icon"),
            json.optString("copy_of"),
        )
    }
}

/** What arrives on [Link.GRID_EVENT]. */
sealed class GridEvent {
    /** [items] as the grid shows them, in order (9 per page); [available] can be added. [re]: the request it answers, 0 if none. */
    data class State(val items: List<GridItem>, val available: List<GridItem>, val re: Long = 0) : GridEvent() {
        /**
         * This state with [requests] applied, oldest first, as the glasses will apply them: what the
         * phone shows while they are on their way. Requests the phone can't foresee (adding an app,
         * a copy) change nothing here.
         */
        fun with(requests: List<JSONObject>): State {
            var shown = items
            var rest = available
            for (request in requests) {
                val id = GridOps.item(request)
                fun edit(transform: (GridItem) -> GridItem) {
                    shown = shown.map { if (it.id == id) transform(it) else it }
                    rest = rest.map { if (it.id == id) transform(it) else it }
                }
                when (request.optString("op")) {
                    GridOps.REMOVE -> {
                        shown = shown.filter { it.id != id }
                        rest = rest.filter { it.id != id }
                    }
                    GridOps.CONFIG -> {
                        val key = request.optString("key")
                        val value = request.optString("value")
                        edit { item ->
                            item.copy(config = item.config.map { field ->
                                if (field.key != key) field else field.copy(value = if (field.secret) "" else value, set = value.isNotEmpty())
                            })
                        }
                    }
                    GridOps.RENAME -> request.optString("name").trim().takeIf { it.isNotEmpty() }?.let { name -> edit { it.copy(name = name) } }
                    GridOps.ENGINE -> edit { it.copy(engine = request.optString("engine")) }
                    GridOps.SET -> {
                        val order = GridOps.strings(request, "order")
                        val all = (shown + rest).associateBy { it.id }
                        shown = order.mapNotNull { all[it] }
                        rest = all.values.filter { it.id !in order }
                    }
                }
            }
            return copy(items = shown, available = rest)
        }

        fun toJson(request: JSONObject? = null): JSONObject = (request?.let { reply(it) } ?: Link.message()).put("type", "state")
            .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
            .put("available", JSONArray().apply { available.forEach { put(it.toJson()) } })
    }

    /** The answer to request [re]. */
    data class Result(val ok: Boolean, val subject: String, val error: String = "", val re: Long = 0) : GridEvent() {
        fun toJson(request: JSONObject): JSONObject = reply(request).put("type", "result").put("ok", ok)
            .put("subject", subject).put("error", error)
    }

    /** One item's icon, a small PNG in base64. */
    data class Icon(val id: String, val png: String) : GridEvent() {
        fun toJson(): JSONObject = Link.message().put("type", "icon").put("id", id).put("png", png)
    }

    companion object {
        /** The answer's envelope: `re` is the request's id ([GridOps.requestId]). */
        private fun reply(request: JSONObject): JSONObject = Link.message().put("re", GridOps.requestId(request))

        private fun items(json: JSONObject, key: String): List<GridItem> {
            val array = json.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).map { GridItem.from(array.getJSONObject(it)) }
        }

        @JvmStatic
        fun from(json: JSONObject): GridEvent? = when (json.optString("type")) {
            "state" -> State(items(json, "items"), items(json, "available"), json.optLong("re"))
            "result" -> Result(json.optBoolean("ok"), json.optString("subject"), json.optString("error"), json.optLong("re"))
            "icon" -> Icon(json.optString("id"), json.optString("png"))
            else -> null
        }
    }
}
