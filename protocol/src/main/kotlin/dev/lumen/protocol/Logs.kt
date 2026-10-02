package dev.lumen.protocol

import org.json.JSONObject

/**
 * The glasses' logs, for the companion's "Share logs" (no adb needed):
 *
 *   phone → glasses  [Link.LOGS]        {op: request, id} | {op: ack, id, seq}
 *   glasses → phone  [Link.LOGS_EVENT]  {type: file, id, size, sha256, chunks} (gzip of the text)
 *                                       {type: chunk, id, seq} + bytes | {type: error, id, message}
 *
 * The file crosses as audio files do ([ChunkSender] / [ChunkReceiver]): pieces acknowledged,
 * missing ones sent again, size and SHA-256 checked at the end.
 */
object LogsOps {
    const val REQUEST = "request"
    const val ACK = "ack"
    const val FILE = "file"
    const val CHUNK = "chunk"
    const val ERROR = "error"

    /** The most the glasses send (gzip): their log is cut to fit. */
    const val MAX_BYTES = 2 * 1024 * 1024

    @JvmStatic
    fun message(op: String, id: String): JSONObject = Link.message().put("op", op).put("id", id)

    @JvmStatic
    fun event(type: String, id: String): JSONObject = Link.message().put("type", type).put("id", id)
}
