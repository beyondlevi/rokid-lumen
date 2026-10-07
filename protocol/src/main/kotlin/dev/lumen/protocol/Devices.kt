package dev.lumen.protocol

import org.json.JSONArray
import org.json.JSONObject

/** One of a device's gesture profiles, as the glasses list it. */
data class DeviceProfile(val id: String, val name: String, val kind: String = "custom") {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind)

    companion object {
        fun from(json: JSONObject) = DeviceProfile(json.optString("id"), json.optString("name"), json.optString("kind", "custom"))
    }
}

/** A computer the phone has been a keyboard for. */
data class DeviceComputer(val address: String, val name: String) {
    fun toJson(): JSONObject = JSONObject().put("address", address).put("name", name)

    companion object {
        fun from(json: JSONObject) = DeviceComputer(json.optString("address"), json.optString("name"))
    }
}

/**
 * phone → glasses, on [Link.PHONE_EVENT] (type `devices`): where the band can go from the glasses'
 * Controls (the phone with its profiles, the computers it has been a keyboard for with the
 * computer profiles) and where it is now: [where] is [GLASSES], [PHONE] or [COMPUTER] ([computer]
 * then says which), with the profile in use there, whether the controls are [paused], and the
 * gesture that resumes them ([pauseGesture], a mapping key, "" for none). [since] is when the band
 * got there (the phone's clock, ms; 0 unknown): a report that the phone let it go for the glasses
 * is acted on only if it's newer than the glasses' own last move. Sent when it changes and with the
 * battery's heartbeat: Rokid's link can hold a message for minutes.
 */
data class BandDevices(
    val where: String,
    val computer: String = "",
    val paused: Boolean = false,
    val pauseGesture: String = "",
    val phoneProfiles: List<DeviceProfile> = emptyList(),
    val phoneProfile: String = "",
    val computers: List<DeviceComputer> = emptyList(),
    val computerProfiles: List<DeviceProfile> = emptyList(),
    val computerProfile: String = "",
    val since: Long = 0L,
) {
    fun toJson(): JSONObject = Link.message().put("type", TYPE).put("where", where).put("computer", computer)
        .put("paused", paused).put("pause_gesture", pauseGesture)
        .put("phone_profiles", JSONArray().apply { phoneProfiles.forEach { put(it.toJson()) } }).put("phone_profile", phoneProfile)
        .put("computers", JSONArray().apply { computers.forEach { put(it.toJson()) } })
        .put("computer_profiles", JSONArray().apply { computerProfiles.forEach { put(it.toJson()) } }).put("computer_profile", computerProfile)
        .put("since", since)

    companion object {
        const val TYPE = "devices"
        const val GLASSES = "glasses"
        const val PHONE = "phone"
        const val COMPUTER = "computer"

        /** A devices event, or null for anything else. */
        @JvmStatic
        fun from(json: JSONObject?): BandDevices? {
            if (json == null || json.optString("type") != TYPE) return null
            fun <T> list(key: String, item: (JSONObject) -> T): List<T> {
                val array = json.optJSONArray(key) ?: return emptyList()
                return (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(item) }
            }
            return BandDevices(
                where = json.optString("where", GLASSES),
                computer = json.optString("computer"),
                paused = json.optBoolean("paused"),
                pauseGesture = json.optString("pause_gesture"),
                phoneProfiles = list("phone_profiles", DeviceProfile::from),
                phoneProfile = json.optString("phone_profile"),
                computers = list("computers", DeviceComputer::from),
                computerProfiles = list("computer_profiles", DeviceProfile::from),
                computerProfile = json.optString("computer_profile"),
                since = json.optLong("since"),
            )
        }
    }
}
