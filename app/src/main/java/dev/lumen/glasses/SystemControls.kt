package dev.lumen.glasses

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * What the home's Controls tab ([ControlsPage]) changes on the glasses: the media volume, the
 * screen brightness, the Rokid launcher's own screens and leaving for the Rokid launcher itself.
 */
object SystemControls {
    private const val TAG = "BandControls"
    const val ROKID_LAUNCHER = "com.rokid.os.sprite.launcher"
    private const val ROKID_LAUNCHER_MAIN = "$ROKID_LAUNCHER.main.SpriteMainActivity"

    /** The Rokid launcher's screens the tab opens. */
    enum class RokidScreen(val activity: String) {
        CAMERA("$ROKID_LAUNCHER.page.camera.CameraPageActivity"),
        GALLERY("$ROKID_LAUNCHER.page.gallery.StorageImageShowActivity"),
        MUSIC("$ROKID_LAUNCHER.page.music.MusicPageActivity"),
        SETTINGS("$ROKID_LAUNCHER.setting.SettingPageActivity"),
        BRIGHTNESS("$ROKID_LAUNCHER.page.brightness.SettingBrightnessActivity"),
    }

    /** Brightness and volume move in steps of a fifteenth, as the Rokid's own bars do. */
    const val STEPS = 15
    private const val BRIGHTNESS_MAX = 255

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var brightnessGrantTried = false

    /**
     * A Rokid screen opened from the tab is showing. Those screens live in the Rokid launcher's
     * task, above its main screen: closing one shows the Rokid launcher, not Lumen (measured on
     * the glasses). While this is set, the launcher's main screen coming up brings Lumen back.
     */
    @Volatile private var returnToLumen = false

    /** Opens [screen]; false when this device doesn't have it. Closing it comes back to Lumen. */
    @JvmStatic
    fun open(context: Context, screen: RokidScreen): Boolean = try {
        context.startActivity(Intent().setComponent(ComponentName(ROKID_LAUNCHER, screen.activity))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        returnToLumen = true
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "No ${screen.name} screen on this device")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "${screen.name} screen refused", e)
        false
    }

    /** The Controls tab's Rokid launcher tile: the system's home (the Rokid launcher) in front. */
    @JvmStatic
    fun openRokidLauncher(context: Context) {
        returnToLumen = false
        try {
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No home screen to go to", e)
        }
    }

    /**
     * Called with every accessibility event: after a Rokid screen opened from the tab closes onto
     * the Rokid launcher's main screen, Lumen's home comes back in front.
     */
    @JvmStatic
    fun onAccessibilityEvent(context: Context, event: AccessibilityEvent) {
        if (!returnToLumen || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (event.packageName?.toString() != ROKID_LAUNCHER || event.className?.toString() != ROKID_LAUNCHER_MAIN) return
        returnToLumen = false
        Log.d(TAG, "A Rokid screen opened from Controls closed: back to Lumen")
        try {
            context.startActivity(Intent(context, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not bring Lumen's home back", e)
        }
    }

    // ---- Volume (media) ----

    @JvmStatic
    fun volume(context: Context): Float {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0f
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    /** One step up or down; returns the new level (0..1). No system volume panel. */
    @JvmStatic
    fun stepVolume(context: Context, up: Boolean): Float {
        val audio = context.getSystemService(AudioManager::class.java) ?: return 0f
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
        return volume(context)
    }

    // ---- Brightness ----

    @JvmStatic
    fun brightness(context: Context): Float =
        Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, BRIGHTNESS_MAX / 4).toFloat() / BRIGHTNESS_MAX

    /**
     * One step up or down from [current] (0..1); returns the new level at once and writes it in
     * the background. Writing system settings needs WRITE_SETTINGS, which only the user or the
     * shell can grant: without it, the self-arm's shell grants it (once) or writes the value.
     * [onFailed] runs on the main thread when neither works (no self-arm).
     */
    @JvmStatic
    fun stepBrightness(context: Context, current: Float, up: Boolean, onFailed: () -> Unit): Float {
        val step = (current * STEPS).roundToInt() + if (up) 1 else -1
        val level = step.coerceIn(1, STEPS).toFloat() / STEPS
        val value = (level * BRIGHTNESS_MAX).roundToInt().coerceIn(1, BRIGHTNESS_MAX)
        val app = context.applicationContext
        writer.execute {
            if (!writeBrightness(app, value)) android.os.Handler(android.os.Looper.getMainLooper()).post(onFailed)
        }
        return level
    }

    private fun writeBrightness(context: Context, value: Int): Boolean {
        if (!Settings.System.canWrite(context) && !brightnessGrantTried) {
            brightnessGrantTried = true
            runCatching { SelfArmController.runShell(context, "appops set ${context.packageName} WRITE_SETTINGS allow") }
                .onFailure { Log.w(TAG, "Could not grant WRITE_SETTINGS: ${it.message}") }
        }
        if (Settings.System.canWrite(context)) {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, value)
            return true
        }
        return runCatching {
            SelfArmController.runShell(context, "settings put system screen_brightness_mode 0; settings put system screen_brightness $value")
        }.onFailure { Log.w(TAG, "Could not set the brightness: ${it.message}") }.isSuccess
    }
}
