package dev.lumen.glasses

import android.content.Context
import android.content.Intent
import dev.lumen.protocol.GridItem
import org.json.JSONArray

/**
 * What the apps grid shows, in what order: arranged from the phone ([GridApi]), kept here.
 * Two lists are stored: the order the phone set and what it hid. Everything else follows rules
 * ([resolve]): a web app installed later joins the end on its own, a native app only when added,
 * and Settings is always there, so the glasses can't be locked out of their own settings.
 */
object GridStore {
    private const val PREFS = "lumen_grid"
    private const val KEY_ORDER = "order"
    private const val KEY_HIDDEN = "hidden"
    private const val ROKID_LAUNCHER = "com.rokid.os.sprite.launcher"

    /** Called on the main thread when the grid changed (from the phone or an install). */
    fun interface Listener {
        fun onGridChanged()
    }

    private val listeners = LinkedHashSet<Listener>()

    @JvmStatic
    fun addListener(listener: Listener) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    @JvmStatic
    fun notifyChanged() = listeners.toList().forEach { it.onGridChanged() }

    /** The grid's ids, in order: what the home's Apps tab shows. */
    @JvmStatic
    fun layout(context: Context): List<String> = resolve(
        stored(context, KEY_ORDER),
        stored(context, KEY_HIDDEN).toSet(),
        WebAppLibrary.all(context).map { GridItem.WEB_PREFIX + it.id },
        nativeApps(context).keys.map { GridItem.APP_PREFIX + it }.toSet(),
    )

    /** Saves the phone's arrangement; Settings can't be hidden. */
    @JvmStatic
    fun set(context: Context, order: List<String>, hidden: List<String>) {
        prefs(context).edit()
            .putString(KEY_ORDER, JSONArray(order).toString())
            .putString(KEY_HIDDEN, JSONArray(hidden.filter { it != GridItem.SETTINGS_ID }).toString())
            .apply()
    }

    /** Takes an id out of the stored lists (a deleted web app). */
    @JvmStatic
    fun forget(context: Context, id: String) {
        set(context, stored(context, KEY_ORDER) - id, stored(context, KEY_HIDDEN) - id)
    }

    /** Everything the grid could show, by id, described for the phone. */
    @JvmStatic
    fun items(context: Context): Map<String, GridItem> {
        val out = LinkedHashMap<String, GridItem>()
        WebAppLibrary.all(context).forEach { app ->
            val id = GridItem.WEB_PREFIX + app.id
            out[id] = GridItem(id, GridItem.Kind.WEB, app.name, if (app.offline) "" else app.remoteUrl, app.offline, app.engine.name,
                config = WebAppConfig.fields(context, app), version = if (app.hasPackage) app.version else "",
                iconStamp = app.icon?.let { java.io.File(it).lastModified() } ?: 0,
                copyOf = app.copyOf.takeIf { it.isNotEmpty() }?.let { GridItem.WEB_PREFIX + it }.orEmpty())
        }
        nativeApps(context).forEach { (pkg, label) ->
            val id = GridItem.APP_PREFIX + pkg
            out[id] = GridItem(id, GridItem.Kind.NATIVE, label, pkg)
        }
        out[GridItem.SETTINGS_ID] = GridItem(GridItem.SETTINGS_ID, GridItem.Kind.SETTINGS, context.getString(R.string.launcher_settings), removable = false)
        return out
    }

    /** The glasses' launchable apps (package to label), this app and the Rokid launcher aside. */
    @JvmStatic
    fun nativeApps(context: Context): Map<String, String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName && it.packageName != ROKID_LAUNCHER }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
            .associate { it.packageName to it.loadLabel(pm).toString() }
    }

    /**
     * The grid from the stored [order] and [hidden] and what exists now: the order's ids that
     * still exist; then web apps that were neither placed nor hidden (a new install shows up);
     * Settings last unless placed. With nothing stored: the web apps, Settings. Notifications
     * are the home's own tab now, not a grid item: an order from before drops them.
     */
    @JvmStatic
    fun resolve(order: List<String>, hidden: Set<String>, webIds: List<String>, nativeIds: Set<String>): List<String> {
        fun exists(id: String) = id == GridItem.SETTINGS_ID || id in webIds || id in nativeIds
        val out = order.filter(::exists).distinct().toMutableList()
        val settingsAt = out.indexOf(GridItem.SETTINGS_ID)
        val newcomers = webIds.filter { it !in out && it !in hidden }
        if (settingsAt >= 0 && settingsAt == out.lastIndex) out.addAll(settingsAt, newcomers) else out.addAll(newcomers)
        if (GridItem.SETTINGS_ID !in out) out += GridItem.SETTINGS_ID
        return out
    }

    private fun stored(context: Context, key: String): List<String> = runCatching {
        val array = JSONArray(prefs(context).getString(key, "[]"))
        (0 until array.length()).map { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
