package dev.lumen.glasses

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest

/**
 * SPIKE: Lumen as the glasses' input method. Logs what Android tells it about each focused field
 * (never the field's text: only its length) and, in debug builds, takes test commands:
 * `am broadcast -a dev.lumen.glasses.IME_TEST --es commit <text>`, `--es replace <text>`,
 * `--es enter 1`, `--es delete <n>`.
 */
class LumenKeyboard : InputMethodService() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ic = currentInputConnection
            if (ic == null) {
                Log.w(TAG, "test command with no field")
                return
            }
            intent.getStringExtra("commit")?.let { text ->
                Log.d(TAG, "commitText (${text.length} chars): ${ic.commitText(text, 1)}")
            }
            intent.getStringExtra("replace")?.let { text ->
                ic.beginBatchEdit()
                val before = ic.getTextBeforeCursor(10_000, 0)?.length ?: 0
                val after = ic.getTextAfterCursor(10_000, 0)?.length ?: 0
                val deleted = ic.deleteSurroundingText(before, after)
                val committed = ic.commitText(text, 1)
                ic.endBatchEdit()
                Log.d(TAG, "replace: deleted $before+$after=$deleted, committed ${text.length} chars=$committed")
            }
            intent.getStringExtra("delete")?.toIntOrNull()?.let { n ->
                Log.d(TAG, "deleteSurroundingText($n): ${ic.deleteSurroundingText(n, 0)}")
            }
            if (intent.getStringExtra("enter") != null) {
                val info = currentInputEditorInfo
                val action = (info?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val ok = if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED &&
                    (info?.imeOptions ?: 0) and EditorInfo.IME_FLAG_NO_ENTER_ACTION == 0) {
                    ic.performEditorAction(action)
                } else {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)) &&
                        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
                }
                Log.d(TAG, "enter (action $action): $ok")
            }
            describe("after the command")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "created")
        if (BuildConfigDebug.debuggable(this)) {
            val filter = IntentFilter(ACTION_TEST)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val type = attribute.inputType
        val klass = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> "text"
            InputType.TYPE_CLASS_NUMBER -> "number"
            InputType.TYPE_CLASS_PHONE -> "phone"
            InputType.TYPE_CLASS_DATETIME -> "datetime"
            else -> "none"
        }
        val variation = when (type and InputType.TYPE_MASK_VARIATION) {
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> "password"
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> "email"
            InputType.TYPE_TEXT_VARIATION_URI -> "url"
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT -> "web"
            else -> (type and InputType.TYPE_MASK_VARIATION).toString()
        }
        val multiline = type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        val action = attribute.imeOptions and EditorInfo.IME_MASK_ACTION
        Log.d(TAG, "onStartInput restarting=$restarting pkg=${attribute.packageName} class=$klass variation=$variation " +
            "multiline=$multiline action=$action noEnterAction=${attribute.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0} " +
            "hint='${attribute.hintText?.toString()?.take(40)}' label='${attribute.label?.toString()?.take(40)}' fieldId=${attribute.fieldId}")
        describe("at start")
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "onStartInputView restarting=$restarting")
    }

    override fun onFinishInput() {
        Log.d(TAG, "onFinishInput")
        super.onFinishInput()
    }

    override fun onEvaluateInputViewShown(): Boolean {
        Log.d(TAG, "onEvaluateInputViewShown")
        return false
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        Log.d(TAG, "onShowInputRequested flags=$flags")
        return super.onShowInputRequested(flags, configChange)
    }

    private fun describe(moment: String) {
        val ic = currentInputConnection ?: run { Log.d(TAG, "$moment: no input connection"); return }
        val extracted = ic.getExtractedText(ExtractedTextRequest(), 0)
        val before = ic.getTextBeforeCursor(10_000, 0)?.length
        val after = ic.getTextAfterCursor(10_000, 0)?.length
        Log.d(TAG, "$moment: extracted length=${extracted?.text?.length} selection=${extracted?.selectionStart}-${extracted?.selectionEnd} before=$before after=$after")
    }

    companion object {
        private const val TAG = "BandKeyboardIme"
        const val ACTION_TEST = BridgeProtocol.APP_PACKAGE + ".IME_TEST"
    }
}
