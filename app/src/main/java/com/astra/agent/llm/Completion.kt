package com.astra.agent.llm

import com.astra.agent.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** One question to the model with no chat history: cloud in Fast Mode (with a key), otherwise the local model. Works offline when local. */
object Completion {
    private val thinkRe = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun usesCloud(s: Settings) = s.fastMode && s.openRouterKey.isNotBlank()

    /** Models to try in Fast Mode, best first. */
    private fun cloudModels(s: Settings): List<String> = OpenRouterClient.candidates(s.openRouterModel, OpenRouterClient.fallbackModels)

    /**
     * Streams one answer from OpenRouter. If a model is busy (429), silent, empty or broken it moves on to the next one; text that a failed
     * model already showed is withdrawn with [onReset] first, so the screen never shows two answers glued together.
     */
    private suspend fun cloud(s: Settings, system: String, user: String, maxTokens: Int, temperature: Float, onReset: () -> Unit, onText: (String) -> Unit): String {
        try { OpenRouterClient.ensureLive() } catch (_: Exception) { }
        val job = currentCoroutineContext()[Job]
        val msgs = listOf(OpenRouterClient.Message("system", system), OpenRouterClient.Message("user", user))
        var last: Exception? = null
        var shown = false
        for ((i, m) in cloudModels(s).withIndex()) {
            val sb = StringBuilder()
            try {
                OpenRouterClient.streamChat(s.openRouterKey, m, msgs, maxTokens, temperature) { sb.append(it); shown = true; onText(it); job?.isActive != false }
                if (sb.isNotBlank()) return sb.toString()
                last = IllegalStateException("The model sent an empty answer.")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                last = e
                if (e is ORException && e.code == 401) break                // a wrong key will not work with another model either
                if (sb.length > 400) return sb.toString()                    // most of the answer arrived: the caller checks it is complete
                if (e is ORException && e.code == 429 && i < 2) delay(1500)  // busy free model: short pause, then the next one
            }
            if (shown) { onReset(); shown = false }
        }
        throw last ?: IllegalStateException("No answer from the cloud model.")
    }

    private suspend fun local(system: String, user: String, maxTokens: Int, temperature: Float, onText: (String) -> Unit): String {
        if (LlmEngine.loadedName == null) throw IllegalStateException("No model loaded. Load a model on the Models tab or turn on Fast Mode.")
        val job = currentCoroutineContext()[Job]
        val prompt = PromptFormat.single(system, user, true)
        val sb = StringBuilder()
        LlmEngine.generate(prompt, maxTokens, temperature, 0.9f) { sb.append(it); onText(it); job?.isActive != false }
        return sb.toString()
    }

    private fun clean(t: String): String = t.replace(thinkRe, "").replace("<|im_end|>", "").trim()

    suspend fun ask(s: Settings, system: String, user: String, maxTokens: Int, temperature: Float = 0.3f): String = withContext(Dispatchers.Default) {
        clean(if (usesCloud(s)) cloud(s, system, user, maxTokens, temperature, {}, {}) else local(system, user, maxTokens, temperature) {})
    }

    /**
     * Like [ask], but hands every piece of text to [onText] while it is being written (cloud or local model), and returns the whole text.
     * [onReset] is called when a cloud model failed after showing text and another model starts over.
     */
    suspend fun stream(s: Settings, system: String, user: String, maxTokens: Int, temperature: Float = 0.3f, onReset: () -> Unit = {}, onText: (String) -> Unit): String =
        withContext(Dispatchers.Default) {
            clean(if (usesCloud(s)) cloud(s, system, user, maxTokens, temperature, onReset, onText) else local(system, user, maxTokens, temperature, onText))
        }
}
