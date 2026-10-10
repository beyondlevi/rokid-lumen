package dev.lumen.glasses

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import dev.lumen.band.Phase
import dev.lumen.protocol.KeyboardField
import org.json.JSONObject

/**
 * Lumen as the glasses' input method, in every app (web apps on GeckoView or the system
 * WebView, native apps): one place for the three ways Lumen types, dictation (the glasses'
 * microphone, transcribed by the phone), the band's handwriting and the phone companion's
 * keyboard ([PhoneKeyboard]). It replaced the web shim's typing, which only reached Lumen's own
 * pages; [KeyboardDefault] makes it the default.
 *
 * A focused field alone opens nothing but the hint pill under the web app's square ([KeyboardPanel]).
 * The band's index tap on it opens the panel ([onBandCommand], ahead of the app: the accessibility
 * service asks here first): choose Dictate, Write or Phone (a password: Write or Phone, its text
 * masked), and with text in the field its Enter action. Swipes move the choice, the index tap
 * starts, the middle tap cancels or finishes. What is dictated or written goes into the field as
 * it comes, through the input connection, as any keyboard types.
 *
 * The window reports no insets ([onComputeInsets]): apps are neither resized nor panned under
 * it. It logs the kind of field and the length of its text, never the text.
 */
class LumenKeyboard : InputMethodService(), PhoneKeyboard.Target {
    private enum class Mode { CLOSED, CHOOSING, DICTATING, WRITING, PHONE }

    private val main = Handler(Looper.getMainLooper())
    private var view: KeyboardPanel? = null
    /** The focused text field; null when the input isn't a text field. */
    private var field: FocusedField? = null
    /** The field's text as last read or written, and its selection. */
    private var text = ""
    private var selStart = 0
    private var selEnd = 0
    /** The window shows (the hint or the panel). */
    private var shown = false
    private var mode = Mode.CLOSED
    private var choices: List<KeyboardChoice> = emptyList()
    /** The focused choice: an index of [choices], or `choices.size` for the action button. */
    private var selected = 0
    private var status = ""
    // Dictating: the field's text when it began plus what was said, and what's being said now.
    private var buffer = ""
    private var partial = ""
    /** Finishing, waiting for the last phrase; a second middle tap closes at once. */
    private var closing = false
    // Writing: when the band last wrote, and whether it wrote anything yet.
    private val handwriting = BandRuntime.HandwritingListener { onHandwriting(it) }
    private var lastWriting = 0L
    private var wrote = false
    private val idleCheck = Runnable { checkIdle() }
    /** Our own edit is going in: the selection updates it causes don't need a read back. */
    private var editing = false
    private val reread = Runnable {
        readField()
        render()
    }
    private val syncPhone = Runnable { reportField(KeyboardField.SYNC) }
    /** A panel opened while the window was hidden that still doesn't show: it mustn't take the band unseen. */
    private val showCheck = Runnable {
        if (!shown && mode != Mode.CLOSED) {
            Log.w(TAG, "the window didn't show for the panel: closed")
            closeNow()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Nothing of the window shows but the pill or the panel: black is see-through on the HUD anyway.
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        Log.d(TAG, "created")
    }

    override fun onDestroy() {
        closeNow()
        PhoneKeyboard.detach(this)
        main.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View = KeyboardPanel(this) { onTap(it) }.also { view = it }.root

    // The glasses' display is small and landscape-free: never the full-screen editor.
    override fun onEvaluateFullscreenMode() = false

    // The glasses report keys (the touchpad): Android would keep the window for a "hardware keyboard".
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return field != null
    }

    override fun onShowInputRequested(flags: Int, configChange: Boolean) = field != null

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val next = FocusedField.of(
            attribute.inputType, attribute.imeOptions, attribute.actionId, attribute.actionLabel,
            attribute.hintText, attribute.label, attribute.fieldId, attribute.packageName,
        )
        val same = next != null && next.key == field?.key
        if (!same) closeNow()
        field = next
        if (next == null) {
            if (!same) Log.d(TAG, "no text field (pkg=${attribute.packageName})")
            PhoneKeyboard.detach(this)
            if (shown) requestHideSelf(0)
            render()
            return
        }
        readField()
        if (!same) Log.d(TAG, "field ${next.describe()} restarting=$restarting length=${text.length}")
        PhoneKeyboard.attach(this)
        reportField(KeyboardField.FOCUS)
        // A field of Lumen's own pages shows its hint even when the page didn't ask for a keyboard
        // (a field focused by a script): activating it opens the panel there anyway.
        if (!shown && next.packageName == packageName) requestShowSelf(0)
        render()
    }

