package dev.lumen.companion.speech

import android.util.Base64
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The cloud engines, as Rokid Nexus talks to them (phone-hub `speech/RealtimeSpeechToText.kt`
 * and `CompletedAudioSpeechToText.kt`): realtime ones stream the utterance over a WebSocket and
 * show words as they come; buffered ones send it as a WAV when it ends.
 */
object CloudStt {
    private const val TAG = "NbCloudStt"

    /** A final that doesn't come this long after the end of the audio is a timeout (Nexus). */
    const val FINAL_TIMEOUT_MS = 15_000L

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
    private val streaming: OkHttpClient by lazy { http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build() }
    private val network = Executors.newSingleThreadExecutor { Thread(it, "speech-http") }

    private const val OPENAI_PROMPT = "Transcribe short speech captured from smart glasses. Preserve the spoken language."

    /** The phone's own language, for realtime engines left on Auto. */
    private fun phoneLanguage(): String = Locale.getDefault().language.takeIf { it.length in 2..3 } ?: "en"

    fun create(engine: SpeechEngine, key: String, region: String?, language: SpeechLanguage, listener: SttListener): SttSession = when {
        engine.provider == SpeechProvider.OPENAI && engine.realtime -> OpenAiRealtime(engine, key, language, listener)
        engine.provider == SpeechProvider.ELEVENLABS && engine.realtime -> ElevenLabsRealtime(engine, key, language, listener)
        else -> Buffered(engine, key, region, language, listener)
    }

    /** Ends with the final text, or with an error, exactly once. */
    private abstract class Once(protected val provider: SpeechProvider, private val listener: SttListener) : SttSession {
        private val done = AtomicBoolean(false)
        private val timer = java.util.Timer("speech-final", true)

        protected val finished get() = done.get()

        protected fun partial(text: String) {
            if (!done.get()) listener.onPartial(text)
        }

        protected fun final(text: String) {
            if (done.compareAndSet(false, true)) {
                timer.cancel()
                if (text.isBlank()) listener.onError(SttError(SttErrorKind.NO_SPEECH, provider)) else listener.onFinal(text.trim())
            }
        }

        protected fun fail(error: SttError) {
            if (done.compareAndSet(false, true)) {
                timer.cancel()
                listener.onError(error)
            }
        }

        /** Starts the wait for the final once the audio has ended. */
        protected fun awaitFinal() {
            timer.schedule(object : java.util.TimerTask() {
                override fun run() = fail(SttError(SttErrorKind.TIMEOUT, provider, "Final speech result timed out"))
            }, FINAL_TIMEOUT_MS)
        }

        override fun cancel() {
            done.set(true)
            timer.cancel()
        }
    }

    /** Sends 100 ms chunks once the socket is ready, queued until then. */
    private abstract class Realtime(provider: SpeechProvider, listener: SttListener) : Once(provider, listener) {
        private val lock = Any()
        private var socket: WebSocket? = null
        private var ready = false
        private val queue = mutableListOf<String>()
        private val pending = ByteArrayOutputStream()
        private var commitAfterReady = false

        abstract fun request(): Request
        abstract fun audioMessage(chunk: ByteArray, commit: Boolean): String
        abstract fun commitMessage(): String?
        abstract fun onMessage(json: JSONObject, socket: WebSocket)

        override fun start() {
            socket = streaming.newWebSocket(request(), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    runCatching { onMessage(JSONObject(text), webSocket) }.onFailure { Log.w(TAG, "${provider.displayName}: bad message", it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(providerError(provider, response?.code, response?.message, t))
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail(SttError(SttErrorKind.NETWORK, provider, "Realtime connection closed before a final result"))
                }
            })
        }

        /** The socket is set up: send what was waiting. */
        protected fun markReady(webSocket: WebSocket) {
            val commit: Boolean
            synchronized(lock) {
                ready = true
                queue.forEach { webSocket.send(it) }
                queue.clear()
                commit = commitAfterReady
            }
            if (commit) sendCommit()
        }

        private fun send(message: String) {
            synchronized(lock) {
                if (ready) socket?.send(message) else queue += message
            }
        }

        override fun acceptPcm(pcm: ByteArray) {
            if (finished) return
            synchronized(lock) {
                pending.write(pcm)
                while (pending.size() >= Pcm.CHUNK_BYTES) {
                    val all = pending.toByteArray()
                    send(audioMessage(all.copyOfRange(0, Pcm.CHUNK_BYTES), false))
                    pending.reset()
                    pending.write(all, Pcm.CHUNK_BYTES, all.size - Pcm.CHUNK_BYTES)
                }
            }
        }

