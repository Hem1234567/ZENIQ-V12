package com.astra.agent.llm

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class SttState { Idle, Listening, Processing, Error }

/** Which TTS engine is active */
enum class TtsEngine { ANDROID, KOKORO }

class VoiceManager(private val ctx: Context) {
    val sttState    = MutableStateFlow(SttState.Idle)
    val partialText = MutableStateFlow("")
    val ttsEnabled  = MutableStateFlow(false)
    val isSpeaking  = MutableStateFlow(false)

    /** Currently selected TTS engine */
    val ttsEngine   = MutableStateFlow(TtsEngine.ANDROID)

    private val mainHandler  = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private val released = AtomicBoolean(false)

    init { initTts() }

    private fun initTts() {
        try {
            tts = TextToSpeech(ctx.applicationContext) { status ->
                if (released.get()) return@TextToSpeech
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.language = Locale.getDefault()
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?)  {
                            if (ttsEngine.value == TtsEngine.ANDROID) isSpeaking.value = true
                        }
                        override fun onDone(id: String?)   {
                            if (ttsEngine.value == TtsEngine.ANDROID) isSpeaking.value = false
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?)  {
                            if (ttsEngine.value == TtsEngine.ANDROID) isSpeaking.value = false
                        }
                    })
                    ttsEnabled.value = true
                }
            }
        } catch (e: Exception) {
            ttsReady = false; ttsEnabled.value = false
        }
    }

    // ── Speak — dispatches to whichever engine is selected ───────────────────

    fun speak(text: String, kokoro: KokoroManager? = null) {
        if (released.get()) return
        when (ttsEngine.value) {
            TtsEngine.KOKORO  -> {
                if (kokoro != null && kokoro.isReady()) {
                    kokoro.isSpeaking.value.let {}   // observe propagated separately
                    kokoro.speak(text)
                } else {
                    // Fall back to Android TTS if Kokoro not ready
                    speakAndroid(text)
                }
            }
            TtsEngine.ANDROID -> speakAndroid(text)
        }
    }

    private fun speakAndroid(text: String) {
        if (!ttsReady || released.get()) return
        try {
            val clean = text
                .replace(Regex("```[\\s\\S]*?```"), "code block")
                .replace(Regex("`[^`]+`"), "")
                .replace(Regex("\\*{1,2}([^*]+)\\*{1,2}"), "$1")
                .replace(Regex("#{1,6}\\s"), "")
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(2000)
            isSpeaking.value = true
            tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "astra_tts")
        } catch (_: Exception) { isSpeaking.value = false }
    }

    fun stopSpeaking(kokoro: KokoroManager? = null) {
        try { tts?.stop() } catch (_: Exception) {}
        kokoro?.stopSpeaking()
        isSpeaking.value = false
    }

    // ── Voice input (STT) ────────────────────────────────────────────────────

    fun startListening(onResult: (String) -> Unit, onError: () -> Unit) {
        if (released.get()) { onError(); return }
        mainHandler.post {
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onError(); return@post }
                destroyRecognizer()

                recognizer = SpeechRecognizer.createSpeechRecognizer(ctx.applicationContext)
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                }
                sttState.value = SttState.Listening
                partialText.value = ""

                recognizer?.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() { sttState.value = SttState.Processing }
                    override fun onError(code: Int) {
                        sttState.value = SttState.Idle
                        partialText.value = ""
                        mainHandler.post { destroyRecognizer() }
                        onError()
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        sttState.value = SttState.Idle
                        partialText.value = ""
                        mainHandler.post { destroyRecognizer() }
                        if (text.isNotBlank()) onResult(text) else onError()
                    }
                    override fun onPartialResults(partial: Bundle?) {
                        partialText.value = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                    }
                    override fun onEvent(type: Int, p: Bundle?) {}
                })
                recognizer?.startListening(intent)
            } catch (e: Exception) {
                sttState.value = SttState.Idle
                onError()
            }
        }
    }

    fun stopListening() {
        mainHandler.post {
            try { recognizer?.stopListening() } catch (_: Exception) {}
            destroyRecognizer()
        }
        sttState.value = SttState.Idle
        partialText.value = ""
    }

    private fun destroyRecognizer() {
        try { recognizer?.cancel() } catch (_: Exception) {}
        try { recognizer?.destroy() } catch (_: Exception) {}
        recognizer = null
    }

    fun release() {
        if (released.getAndSet(true)) return
        stopListening()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ttsReady = false
        isSpeaking.value = false
    }
}