    override fun onFinishInput() {
        closeNow()
        if (field != null) Log.d(TAG, "field left")
        field = null
        PhoneKeyboard.detach(this)
        main.removeCallbacks(reread)
        main.removeCallbacks(syncPhone)
        super.onFinishInput()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        shown = true
        render()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        shown = false
        // The app hid the keyboard (its field went): the panel goes with it.
        closeNow()
        super.onFinishInputView(finishingInput)
    }

    override fun onWindowHidden() {
        shown = false
        closeNow()
        super.onWindowHidden()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = minOf(newSelStart, newSelEnd).coerceAtLeast(0)
        selEnd = maxOf(newSelStart, newSelEnd).coerceAtLeast(0)
        // The app may have changed the text itself (a sent message clears its box): read it again.
        if (!editing && shown) {
            main.removeCallbacks(reread)
            main.postDelayed(reread, REREAD_MS)
        }
    }

    /**
     * No insets: the app keeps its whole window under the hint and the panel (WebAppActivity pans
     * otherwise, and a native app would resize). Taps reach the window only where it draws.
     */
    override fun onComputeInsets(outInsets: Insets) {
        val decor = window?.window?.decorView
        val height = decor?.height ?: 0
        outInsets.contentTopInsets = height
        outInsets.visibleTopInsets = height
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        val panel = view
        if (panel == null) outInsets.touchableRegion.setEmpty() else panel.touchable(outInsets.touchableRegion)
    }

    /** The touchpad's keys come here before the app: they drive the open panel as the band does. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val command = commandOf(keyCode) ?: return super.onKeyDown(keyCode, event)
        val route = route(command, bannerUp = false)
        if (route == KeyboardRoute.PASS) {
            // Back with the panel closed is the app's (Android would only hide the hint).
            return if (keyCode == KeyEvent.KEYCODE_BACK) false else super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val command = commandOf(keyCode) ?: return super.onKeyUp(keyCode, event)
        val route = route(command, bannerUp = false)
        if (route == KeyboardRoute.PASS) return if (keyCode == KeyEvent.KEYCODE_BACK) false else super.onKeyUp(keyCode, event)
        take(route, command)
        return true
    }

    private fun commandOf(keyCode: Int): String? = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> BandCommand.ACTIVATE
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> BandCommand.BACK
        KeyEvent.KEYCODE_DPAD_LEFT -> BandCommand.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> BandCommand.RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> BandCommand.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> BandCommand.DOWN
        else -> null
    }

    private fun route(command: String, bannerUp: Boolean): KeyboardRoute {
        val field = field
        return KeyboardRoute.of(mode != Mode.CLOSED, field != null, shown, field?.packageName == packageName, bannerUp, command)
    }

    /** A band command, before the app (and with the panel open, before the banner); true when taken. */
    private fun onBand(command: String, bannerUp: Boolean): Boolean {
        val route = route(command, bannerUp)
        if (route == KeyboardRoute.PASS) return false
        Log.d(TAG, "band $command → ${route.name.lowercase()} (${mode.name.lowercase()})")
        take(route, command)
        return true
    }

