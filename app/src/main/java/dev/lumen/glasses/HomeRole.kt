package dev.lumen.glasses

import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * The glasses' home app: whether [LauncherActivity] is the one Android opens on Home, at boot and
 * when an app closes, and the way to choose it. The choice is the user's, in Android's own
 * "Default home app" screen (`HOME_SETTINGS`, Permission Controller's on the glasses): an app
 * can't make itself the home. Picking the Rokid launcher there puts it back.
 */
object HomeRole {
    private const val TAG = "BandHomeRole"
    private const val ROKID_LAUNCHER = "com.rokid.os.sprite.launcher"
    private const val ROKID_LAUNCHER_MAIN = "com.rokid.os.sprite.launcher.main.SpriteMainActivity"

    /** Whether Home resolves to this app, i.e. the user picked Lumen as the home app. */
    @JvmStatic
    fun isDefault(context: Context): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    /**
     * Opens the screen where the home app is chosen: Android's "Default home app", else the
     * home role request, else the system settings. False if none opened.
     */
    @JvmStatic
    fun openChooser(context: Context): Boolean {
        val candidates = buildList {
            add(Intent(Settings.ACTION_HOME_SETTINGS))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getSystemService(RoleManager::class.java)
                    ?.takeIf { it.isRoleAvailable(RoleManager.ROLE_HOME) }
                    ?.let { add(it.createRequestRoleIntent(RoleManager.ROLE_HOME)) }
            }
            add(Intent(Settings.ACTION_SETTINGS))
        }
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                Log.d(TAG, "Opened home chooser action=${intent.action}")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "No home chooser for action=${intent.action}")
            } catch (e: SecurityException) {
                Log.w(TAG, "Home chooser refused action=${intent.action}", e)
            }
        }
        return false
    }

    /**
     * The Rokid launcher's own screens (brightness, translation...) live in its task, above its
     * main screen, and don't go through Home when closed: Back from one showed the Rokid launcher
     * even with Lumen as the home app (measured on the glasses, as EKHome's notes say). When
     * Lumen is the home and the Rokid launcher's main screen comes up, [LauncherActivity] is
     * brought back in front of it. Called with every accessibility event.
     */
    @JvmStatic
    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != ROKID_LAUNCHER || event.className?.toString() != ROKID_LAUNCHER_MAIN) return
        if (!isDefault(context)) return
        Log.d(TAG, "Rokid launcher came up with Lumen as the home app: back to Lumen")
        try {
            context.startActivity(Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not bring Lumen's home back", e)
        }
    }
}
