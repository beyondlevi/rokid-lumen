package dev.lumen.companion

import java.io.ByteArrayOutputStream

/**
 * Splits an H.264 Annex B byte stream (what `screenrecord --output-format=h264` writes) into NAL
 * units, each with its start code, the unit a decoder takes per input buffer. Bytes arrive in
 * arbitrary chunks; a unit is complete when the next start code shows up.
 */
class AnnexB {
    private val pending = ByteArrayOutputStream()

    /** The units completed by [data] (offset 0, [length] bytes). */
    fun feed(data: ByteArray, length: Int = data.size): List<ByteArray> {
        pending.write(data, 0, length)
        val buffer = pending.toByteArray()
        val starts = startCodes(buffer)
        if (starts.size < 2) return emptyList()
        val units = (0 until starts.size - 1).map { buffer.copyOfRange(starts[it], starts[it + 1]) }
        pending.reset()
        pending.write(buffer, starts.last(), buffer.size - starts.last())
        return units
    }

    /** What's left when the stream ends: the last unit, if any. */
    fun flush(): ByteArray? {
        val rest = pending.toByteArray()
        pending.reset()
        return rest.takeIf { startCodes(it).isNotEmpty() }
    }

    companion object {
        /** NAL unit types: sequence and picture parameter sets go to the decoder as its configuration. */
        const val SPS = 7
        const val PPS = 8

        /** The NAL unit type of a unit that begins with a start code (-1 if it doesn't). */
        @JvmStatic
        fun type(unit: ByteArray): Int {
            val header = when {
                unit.size > 4 && unit[0] == 0.toByte() && unit[1] == 0.toByte() && unit[2] == 0.toByte() && unit[3] == 1.toByte() -> 4
                unit.size > 3 && unit[0] == 0.toByte() && unit[1] == 0.toByte() && unit[2] == 1.toByte() -> 3
                else -> return -1
            }
            return unit[header].toInt() and 0x1f
        }

        /** Where each start code (00 00 01, or 00 00 00 01 counted from its first zero) begins. */
        private fun startCodes(buffer: ByteArray): List<Int> {
            val out = ArrayList<Int>()
            var i = 0
            while (i + 2 < buffer.size) {
                if (buffer[i] == 0.toByte() && buffer[i + 1] == 0.toByte() && buffer[i + 2] == 1.toByte()) {
                    out += if (i > 0 && buffer[i - 1] == 0.toByte()) i - 1 else i
                    i += 3
                } else {
                    i++
                }
            }
            return out
        }
    }
}