    private fun take(route: KeyboardRoute, command: String) {
        when (route) {
            KeyboardRoute.OPEN -> openPanel()
            KeyboardRoute.PANEL -> onPanelCommand(command)
            KeyboardRoute.PASS -> Unit
        }
    }

    /** An air mouse tap on a choice or the action button. */
    private fun onTap(index: Int) {
        if (mode != Mode.CHOOSING) return
        selected = index.coerceIn(0, lastIndex())
        choose()
    }

    // ---- The panel ----

    private fun openPanel() {
        val field = field ?: return
        readField()
        choices = KeyboardChoice.forField(field.kind)
        mode = Mode.CHOOSING
        status = ""
        selected = if (hasAction()) choices.size else choices.indexOf(lastChoice(field)).coerceAtLeast(0)
        // A field of Lumen's own pages may not have asked for the keyboard (see onStartInput).
        if (!shown) {
            requestShowSelf(0)
            main.removeCallbacks(showCheck)
            main.postDelayed(showCheck, SHOW_WAIT_MS)
        }
        Log.d(TAG, "panel opens (${field.describe()}, ${text.length} chars)")
        render()
    }

    /** The field has text and an Enter to offer: the action button shows (and comes focused). */
    private fun hasAction(): Boolean = text.isNotEmpty() && field?.action?.let { it != EnterAction.NONE } == true

    private fun lastIndex() = if (hasAction()) choices.size else choices.size - 1

    private fun onPanelCommand(command: String) {
        when (mode) {
            Mode.CHOOSING -> when (command) {
                BandCommand.RIGHT -> if (selected < choices.size - 1) selected++
                BandCommand.LEFT -> if (selected in 1 until choices.size) selected--
                BandCommand.FORWARD -> if (selected < lastIndex()) selected++
                BandCommand.BACKWARD -> if (selected > 0) selected--
                BandCommand.DOWN -> if (hasAction()) selected = choices.size
                BandCommand.UP -> if (selected == choices.size) selected = choices.indexOf(field?.let { lastChoice(it) }).coerceAtLeast(0)
                BandCommand.ACTIVATE -> return choose()
                BandCommand.BACK -> return closePanel()
            }
            Mode.DICTATING -> when (command) {
                BandCommand.ACTIVATE -> if (Dictation.isListening()) {
                    Dictation.stop()
                    status = getString(R.string.composer_paused)
                } else {
                    listen()
                }
                BandCommand.LEFT -> {
                    buffer = buffer.substringBeforeLast(' ', "").trim()
                    putText(buffer)
                }
                BandCommand.BACK -> return if (closing) closePanel() else finishDictation()
            }
            // Only the middle tap reaches here while the band writes (the touchpad's Back too).
            Mode.WRITING -> if (command == BandCommand.BACK) return closePanel()
            Mode.PHONE -> if (command == BandCommand.BACK) return closePanel()
            Mode.CLOSED -> Unit
        }
        render()
    }

    private fun choose() {
        if (selected == choices.size) return enter()
        when (choices.getOrNull(selected)) {
            KeyboardChoice.DICTATE -> dictate()
            KeyboardChoice.WRITE -> write()
            KeyboardChoice.PHONE -> phone()
            null -> Unit
        }
    }

