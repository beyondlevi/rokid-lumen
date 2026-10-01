package dev.lumen.companion.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SpeechTest {
    private fun tone(samples: Int, amplitude: Int): ByteArray {
        val buffer = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples) { buffer.putShort((if (it % 2 == 0) amplitude else -amplitude).toShort()) }
        return buffer.array()
    }

    @Test
    fun `voice needs a level above the thresholds`() {
        assertFalse(Voice.isVoice(tone(320, 100)))
        assertTrue(Voice.isVoice(tone(320, 400)))
        assertFalse(Voice.isVoice(ByteArray(640)))
    }

    @Test
    fun `16 kHz becomes 24 kHz and a WAV has its header`() {
        assertEquals(480 * 2, Pcm.upsample16kTo24k(tone(320, 1000)).size)
        val wav = Pcm.wav(ByteArray(100))
        assertEquals(144, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(16_000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun `an api key error is an auth error`() {
        assertEquals(SttErrorKind.AUTH, providerError(SpeechProvider.OPENAI, 401, "Incorrect API key").kind)
        assertEquals(SttErrorKind.QUOTA_RATE, providerError(SpeechProvider.ELEVENLABS, 429, null).kind)
        assertEquals(SttErrorKind.NETWORK, providerError(SpeechProvider.AZURE, null, null, java.io.IOException("down")).kind)
    }

    /** An engine that answers when told to, as a slow buffered one would. */
    private class Fake(val listener: SttListener) : SttSession {
        val audio = java.io.ByteArrayOutputStream()
        var finished = CountDownLatch(1)
        override fun start() = Unit
        override fun acceptPcm(pcm: ByteArray) { audio.write(pcm) }
        override fun finishAudio() = finished.countDown()
        override fun cancel() = Unit
    }

    @Test
    fun `utterances reach the sink in the order they were spoken`() {
        val fakes = mutableListOf<Fake>()
        val phrases = mutableListOf<String>()
        val stopped = CountDownLatch(1)
        val session = DictationSession(
            context = android.app.Application(),
            engine = SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE,
            language = SpeechLanguage.AUTO,
            patience = SpeechPatience.QUICK,
            factory = { listener -> Fake(listener).also { synchronized(fakes) { fakes += it } } },
            sink = object : DictationSession.Sink {
                override fun partial(text: String) = Unit
                override fun phrase(text: String) { synchronized(phrases) { phrases += text } }
                override fun error(error: SttError) = Unit
                override fun stopped() = stopped.countDown()
            },
        )
        session.start()
        // First utterance: speech, then a pause longer than the patience (1.5 s) after 1 s.
        repeat(55) { session.onPcm(tone(320, 3_000)); Thread.sleep(20) }
        repeat(95) { session.onPcm(ByteArray(640)); Thread.sleep(20) }
        val first = synchronized(fakes) { fakes.single() }
        assertTrue("the pause ends the first utterance", first.finished.await(2, TimeUnit.SECONDS))
        // Second utterance, still unanswered while the first is.
        repeat(10) { session.onPcm(tone(320, 3_000)); Thread.sleep(20) }
        val second = synchronized(fakes) { fakes[1] }
        session.stop()
        assertTrue(second.finished.await(2, TimeUnit.SECONDS))
        second.listener.onFinal("second")
        Thread.sleep(200)
        assertEquals(emptyList<String>(), synchronized(phrases) { phrases.toList() })
        first.listener.onFinal("first")
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("first", "second"), synchronized(phrases) { phrases.toList() })
        assertTrue("the lead-in and the speech reach the engine", first.audio.size() >= 55 * 640)
    }
}
