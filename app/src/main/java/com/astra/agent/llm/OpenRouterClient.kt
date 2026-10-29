package com.astra.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** Error from OpenRouter with HTTP status so callers can decide whether to fall back to another model. */
class ORException(val code: Int, message: String) : Exception(message)

/**
 * Calls OpenRouter for fast cloud inference (streaming SSE).
 * Also fetches the LIVE list of free models, because free model ids change often
 * and a hard-coded list quickly goes stale ("model is not free / no endpoints").
 */
object OpenRouterClient {

    private const val OR_BASE = "https://openrouter.ai/api/v1"
    /** The provider that Fast Mode talks to. OpenRouter by default; any OpenAI-compatible service can be connected (see Providers). */
    @Volatile private var base: String = OR_BASE
    @Volatile var providerId: String = Providers.OPENROUTER
        private set
    val isOpenRouter: Boolean get() = providerId == Providers.OPENROUTER

    fun configure(provider: String, baseUrl: String) {
        providerId = provider.ifBlank { Providers.OPENROUTER }
        base = if (providerId == Providers.OPENROUTER) OR_BASE else Providers.baseFor(providerId, baseUrl)
    }

    /** Models to try, best first. Only OpenRouter has "free" auto-routing and fallbacks; another provider uses the model the user chose. */
    fun candidates(selected: String, vararg extras: List<String>): List<String> {
        val l = ArrayList<String>()
        l.add(selected)
        if (isOpenRouter) { extras.forEach { l.addAll(it) }; l.add(AUTO_FREE) }
        return l.filter { it.isNotBlank() }.distinct().take(5)
    }
    const val AUTO_FREE = "openrouter/free"

    /** One function call the model asked for (OpenAI "tool_calls" format). [arguments] is a JSON object as text. */
    class ToolCall(val id: String, val name: String, val arguments: String)

    /** What a streamed answer produced: the text and any tool calls. */
    class ChatResult(val text: String, val toolCalls: List<ToolCall>)

    data class Message(val role: String, val content: String, val images: List<String> = emptyList(),
                       val toolCalls: List<ToolCall> = emptyList(), val toolCallId: String? = null)
    data class ORModel(val id: String, val name: String, val contextLength: Int, val free: Boolean,
                       val vision: Boolean = false, val tools: Boolean = false)

    /** Free model ids discovered at runtime; used as fallbacks when the selected one fails. */
    @Volatile var fallbackModels: List<String> = listOf(AUTO_FREE)
    /** Free models that accept images (used when the user attaches a photo). */
    @Volatile var visionModels: List<String> = emptyList()
    /** Free models that support native tool calling (function calling). */
    @Volatile var toolModels: List<String> = emptyList()

    val defaultFree = listOf(
        ORModel(AUTO_FREE, "Auto \u2022 best available free model", 0, true),
        ORModel("meta-llama/llama-3.3-70b-instruct:free", "Llama 3.3 70B (Free)", 65536, true),
        ORModel("google/gemma-3-27b-it:free", "Gemma 3 27B (Free)", 96000, true),
        ORModel("mistralai/mistral-small-3.2-24b-instruct:free", "Mistral Small 3.2 (Free)", 96000, true),
        ORModel("qwen/qwen3-14b:free", "Qwen3 14B (Free)", 40960, true)
    )

    private fun friendly(code: Int, body: String): String {
        val raw = try {
            JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        } catch (_: Exception) { "" }.ifBlank { body.take(200) }
        return when {
            code == 401 -> "Invalid API key for ${Providers.byId(providerId).name}. Check the key in Settings > Connect provider."
            code == 402 -> "This model needs credits. Pick a free model in Settings."
            code == 404 && raw.contains("data policy", true) ->
                "OpenRouter blocked free models by your privacy settings. Open openrouter.ai/settings/privacy and enable free-model access."
            code == 404 -> "This model is currently unavailable (${raw.take(120)})."
            code == 429 -> "Free model is rate-limited right now."
            else -> "OpenRouter error $code: ${raw.take(200)}"
        }
    }

    @Volatile private var liveLoaded = false

