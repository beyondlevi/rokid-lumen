package dev.lumen.protocol

import org.json.JSONArray
import org.json.JSONObject

/** glasses → phone, on [Link.DICTATION]. */
enum class DictationCommand(val action: String) {
    HELLO("hello"), START("start"), STOP("stop");

    fun toJson(): JSONObject = Link.message().put("action", action)

    companion object {
        @JvmStatic
        fun from(json: JSONObject): DictationCommand? = entries.firstOrNull { it.action == json.optString("action") }
    }
}

/** phone → glasses, on [Link.DICTATION_EVENT]: [type] is ready|status|listening|partial|phrase|stopped|error. */
data class DictationEvent(val type: String, val text: String = "") {
    fun toJson(): JSONObject = Link.message().put("type", type).put("text", text)

    companion object {
        const val READY = "ready"
        const val STATUS = "status"
        const val LISTENING = "listening"
        const val PARTIAL = "partial"
        const val PHRASE = "phrase"
        const val STOPPED = "stopped"
        const val ERROR = "error"

        @JvmStatic
        fun from(json: JSONObject) = DictationEvent(json.optString("type"), json.optString("text"))
    }
}

/**
 * glasses → phone, on [Link.NOTIFY]: resend the phone's notifications ({action: sync}), or the
 * banners' snooze as it stands ({action: snooze, until}: wall-clock ms, 0 when off), sent at
 * start and on every change, or notifications dismissed on the glasses ({action: dismiss, keys}),
 * which the phone clears from its shade too (their removal comes back as usual), or a reply
 * ({action: reply, key, text}) the phone sends through the notification's own reply action
 * (answered with [NotifyEvent.replied]), or one of a notification's pictures ({action: picture,
 * id, key, index, at}: [PictureRequest], answered on [Link.PICTURE_EVENT], see [PictureOps]).
 */
object NotifyCommand {
    const val SYNC = "sync"
    const val SNOOZE = "snooze"
    const val DISMISS = "dismiss"
    const val REPLY = "reply"
    const val PICTURE = PictureOps.PICTURE

    @JvmStatic
    fun picture(id: String, key: String, index: Int, at: Long): JSONObject = PictureRequest(id, key, index, at).toJson()

    /** The [picture] request in [json], or null for another action. */
    @JvmStatic
    fun pictureOf(json: JSONObject): PictureRequest? = PictureRequest.from(json)

    @JvmStatic
    fun reply(key: String, text: String): JSONObject = Link.message().put("action", REPLY).put("key", key).put("text", text)

    /** The key and text of a [reply] message, or null for another action (or an empty one). */
    @JvmStatic
    fun replyOf(json: JSONObject): Pair<String, String>? {
        if (json.optString("action") != REPLY) return null
        val key = json.optString("key")
        val text = json.optString("text")
        return if (key.isEmpty() || text.isBlank()) null else key to text
    }

    @JvmStatic
    fun dismiss(keys: List<String>): JSONObject = Link.message().put("action", DISMISS).put("keys", org.json.JSONArray(keys))

    /** The keys of a [dismiss] message, or null for another action. */
    @JvmStatic
    fun dismissedKeys(json: JSONObject): List<String>? {
        if (json.optString("action") != DISMISS) return null
        val array = json.optJSONArray("keys") ?: return emptyList()
        return (0 until array.length()).map { array.optString(it) }.filter { it.isNotEmpty() }
    }

    @JvmStatic
    fun sync(): JSONObject = Link.message().put("action", SYNC)

    @JvmStatic
    fun isSync(json: JSONObject) = json.optString("action") == SYNC

    @JvmStatic
    fun snooze(until: Long): JSONObject = Link.message().put("action", SNOOZE).put("until", until)

    /** The snooze's end from a [snooze] message, or null for another action. */
    @JvmStatic
    fun snoozeUntil(json: JSONObject): Long? = if (json.optString("action") == SNOOZE) json.optLong("until") else null
}

/**
 * phone → glasses, on [Link.NOTIFY_EVENT].
 * post {key, app, pkg, title, text, when, redacted, live, alert, icon (base64 PNG, optional),
 * reply (it can be answered from the glasses), shortcut (the conversation's shortcut id, "" if
 * none), pictures (optional, [putPictures]: what it shows, the bytes come on demand)}; remove
 * {key}; reset {} (a full sync follows as posts with alert false); snooze {on} (start or end the
 * banners' snooze; the glasses answer with [NotifyCommand.snooze]); replied {key, ok} (how a
 * [NotifyCommand.reply] went).
 */