        override fun finishAudio() {
            if (finished) return
            awaitFinal()
            val isReady = synchronized(lock) { ready.also { if (!it) commitAfterReady = true } }
            if (isReady) sendCommit()
        }

        private fun sendCommit() {
            val rest = synchronized(lock) { pending.toByteArray().also { pending.reset() } }
            val commit = commitMessage()
            if (commit == null) {
                send(audioMessage(rest, true))
            } else {
                if (rest.isNotEmpty()) send(audioMessage(rest, false))
                send(commit)
            }
        }

        protected fun close() {
            socket?.close(1000, null)
        }

        override fun cancel() {
            super.cancel()
            socket?.cancel()
        }
    }

    private class OpenAiRealtime(val engine: SpeechEngine, val key: String, val language: SpeechLanguage, listener: SttListener) :
        Realtime(SpeechProvider.OPENAI, listener) {
        private val text = StringBuilder()

        override fun request() = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?intent=transcription")
            .header("Authorization", "Bearer $key")
            .build()

        override fun audioMessage(chunk: ByteArray, commit: Boolean) = JSONObject()
            .put("type", "input_audio_buffer.append")
            .put("audio", Base64.encodeToString(Pcm.upsample16kTo24k(chunk), Base64.NO_WRAP))
            .toString()

        override fun commitMessage() = JSONObject().put("type", "input_audio_buffer.commit").toString()

        override fun onMessage(json: JSONObject, socket: WebSocket) {
            when (json.optString("type")) {
                "session.created" -> socket.send(sessionUpdate())
                "session.updated" -> markReady(socket)
                "conversation.item.input_audio_transcription.delta" -> {
                    text.append(json.optString("delta"))
                    partial(text.toString())
                }
                "conversation.item.input_audio_transcription.completed" -> {
                    final(json.optString("transcript").ifBlank { text.toString() })
                    close()
                }
                "conversation.item.input_audio_transcription.failed", "error" -> {
                    val error = json.optJSONObject("error")
                    fail(providerError(provider, null, error?.optString("message") ?: json.optString("message")))
                    close()
                }
            }
        }

        private fun sessionUpdate(): String {
            val transcription = JSONObject().put("model", engine.realtimeModel).put("delay", "low")
                .put("language", language.openAiCode ?: if (language == SpeechLanguage.AUTO) phoneLanguage() else null)
            language.openAiPrompt?.let { transcription.put("prompt", it) }
            val input = JSONObject()
                .put("format", JSONObject().put("type", "audio/pcm").put("rate", 24_000))
                .put("transcription", transcription)
                .put("turn_detection", JSONObject.NULL)
            val session = JSONObject().put("type", "transcription").put("audio", JSONObject().put("input", input))
            return JSONObject().put("type", "session.update").put("session", session).toString()
        }
    }

    private class ElevenLabsRealtime(val engine: SpeechEngine, val key: String, val language: SpeechLanguage, listener: SttListener) :
        Realtime(SpeechProvider.ELEVENLABS, listener) {
        override fun request(): Request {
            val code = language.elevenLabsCode ?: phoneLanguage()
            return Request.Builder()
                .url(
                    "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=${engine.realtimeModel}&audio_format=pcm_16000" +
                        "&commit_strategy=manual&include_timestamps=false&language_code=$code",
                )
                .header("xi-api-key", key)
                .build()
        }

        override fun audioMessage(chunk: ByteArray, commit: Boolean) = JSONObject()
            .put("message_type", "input_audio_chunk")
            .put("audio_base_64", Base64.encodeToString(chunk, Base64.NO_WRAP))
            .put("sample_rate", Pcm.SAMPLE_RATE)
            .put("commit", commit)
            .toString()

        override fun commitMessage(): String? = null

        override fun onMessage(json: JSONObject, socket: WebSocket) {
            when (val type = json.optString("message_type")) {
                "session_started" -> markReady(socket)
                "partial_transcript" -> partial(json.optString("text"))
                "committed_transcript", "committed_transcript_with_timestamps" -> {
                    final(json.optString("text"))
                    close()
                }
                else -> if (type.endsWith("error") || type in ERRORS) {
                    fail(providerError(provider, null, json.optString("message").ifBlank { type }))
                    close()
                }
            }
        }

        companion object {
            val ERRORS = setOf(
                "quota_exceeded", "throttled", "rate_limited", "unaccepted_terms", "queue_overflow", "resource_exhausted",
                "chunk_size_exceeded", "insufficient_audio_activity", "commit_throttled",
            )
        }
    }

