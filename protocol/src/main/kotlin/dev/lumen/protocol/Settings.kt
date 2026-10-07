package dev.lumen.protocol

import org.json.JSONArray
import org.json.JSONObject

/**
 * The glasses' settings, edited from the phone. The glasses describe what they have (a schema
 * of typed settings and actions, with values), the phone draws it and sends changes back: a
 * setting the glasses add later shows up on the phone without a companion update.
 *
 *   phone → glasses   [Link.SETTINGS]        {op: describe} | {op: set, key, value} | {op: action, name}, each a request (id)
 *   glasses → phone   [Link.SETTINGS_EVENT]  {type: schema, settings, actions, status} (the answer to describe, and after a change)
 *                                            {type: result, ok, key|name, error?} (the answer to set and action)
 *                                            {type: status, status} (whenever the band's status changes)
 *                                            {type: debug, debug} (whenever the wireless debugging state changes)
 *                                            {type: self_arm, state, message, armed} (after describe, and as the self-arm goes)
 *
 * Wireless debugging ([DebugStatus]) is set like a setting ([SettingsOps.KEY_WIRELESS_DEBUG],
 * "true"/"false") but isn't in the band's list: the phone shows it apart, with the address.
 *
 * Labels travel in English as a fallback: the phone shows its own translation for a known key
 * and the glasses' label for one it doesn't know yet.
 */
object SettingsOps {
    const val DESCRIBE = "describe"
    const val SET = "set"
    const val ACTION = "action"

    /**
     * The band talks to one device at a time. [ACTION_TO_PHONE]: the glasses let it go and
     * stay off it (a restart included) while the phone uses it; [ACTION_TO_GLASSES]: they take
     * it back (the phone lets go first). The glasses' status says which ([BandStatus.onPhone]).
     */
    const val ACTION_TO_PHONE = "to_phone"
    const val ACTION_TO_GLASSES = "to_glasses"

    /**
     * How long the phone waits, after letting the band go for the glasses ([handOver]), for
     * their status to say they took it. Rokid's link can lose the request: unanswered by then,
     * the phone takes the band back, and the glasses ignore a hand-over (or a report that the
     * band was let go for them, [BandDevices.since]) that reaches them older than this.
     */
    const val HAND_OVER_MS = 10_000L

    /**
     * Runs the self-arm on the glasses, as their Settings row does: it needs their accessibility
     * service on, a Wi-Fi network and USB debugging allowed in Hi Rokid. Its progress comes back
     * as [SettingsEvent.SelfArm].
     */
    const val ACTION_SELF_ARM = "self_arm"

    /** Keeps the glasses reachable over Wi-Fi for `adb connect` ([DebugStatus]). */
    const val KEY_WIRELESS_DEBUG = "wireless_debug"

    @JvmStatic
    fun describe(): JSONObject = Link.request().put("op", DESCRIBE)

    @JvmStatic
    fun set(key: String, value: String): JSONObject = Link.request().put("op", SET).put("key", key).put("value", value)

    @JvmStatic
    fun action(name: String): JSONObject = Link.request().put("op", ACTION).put("name", name)

    /** [ACTION_TO_GLASSES] from the phone's switch, stamped (`at`, the phone's clock, ms) for [isLate]. */
    @JvmStatic
    fun handOver(now: Long = System.currentTimeMillis()): JSONObject = action(ACTION_TO_GLASSES).put("at", now)

    /** A stamped hand-over older than [HAND_OVER_MS] at [now]: the phone has taken the band back by then. */
    @JvmStatic
    fun isLate(request: JSONObject, now: Long = System.currentTimeMillis()): Boolean =
        request.has("at") && now - request.optLong("at") > HAND_OVER_MS
}

/**
 * One choice of a [Setting.Kind.CHOICE] setting. Choices with a [group] are listed under it
 * (a long list of gesture actions); a phone too old to know groups lists them all the same.
 */
data class SettingOption(val id: String, val label: String, val group: String = "") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).apply { if (group.isNotEmpty()) put("group", group) }

    companion object {
        fun from(json: JSONObject) = SettingOption(json.optString("id"), json.optString("label"), json.optString("group"))
    }
}

/**
 * A setting: a [Kind.CHOICE] among [options], an on/off [Kind.TOGGLE] ("true"/"false") or a
 * number on a [Kind.RANGE] from [min] to [max] in [step]s (a slider). A setting with
 * [visibleWhen] shows only while that other setting has that value.
 */