object NotifyEvent {
    const val POST = "post"
    const val REMOVE = "remove"
    const val RESET = "reset"
    const val SNOOZE = "snooze"
    const val REPLIED = "replied"

    @JvmStatic
    fun replied(key: String, ok: Boolean): JSONObject = Link.message().put("type", REPLIED).put("key", key).put("ok", ok)

    @JvmStatic
    fun snooze(on: Boolean): JSONObject = Link.message().put("type", SNOOZE).put("on", on)

    @JvmStatic
    fun remove(key: String): JSONObject = Link.message().put("type", REMOVE).put("key", key)

    @JvmStatic
    fun reset(): JSONObject = Link.message().put("type", RESET)

    /** The post's fields are filled by the caller (the icon is Android-only). */
    @JvmStatic
    fun post(key: String): JSONObject = Link.message().put("type", POST).put("key", key)

    /**
     * A post's pictures, newest last: the newest [PictureOps.MAX_PICTURES], captions cut to
     * [PictureOps.MAX_CAPTION]. Nothing is added for none.
     */
    @JvmStatic
    fun putPictures(post: JSONObject, pictures: List<NotificationPicture>): JSONObject {
        if (pictures.isEmpty()) return post
        val array = JSONArray()
        pictures.takeLast(PictureOps.MAX_PICTURES).forEach { array.put(it.copy(caption = it.caption.take(PictureOps.MAX_CAPTION)).toJson()) }
        return post.put("pictures", array)
    }

    /** The pictures a post names, oldest first; none for a post without them (an older companion). */
    @JvmStatic
    fun picturesOf(post: JSONObject): List<NotificationPicture> {
        val array = post.optJSONArray("pictures") ?: return emptyList()
        return (0 until array.length()).mapNotNull { NotificationPicture.from(array.optJSONObject(it)) }.take(PictureOps.MAX_PICTURES)
    }
}

/** glasses → phone, on [Link.NET]: hold (and renew) or release the phone's internet. */
enum class NetCommand(val action: String) {
    UP("up"), DOWN("down");

    fun toJson(): JSONObject = Link.message().put("action", action)

    companion object {
        @JvmStatic
        fun from(json: JSONObject): NetCommand? = entries.firstOrNull { it.action == json.optString("action") }
    }
}

/** phone → glasses, on [Link.NET_EVENT]. */
sealed class NetEvent {
    abstract fun toJson(): JSONObject

    /** The phone's hotspot is up: join [ssid] and use the proxy at [address]:[port]. */
    data class Ready(val ssid: String, val passphrase: String, val address: String, val port: Int) : NetEvent() {
        val proxy: String get() = "$address:$port"

        override fun toJson(): JSONObject = Link.message().put("type", "ready").put("ssid", ssid)
            .put("passphrase", passphrase).put("address", address).put("port", port)
    }

    data class Failed(val text: String) : NetEvent() {
        override fun toJson(): JSONObject = Link.message().put("type", "failed").put("text", text)
    }

    /** The hotspot [ssid] closed (empty when unknown, as from an old companion). */
    data class Down(val ssid: String = "") : NetEvent() {
        override fun toJson(): JSONObject = Link.message().put("type", "down").put("ssid", ssid)
    }

    companion object {
        /** Null for an unknown type or a `ready` missing any field. */
        @JvmStatic
        fun from(json: JSONObject): NetEvent? = when (json.optString("type")) {
            "ready" -> {
                val ready = Ready(json.optString("ssid"), json.optString("passphrase"), json.optString("address"), json.optInt("port"))
                ready.takeIf { it.ssid.isNotEmpty() && it.passphrase.isNotEmpty() && it.address.isNotEmpty() && it.port > 0 }
            }
            "failed" -> Failed(json.optString("text"))
            "down" -> Down(json.optString("ssid"))
            else -> null
        }
    }
}

/**
 * phone → glasses, on [Link.PHONE_EVENT]: battery {level (0..100), charging}, sent when it
 * changes, when the glasses ask for their sync ([NotifyCommand.sync]) and every few minutes
 * (Rokid's link can drop a message).
 */
data class PhoneEvent(val level: Int, val charging: Boolean) {
    fun toJson(): JSONObject = Link.message().put("type", BATTERY).put("level", level).put("charging", charging)

    companion object {
        const val BATTERY = "battery"

        /** A battery event, or null for anything else (or a level out of range). */
        @JvmStatic
        fun from(json: JSONObject): PhoneEvent? {
            if (json.optString("type") != BATTERY) return null
            val level = json.optInt("level", -1)
            if (level !in 0..100) return null
            return PhoneEvent(level, json.optBoolean("charging"))
        }
    }
}
