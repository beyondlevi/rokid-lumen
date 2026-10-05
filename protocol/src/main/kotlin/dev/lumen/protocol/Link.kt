package dev.lumen.protocol

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The messages between the glasses app and the phone companion, in one place for both. They
 * travel over Rokid's own link (CXR-S on the glasses, CXR-L on the phone, through Hi Rokid) as
 * one JSON string per Caps; this module is the format, each app keeps its transport.
 *
 * Every message is an envelope: `v` (the protocol version), and for requests that expect an
 * answer an `id`, echoed as `re` by the answer. Messages from before the envelope (no `v`) are
 * version 0 and read the same way, so an old companion and a new glasses app still talk.
 */
object Link {
    /** 1: the envelope. Bump when a message changes meaning, not when one is added. */
    const val VERSION = 1

    /** glasses → phone {action: hello|start|stop} ([DictationCommand]). */
    const val DICTATION = "nb.dictation"

    /** phone → glasses {type: ready|status|listening|partial|phrase|stopped|error, text} ([DictationEvent]). */
    const val DICTATION_EVENT = "nb.dictation.event"

    /** glasses → phone {action: sync} ([NotifyCommand]). */
    const val NOTIFY = "nb.notify"

    /** phone → glasses {type: post|remove|reset, …} ([NotifyEvent]). */
    const val NOTIFY_EVENT = "nb.notify.event"

    /** glasses → phone {action: up|down} ([NetCommand]). */
    const val NET = "nb.net"

    /** phone → glasses {type: ready|failed|down, …} ([NetEvent]). */
    const val NET_EVENT = "nb.net.event"

    /** phone → glasses {seq, of} + a binary payload: the link benchmark (debug builds). */
    const val BENCH = "nb.bench"

    /** phone → glasses: the glasses' settings, edited from the phone ([SettingsOps]). */
    const val SETTINGS = "nb.settings"

    /** glasses → phone: the settings' schema, results and the band's status ([SettingsEvent]). */
    const val SETTINGS_EVENT = "nb.settings.event"

    /** phone → glasses: the apps grid, arranged from the phone ([GridOps]). */
    const val GRID = "nb.grid"

    /** glasses → phone: the grid's state, results and icons ([GridEvent]). */
    const val GRID_EVENT = "nb.grid.event"

    /** glasses → phone: recording and transcription for web apps ([AudioOps]). */
    const val AUDIO = "nb.audio"

    /** phone → glasses: their progress, files and text ([AudioOps]). */
    const val AUDIO_EVENT = "nb.audio.event"

    /** phone → glasses: the phone's own state, its battery ([PhoneEvent]). */
    const val PHONE_EVENT = "nb.phone.event"

    /** phone → glasses: a request for the glasses' logs, and acks ([LogsOps]). */
    const val LOGS = "nb.logs"

    /** glasses → phone: the logs, in chunks ([LogsOps]). */
    const val LOGS_EVENT = "nb.logs.event"

    /** phone → glasses: the companion's keyboard, typing into a web app's field ([KeyboardCommand]). */
    const val KEYBOARD = "nb.keyboard"

    /** glasses → phone: the web app's focused text field, or none ([KeyboardField]). */
    const val KEYBOARD_FIELD = "nb.keyboard.field"

    /**
     * phone → glasses: the band's key, a request `{id, bundle}` with the `air-gestures-band`
     * export as text (see dev.lumen.band.Identity); the answer is a [SettingsEvent.Result] with
     * subject "band_key" on [SETTINGS_EVENT]. Never logged.
     */
    const val BAND_KEY = "nb.band.key"

    /** What the glasses subscribe to (phone → glasses). */
    val TO_GLASSES = listOf(DICTATION_EVENT, NOTIFY_EVENT, NET_EVENT, BENCH, SETTINGS, GRID, AUDIO_EVENT, PHONE_EVENT, LOGS, KEYBOARD, BAND_KEY)

    /** What the phone handles (glasses → phone). */
    val TO_PHONE = listOf(DICTATION, NOTIFY, NET, SETTINGS_EVENT, GRID_EVENT, AUDIO, LOGS_EVENT, KEYBOARD_FIELD)

    private val ids = AtomicLong(System.currentTimeMillis())

    /** A fresh message: the envelope's version set. */
    @JvmStatic
    fun message(): JSONObject = JSONObject().put("v", VERSION)

    /** A request that expects an answer: a new `id`. */
    @JvmStatic
    fun request(): JSONObject = message().put("id", ids.incrementAndGet())

    /** The answer to [request]: its `id` as `re`. */
    @JvmStatic
    fun reply(request: JSONObject): JSONObject = message().put("re", request.optLong("id"))

    /** The protocol version of [json]: 0 for messages from before the envelope. */
    @JvmStatic
    fun version(json: JSONObject): Int = json.optInt("v", 0)

    /** Parses a message body, never throwing: bad or empty input is an empty message. */
    @JvmStatic
    fun parse(text: String?): JSONObject =
        runCatching { JSONObject(text ?: "{}") }.getOrDefault(JSONObject())
}