data class Setting(
    val key: String,
    val kind: Kind,
    val label: String,
    val value: String,
    val options: List<SettingOption> = emptyList(),
    val section: String = "",
    val visibleWhen: Pair<String, String>? = null,
    val min: Double = 0.0,
    val max: Double = 0.0,
    val step: Double = 0.0,
) {
    enum class Kind(val id: String) {
        CHOICE("choice"), TOGGLE("toggle"), RANGE("range");

        companion object {
            fun of(id: String) = entries.firstOrNull { it.id == id } ?: CHOICE
        }
    }

    val checked: Boolean get() = value == "true"

    /** A [Kind.RANGE]'s value, kept within its range. */
    val number: Double get() = (value.toDoubleOrNull() ?: min).coerceIn(min, maxOf(min, max))

    fun isVisible(all: List<Setting>): Boolean {
        val (key, value) = visibleWhen ?: return true
        return all.firstOrNull { it.key == key }?.value == value
    }

    fun toJson(): JSONObject = JSONObject().put("key", key).put("kind", kind.id).put("label", label).put("value", value)
        .put("section", section)
        .put("options", JSONArray().apply { options.forEach { put(it.toJson()) } })
        .apply { visibleWhen?.let { put("visibleWhen", JSONObject().put("key", it.first).put("value", it.second)) } }
        .apply { if (kind == Kind.RANGE) put("min", min).put("max", max).put("step", step) }

    companion object {
        fun from(json: JSONObject): Setting {
            val options = json.optJSONArray("options")
            val visible = json.optJSONObject("visibleWhen")
            return Setting(
                key = json.optString("key"),
                kind = Kind.of(json.optString("kind")),
                label = json.optString("label"),
                value = json.optString("value"),
                options = (0 until (options?.length() ?: 0)).map { SettingOption.from(options!!.getJSONObject(it)) },
                section = json.optString("section"),
                visibleWhen = visible?.let { it.optString("key") to it.optString("value") },
                min = json.optDouble("min", 0.0),
                max = json.optDouble("max", 0.0),
                step = json.optDouble("step", 0.0),
            )
        }
    }
}

/** Something to do rather than a value to set (reconnect, forget); [destructive] asks first. */
data class SettingsAction(val name: String, val label: String, val destructive: Boolean = false) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("label", label).put("destructive", destructive)

    companion object {
        fun from(json: JSONObject) = SettingsAction(json.optString("name"), json.optString("label"), json.optBoolean("destructive"))
    }
}

/** The band as the glasses see it; [battery] is -1 until the band reports it. */
data class BandStatus(
    val phase: String = PHASE_STOPPED,
    val name: String = "",
    val battery: Int = -1,
    val charging: Boolean = false,
    val paused: Boolean = false,
    /** The band is with the phone ([SettingsOps.ACTION_TO_PHONE]): the glasses leave it alone. */
    val onPhone: Boolean = false,
    /** The glasses hold the band's key (null from glasses too old to say). */
    val hasKey: Boolean? = null,
) {
    val connected: Boolean get() = phase == PHASE_CONNECTED

    fun toJson(): JSONObject = JSONObject().put("phase", phase).put("name", name).put("battery", battery)
        .put("charging", charging).put("paused", paused).put("on_phone", onPhone)
        .apply { hasKey?.let { put("has_key", it) } }

    companion object {
        const val PHASE_STOPPED = "stopped"
        const val PHASE_SEARCHING = "searching"
        const val PHASE_CONNECTING = "connecting"
        const val PHASE_CONNECTED = "connected"

        fun from(json: JSONObject?) = if (json == null) BandStatus() else BandStatus(
            json.optString("phase", PHASE_STOPPED),
            json.optString("name"),
            json.optInt("battery", -1),
            json.optBoolean("charging"),
            json.optBoolean("paused"),
            json.optBoolean("on_phone"),
            if (json.has("has_key")) json.optBoolean("has_key") else null,
        )
    }
}

/**
 * Wireless debugging on the glasses: while [enabled] they keep their Wi-Fi on and awake and
 * report where adb finds them. [address] is their Wi-Fi IPv4 ("" without one), [listening] whether
 * adb answers on [port] there, [onPhoneHotspot] whether that Wi-Fi is the phone's hotspot (which a
 * computer can't reach).
 */
