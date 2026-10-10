package dev.lumen.protocol

import org.json.JSONObject

/**
 * Pictures in the phone's notifications (a chat's photo, a big picture), for the glasses'
 * inbox. A post only names them ([NotifyEvent.putPictures]: one [NotificationPicture] each,
 * newest last, never for a notification whose content stays on the phone); the bytes cross on
 * demand, when the glasses open that notification, one picture at a time, so the posts stay
 * small on Rokid's slow link:
 *
 *   glasses → phone  [Link.NOTIFY]         {action: picture, id, key, index, at} ([PictureRequest])
 *                    [Link.PICTURE]        {op: ack, id, seq} | {op: cancel, id}
 *   phone → glasses  [Link.PICTURE_EVENT]  {type: picture, id, key, index, ok: true, size, sha256, chunks, mime, width, height}
 *                                          {type: picture, id, key, index, ok: false, reason} ([PictureAnswer])
 *                                          {type: chunk, id, seq} + bytes
 *
 * The file crosses as audio files do ([ChunkSender] / [ChunkReceiver]): pieces acknowledged,
 * missing ones sent again, size and SHA-256 checked at the end. It is a grayscale JPEG (the HUD
 * is monochrome) at most [MAX_SIDE] px on its longer side and [MAX_BYTES] long: a few chunks.
 * The glasses repeat a request until the answer comes; the phone answers a repeat with the same
 * header and drops a transfer the glasses cancel or a newer request replaces.
 */
object PictureOps {
    const val ACK = "ack"
    const val CANCEL = "cancel"
    const val PICTURE = "picture"
    const val CHUNK = "chunk"

    /** The HUD's square: nothing bigger is worth the link's time. */
    const val MAX_SIDE = 480
    /** The most a picture may weigh on the link (10 chunks at most, usually 2 or 3). */
    const val MAX_BYTES = 120 * 1024
    /** The newest pictures a post names; older ones stay on the phone. */
    const val MAX_PICTURES = 4
    /** A caption's longest run in a post (the text has the rest). */
    const val MAX_CAPTION = 300
    const val MIME = "image/jpeg"

    /** Why the phone couldn't send a picture ([PictureAnswer.reason]). */
    const val REASON_GONE = "gone"
    const val REASON_HIDDEN = "hidden"
    const val REASON_DENIED = "denied"
    const val REASON_UNREADABLE = "unreadable"
    const val REASON_TOO_LARGE = "too-large"

    @JvmStatic
    fun message(op: String, id: String): JSONObject = Link.message().put("op", op).put("id", id)

    @JvmStatic
    fun chunk(id: String, seq: Int): JSONObject = Link.message().put("type", CHUNK).put("id", id).put("seq", seq)
}

/** One picture a notification carries: [at], its message's time (the post's for a big picture), and its caption ("" for none). */
data class NotificationPicture(val at: Long, val caption: String = "") {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("caption", caption)

    companion object {
        @JvmStatic
        fun from(json: JSONObject?): NotificationPicture? =
            json?.let { NotificationPicture(it.optLong("at"), it.optString("caption")) }
    }
}

/** The glasses ask for picture [index] of notification [key] ([at] tells it from one that took its place). */
data class PictureRequest(val id: String, val key: String, val index: Int, val at: Long) {
    fun toJson(): JSONObject = Link.message().put("action", PictureOps.PICTURE).put("id", id)
        .put("key", key).put("index", index).put("at", at)

    companion object {
        /** The request in [json], or null for another action (or an incomplete one). */
        @JvmStatic
        fun from(json: JSONObject): PictureRequest? {
            if (json.optString("action") != PictureOps.PICTURE) return null
            val request = PictureRequest(json.optString("id"), json.optString("key"), json.optInt("index", -1), json.optLong("at"))
            return request.takeIf { it.id.isNotEmpty() && it.key.isNotEmpty() && it.index in 0 until PictureOps.MAX_PICTURES }
        }
    }
}

/**
 * The phone's answer to a [PictureRequest]: the file's header ([ok]: its [size], [sha256],
 * [chunks] and pixel size follow as chunks) or why there is none ([reason]).
 */
data class PictureAnswer(
    val id: String,
    val key: String,
    val index: Int,
    val ok: Boolean,
    val reason: String = "",
    val size: Int = 0,
    val sha256: String = "",
    val chunks: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
) {
    fun toJson(): JSONObject {
        val json = Link.message().put("type", PictureOps.PICTURE).put("id", id).put("key", key).put("index", index).put("ok", ok)
        return if (ok) {
            json.put("size", size).put("sha256", sha256).put("chunks", chunks).put("mime", PictureOps.MIME).put("width", width).put("height", height)
        } else {
            json.put("reason", reason)
        }
    }

    companion object {
        /** The header for [bytes], a JPEG of [width] x [height]. */
        @JvmStatic
        fun of(request: PictureRequest, bytes: ByteArray, width: Int, height: Int) = PictureAnswer(
            request.id, request.key, request.index, ok = true,
            size = bytes.size, sha256 = AudioOps.sha256(bytes), chunks = Chunks.count(bytes.size), width = width, height = height,
        )

        @JvmStatic
        fun failed(request: PictureRequest, reason: String) = PictureAnswer(request.id, request.key, request.index, ok = false, reason = reason)

        /** The answer in [json], or null for a chunk or anything else. */
        @JvmStatic
        fun from(json: JSONObject): PictureAnswer? {
            if (json.optString("type") != PictureOps.PICTURE) return null
            val id = json.optString("id").ifEmpty { return null }
            return PictureAnswer(
                id, json.optString("key"), json.optInt("index", -1), json.optBoolean("ok"), json.optString("reason"),
                json.optInt("size"), json.optString("sha256"), json.optInt("chunks"), json.optInt("width"), json.optInt("height"),
            )
        }
    }
}
