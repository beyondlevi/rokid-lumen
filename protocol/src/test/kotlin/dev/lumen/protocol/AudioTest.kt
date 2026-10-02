package dev.lumen.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AudioTest {
    private val file = Random(7).nextBytes(Chunks.SIZE * 5 + 123)

    @Test
    fun `a file crosses a lossy link and arrives whole`() {
        val sender = ChunkSender(file, window = 3, retryMs = 100)
        val header = AudioOps.header(JSONObjectFactory.empty(), file)
        val receiver = ChunkReceiver(header.getInt("size"), header.getString("sha256"), header.getInt("chunks"))
        assertEquals(6, sender.total)
        var now = 0L
        var sends = 0
        val lossy = Random(1)
        while (!sender.done && now < 10_000) {
            sender.pump(now) { seq, piece ->
                sends++
                // A third of the pieces and of the acks are lost on the way.
                if (lossy.nextInt(3) != 0) {
                    receiver.put(seq, piece)
                    if (lossy.nextInt(3) != 0) sender.ack(seq)
                }
            }
            now += 50
        }
        assertTrue(sender.done)
        assertFalse(sender.failed)
        assertTrue(sends > 6)
        assertArrayEquals(file, receiver.bytes())
    }

    @Test
    fun `the window bounds what is in flight, and a dead link gives up`() {
        val sender = ChunkSender(file, window = 2, retryMs = 100, maxTries = 3)
        val first = mutableListOf<Int>()
        sender.pump(0) { seq, _ -> first += seq }
        assertEquals(listOf(0, 1), first)
        sender.ack(0)
        val next = mutableListOf<Int>()
        sender.pump(10) { seq, _ -> next += seq }
        assertEquals(listOf(2), next)
        var now = 10L
        while (!sender.failed && now < 5_000) {
            now += 100
            sender.pump(now) { _, _ -> }
        }
        assertTrue(sender.failed)
    }

    @Test
    fun `a damaged or short file is refused`() {
        val header = AudioOps.header(JSONObjectFactory.empty(), file)
        val receiver = ChunkReceiver(header.getInt("size"), header.getString("sha256"), header.getInt("chunks"))
        assertFalse(receiver.put(9, ByteArray(1)))
        assertFalse(receiver.put(0, ByteArray(10)))
        (0 until receiver.chunks).forEach { receiver.put(it, Chunks.piece(file, it)) }
        assertArrayEquals(file, receiver.bytes())
        val wrong = ChunkReceiver(file.size, "00", Chunks.count(file.size))
        (0 until wrong.chunks).forEach { wrong.put(it, Chunks.piece(file, it)) }
        assertNull(wrong.bytes())
        assertEquals(1, Chunks.count(0))
    }

    @Test
    fun `errors keep their page codes`() {
        val json = AudioOps.error("a1", AudioError.BUSY, "dictating")
        assertEquals("busy", json.getString("code"))
        assertEquals(AudioError.NO_PHONE, AudioError.of("no-phone"))
        assertEquals(AudioError.ENGINE, AudioError.of("whatever"))
        assertTrue(Link.AUDIO in Link.TO_PHONE && Link.AUDIO_EVENT in Link.TO_GLASSES)
    }
}

private object JSONObjectFactory {
    fun empty() = org.json.JSONObject()
}
