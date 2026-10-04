package dev.lumen.glasses

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/**
 * The display goes off after a while without input. Rokid ships `screen_off_timeout` at 10 days
 * (only the button or the band's middle double tap turned the display off), so Lumen writes the
 * system's own timeout: the system then counts the touchpad and keeps the display on for windows
 * that ask for it (a playing video, the Rokid assistant). The band's gestures act through
 * accessibility, which the system doesn't see as input: [onUserActivity] tells it.
 */
object ScreenTimeout {
    private const val TAG = "BandScreen"
    const val DEFAULT_SECONDS = 120
    const val NEVER = 0

    /** The choices, in seconds ([NEVER] keeps Rokid's own value). */
    @JvmField
    val CHOICES = listOf(30, 60, 120, 300, 600, NEVER)

    /** Rokid's value, written back for [NEVER]. */
    private const val ROKID_TIMEOUT_MS = 864_000_000

    /** Gestures come several a second: one poke per this is enough for any timeout above. */
    private const val POKE_EVERY_MS = 5_000L

    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var grantTried = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastPoke = 0L

    /** Writes the chosen timeout to the system when it differs (off the main thread). */
    @JvmStatic
    fun apply(context: Context) {
        val app = context.applicationContext
        val seconds = GestureMappings.screenTimeout(app)
        val millis = if (seconds == NEVER) ROKID_TIMEOUT_MS else seconds * 1000
        writer.execute {
            val current = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
            if (current == millis) return@execute
            Log.d(TAG, "screen_off_timeout $current -> $millis: ${write(app, millis)}")
        }
    }

    private fun write(context: Context, millis: Int): Boolean {
        if (!Settings.System.canWrite(context) && !grantTried) {
            grantTried = true
            runCatching { SelfArmController.runShell(context, "appops set ${context.packageName} WRITE_SETTINGS allow") }
                .onFailure { Log.w(TAG, "Could not grant WRITE_SETTINGS: ${it.message}") }
        }
        if (Settings.System.canWrite(context)) {
            return runCatching { Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis) }
                .onFailure { Log.w(TAG, "Could not write screen_off_timeout: ${it.message}") }.getOrDefault(false)
        }
        return runCatching { SelfArmController.runShell(context, "settings put system screen_off_timeout $millis") }
            .onFailure { Log.w(TAG, "Could not set screen_off_timeout: ${it.message}") }.isSuccess
    }

    /**
     * A band gesture with the display on: restarts the system's countdown, as a touch would.
     * Releasing a screen wake lock taken with ON_AFTER_RELEASE is the user activity an app can
     * report. Main thread.
     */
    @JvmStatic
    fun onUserActivity(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastPoke < POKE_EVERY_MS) return
        lastPoke = now
        runCatching {
            val lock = wakeLock ?: newWakeLock(context).also { wakeLock = it }
            lock.acquire(POKE_EVERY_MS)
            lock.release()
        }.onFailure { Log.w(TAG, "Could not report user activity: ${it.message}") }
    }

    @Suppress("DEPRECATION")
    private fun newWakeLock(context: Context): PowerManager.WakeLock {
        val power = context.applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE, "Lumen:bandActivity")
            .apply { setReferenceCounted(false) }
    }
}
