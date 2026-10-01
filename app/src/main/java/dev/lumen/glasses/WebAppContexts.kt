package dev.lumen.glasses

import android.content.Context

/**
 * Each web app's own GeckoView session context: its cookies, storage and cache, apart from every
 * other app's ([GeckoWebEngine] opens the app's session in it). A removed app's context is
 * cleared; with no Gecko runtime in the process yet, the clearing waits for the next one
 * (starting Gecko only for that would cost the glasses ~a second and a lot of memory).
 *
 * The system WebView has no such contexts: its cookies are shared by every app on that engine
 * (see [SystemWebEngine]).
 */
object WebAppContexts {
    private const val PREFS = "lumen_gecko_contexts"
    private const val KEY_PENDING = "pending_clear"

    /** Clears one context's data; set by [GeckoWebEngine] once the runtime exists. */
    @Volatile
    var clearer: ((String) -> Unit)? = null

    /** The session context of the app [appId]: its id (unique in the library, kept on updates). */
    @JvmStatic
    fun idFor(appId: String): String = appId

    /** Deletes the app's cookies and storage in its context, now or when Gecko next starts. */
    @JvmStatic
    @Synchronized
    fun clear(context: Context, appId: String) {
        val id = idFor(appId)
        val now = clearer
        if (now != null && runCatching { now(id) }.isSuccess) return
        val prefs = prefs(context)
        prefs.edit().putStringSet(KEY_PENDING, prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty() + id).apply()
    }

    /** The contexts still to clear (removed while Gecko wasn't running), forgotten once returned. */
    @JvmStatic
    @Synchronized
    fun takePending(context: Context): Set<String> {
        val prefs = prefs(context)
        val pending = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty().toSet()
        if (pending.isNotEmpty()) prefs.edit().remove(KEY_PENDING).apply()
        return pending
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
