package dev.lumen.glasses

import android.content.Context
import android.util.Log

/**
 * After an update (the companion installs new versions from GitHub), one banner says so the
 * first time the accessibility service connects on the new version: "Lumen updated · <version>".
 * The release notes are on the phone. Nothing shows on a fresh install.
 */
object AppUpdateNotice {
    private const val TAG = "BandUpdate"
    private const val PREFS = "lumen_update"
    private const val KEY_SEEN = "seen_version"

    @JvmStatic
    fun showIfUpdated(context: Context, banner: NotificationBanner?) {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val seen = prefs.getString(KEY_SEEN, null)
        prefs.edit().putString(KEY_SEEN, version).apply()
        if (seen == null || seen == version || banner == null) return
        Log.d(TAG, "Updated from $seen to $version")
        banner.show(
            PhoneNotification(
                key = "lumen-update|$version",
                appName = context.getString(R.string.app_name),
                packageName = context.packageName,
                title = context.getString(R.string.update_banner_title, version),
                text = context.getString(R.string.update_banner_text),
                postedAt = System.currentTimeMillis(),
                redacted = false,
                icon = null,
            ),
        )
    }
}
