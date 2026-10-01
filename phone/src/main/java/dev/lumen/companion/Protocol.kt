package dev.lumen.companion

import com.rokid.cxr.Caps
import org.json.JSONObject

/**
 * This companion's side of the link transport: CXR custom commands, one Caps holding one JSON
 * string. The messages themselves (names, envelope, types) are in the shared :protocol module
 * (dev.lumen.protocol.Link), which the glasses app uses too.
 */
object Protocol {
    const val GLASSES_PACKAGE = "dev.lumen.glasses"
    const val SAMPLE_RATE = 16_000
    /** CXR-L's PCM codec (16 kHz mono PCM16), as Rokid Nexus measured it. */
    const val CODEC_PCM = 1

    fun encode(json: JSONObject): Caps = Caps().apply { write(json.toString()) }

    fun decode(data: ByteArray?): JSONObject = runCatching {
        val caps = Caps.fromBytes(data ?: return JSONObject())
        JSONObject(caps.at(0).string)
    }.getOrDefault(JSONObject())

    fun field(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")

    /** RMS of a PCM16 chunk, for the log: near 0 is silence (or glasses not worn). */
    fun level(data: ByteArray): Int {
        var sum = 0.0
        var i = 0
        while (i + 1 < data.size) {
            val v = (data[i].toInt() and 0xff) or (data[i + 1].toInt() shl 8)
            sum += v.toDouble() * v
            i += 2
        }
        return if (data.size < 2) 0 else Math.sqrt(sum / (data.size / 2)).toInt()
    }

    /** The first channel of interleaved PCM16. */
    fun firstChannel(data: ByteArray, channels: Int): ByteArray {
        val frame = 2 * channels
        val out = ByteArray(data.size / frame * 2)
        var o = 0
        var i = 0
        while (i + 1 < data.size && o + 1 < out.size) {
            out[o] = data[i]
            out[o + 1] = data[i + 1]
            o += 2
            i += frame
        }
        return out
    }
}
