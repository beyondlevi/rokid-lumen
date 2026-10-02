package dev.lumen.companion.audio

import java.io.ByteArrayOutputStream

/**
 * An Ogg Opus file (RFC 7845, RFC 3533) from raw Opus packets: the OpusHead and OpusTags pages,
 * then the audio in pages of about a second, each page's granule position the sample count
 * (48 kHz, pre-skip included) at the end of its last packet, the last page marked end of
 * stream. One stream, mono.
 */
class OggOpusWriter(private val preSkip: Int, private val inputRate: Int, private val serial: Int = 0x4c756d6e) {
    private val out = ByteArrayOutputStream()
    private var sequence = 0
    private var granule = 0L
    private val packets = ArrayList<ByteArray>()
    private var pageSamples = 0

    init {
        val head = ByteArray(19)
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head)
        head[8] = 1 // version
        head[9] = 1 // channels
        putLe(head, 10, preSkip, 2)
        putLe(head, 12, inputRate, 4)
        // Output gain 0, channel mapping family 0.
        page(listOf(head), granule = 0, flags = BOS)
        val vendor = "Rokid Lumen".toByteArray(Charsets.US_ASCII)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        "OpusTags".toByteArray(Charsets.US_ASCII).copyInto(tags)
        putLe(tags, 8, vendor.size, 4)
        vendor.copyInto(tags, 12)
        page(listOf(tags), granule = 0, flags = 0)
    }

    fun packet(packet: ByteArray) {
        packets += packet
        val samples = samples(packet)
        granule += samples
        pageSamples += samples
        if (pageSamples >= PAGE_SAMPLES || packets.sumOf { segmentsOf(it.size) } > MAX_SEGMENTS - 8) flush(flags = 0)
    }

    /** The whole file, the last page marked end of stream. */
    fun finish(): ByteArray {
        flush(flags = EOS, force = true)
        return out.toByteArray()
    }

    private fun flush(flags: Int, force: Boolean = false) {
        if (packets.isEmpty() && !force) return
        page(packets.toList(), granule, flags)
        packets.clear()
        pageSamples = 0
    }

    private fun page(content: List<ByteArray>, granule: Long, flags: Int) {
        val lacing = ByteArrayOutputStream()
        content.forEach { packet ->
            var left = packet.size
            while (left >= 255) {
                lacing.write(255)
                left -= 255
            }
            lacing.write(left)
        }
        val segments = lacing.toByteArray()
        val header = ByteArray(27)
        "OggS".toByteArray(Charsets.US_ASCII).copyInto(header)
        header[4] = 0
        header[5] = flags.toByte()
        putLe(header, 6, granule, 8)
        putLe(header, 14, serial, 4)
        putLe(header, 18, sequence++, 4)
        header[26] = segments.size.toByte()
        val page = ByteArrayOutputStream()
        page.write(header)
        page.write(segments)
        content.forEach { page.write(it) }
        val bytes = page.toByteArray()
        putLe(bytes, 22, crc(bytes), 4)
        out.write(bytes)
    }

    companion object {
        private const val BOS = 0x02
        private const val EOS = 0x04
        /** About a second of audio per page (48 kHz samples). */
        private const val PAGE_SAMPLES = 48_000
        private const val MAX_SEGMENTS = 255

        private fun segmentsOf(size: Int) = size / 255 + 1

        /** The 48 kHz samples in an Opus packet, from its TOC byte (RFC 6716, 3.1). */
        @JvmStatic
        fun samples(packet: ByteArray): Int {
            if (packet.isEmpty()) return 0
            val toc = packet[0].toInt() and 0xff
            val config = toc shr 3
            val frame = when {
                config < 12 -> intArrayOf(480, 960, 1920, 2880)[config and 3] // SILK: 10, 20, 40, 60 ms
                config < 16 -> intArrayOf(480, 960)[config and 1] // Hybrid: 10, 20 ms
                else -> intArrayOf(120, 240, 480, 960)[config and 3] // CELT: 2.5, 5, 10, 20 ms
            }
            val frames = when (toc and 3) {
                0 -> 1
                1, 2 -> 2
                else -> if (packet.size > 1) packet[1].toInt() and 0x3f else 0
            }
            return frame * frames
        }

        private fun putLe(bytes: ByteArray, at: Int, value: Long, count: Int) {
            for (i in 0 until count) bytes[at + i] = (value ushr (8 * i) and 0xff).toByte()
        }

        private fun putLe(bytes: ByteArray, at: Int, value: Int, count: Int) = putLe(bytes, at, value.toLong() and 0xffffffffL, count)

        private val table = IntArray(256) { n ->
            var r = n shl 24
            repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04c11db7 else r shl 1 }
            r
        }

        /** Ogg's CRC-32 (polynomial 0x04c11db7, not reflected, initial 0), with the CRC field zero. */
        @JvmStatic
        fun crc(page: ByteArray): Int {
            var crc = 0
            page.forEach { b -> crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xff)) and 0xff] }
            return crc
        }
    }
}