    /** Stores the live lists (fallbacks, tool-capable, vision) so every feature can use them. */
    fun applyLive(list: List<ORModel>) {
        if (list.isEmpty()) return
        fallbackModels = (list.filter { it.tools } + list.filter { !it.tools }).take(6).map { it.id }
        toolModels = list.filter { it.tools }.take(8).map { it.id }
        visionModels = list.filter { it.vision }.take(4).map { it.id }
        liveLoaded = true
    }

    /** Model ids offered by a provider (GET {base}/models). Throws on a network or key problem. */
    suspend fun listModels(providerId: String, baseUrl: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val b = if (providerId == Providers.OPENROUTER) OR_BASE else Providers.baseFor(providerId, baseUrl)
        val conn = URL("$b/models").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 12_000; conn.readTimeout = 20_000
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw ORException(code, friendly(code, text))
            val root = JSONObject(text)
            val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", o.optString("name", "")).removePrefix("models/")
                if (id.isNotBlank()) ids.add(id)
            }
            ids.sorted()
        } finally { conn.disconnect() }
    }

    /** Sends a tiny request to check the key and model. Returns null when it works, otherwise a readable problem. */
    suspend fun testConnection(providerId: String, baseUrl: String, key: String, model: String): String? = withContext(Dispatchers.IO) {
        val b = if (providerId == Providers.OPENROUTER) OR_BASE else Providers.baseFor(providerId, baseUrl)
        val conn = URL("$b/chat/completions").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.connectTimeout = 12_000; conn.readTimeout = 30_000
            conn.doOutput = true
            val body = JSONObject().put("model", model).put("max_tokens", 8).put("stream", false)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "Say OK"))).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            if (code in 200..299) null
            else friendly(code, conn.errorStream?.bufferedReader()?.readText().orEmpty())
        } catch (e: Exception) { "Could not reach the server: ${e.message?.take(120)}" }
        finally { conn.disconnect() }
    }

    /** Loads the live free-model lists once (cheap), so Fast Mode knows which models can call tools even if the Settings page was never opened. */
    suspend fun ensureLive() { if (isOpenRouter && !liveLoaded) applyLive(fetchFreeModels()) }

    /** Fetches models from OpenRouter and returns only the free ones (prompt + completion price = 0). */
    suspend fun fetchFreeModels(): List<ORModel> = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("$OR_BASE/models").openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/json")
            if (conn.responseCode != 200) return@withContext emptyList()
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONObject(text).getJSONArray("data")
            val out = ArrayList<ORModel>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val id = m.optString("id")
                if (id.isBlank()) continue
                val pr = m.optJSONObject("pricing")
                val free = pr != null &&
                    pr.optString("prompt", "1").toDoubleOrNull() == 0.0 &&
                    pr.optString("completion", "1").toDoubleOrNull() == 0.0
                if (!free) continue
                // text-output models only
                val outMods = m.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (outMods != null) {
                    var textOk = false
                    for (k in 0 until outMods.length()) if (outMods.optString(k) == "text") textOk = true
                    if (!textOk) continue
                }
                val arch = m.optJSONObject("architecture")
                val inMods = arch?.optJSONArray("input_modalities")
                var vision = false
                if (inMods != null) for (k in 0 until inMods.length()) if (inMods.optString(k) == "image") vision = true
                val sp = m.optJSONArray("supported_parameters")
                var tools = false
                if (sp != null) for (k in 0 until sp.length()) if (sp.optString(k) == "tools") tools = true
                out.add(ORModel(id, m.optString("name", id), m.optInt("context_length", 0), true, vision, tools))
            }
            out.sortedByDescending { it.contextLength }
        } catch (_: Exception) {
            emptyList()
        } finally {
            conn?.disconnect()
        }
    }

    private class ToolAcc { var id = ""; var name = ""; val args = StringBuilder() }

    private fun messageJson(msg: Message): JSONObject = JSONObject().apply {
        put("role", msg.role)
        when {
            msg.role == "tool" -> { put("tool_call_id", msg.toolCallId ?: "call_0"); put("content", msg.content) }
            msg.role == "assistant" && msg.toolCalls.isNotEmpty() -> {
                put("content", msg.content)
                put("tool_calls", JSONArray().apply {
                    msg.toolCalls.forEach { tc ->
                        put(JSONObject().put("id", tc.id).put("type", "function")
                            .put("function", JSONObject().put("name", tc.name).put("arguments", tc.arguments)))
                    }
                })
            }
            msg.images.isEmpty() -> put("content", msg.content)
            else -> put("content", JSONArray().apply {
                put(JSONObject().put("type", "text").put("text", msg.content))
                msg.images.forEach { b64 ->
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64")))
                }
            })
        }
    }

    /**
     * Streams a chat completion. [onChunk] is called for each text piece; return false to cancel.
     * Throws [ORException] on HTTP errors.
     */
    suspend fun streamChat(
        apiKey: String,
        model: String,
        messages: List<Message>,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        onChunk: (String) -> Boolean
    ): String = streamChatTools(apiKey, model, messages, null, maxTokens, temperature, onChunk).text

    /**
     * Like [streamChat] but can offer [tools] (OpenAI function-calling format, see ToolDef.spec()) and returns the tool calls the model made.
     * Models that cannot use tools answer HTTP 404/400 ("no endpoints support tool use"): the caller then tries another model or the text protocol.
     */
    suspend fun streamChatTools(
        apiKey: String,
        model: String,
        messages: List<Message>,
        tools: JSONArray?,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
        onChunk: (String) -> Boolean
    ): ChatResult = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("model", model)
            put("stream", true)
            put("max_tokens", maxTokens)
            put("temperature", temperature.toDouble())
            // Hide reasoning tokens so we never wait on / display hidden "thinking" text (an OpenRouter option, other providers reject it)
            if (isOpenRouter) put("reasoning", JSONObject().put("exclude", true))
            if (tools != null && tools.length() > 0) { put("tools", tools); put("tool_choice", "auto") }
            put("messages", JSONArray().apply { messages.forEach { put(messageJson(it)) } })
        }.toString()

        val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            if (isOpenRouter) {
                setRequestProperty("HTTP-Referer", "https://github.com/Hem1234567/Hems-ZENIQ-04")
                setRequestProperty("X-Title", "ZENIQ Android")
            }
            connectTimeout = 15_000
            readTimeout = 30_000          // free models can queue for a while; a model silent for 30 s is skipped (the next one is tried)
            doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
        }

        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = try { conn.errorStream?.bufferedReader()?.readText() ?: "" } catch (_: Exception) { "" }
                throw ORException(code, friendly(code, err))
            }

            val sb = StringBuilder()
            val acc = java.util.TreeMap<Int, ToolAcc>()
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    try {
                        val json = JSONObject(data)
                        json.optJSONObject("error")?.let { e ->
                            throw ORException(e.optInt("code", 500), e.optString("message", "Model error"))
                        }
                        val choices = json.optJSONArray("choices") ?: continue
                        if (choices.length() == 0) continue
                        val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                        val content = delta.optString("content", "")
                        if (content.isNotEmpty() && content != "null") {
                            sb.append(content)
                            if (!onChunk(content)) break
                        }
                        val tcs = delta.optJSONArray("tool_calls")
                        if (tcs != null) {
                            for (k in 0 until tcs.length()) {
                                val t = tcs.getJSONObject(k)
                                val a = acc.getOrPut(t.optInt("index", k)) { ToolAcc() }
                                val id = t.optString("id", "")
                                if (id.isNotBlank() && id != "null" && a.id.isEmpty()) a.id = id
                                val fn = t.optJSONObject("function")
                                if (fn != null) {
                                    val n = fn.optString("name", "")
                                    if (n.isNotBlank() && n != "null" && a.name.isEmpty()) a.name = n
                                    val ar = fn.opt("arguments")
                                    if (ar != null && ar != JSONObject.NULL) a.args.append(ar.toString())
                                }
                            }
                        }
                    } catch (e: ORException) {
                        throw e
                    } catch (_: Exception) { /* skip malformed SSE lines */ }
                }
            }
            val calls = ArrayList<ToolCall>()
            for ((idx, a) in acc) {
                if (a.name.isBlank()) continue
                val args = a.args.toString().trim().ifBlank { "{}" }
                calls.add(ToolCall(a.id.ifBlank { "call_$idx" }, a.name, args))
            }
            ChatResult(sb.toString(), calls)
        } finally {
            conn.disconnect()
        }
    }
}