    /** The field's Enter (its action, or an Enter key); the panel closes. */
    private fun enter() {
        val field = field ?: return
        val ic = currentInputConnection ?: return
        val done = if (field.actionCode != 0) ic.performEditorAction(field.actionCode) else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            true
        }
        Log.d(TAG, "enter (${field.action.name.lowercase()}): $done")
        closePanel()
        // The app may change the field after its action (a search clears the box, a message is sent).
        main.removeCallbacks(syncPhone)
        main.postDelayed({
            readField()
            reportField(KeyboardField.SYNC)
            render()
        }, SYNC_AFTER_ENTER_MS)
    }

    private fun dictate() {
        remember(KeyboardChoice.DICTATE)
        mode = Mode.DICTATING
        buffer = text.trim()
        partial = ""
        closing = false
        status = getString(R.string.composer_speak)
        listen()
        render()
    }

    private fun listen() {
        // On the Rokid glasses the phone listens (Rokid's link, not this app's microphone).
        if (!PhoneDictation.applies(this) && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "no microphone permission")
            status = getString(R.string.composer_allow_mic)
            return render()
        }
        Dictation.start(this, object : Dictation.Listener {
            override fun onStatus(status: String) {
                if (mode != Mode.DICTATING) return
                this@LumenKeyboard.status = status
                render()
            }

            override fun onPartial(text: String) {
                if (mode != Mode.DICTATING) return
                partial = text
                render()
            }

            override fun onPhrase(text: String) {
                if (mode != Mode.DICTATING) return
                buffer = listOf(buffer, text).filter { it.isNotBlank() }.joinToString(" ")
                partial = ""
                putText(buffer)
                render()
            }

            override fun onError(message: String) {
                if (mode != Mode.DICTATING) return
                Log.d(TAG, "dictation: $message")
                status = message
                render()
            }

            override fun onStopped() {
                if (mode != Mode.DICTATING) return
                if (closing) return closePanel()
                if (!Dictation.isListening()) {
                    status = getString(R.string.composer_paused)
                    render()
                }
            }
        })
    }

    /**
     * Finishes dictating: the phone may still be transcribing what was just said (an engine that
     * transcribes after the speech), so the panel waits for it before closing.
     */
    private fun finishDictation() {
        val busy = PhoneDictation.isBusy()
        Dictation.stop()
        if (busy && !closing) {
            closing = true
            status = getString(R.string.composer_finishing)
            Log.d(TAG, "finishing after the last phrase")
            return render()
        }
        closePanel()
    }

    private fun write() {
        if (BandRuntime.phase != Phase.CONNECTED) {
            status = getString(R.string.composer_write_no_band)
            return render()
        }
        BandRuntime.addHandwritingListener(handwriting)
        if (!BandRuntime.setHandwriting(true)) {
            BandRuntime.removeHandwritingListener(handwriting)
            status = getString(R.string.composer_write_no_band)
            return render()
        }
        // The band writes after what the field already holds.
        BandRuntime.resetHandwritingText(text)
        remember(KeyboardChoice.WRITE)
        mode = Mode.WRITING
        status = getString(R.string.composer_write_preparing)
        lastWriting = System.currentTimeMillis()
        wrote = false
        Log.d(TAG, "writing")
        render()
    }

    private fun stopWriting() {
        main.removeCallbacks(idleCheck)
        BandRuntime.removeHandwritingListener(handwriting)
        BandRuntime.setHandwriting(false)
        Log.d(TAG, "writing ends")
    }

    private fun onHandwriting(event: JSONObject) {
        if (mode != Mode.WRITING) return
        when (event.optString("type")) {
            "state" -> when (event.optString("phase")) {
                "ready" -> {
                    status = getString(R.string.composer_write_ready)
                    lastWriting = System.currentTimeMillis()
                    main.removeCallbacks(idleCheck)
                    main.postDelayed(idleCheck, IDLE_CHECK_MS)
                }
                // The band stopped writing on its own (a failure, then its restore).
                "restoring", "finished" -> {
                    val problem = event.optString("problem").takeIf { it.isNotEmpty() && it != "null" }
                    main.removeCallbacks(idleCheck)
                    BandRuntime.removeHandwritingListener(handwriting)
                    mode = Mode.CHOOSING
                    status = if (problem != null) getString(R.string.composer_write_failed, problem) else ""
                }
            }
            "text" -> {
                lastWriting = System.currentTimeMillis()
                wrote = true
                putText(event.optString("text"))
            }
        }
        render()
    }

    /** A pause in the writing finishes, as kinesis does: the text stays, the band goes back. */
    private fun checkIdle() {
        if (mode != Mode.WRITING) return
        val limit = if (wrote) IDLE_MS else FIRST_LETTER_MS
        if (System.currentTimeMillis() - lastWriting >= limit) {
            Log.d(TAG, "writing paused for ${limit / 1000} s: done")
            closePanel()
        } else {
            main.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
    }

    /** The phone's keyboard: the phone hears the field and offers to open its keyboard screen. */
    private fun phone() {
        val field = field ?: return
        remember(KeyboardChoice.PHONE)
        mode = Mode.PHONE
        PhoneKeyboard.ask(this, appName(field), field.label, field.kind.phoneType, field.multiline, text)
        status = getString(if (PhoneLink.applies(this)) R.string.keyboard_phone_status else R.string.dictation_phone_unavailable)
        Log.d(TAG, "the phone's keyboard asked for")
        render()
    }

    /** Back to the hint (the text stays in the field): the middle tap, a pause, the end of a mode. */
    private fun closePanel() {
        closeNow()
        render()
    }

    /** Ends whatever the panel was doing, at once (the field went, the window hid, a second Back). */
    private fun closeNow() {
        if (mode == Mode.CLOSED) return
        if (mode == Mode.DICTATING) Dictation.stop()
        if (mode == Mode.WRITING) stopWriting()
        Log.d(TAG, "panel closes (${mode.name.lowercase()})")
        mode = Mode.CLOSED
        closing = false
        partial = ""
        status = ""
    }

    // ---- The phone's keyboard (PhoneKeyboard.Target) ----

    override fun keyboardOpen(open: Boolean) {
        // Its text comes now that the phone types into it (PhoneKeyboard withholds it otherwise).
        if (open) reportField(KeyboardField.FOCUS)
        // The phone's keyboard screen closed: done typing there.
        if (!open && mode == Mode.PHONE) closePanel()
    }

    override fun keyboardText(text: String) {
        putText(text, fromPhone = true)
        render()
    }

    override fun keyboardEnter() = enter()

    // ---- The field ----

    /** Reads the field's whole text and selection through the input connection. */
    private fun readField() {
        val ic = currentInputConnection ?: return
        val extracted = runCatching {
            ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_TEXT; hintMaxLines = MAX_LINES }, 0)
        }.getOrNull()
        val whole = extracted?.text
        if (whole != null && extracted.startOffset == 0 && whole.length < MAX_TEXT) {
            text = whole.toString()
            val a = extracted.selectionStart.coerceIn(0, text.length)
            val b = extracted.selectionEnd.coerceIn(0, text.length)
            selStart = minOf(a, b)
            selEnd = maxOf(a, b)
            return
        }
        val before = ic.getTextBeforeCursor(MAX_TEXT, 0)?.toString().orEmpty()
        val chosen = ic.getSelectedText(0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(MAX_TEXT, 0)?.toString().orEmpty()
        text = before + chosen + after
        selStart = before.length
        selEnd = before.length + chosen.length
    }

    /**
     * Makes the field's text [next] with the fewest edits ([TextEdit]). The phone's text isn't
     * reported back to it (it would race its own typing); the panel's is, while its keyboard is open.
     */
    private fun putText(next: String, fromPhone: Boolean = false): Boolean {
        val ic = currentInputConnection ?: return false
        val edit = TextEdit.between(text, selStart, selEnd, next) ?: return true
        editing = true
        ic.beginBatchEdit()
        val moved = edit.cursor?.let { ic.setSelection(it, it) } ?: true
        if (moved) {
            if (edit.deleteBefore > 0) ic.deleteSurroundingText(edit.deleteBefore, 0)
            if (edit.insert.isNotEmpty()) ic.commitText(edit.insert, 1)
        } else {
            // The whole text instead, as measured on GeckoView: what's around the cursor goes, then the new text.
            val before = ic.getTextBeforeCursor(MAX_TEXT, 0)?.length ?: 0
            val after = ic.getTextAfterCursor(MAX_TEXT, 0)?.length ?: 0
            ic.deleteSurroundingText(before, after)
            ic.commitText(next, 1)
        }
        ic.endBatchEdit()
        editing = false
        val cursor = if (moved) (edit.cursor ?: selEnd) - edit.deleteBefore + edit.insert.length else next.length
        text = next
        selStart = cursor.coerceIn(0, next.length)
        selEnd = selStart
        Log.d(TAG, "edit -${edit.deleteBefore} +${edit.insert.length} (${next.length} chars)")
        if (!fromPhone && PhoneKeyboard.open) {
            main.removeCallbacks(syncPhone)
            main.postDelayed(syncPhone, SYNC_MS)
        }
        return true
    }

    private fun reportField(reason: String) {
        val field = field ?: return
        PhoneKeyboard.focus(this, appName(field), field.label, field.kind.phoneType, field.multiline, text, reason)
    }

    /** The app the field is in, for the phone: the web app in front for Lumen's own screens. */
    private fun appName(field: FocusedField): String {
        if (field.packageName == packageName) webAppName.takeIf { it.isNotEmpty() }?.let { return it }
        return runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(field.packageName, 0)).toString()
        }.getOrDefault(field.packageName)
    }

    private fun fieldName(field: FocusedField): String = field.label.ifEmpty {
        getString(
            when (field.kind) {
                FieldKind.TEXT -> R.string.keyboard_field_text
                FieldKind.PASSWORD -> R.string.keyboard_field_password
                FieldKind.EMAIL -> R.string.keyboard_field_email
                FieldKind.URL -> R.string.keyboard_field_url
                FieldKind.NUMBER -> R.string.keyboard_field_number
                FieldKind.PHONE -> R.string.keyboard_field_phone
            },
        )
    }

    /** The Enter action's name ("Search"), or null when it has none worth naming. */
    private fun actionName(field: FocusedField): String? = when (field.action) {
        EnterAction.NONE -> null
        EnterAction.CUSTOM -> field.customLabel
        else -> getString(field.action.label)
    }

    // ---- Drawing ----

    private fun render() {
        val view = view ?: return
        val field = field
        if (field == null) return view.render(KeyboardPanel.State(panel = false))
        val secret = field.kind.secret
        val action = actionName(field)
        val locale = resources.configuration.locales[0]
        if (mode == Mode.CLOSED) {
            val named = field.action.named && text.isNotEmpty() && action != null
            val hint = when {
                named -> getString(R.string.keyboard_hint_action, action!!.lowercase(locale))
                secret -> getString(R.string.keyboard_hint_password)
                else -> getString(R.string.keyboard_hint)
            }
            val icon = when {
                named && field.action == EnterAction.SEARCH -> KeyboardPanel.Icon.SEARCH
                named -> KeyboardPanel.Icon.ENTER
                secret -> KeyboardPanel.Icon.PEN
                else -> KeyboardPanel.Icon.MIC
            }
            return view.render(KeyboardPanel.State(panel = false, hint = hint, hintIcon = icon))
        }
        val choosing = mode == Mode.CHOOSING
        val button = choosing && hasAction()
        val statusLine = status.ifEmpty {
            when {
                !choosing -> ""
                secret -> getString(R.string.keyboard_status_password)
                text.isEmpty() -> getString(R.string.keyboard_status_choose)
                else -> getString(R.string.keyboard_status_continue)
            }
        }
        val statusIcon = when (mode) {
            Mode.DICTATING -> KeyboardPanel.Icon.MIC
            Mode.WRITING -> KeyboardPanel.Icon.PEN
            Mode.PHONE -> KeyboardPanel.Icon.PHONE
            else -> if (secret) KeyboardPanel.Icon.LOCK else null
        }
        val panelHint = when (mode) {
            Mode.CHOOSING -> getString(
                when {
                    secret -> R.string.keyboard_password_hint
                    text.isNotEmpty() -> R.string.keyboard_continue_hint
                    else -> R.string.composer_choose_hint
                },
            )
            Mode.DICTATING -> getString(R.string.composer_hint)
            Mode.WRITING -> getString(if (secret) R.string.keyboard_password_writing_hint else R.string.composer_write_hint)
            Mode.PHONE -> getString(R.string.keyboard_phone_hint)
            Mode.CLOSED -> ""
        }
        val shownText = if (secret) MASK.repeat(text.length) else text
        view.render(
            KeyboardPanel.State(
                panel = true,
                status = statusLine,
                statusIcon = statusIcon,
                fieldIcon = when {
                    secret -> KeyboardPanel.Icon.LOCK
                    field.action == EnterAction.SEARCH -> KeyboardPanel.Icon.SEARCH
                    else -> null
                },
                fieldLabel = fieldName(field),
                // The action button names it already.
                fieldAction = if (!button && field.action.named && action != null) getString(R.string.keyboard_enter_label, action.lowercase(locale)) else "",
                text = tail(shownText),
                partial = if (mode == Mode.DICTATING) partial else "",
                choices = if (choosing) choices.map { iconOf(it) to getString(it.label) } else emptyList(),
                action = if (button) action ?: getString(R.string.keyboard_action_enter) else null,
                actionIcon = if (field.action == EnterAction.SEARCH) KeyboardPanel.Icon.SEARCH else KeyboardPanel.Icon.ENTER,
                selected = if (choosing) selected else -1,
                panelHint = panelHint,
            ),
        )
    }

    private fun iconOf(choice: KeyboardChoice) = when (choice) {
        KeyboardChoice.DICTATE -> KeyboardPanel.Icon.MIC
        KeyboardChoice.WRITE -> KeyboardPanel.Icon.PEN
        KeyboardChoice.PHONE -> KeyboardPanel.Icon.PHONE
    }

    /** The end of a long text, where the typing is: the box shows a few lines. */
    private fun tail(value: String) = if (value.length <= SHOWN_CHARS) value else "…" + value.takeLast(SHOWN_CHARS)

    // ---- The last choice used, preselected next time ----

    private fun remember(choice: KeyboardChoice) {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, choice.name.lowercase()).apply()
    }

    private fun lastChoice(field: FocusedField): KeyboardChoice {
        val stored = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        val choice = KeyboardChoice.entries.firstOrNull { it.name.lowercase() == stored } ?: KeyboardChoice.DICTATE
        return if (choice in KeyboardChoice.forField(field.kind)) choice else KeyboardChoice.WRITE
    }

    companion object {
        private const val TAG = "BandKeyboardIme"
        private const val MAX_TEXT = 10_000
        private const val MAX_LINES = 1_000
        private const val SHOWN_CHARS = 160
        private const val MASK = "•"
        private const val REREAD_MS = 150L
        private const val SYNC_MS = 300L
        private const val SYNC_AFTER_ENTER_MS = 500L
        private const val SHOW_WAIT_MS = 1_500L
        /** Writing that pauses this long finishes (kinesis waits 12 s; letters here take longer). */
        private const val IDLE_MS = 15_000L
        /** Before the first letter: time to read the panel and start. */
        private const val FIRST_LETTER_MS = 30_000L
        private const val IDLE_CHECK_MS = 1_000L
        /** The composer's preference, kept: the last way of typing used. */
        private const val PREFS = "lumen_composer"
        private const val KEY_MODE = "mode"

        private var instance: LumenKeyboard? = null

        /** The web app in front's name, for the phone ("Field in focus: Message · WhatsApp"). */
        @JvmStatic
        var webAppName = ""

        /**
         * A band command, from the accessibility service before anything else takes it: the open
         * panel takes it (before the banner too), and the index tap on a focused field opens the
         * panel ([KeyboardRoute]). [bannerUp]: a notification banner shows. False: not taken.
         */
        @JvmStatic
        fun onBandCommand(command: String, bannerUp: Boolean): Boolean = instance?.onBand(command, bannerUp) ?: false
    }
}
