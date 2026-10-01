package dev.lumen.companion.speech

import androidx.annotation.StringRes
import dev.lumen.companion.R

/**
 * The dictation engines, languages and patience levels. The cloud engines, their model ids, the
 * languages table and the patience values follow Rokid Nexus (Anezium/Rokid-Nexus, Apache-2.0,
 * phone-hub `speech/`); Vosk is the offline engine this app had before them.
 */
enum class SpeechProvider(val displayName: String) {
    VOSK("Vosk"),
    ANDROID("Android"),
    OPENAI("OpenAI"),
    ELEVENLABS("ElevenLabs"),
    AZURE("Azure"),
    ;

    /** A provider whose engines need an API key (Azure also a region). */
    val needsKey: Boolean get() = this == OPENAI || this == ELEVENLABS || this == AZURE
}

enum class SpeechEngine(
    val id: String,
    val provider: SpeechProvider,
    /** Product names stay in English in every language. */
    val displayName: String,
    @StringRes val description: Int,
    @StringRes val badges: Int,
    val completedAudioModel: String? = null,
    val realtimeModel: String? = null,
    /**
     * Google's recognizer answers a stream of silence with NO_MATCH after about three seconds
     * (Nexus measured it): such an engine starts at the first speech, with the lead-in.
     */
    val startsOnSpeech: Boolean = false,
) {
    VOSK_OFFLINE("vosk", SpeechProvider.VOSK, "Vosk (offline)", R.string.speech_vosk_description, R.string.speech_vosk_badges),
    ANDROID_RECOGNIZER(
        "android_recognizer", SpeechProvider.ANDROID, "Android recognizer",
        R.string.speech_android_description, R.string.speech_android_badges, startsOnSpeech = true,
    ),
    OPENAI_REALTIME_WHISPER(
        "openai_gpt_realtime_whisper", SpeechProvider.OPENAI, "GPT Realtime Whisper",
        R.string.speech_openai_realtime_description, R.string.speech_realtime_badges, realtimeModel = "gpt-realtime-whisper",
    ),
    OPENAI_GPT_4O_TRANSCRIBE(
        "openai_gpt_4o_transcribe", SpeechProvider.OPENAI, "GPT-4o Transcribe",
        R.string.speech_openai_4o_description, R.string.speech_most_accurate_badges, completedAudioModel = "gpt-4o-transcribe",
    ),
    OPENAI_GPT_4O_MINI_TRANSCRIBE(
        "openai_gpt_4o_mini_transcribe", SpeechProvider.OPENAI, "GPT-4o mini Transcribe",
        R.string.speech_openai_4o_mini_description, R.string.speech_lower_cost_badges, completedAudioModel = "gpt-4o-mini-transcribe",
    ),
    ELEVENLABS_SCRIBE_V2_REALTIME(
        "elevenlabs_scribe_v2_realtime", SpeechProvider.ELEVENLABS, "Scribe v2 Realtime",
        R.string.speech_scribe_realtime_description, R.string.speech_realtime_badges, realtimeModel = "scribe_v2_realtime",
    ),
    ELEVENLABS_SCRIBE_V2(
        "elevenlabs_scribe_v2", SpeechProvider.ELEVENLABS, "Scribe v2",
        R.string.speech_scribe_v2_description, R.string.speech_balanced_badges, completedAudioModel = "scribe_v2",
    ),
    ELEVENLABS_SCRIBE_V1(
        "elevenlabs_scribe_v1", SpeechProvider.ELEVENLABS, "Scribe v1",
        R.string.speech_scribe_v1_description, R.string.speech_legacy_badges, completedAudioModel = "scribe_v1",
    ),
    AZURE_SPEECH(
        "azure_speech", SpeechProvider.AZURE, "Azure Speech to Text",
        R.string.speech_azure_description, R.string.speech_azure_badges, completedAudioModel = "azure-conversation",
    ),
    ;

    val realtime: Boolean get() = realtimeModel != null
    val buffered: Boolean get() = completedAudioModel != null

    /** The engine picks its language itself (the choice doesn't apply). */
    val fixedLanguage: Boolean get() = provider == SpeechProvider.ANDROID

    /** The languages the engine can be set to: Vosk has a model for a few. */
    val languages: List<SpeechLanguage>
        get() = if (provider == SpeechProvider.VOSK) dev.lumen.companion.VoskModel.languages else SpeechLanguage.entries

    companion object {
        val DEFAULT = ANDROID_RECOGNIZER

        fun fromId(id: String?): SpeechEngine? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
    }
}

/**
 * A transcription language, and what each engine calls it. Labels are the languages' own names
 * (the same in every UI language), [AUTO] aside.
 */
enum class SpeechLanguage(
    val id: String,
    val nativeName: String,
    val openAiCode: String?,
    val openAiPrompt: String?,
    val elevenLabsCode: String?,
    val azureLocale: String?,
    val androidTags: List<String>,
) {
    AUTO("auto", "", null, null, null, null, emptyList()),
    EN("en", "English", "en", null, "en", "en-US", listOf("en-US")),
    FR("fr", "Français", "fr", null, "fr", "fr-FR", listOf("fr-FR")),
    DE("de", "Deutsch", "de", null, "de", "de-DE", listOf("de-DE")),
    ES("es", "Español", "es", null, "es", "es-ES", listOf("es-ES")),
    IT("it", "Italiano", "it", null, "it", "it-IT", listOf("it-IT")),
    PT("pt", "Português", "pt", null, "pt", "pt-BR", listOf("pt-BR")),
    PL("pl", "Polski", "pl", null, "pl", "pl-PL", listOf("pl-PL")),
    JA("ja", "日本語", "ja", null, "ja", "ja-JP", listOf("ja-JP")),
    KO("ko", "한국어", "ko", null, "ko", "ko-KR", listOf("ko-KR")),
    YUE("yue", "廣東話", null, "廣東話語音。請用繁體中文轉寫。", "yue", "zh-HK", listOf("yue-Hant-HK", "yue-HK", "zh-HK")),
    ZH_HANT("zh-hant", "中文繁體", "zh", "請使用繁體中文。", "zh", "zh-TW", listOf("zh-TW")),
    ZH_HANS("zh-hans", "中文简体", "zh", "请使用简体中文。", "zh", "zh-CN", listOf("zh-CN")),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromId(id: String?): SpeechLanguage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How long a pause can last before the sentence is taken as finished (Nexus: quick, normal,
 * patient). Dictation keeps listening after it; the pause only commits the text.
 */
enum class SpeechPatience(val id: String, @StringRes val label: Int, val silenceMs: Long, val initialWaitMs: Long) {
    QUICK("quick", R.string.speech_patience_quick, 1_500L, 5_000L),
    NORMAL("normal", R.string.speech_patience_normal, 2_500L, 8_000L),
    PATIENT("patient", R.string.speech_patience_patient, 4_000L, 15_000L),
    ;

    /** Android's own endpointer waits longer than ours, so ours decides (Nexus: +2 s). */
    val androidSilenceMs: Int get() = (initialWaitMs + 2_000L).toInt()

    companion object {
        val DEFAULT = NORMAL

        fun fromId(id: String?): SpeechPatience? = entries.firstOrNull { it.id == id }
    }
}
