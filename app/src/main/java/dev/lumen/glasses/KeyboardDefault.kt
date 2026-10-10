package dev.lumen.glasses

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager

/**
 * Lumen's keyboard ([LumenKeyboard]) as the glasses' input method, in place of Rokid's: the
 * self-arm's WRITE_SECURE_SETTINGS lets the app enable itself and make itself the default
 * (Android's input method service follows those settings at once). With the setting off
 * ([GestureMappings.isLumenKeyboard]), Rokid's goes back. Without the permission nothing
 * changes. Run where the app starts (the accessibility service, the home) and when the setting
 * changes; it only writes what differs.
 */
object KeyboardDefault {
    private const val TAG = "BandKeyboardIme"

    /** Rokid's own input method (its assistant's), the glasses' default out of the box. */
    const val ROKID_IME = "com.rokid.os.sprite.assistserver/com.rokid.os.sprite.assist.ime.RemoteInputMethodService"

    /** What to write: the new enabled list and the new default, each null when it stays. */
    data class Plan(val enabled: String?, val default: String?) {
        val changes: Boolean get() = enabled != null || default != null
    }

    /**
     * The settings for [wanted] (Lumen's keyboard on), from the current [enabled] list
     * (`id[;subtype…]:id…`) and [current] default. [restore] is the input method to go back to
     * when it's off (Rokid's when installed, else another one enabled), or null when there is
     * none: Lumen's then stays, so the glasses keep a keyboard.
     */
    @JvmStatic
    fun plan(wanted: Boolean, enabled: String?, current: String?, ours: String, restore: String?): Plan {
        val entries = enabled.orEmpty().split(':').filter { it.isNotBlank() }
        val ids = entries.map { it.substringBefore(';') }
        if (wanted) {
            val list = if (ours in ids) null else (entries + ours).joinToString(":")
            return Plan(list, if (current == ours) null else ours)
        }
        if (current != ours && ours !in ids) return Plan(null, null)
        val back = restore?.takeIf { it != ours }
        if (current == ours && back == null) return Plan(null, null)
        var next = entries.filter { it.substringBefore(';') != ours }
        if (back != null && back !in ids) next = next + back
        val list = next.joinToString(":").takeIf { it != entries.joinToString(":") }
        return Plan(list, if (current == ours) back else null)
    }

    /** The input method to go back to without Lumen's: Rokid's when installed, else the first other enabled. */
    @JvmStatic
    fun restoreFor(installed: List<String>, enabled: String?, ours: String): String? {
        if (ROKID_IME in installed) return ROKID_IME
        return enabled.orEmpty().split(':').map { it.substringBefore(';') }.firstOrNull { it.isNotBlank() && it != ours && it in installed }
    }

    /** Lumen's input method id, as Android names it. */
    @JvmStatic
    fun ours(context: Context): String = ComponentName(context, LumenKeyboard::class.java).flattenToShortString()

    /** Makes the settings match [GestureMappings.isLumenKeyboard]; false when it can't (no self-arm). */
    @JvmStatic
    fun apply(context: Context): Boolean {
        val app = context.applicationContext
        if (!LocalSelfArmStatus.armed(app)) {
            Log.d(TAG, "keyboard default left alone: no WRITE_SECURE_SETTINGS (self-arm)")
            return false
        }
        val resolver = app.contentResolver
        val ours = ours(app)
        val wanted = GestureMappings.isLumenKeyboard(app)
        return runCatching {
            val enabled = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_INPUT_METHODS)
            val current = Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            val restore = if (wanted) null else {
                val installed = app.getSystemService(InputMethodManager::class.java)?.inputMethodList.orEmpty().map { it.id }
                restoreFor(installed, enabled, ours)
            }
            val plan = plan(wanted, enabled, current, ours, restore)
            if (!plan.changes) return@runCatching true
            plan.enabled?.let { Settings.Secure.putString(resolver, Settings.Secure.ENABLED_INPUT_METHODS, it) }
            plan.default?.let { Settings.Secure.putString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD, it) }
            Log.i(TAG, "keyboard ${if (wanted) "Lumen's" else "back to ${plan.default ?: current}"}: default $current -> ${plan.default ?: current}")
            true
        }.onFailure { Log.w(TAG, "couldn't set the keyboard", it) }.getOrDefault(false)
    }
}
