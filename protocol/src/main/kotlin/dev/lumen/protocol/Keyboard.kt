package dev.lumen.protocol

import org.json.JSONObject

/**
 * phone → glasses, on [Link.KEYBOARD]: the companion's keyboard typing into the glasses'
 * focused field. [OPEN] while its screen shows (again every [HEARTBEAT_MS]: the glasses forget
 * a keyboard they stop hearing from after [EXPIRY_MS]), [TEXT] the field's whole text after every
 * change ([seq] grows, so a late one never overwrites a newer), [ENTER] the keyboard's Enter,
 * [CLOSE] when the screen goes.
 */
data class KeyboardCommand(val action: String, val text: String = "", val seq: Long = 0) {
    fun toJson(): JSONObject = Link.message().put("action", action).put("text", text).put("seq", seq)

    companion object {
        const val OPEN = "open"
        const val CLOSE = "close"
        const val TEXT = "text"
        const val ENTER = "enter"

        const val HEARTBEAT_MS = 30_000L
        const val EXPIRY_MS = 75_000L

        @JvmStatic
        fun from(json: JSONObject) = KeyboardCommand(json.optString("action"), json.optString("text"), json.optLong("seq"))
    }
}

/**
 * glasses → phone, on [Link.KEYBOARD_FIELD]: the text field focused on the glasses (in any app:
 * Lumen is their input method), or none ([focused] false). [reason]: [FOCUS] a field just got
 * focus (or the phone's keyboard opened), [SYNC] its text changed on the glasses (the app after
 * an Enter, dictation, the band's writing), [ASK] the wearer chose the phone's keyboard on the
 * glasses (the phone offers to open it), [BLUR] no field. [type] is the field's (text, email,
 * url, tel, number, password). [value] is the field's text while the phone's keyboard is open or
 * asked for, "" otherwise.
 */
data class KeyboardField(
    val focused: Boolean,
    val app: String = "",
    val label: String = "",
    val type: String = "text",
    val multiline: Boolean = false,
    val value: String = "",
    val reason: String = BLUR,
) {
    fun toJson(): JSONObject = Link.message()
        .put("focused", focused).put("app", app).put("label", label).put("type", type)
        .put("multiline", multiline).put("value", value).put("reason", reason)

    companion object {
        const val FOCUS = "focus"
        const val SYNC = "sync"
        const val ASK = "ask"
        const val BLUR = "blur"

        @JvmStatic
        fun from(json: JSONObject) = KeyboardField(
            json.optBoolean("focused"), json.optString("app"), json.optString("label"),
            json.optString("type", "text"), json.optBoolean("multiline"), json.optString("value"),
            json.optString("reason", BLUR),
        )
    }
}
