package dev.lumen.glasses

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * The decisions behind Lumen's input method ([LumenKeyboard]), kept apart from Android so they
 * are unit-tested: what kind of field Android describes, what its Enter does, how to turn the
 * field's text into another with the fewest edits, and who takes a band command.
 */

/** What a field is for: the panel's choices and the phone's keyboard follow it. */
enum class FieldKind(val phoneType: String) {
    TEXT("text"), PASSWORD("password"), EMAIL("email"), URL("url"), NUMBER("number"), PHONE("tel");

    /** Passwords are never dictated (spoken aloud) and never shown. */
    val secret: Boolean get() = this == PASSWORD
}

/**
 * The field's Enter, as its editor asks for it. [NONE]: Enter is a new line (a multi-line
 * field), so there is no action to offer; [ENTER]: a plain Enter key (a single-line field that
 * names no action); [CUSTOM]: the app's own label ([FocusedField.customLabel]).
 */
enum class EnterAction(val label: Int) {
    NONE(0),
    ENTER(R.string.keyboard_action_enter),
    SEARCH(R.string.keyboard_action_search),
    SEND(R.string.keyboard_action_send),
    GO(R.string.keyboard_action_go),
    NEXT(R.string.keyboard_action_next),
    PREVIOUS(R.string.keyboard_action_previous),
    DONE(R.string.keyboard_action_done),
    CUSTOM(0);

    /** A named action, worth a button and "Enter: …" ([ENTER] and [NONE] aren't). */
    val named: Boolean get() = this != NONE && this != ENTER
}

/**
 * The focused field as its editor describes it ([EditorInfo]). [label] is the field's hint or
 * label ("" when it has none), [actionCode] what [android.view.inputmethod.InputConnection.performEditorAction]
 * gets (0: send an Enter key instead), [key] tells one field from another in the same app.
 * Never its text.
 */
data class FocusedField(
    val kind: FieldKind,
    val multiline: Boolean,
    val action: EnterAction,
    val actionCode: Int,
    val customLabel: String,
    val label: String,
    val packageName: String,
    val key: String,
) {
    /** For the log: the kind of field, never its text or label. */
    fun describe(): String = "${kind.name.lowercase()}${if (multiline) " multiline" else ""} enter=${action.name.lowercase()} pkg=$packageName"

    companion object {
        /** The field Android describes, or null for no text field (a view that takes no text). */
        fun of(
            inputType: Int,
            imeOptions: Int,
            actionId: Int,
            actionLabel: CharSequence?,
            hint: CharSequence?,
            label: CharSequence?,
            fieldId: Int,
            packageName: String?,
        ): FocusedField? {
            val klass = inputType and InputType.TYPE_MASK_CLASS
            if (klass == InputType.TYPE_NULL) return null
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val kind = when (klass) {
                InputType.TYPE_CLASS_NUMBER -> if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) FieldKind.PASSWORD else FieldKind.NUMBER
                InputType.TYPE_CLASS_PHONE -> FieldKind.PHONE
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD -> FieldKind.PASSWORD
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS -> FieldKind.EMAIL
                    InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                    else -> FieldKind.TEXT
                }
                else -> FieldKind.TEXT
            }
            val multiline = klass == InputType.TYPE_CLASS_TEXT &&
                inputType and (InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0
            val code = imeOptions and EditorInfo.IME_MASK_ACTION
            val custom = actionLabel?.toString()?.trim().orEmpty()
            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val action = when {
                custom.isNotEmpty() -> EnterAction.CUSTOM
                noEnterAction -> if (multiline) EnterAction.NONE else EnterAction.ENTER
                code == EditorInfo.IME_ACTION_SEARCH -> EnterAction.SEARCH
                code == EditorInfo.IME_ACTION_SEND -> EnterAction.SEND
                code == EditorInfo.IME_ACTION_GO -> EnterAction.GO
                code == EditorInfo.IME_ACTION_NEXT -> EnterAction.NEXT
                code == EditorInfo.IME_ACTION_PREVIOUS -> EnterAction.PREVIOUS
                code == EditorInfo.IME_ACTION_DONE -> EnterAction.DONE
                else -> if (multiline) EnterAction.NONE else EnterAction.ENTER
            }
            val actionCode = when (action) {
                EnterAction.NONE, EnterAction.ENTER -> 0
                EnterAction.CUSTOM -> if (actionId != 0) actionId else code
                else -> code
            }
            val name = (hint?.toString()?.trim().orEmpty()).ifEmpty { label?.toString()?.trim().orEmpty() }
                .replace(Regex("\\s+"), " ").take(MAX_LABEL)
            val pkg = packageName.orEmpty()
            return FocusedField(kind, multiline, action, actionCode, custom, name, pkg, "$pkg/$fieldId/$inputType/$imeOptions/$name")
        }

        private const val MAX_LABEL = 80
    }
}