data class DebugStatus(
    val enabled: Boolean = false,
    val wifiOn: Boolean = false,
    val ssid: String = "",
    val address: String = "",
    val port: Int = DEFAULT_PORT,
    val listening: Boolean = false,
    val onPhoneHotspot: Boolean = false,
) {
    /** Reachable from a computer on the same network. */
    val ready: Boolean get() = enabled && address.isNotEmpty() && listening && !onPhoneHotspot

    val command: String get() = "adb connect $address:$port"

    fun toJson(): JSONObject = JSONObject().put("enabled", enabled).put("wifi_on", wifiOn).put("ssid", ssid)
        .put("address", address).put("port", port).put("listening", listening).put("on_phone_hotspot", onPhoneHotspot)

    companion object {
        const val DEFAULT_PORT = 5555

        fun from(json: JSONObject?) = if (json == null) DebugStatus() else DebugStatus(
            json.optBoolean("enabled"),
            json.optBoolean("wifi_on"),
            json.optString("ssid"),
            json.optString("address"),
            json.optInt("port", DEFAULT_PORT),
            json.optBoolean("listening"),
            json.optBoolean("on_phone_hotspot"),
        )
    }
}

/** What arrives on [Link.SETTINGS_EVENT]. */
sealed class SettingsEvent {
    data class Schema(
        val settings: List<Setting>,
        val actions: List<SettingsAction>,
        val status: BandStatus,
        val debug: DebugStatus = DebugStatus(),
        /** The glasses app's versionName ("" from an app older than the updater). */
        val appVersion: String = "",
    ) : SettingsEvent() {
        fun toJson(request: JSONObject? = null): JSONObject = (request?.let { Link.reply(it) } ?: Link.message()).put("type", "schema")
            .put("settings", JSONArray().apply { settings.forEach { put(it.toJson()) } })
            .put("actions", JSONArray().apply { actions.forEach { put(it.toJson()) } })
            .put("status", status.toJson())
            .put("debug", debug.toJson())
            .put("app_version", appVersion)
    }

    data class Debug(val debug: DebugStatus) : SettingsEvent() {
        fun toJson(): JSONObject = Link.message().put("type", "debug").put("debug", debug.toJson())
    }

    /** The answer to a set or an action; [error] explains a refusal. */
    data class Result(val ok: Boolean, val subject: String, val error: String = "") : SettingsEvent() {
        fun toJson(request: JSONObject): JSONObject = Link.reply(request).put("type", "result").put("ok", ok)
            .put("subject", subject).put("error", error)
    }

    /**
     * Where the self-arm stands: [state] is the glasses' step or outcome (`requested`,
     * `usb_debugging_off`, `wireless_bootstrap_complete`...), [message] its text in the glasses'
     * language, [armed] whether it's done (the app holds WRITE_SECURE_SETTINGS).
     */
    data class SelfArm(val state: String, val message: String, val armed: Boolean) : SettingsEvent() {
        fun toJson(): JSONObject = Link.message().put("type", "self_arm").put("state", state).put("message", message).put("armed", armed)
    }

    data class Status(val status: BandStatus) : SettingsEvent() {
        fun toJson(): JSONObject = Link.message().put("type", "status").put("status", status.toJson())
    }

    /**
     * The glasses' Controls moved the band: to [target] ([BandDevices.PHONE] or
     * [BandDevices.COMPUTER], [computer] its address) with [profile] (an id there, "" for the one
     * in use). The glasses let the band go themselves; back to the glasses needs no message (their
     * status says it).
     */
    data class BandTarget(val target: String, val computer: String = "", val profile: String = "") : SettingsEvent() {
        fun toJson(): JSONObject = Link.message().put("type", "band_target").put("target", target).put("computer", computer)
            .put("profile", profile)
    }

    companion object {
        @JvmStatic
        fun from(json: JSONObject): SettingsEvent? = when (json.optString("type")) {
            "schema" -> {
                val settings = json.optJSONArray("settings")
                val actions = json.optJSONArray("actions")
                Schema(
                    (0 until (settings?.length() ?: 0)).map { Setting.from(settings!!.getJSONObject(it)) },
                    (0 until (actions?.length() ?: 0)).map { SettingsAction.from(actions!!.getJSONObject(it)) },
                    BandStatus.from(json.optJSONObject("status")),
                    DebugStatus.from(json.optJSONObject("debug")),
                    json.optString("app_version"),
                )
            }
            "debug" -> Debug(DebugStatus.from(json.optJSONObject("debug")))
            "result" -> Result(json.optBoolean("ok"), json.optString("subject"), json.optString("error"))
            "status" -> Status(BandStatus.from(json.optJSONObject("status")))
            "self_arm" -> SelfArm(json.optString("state"), json.optString("message"), json.optBoolean("armed"))
            "band_target" -> BandTarget(json.optString("target"), json.optString("computer"), json.optString("profile"))
            else -> null
        }
    }
}
