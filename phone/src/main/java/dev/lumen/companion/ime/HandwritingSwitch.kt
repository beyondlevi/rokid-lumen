package dev.lumen.companion.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import dev.lumen.companion.PhoneTouchService
import dev.lumen.companion.R

/**
 * The phone's Write gesture ([dev.lumen.companion.PhoneSettings.WRITE]): the Lumen handwriting
 * keyboard takes over the focused field, the band writes there, and the keyboard the person had
 * comes back when the writing ends. An app can't switch keyboards; the Screen gestures service can
 * ([PhoneTouchService.switchKeyboard]), if the Lumen keyboard is turned on in the system's
 * keyboard settings. Main thread.
 */
object HandwritingSwitch {
    private const val TAG = "NbHandwriting"

    /** The keyboard's id, as the system lists it. */
    const val KEYBOARD = "dev.lumen.companion/.ime.HandwritingKeyboard"

    /** The keyboard to go back to, while a gesture switched to Lumen's. */
    @Volatile var returnTo: String? = null
        private set

    /** Lumen's keyboard writing in the focused field; false (and a word to the person) when it can't. */
    fun start(context: Context): Boolean {
        val service = PhoneTouchService.instance ?: run {
            say(context, R.string.phone_write_no_touch)
            return false
        }
        val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        if (current == KEYBOARD) {
            // Already the keyboard: it writes again where it is.
            HandwritingKeyboard.writeAgain()
            return true
        }
        if (!service.switchKeyboard(KEYBOARD)) {
            say(context, R.string.phone_write_no_keyboard)
            return false
        }
        returnTo = current
        Log.d(TAG, "write: the handwriting keyboard takes the field")
        return true
    }

    /** The writing ended (or the field went away): the person's keyboard again, if a gesture switched. */
    fun finished(keyboard: InputMethodService) {
        val back = returnTo ?: return
        returnTo = null
        if (PhoneTouchService.instance?.switchKeyboard(back) != true) keyboard.switchToPreviousInputMethod()
        Log.d(TAG, "write: back to the previous keyboard")
    }

    /** The person chose a keyboard: nothing to go back to any more. */
    fun forget() {
        returnTo = null
    }

    private fun say(context: Context, text: Int) =
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
}
