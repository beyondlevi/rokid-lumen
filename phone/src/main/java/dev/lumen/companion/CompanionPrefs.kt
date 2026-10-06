package dev.lumen.companion

import android.content.Context
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission

/** The Hi Rokid authorization token (CXR-L), in the app's private storage. */
object CompanionPrefs {
    private const val PREFS = "companion"

    fun token(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("token", null)

    fun setToken(context: Context, token: String?) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("token", token).apply()

    /** The glasses permissions granted with the token (CXR-L forgets them on restart). */
    fun permissions(context: Context): List<GlassPermission> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("permissions", emptySet())
            .orEmpty().mapNotNull { name -> GlassPermission.entries.firstOrNull { it.name == name } }

    fun setPermissions(context: Context, permissions: List<GlassPermission>) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet("permissions", permissions.map { it.name }.toSet()).apply()

    /** Banners only while the phone's screen is off (the inbox gets everything), as Relay does. */
    fun pauseWhileScreenOn(context: Context) = prefs(context).getBoolean("pause_screen_on", true)
    fun setPauseWhileScreenOn(context: Context, value: Boolean) = prefs(context).edit().putBoolean("pause_screen_on", value).apply()

    /** Send only the app and the title, never the text. */
    fun hideContent(context: Context) = prefs(context).getBoolean("hide_content", false)
    fun setHideContent(context: Context, value: Boolean) = prefs(context).edit().putBoolean("hide_content", value).apply()

    /** The band is used on the phone ([PhoneBand]); the glasses stay off it meanwhile. */
    fun bandOnPhone(context: Context) = prefs(context).getBoolean("band_on_phone", false)
    fun setBandOnPhone(context: Context, value: Boolean) = prefs(context).edit().putBoolean("band_on_phone", value).apply()

    /**
     * The band on the phone works for a computer: the phone is its Bluetooth keyboard and mouse
     * ([dev.lumen.companion.computer.ComputerLink]). Only counts while [bandOnPhone].
     */
    fun bandOnComputer(context: Context) = bandOnPhone(context) && prefs(context).getBoolean("band_on_computer", false)
    fun setBandOnComputer(context: Context, value: Boolean) = prefs(context).edit().putBoolean("band_on_computer", value).apply()

    /** The glasses' banner blacks out the app behind it (the additive display shows only the notification). */
    fun focusBanner(context: Context) = prefs(context).getBoolean("focus_banner", false)
    fun setFocusBanner(context: Context, value: Boolean) = prefs(context).edit().putBoolean("focus_banner", value).apply()

    fun notificationsEnabled(context: Context) = prefs(context).getBoolean("notifications", true)
    fun setNotificationsEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean("notifications", value).apply()

    /**
     * Apps whose notifications stay on the phone. A deny list, not an allow list: Relay found an
     * allow list loses each app's first message (it can't be ticked before it has sent one).
     */
    fun blockedApps(context: Context): Set<String> = prefs(context).getStringSet("blocked", emptySet()).orEmpty()
    fun setBlocked(context: Context, pkg: String, blocked: Boolean) {
        val set = blockedApps(context).toMutableSet()
        if (blocked) set += pkg else set -= pkg
        prefs(context).edit().putStringSet("blocked", set).apply()
    }

    /** Apps seen notifying, for the block list's choices (package names only, no content). */
    fun seenApps(context: Context): Set<String> = prefs(context).getStringSet("seen", emptySet()).orEmpty()
    fun noteSeen(context: Context, pkg: String) {
        val seen = seenApps(context)
        if (pkg !in seen) prefs(context).edit().putStringSet("seen", seen + pkg).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