/**
 * One edit that turns a field's text into another: put the cursor at [cursor] (null: it is
 * there already), delete [deleteBefore] characters before it, then insert [insert]. Only the
 * part that differs is touched, so appending a dictated phrase is one insert at the end.
 */
data class TextEdit(val cursor: Int?, val deleteBefore: Int, val insert: String) {
    companion object {
        /**
         * The edit from [old] (its selection [selStart]..[selEnd]) to [new]; null when they're
         * the same. A surrogate pair is never split.
         */
        fun between(old: String, selStart: Int, selEnd: Int, new: String): TextEdit? {
            if (old == new) return null
            val shorter = minOf(old.length, new.length)
            var prefix = 0
            while (prefix < shorter && old[prefix] == new[prefix]) prefix++
            if (prefix in 1 until old.length && Character.isLowSurrogate(old[prefix])) prefix--
            var suffix = 0
            while (suffix < shorter - prefix && old[old.length - 1 - suffix] == new[new.length - 1 - suffix]) suffix++
            if (suffix > 0 && Character.isLowSurrogate(old[old.length - suffix])) suffix--
            val end = old.length - suffix
            val insert = new.substring(prefix, new.length - suffix)
            val cursor = if (selStart == end && selEnd == end) null else end
            return TextEdit(cursor, end - prefix, insert)
        }
    }
}

/** Who takes a band command while Lumen is the input method. */
enum class KeyboardRoute {
    /** The open panel. */
    PANEL,

    /** The index tap on a focused field: the panel opens, the app gets nothing. */
    OPEN,

    /** Everything else goes on as without a keyboard (the banner, the screen in front, the app). */
    PASS;

    companion object {
        /**
         * [panelOpen]: the panel is up; [field]: a text field has the input; [hintShown]: the
         * keyboard's hint shows for it (the app asked for a keyboard); [ownApp]: the field is in
         * one of Lumen's screens (a web app, where activating a field opens the panel even when
         * the page didn't ask for a keyboard); [bannerUp]: a notification banner shows (its index
         * tap is the banner's). The open panel takes every command but the volume and the
         * brightness steps; a field only takes the index tap.
         */
        @JvmStatic
        fun of(panelOpen: Boolean, field: Boolean, hintShown: Boolean, ownApp: Boolean, bannerUp: Boolean, command: String): KeyboardRoute = when {
            panelOpen -> if (command in PASSING) PASS else PANEL
            bannerUp -> PASS
            field && command == BandCommand.ACTIVATE && (hintShown || ownApp) -> OPEN
            else -> PASS
        }

        private val PASSING = setOf(BandCommand.VOLUME_UP, BandCommand.VOLUME_DOWN, BandCommand.BRIGHTNESS_UP, BandCommand.BRIGHTNESS_DOWN)
    }
}

/** What the panel's choices are for a field: no dictation for a password (it would be said aloud). */
enum class KeyboardChoice(val label: Int) {
    DICTATE(R.string.composer_mode_dictate), WRITE(R.string.composer_mode_write), PHONE(R.string.keyboard_choice_phone);

    companion object {
        @JvmStatic
        fun forField(kind: FieldKind): List<KeyboardChoice> = if (kind.secret) listOf(WRITE, PHONE) else entries
    }
}
