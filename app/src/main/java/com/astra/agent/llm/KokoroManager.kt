package com.astra.agent.llm

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ── Kokoro voice catalogue ─────────────────────────────────────────────────────
// Each voice maps to specific Android TTS pitch + speech rate settings that
// produce distinctly different character voices.

data class KokoroVoice(
    val id: String,
    val displayName: String,
    val gender: String,      // "Female" / "Male"
    val accent: String,      // "American" / "British"
    val pitch: Float,        // 0.5 – 2.0  (1.0 = normal)
    val rate: Float,         // 0.5 – 2.0  (1.0 = normal)
    val locale: Locale = Locale.US
)

val KOKORO_VOICES = listOf(
    KokoroVoice("af_heart",    "Heart \u2665",      "Female", "American", pitch = 1.25f, rate = 1.05f),
    KokoroVoice("af_bella",    "Bella",         "Female", "American", pitch = 1.15f, rate = 0.95f),
    KokoroVoice("af_sarah",    "Sarah",         "Female", "American", pitch = 1.10f, rate = 1.00f),
    KokoroVoice("af_nicole",   "Nicole",        "Female", "American", pitch = 1.20f, rate = 1.10f),
    KokoroVoice("am_adam",     "Adam",          "Male",   "American", pitch = 0.80f, rate = 1.00f),
    KokoroVoice("am_michael",  "Michael",       "Male",   "American", pitch = 0.85f, rate = 0.95f),
    KokoroVoice("bf_emma",     "Emma",          "Female", "British",  pitch = 1.15f, rate = 0.90f, locale = Locale.UK),
    KokoroVoice("bf_isabella", "Isabella",      "Female", "British",  pitch = 1.20f, rate = 0.95f, locale = Locale.UK),
    KokoroVoice("bm_george",   "George",        "Male",   "British",  pitch = 0.75f, rate = 0.90f, locale = Locale.UK),
    KokoroVoice("bm_lewis",    "Lewis",         "Male",   "British",  pitch = 0.80f, rate = 0.85f, locale = Locale.UK),
)

// Dummy kept for UI compatibility — no files to download
data class KokoroFile(val name: String, val url: String, val sizeKb: Long)
val KOKORO_MODEL_FILES: List<KokoroFile> = emptyList()

// ── KokoroManager ─────────────────────────────────────────────────────────────

/**
 * Manages Kokoro-style named voices using Android's built-in TextToSpeech engine.
 * Each KokoroVoice maps to specific pitch/rate/locale that gives a distinct character.
 * No external library, no download — works instantly offline.
 */
class KokoroManager(private val ctx: Context) {

    val selectedVoice = MutableStateFlow(KOKORO_VOICES.first())
    val speed         = MutableStateFlow(1.0f)
    val isSpeaking    = MutableStateFlow(false)
    val downloads     = MutableStateFlow<Map<String, DlState>>(emptyMap())

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    init { initTts() }

    private fun initTts() {
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) applyVoiceSettings()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?)  { isSpeaking.value = true }
                    override fun onDone(id: String?)   { isSpeaking.value = false }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?)  { isSpeaking.value = false }
                })
            }
        } catch (_: Exception) {}
    }

    private fun applyVoiceSettings() {
        val v = selectedVoice.value
        try {
            tts?.language = v.locale
            tts?.setPitch(v.pitch)
            tts?.setSpeechRate(v.rate * speed.value)
        } catch (_: Exception) {}
    }

    fun isReady(): Boolean = ttsReady
    /** Always true — built-in TTS needs no download */
    fun isDownloaded(): Boolean = true
    /** No-op — engine is always ready */
    fun initEngine() {}
    /** Always 1.0 — nothing to download */
    val overallProgress: Float get() = 1f

    fun speak(text: String) {
        if (!ttsReady) return
        applyVoiceSettings()
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), "code block")
            .replace(Regex("`[^`]+`"), "")
            .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
            .replace(Regex("#{1,6}\\s"), "")
            .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(2000)
        if (clean.isBlank()) return
        isSpeaking.value = true
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "kokoro_tts")
    }

    fun stopSpeaking() {
        try { tts?.stop() } catch (_: Exception) {}
        isSpeaking.value = false
    }

    /** No-op: nothing to download for built-in voice engine */
    fun downloadAll(scope: CoroutineScope): Job = scope.launch { /* no-op */ }
    fun cancelDownload() { downloads.value = emptyMap() }
    fun deleteAll() { /* no files to delete */ }

    fun release() {
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        isSpeaking.value = false
    }
}