    /** The whole utterance in memory, sent as a WAV when it ends. */
    private class Buffered(
        val engine: SpeechEngine,
        val key: String,
        val region: String?,
        val language: SpeechLanguage,
        listener: SttListener,
    ) : Once(engine.provider, listener) {
        private val audio = ByteArrayOutputStream()

        override fun start() = Unit

        override fun acceptPcm(pcm: ByteArray) {
            if (!finished) synchronized(audio) { audio.write(pcm) }
        }

        override fun finishAudio() {
            if (finished) return
            val pcm = synchronized(audio) { audio.toByteArray() }
            if (pcm.size < Pcm.CHUNK_BYTES) return fail(SttError(SttErrorKind.NO_SPEECH, provider, "Not enough speech audio"))
            awaitFinal()
            network.execute {
                if (finished) return@execute
                runCatching { transcribe(Pcm.wav(pcm)) }
                    .onSuccess { final(it) }
                    .onFailure { if (it is ProviderFailure) fail(it.error) else fail(providerError(provider, null, null, it)) }
            }
        }

        private class ProviderFailure(val error: SttError) : Exception(error.detail)

        private fun transcribe(wav: ByteArray): String {
            val request = when (provider) {
                SpeechProvider.OPENAI -> {
                    val prompt = language.openAiPrompt?.let { "$OPENAI_PROMPT $it" } ?: OPENAI_PROMPT
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model", engine.completedAudioModel!!)
                        .addFormDataPart("response_format", "json")
                        .addFormDataPart("prompt", prompt)
                        .apply { language.openAiCode?.let { addFormDataPart("language", it) } }
                        .addFormDataPart("file", "lumen-speech.wav", wav.toRequestBody(WAV))
                        .build()
                    Request.Builder().url("https://api.openai.com/v1/audio/transcriptions")
                        .header("Authorization", "Bearer $key").header("Accept", "application/json").post(body).build()
                }
                SpeechProvider.ELEVENLABS -> {
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("model_id", engine.completedAudioModel!!)
                        .apply { language.elevenLabsCode?.let { addFormDataPart("language_code", it) } }
                        .addFormDataPart("file", "lumen-speech.wav", wav.toRequestBody(WAV))
                        .build()
                    Request.Builder().url("https://api.elevenlabs.io/v1/speech-to-text")
                        .header("xi-api-key", key).header("Accept", "application/json").post(body).build()
                }
                else -> {
                    val area = region?.takeIf { SpeechSecrets.isValidAzureRegion(it) }
                        ?: throw ProviderFailure(SttError(SttErrorKind.AUTH, provider, "No valid Azure region"))
                    val locale = language.azureLocale ?: azureLocale()
                    Request.Builder()
                        .url("https://$area.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=$locale&format=simple")
                        .header("Ocp-Apim-Subscription-Key", key).header("Accept", "application/json")
                        .post(wav.toRequestBody("audio/wav; codecs=audio/pcm; samplerate=16000".toMediaType()))
                        .build()
                }
            }
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (!response.isSuccessful) {
                    val message = json?.optJSONObject("error")?.optString("message")
                        ?: json?.optJSONObject("detail")?.optString("message") ?: json?.optString("detail") ?: response.message
                    throw ProviderFailure(providerError(provider, response.code, message))
                }
                if (provider == SpeechProvider.AZURE) {
                    return when (json?.optString("RecognitionStatus")) {
                        "Success" -> json.optString("DisplayText")
                        "NoMatch", "InitialSilenceTimeout", "BabbleTimeout" -> ""
                        else -> throw ProviderFailure(providerError(provider, null, json?.optString("RecognitionStatus")))
                    }
                }
                return json?.optString("text").orEmpty()
            }
        }

        /** Azure needs a locale even on Auto: the phone's (Nexus's derivation, simplified). */
        private fun azureLocale(): String {
            val locale = Locale.getDefault()
            return when {
                locale.language == "yue" -> "zh-HK"
                locale.language == "zh" -> when (locale.country) { "HK" -> "zh-HK"; "TW" -> "zh-TW"; else -> "zh-CN" }
                locale.country.isNotEmpty() -> "${locale.language}-${locale.country}"
                else -> DEFAULT_LOCALES[locale.language] ?: "en-US"
            }
        }

        companion object {
            val WAV = "audio/wav".toMediaType()
            val DEFAULT_LOCALES = mapOf(
                "en" to "en-US", "fr" to "fr-FR", "de" to "de-DE", "es" to "es-ES", "it" to "it-IT", "pt" to "pt-BR",
                "ja" to "ja-JP", "ko" to "ko-KR", "nl" to "nl-NL", "pl" to "pl-PL", "ru" to "ru-RU",
            )
        }
    }
}
