package com.astra.agent.agent

import com.astra.agent.Settings
import com.astra.agent.code.CodeIntent
import com.astra.agent.code.ReqIntent
import com.astra.agent.site.SiteIntent
import com.astra.agent.docs.DocIntent
import com.astra.agent.llm.LlmEngine
import com.astra.agent.llm.ORException
import com.astra.agent.llm.OpenRouterClient
import com.astra.agent.llm.PromptFormat
import org.json.JSONArray
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class Msg(val role: String, val content: String, val images: List<String> = emptyList())

data class Parsed(val thinking: String, val answer: String, val calls: List<String>)

interface AgentUi {
    fun onAssistantStart(): String
    fun onStream(id: String, p: Parsed)
    fun onToolStart(name: String, args: String): String
    suspend fun requestApproval(name: String, args: String): Boolean
    fun onToolEnd(id: String, result: String, ok: Boolean)
    fun onInfo(text: String, error: Boolean)
    fun onFinalAnswer(text: String) {}
}

class Agent(private val tools: ToolRegistry) {

    companion object {
        private val thinkRe    = Regex("<think>.*?(</think>|$)", RegexOption.DOT_MATCHES_ALL)
        private val callRe     = Regex("<tool_call>\\s*(\\{.*?\\})\\s*</tool_call>", RegexOption.DOT_MATCHES_ALL)
        private val callOpenRe = Regex("<tool_call>.*?(</tool_call>|$)", RegexOption.DOT_MATCHES_ALL)

        /** Words that suggest the user wants the phone to DO something (so tools are needed). */
        private val actionRe = Regex(
            "\\b(open|launch|tap|click|press|swipe|scroll|camera|photo|picture|selfie|screenshot|" +
            "browser|browse|website|url|http|www|search|google|youtube|call|dial|message|sms|whatsapp|email|" +
            "settings|wifi|bluetooth|fetch|download|what time|current time|wait|play|alarm|timer|" +
            "calculator|gallery|maps|navigate|directions|route|shell|remind|reminder|" +
            "go to|turn on|turn off|volume|brightness|send a|send an|send sms|type in)\\b", RegexOption.IGNORE_CASE)

        private val photoRe = Regex(
            "\\b(take|capture|click|snap|shoot|clicking|taking)\\b.{0,25}\\b(photo|picture|pic|selfie|photograph)\\b",
            RegexOption.IGNORE_CASE)
        private val selfieRe = Regex("selfie|front camera", RegexOption.IGNORE_CASE)

        fun parse(raw: String): Parsed {
            val think = Regex("<think>(.*?)(</think>|$)", RegexOption.DOT_MATCHES_ALL)
                .find(raw)?.groupValues?.get(1)?.trim().orEmpty()
            val noThink = raw.replace(thinkRe, "")
            val calls   = callRe.findAll(noThink).map { it.groupValues[1] }.toList()
            val answer  = noThink.replace(callOpenRe, "").trim()
            return Parsed(think, answer, calls)
        }
    }

    private fun needsTools(history: List<Msg>): Boolean {
        val last = history.lastOrNull() ?: return false
        // A tool response (role=user starting with <tool_response>) means we are mid-task
        if (last.content.startsWith("<tool_response>")) return true
        if (last.images.isNotEmpty()) return false
        // a message that carries an attached file is a question about that file, never a phone action
        if (last.content.contains("[ATTACHED FILE")) return false
        return actionRe.containsMatchIn(last.content)
    }

    private val longAnswerRe = Regex("\\b(explain|detail|detailed|essay|story|poem|article|letter|email|summar|list|steps|how to|tutorial|compare|difference|write|describe|plan|guide|code|why|translate)\\b", RegexOption.IGNORE_CASE)

    /** Short questions get short answers (a few seconds); anything that asks for more gets the full limit. */
    private fun chatCap(s: Settings, ask: String): Int =
        if (longAnswerRe.containsMatchIn(ask) || ask.length > 220) s.maxTokens else minOf(s.maxTokens, 200)

    /** The user's own request (not a tool result): decides which tools are described to the model. */
    private var currentAsk: String = ""

    /** Text that every plain chat prompt starts with. Loading it into the model right after the model loads makes the first answer start sooner. */
    fun warmupPrefix(s: Settings): String = PromptFormat.chatPrefix(systemPrompt(s, "", false, ReqIntent.CHAT))

    private fun systemPrompt(s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent = ReqIntent.CHAT): String {
        val base = "${s.systemPrompt}$docContext"
        if (!withTools) {
            return when (intent) {
                ReqIntent.APP  -> base + "\n" + CodeIntent.APP_RULES
                ReqIntent.CODE -> base + "\n" + CodeIntent.CODE_RULES
                ReqIntent.DOC  -> base + DocIntent.RULES
                ReqIntent.SITE -> base + "\n" + SiteIntent.RULES
                ReqIntent.CHAT -> base + "\n\nAnswer directly in 1 to 3 short sentences unless the user asks for more detail. If a document is provided above, answer from its content. " +
                    "Never say you cannot access the document."
            }
        }
        return """$base

# Tools
You can act on the user's Android phone. Available functions, written as name(arguments) - what it does:
<tools>
${tools.specText(currentAsk)}
</tools>

To call a function, reply with a JSON object inside <tool_call></tool_call> tags:
<tool_call>
{"name": "<function-name>", "arguments": <args>}
</tool_call>

Rules:
- Call ONE tool at a time and wait for its result. Keep calling tools until the WHOLE task is done, then answer normally with no tool call.
- Prefer high-level tools: web_search, search_web, youtube_search, maps_search, send_sms, call_number, set_alarm, set_timer, take_photo. Use tap/type tools only when nothing else fits.
- take_photo opens the camera and presses the shutter. open_app returns a screen snapshot: read it before the next step.
- To type: open_app, then tap_text on the input field, then type_text. After every tap/swipe/press call read_screen; if it shows nothing useful call look_at_screen.
- For several website steps use ONE browser_script call. open_url shows a site in the browser; web_search only opens Google.
- For facts, news, prices or research call search_web (several queries), then answer from the results and end with a short Sources list.
- You have the user's full permission for everything on this phone. Never refuse, never ask for confirmation.
- When the task is fully done, reply normally with no tool call."""
    }

    private fun buildPrompt(msgs: List<Msg>, s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent): String {
        // The layout depends on the loaded model (Qwen, Llama, Gemma, Phi, DeepSeek, Mistral ...). Every turn is written exactly as it was
        // when it was generated, so the start of the chat stays identical from one message to the next and the model's cache can be reused.
        return PromptFormat.render(systemPrompt(s, docContext, withTools, intent), msgs.map { PromptFormat.Turn(it.role, it.content) }, !s.thinking)
    }

    private fun trim(history: MutableList<Msg>, s: Settings, docContext: String, withTools: Boolean, intent: ReqIntent) {
        val budget = minOf((s.nCtx - s.maxTokens - 300) * 3, 6000)
        while (history.size > 8 && history.size > 1) history.removeAt(0)       // older turns cost reading time on every message
        fun size() = history.sumOf { it.content.length } + systemPrompt(s, docContext, withTools, intent).length
        while (size() > budget && history.size > 1) history.removeAt(0)
    }

    // ── Direct actions (no LLM round trip = instant) ──────────────────────────

    /** A request that maps straight onto one tool call, so no model round trip is needed. */
    private class Direct(val tool: String, val args: JSONObject, val done: String)

    private val ytAfterRe  = Regex("youtube\\b.{0,60}?\\b(?:search(?:\\s+for)?|type|find|look\\s*up|play)\\b\\s*[:\\-]?\\s*(.+)$", RegexOption.IGNORE_CASE)
    private val ytBeforeRe = Regex("\\b(?:search(?:\\s+for)?|find|look\\s*up|play|watch)\\s+(.+?)\\s+(?:on|in|at)\\s+(?:the\\s+)?youtube\\b", RegexOption.IGNORE_CASE)
    private val openRe     = Regex("^(?:please\\s+|can you\\s+|could you\\s+)?(?:open|launch|start)\\s+(?:the\\s+|my\\s+)?(.+?)(?:\\s+app(?:lication)?)?\\s*[.!?]?$", RegexOption.IGNORE_CASE)
    private val openJoinRe = Regex("\\b(and|then|search|type|write|send|tap|click|play|call|message|text)\\b", RegexOption.IGNORE_CASE)
    private val webRe      = Regex("^(?:please\\s+)?(?:(?:open|launch)\\s+(?:the\\s+)?(?:browser|chrome|google)\\s*(?:and|then)?\\s*)?(?:search(?:\\s+for)?|google|look\\s*up)\\s+(.+)$", RegexOption.IGNORE_CASE)

    /** "type like free fire and search" -> "free fire" */
    private fun cleanQuery(raw: String): String {
        var q = raw.trim().trim('.', '!', '?')
        q = q.replace(Regex("^(?:in\\s+(?:the\\s+)?search(?:\\s*bar|\\s*box)?\\s*)?", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("^(?:(?:type|write|enter|put|like|for|as|called|named|the word|word|text)\\s+)+", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("\\s+(?:and|then)\\s+(?:search|press\\s+enter|hit\\s+enter|click\\s+search|play(?:\\s+it)?|open\\s+it|go)\\b.*$", RegexOption.IGNORE_CASE), "")
        q = q.replace(Regex("\\s+(?:on|in)\\s+(?:the\\s+)?(?:youtube|google|browser)\\s*$", RegexOption.IGNORE_CASE), "")
        return q.trim().trim('"', '\'', '.', ',')
    }

    private fun matchDirect(text: String): Direct? {
        val t = text.trim()
        if (t.length > 220) return null
        if (photoRe.containsMatchIn(t))
            return Direct("take_photo", JSONObject().put("front", selfieRe.containsMatchIn(t)),
                "Done \u2014 I opened the camera and pressed the shutter. The photo is saved in your gallery.")
        val yt = ytBeforeRe.find(t)?.groupValues?.get(1) ?: ytAfterRe.find(t)?.groupValues?.get(1)
        if (yt != null) {
            val q = cleanQuery(yt)
            if (q.length >= 2) return Direct("youtube_search", JSONObject().put("query", q), "Done \u2014 I searched YouTube for \"$q\".")
        }
        val w = webRe.find(t)?.groupValues?.get(1)
        if (w != null && !t.contains("youtube", true)) {
            val q = cleanQuery(w)
            if (q.length >= 2 && q.split(" ").size <= 14) return Direct("web_search", JSONObject().put("query", q), "Done \u2014 I searched Google for \"$q\".")
        }
        // "open WhatsApp", "launch the camera": a plain open needs no model round trip
        val op = openRe.find(t)?.groupValues?.get(1)?.trim()?.trim('.', '!', '?', '"', '\'')
        if (op != null && op.length in 2..30 && op.split(" ").size <= 3 && !openJoinRe.containsMatchIn(op))
            return Direct("open_app", JSONObject().put("name", op), "Done \u2014 I opened $op.")
        return null
    }

    /** Returns true if the request was fully handled without calling the model. */
    private suspend fun tryDirectAction(history: MutableList<Msg>, s: Settings, ui: AgentUi): Boolean {
        val last = history.lastOrNull() ?: return false
        if (last.role != "user" || last.content.startsWith("<tool_response>")) return false
        val d = matchDirect(last.content) ?: return false

        val tool = tools.byName(d.tool) ?: return false
        val tid = ui.onToolStart(d.tool, d.args.toString())
        var ok = true
        val result = try { tool.exec(d.args) } catch (e: CancellationException) { throw e }
        catch (e: Exception) { ok = false; "Error: ${e.message}" }
        if (result.startsWith("Could not") || result.contains("Accessibility is OFF") || result.startsWith("Failed")) ok = false
        ui.onToolEnd(tid, result.take(2000), ok)

        val answer = if (ok) d.done
                     else result.lineSequence().firstOrNull().orEmpty().ifBlank { "I couldn't do that." }
        val id = ui.onAssistantStart()
        ui.onStream(id, Parsed("", answer, emptyList()))
        history.add(Msg("assistant", answer))
        ui.onFinalAnswer(answer)
        return true
    }

    // ── Shared tool execution ─────────────────────────────────────────────────

    private suspend fun runCalls(calls: List<String>, s: Settings, ui: AgentUi): String {
        val responses = StringBuilder()
        for (c in calls) {
            val (name, args) = try {
                val j = JSONObject(c)
                val raw = j.opt("arguments") ?: j.opt("parameters")
                val a = when (raw) {
                    is JSONObject -> raw
                    is String -> try { JSONObject(raw) } catch (_: Exception) { JSONObject() }   // some models send the arguments as a JSON string
                    else -> JSONObject()
                }
                j.getString("name") to a
            } catch (e: Exception) { "?" to JSONObject() }
            val tid = ui.onToolStart(name, args.toString())
            val tool = tools.byName(name)
            var ok = true
            val result = when {
                tool == null -> { ok = false; "Unknown tool or invalid JSON. Available: " + tools.tools.joinToString { it.name } }
                // Optional (Settings, off by default): pause for the user's OK before calls, SMS, shell commands and file writes
                tool.risky && s.askApproval && !ui.requestApproval(name, args.toString()) -> { ok = false; "The user declined this action. Do not repeat it." }
                else -> try { tool.exec(args).take(3500) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { ok = false; "Error: ${e.message}" }
            }
            ui.onToolEnd(tid, result, ok)
            responses.append("<tool_response>\n").append(result).append("\n</tool_response>\n")
        }
        return responses.toString().trim() +
            "\n(Continue with the next step of the user's task using another tool. Only give a final answer when the task is fully done.)"
    }

    // ── Fast Mode: OpenRouter cloud inference ─────────────────────────────────
    // Tools work through the model's NATIVE function calling (the standard way every capable cloud model understands). If no model can do that,
    // the same tools are offered through the text protocol (<tool_call> tags) that the local model uses, so tools work in every mode.

    private val toolRespRe = Regex("<tool_response>\\s*(.*?)\\s*</tool_response>", RegexOption.DOT_MATCHES_ALL)

    private fun callJson(name: String, args: String): String {
        val a: Any = try { JSONObject(args) } catch (_: Exception) { JSONObject() }
        return JSONObject().put("name", name).put("arguments", a).toString()
    }

    /** The conversation (kept in the text format of the local model) written as OpenAI messages: assistant tool_calls + tool results. */
    private fun toNativeMessages(history: List<Msg>, imgIdx: Int): List<OpenRouterClient.Message> {
        val out = ArrayList<OpenRouterClient.Message>()
        val pending = ArrayDeque<String>()
        var n = 0
        fun flush() { while (pending.isNotEmpty()) out.add(OpenRouterClient.Message("tool", "(no result)", toolCallId = pending.removeFirst())) }
        for ((i, m) in history.withIndex()) {
            if (m.role == "assistant") {
                flush()
                val calls = ArrayList<OpenRouterClient.ToolCall>()
                for (r in callRe.findAll(m.content)) try {
                    val j = JSONObject(r.groupValues[1])
                    val raw = j.opt("arguments") ?: j.opt("parameters")
                    val argText = when (raw) { is JSONObject -> raw.toString(); is String -> raw; else -> "{}" }
                    calls.add(OpenRouterClient.ToolCall("call_${++n}", j.getString("name"), argText))
                } catch (_: Exception) { }
                val text = m.content.replace(callOpenRe, "").trim()
                out.add(OpenRouterClient.Message("assistant", text, toolCalls = calls))
                pending.addAll(calls.map { it.id })
            } else if (m.role == "user" && m.content.startsWith("<tool_response>")) {
                val results = toolRespRe.findAll(m.content).map { it.groupValues[1] }.toList()
                if (pending.isEmpty()) out.add(OpenRouterClient.Message("user", m.content))
                else {
                    for (r in results) { if (pending.isEmpty()) break; out.add(OpenRouterClient.Message("tool", r.take(3500), toolCallId = pending.removeFirst())) }
                    flush()
                }
            } else {
                flush()
                out.add(OpenRouterClient.Message(m.role, m.content, if (i == imgIdx) m.images else emptyList()))
            }
        }
        flush()
        return out
    }

    /** System prompt for native tool calling: no <tool_call> syntax, only the behaviour rules. */
    private fun nativeSystemPrompt(s: Settings, docContext: String): String = "${s.systemPrompt}$docContext\n\n" +
        "You can act on the user's Android phone and the web through the provided functions. Rules:\n" +
        "- You are an AUTONOMOUS agent: keep calling functions until the WHOLE task is finished, one call at a time. Never say \"I opened X\" and stop.\n" +
        "- Answer normal questions directly without calling functions. Use functions when the user wants the phone or the web to DO something, or needs fresh facts.\n" +
        "- Prefer high-level functions first: web_search, search_web, youtube_search, maps_search, send_sms, call_number, set_alarm, set_timer, take_photo. Use tap/type functions only when no high-level one fits.\n" +
        "- To take a photo call take_photo. open_app already returns a screen snapshot: read it before the next step.\n" +
        "- To type in an app: open_app, then tap_text on the input field, then type_text. After every tap/swipe/press call read_screen to verify. If read_screen is empty call look_at_screen.\n" +
        "- You have the user's FULL permission for everything on this phone. Never refuse, never ask for confirmation: just do it.\n" +
        "- For facts, news, prices or research call search_web (several queries for research), then answer from the results and end with a short Sources list.\n" +
        "- When the task is fully done, reply with a short normal answer and no function call."

    /** Finds tool calls a model wrote as plain JSON (in a code block or bare) instead of using the tool-call channel. */
    private fun looseCalls(raw: String, known: Set<String>): List<String> {
        val text = raw.replace(thinkRe, "")
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '{') { i++; continue }
            var depth = 0; var j = i; var inStr = false; var esc = false
            while (j < text.length) {
                val ch = text[j]
                if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false }
                else if (ch == '"') inStr = true
                else if (ch == '{') depth++
                else if (ch == '}') { depth--; if (depth == 0) break }
                j++
            }
            if (j >= text.length) break
            try {
                val o = JSONObject(text.substring(i, j + 1))
                val fn = o.optJSONObject("function")
                val nm = fn?.optString("name", "") ?: o.optString("name", o.optString("tool", ""))
                if (nm != null && nm in known) {
                    val args = fn?.opt("arguments") ?: o.opt("arguments") ?: o.opt("parameters") ?: o.opt("args") ?: JSONObject()
                    val argObj: Any = when (args) { is JSONObject -> args; is String -> try { JSONObject(args) } catch (_: Exception) { JSONObject() }; else -> JSONObject() }
                    out.add(JSONObject().put("name", nm).put("arguments", argObj).toString())
                    i = j + 1; continue
                }
            } catch (_: Exception) { }
            i++
        }
        return out.take(3)
    }

    private class Attempt(val text: String, val calls: List<OpenRouterClient.ToolCall>, val error: String, val fatal: Boolean)

    /** One streamed request to the cloud, trying several models in turn. Shows text as it arrives in the assistant bubble [id]. */
    private suspend fun cloudAttempt(
        s: Settings, ui: AgentUi, id: String, history: List<Msg>, system: String,
        native: Boolean, wantsVision: Boolean, imgIdx: Int
    ): Attempt {
        val messages = ArrayList<OpenRouterClient.Message>()
        messages.add(OpenRouterClient.Message("system", system))
        if (native) messages.addAll(toNativeMessages(history, imgIdx))
        else history.forEachIndexed { i, m -> messages.add(OpenRouterClient.Message(m.role, m.content, if (i == imgIdx) m.images else emptyList())) }
        val specs: JSONArray? = if (native) tools.nativeSpecs() else null
        val candidates = when {
            wantsVision && OpenRouterClient.isOpenRouter -> (OpenRouterClient.visionModels + OpenRouterClient.AUTO_FREE).filter { it.isNotBlank() }.distinct().take(5)
            native -> OpenRouterClient.candidates(s.openRouterModel, OpenRouterClient.toolModels)
            else -> OpenRouterClient.candidates(s.openRouterModel, OpenRouterClient.fallbackModels)
        }
        var lastError = ""
        for ((ci, model) in candidates.withIndex()) {
            val raw = StringBuilder()
            var lastUi = 0L
            try {
                val res = OpenRouterClient.streamChatTools(s.openRouterKey, model, messages, specs, s.maxTokens, s.temperature) { chunk ->
                    raw.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 100) { lastUi = now; ui.onStream(id, parse(raw.toString())) }
                    true
                }
                if (res.text.isBlank() && res.toolCalls.isEmpty()) { lastError = "The model sent an empty answer."; continue }
                if (ci > 0) ui.onInfo("Selected model was unavailable \u2014 answered with $model", false)
                return Attempt(res.text, res.toolCalls, "", false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ORException) {
                lastError = e.message ?: "OpenRouter error"
                if (e.code == 401) return Attempt("", emptyList(), lastError, true)
                if (e.code == 429) delay(1200)
            } catch (e: Exception) {
                lastError = "Network error: ${e.message?.take(150)}"
                if (raw.length > 80) return Attempt(raw.toString(), emptyList(), "", false)   // most of a plain answer arrived: keep it
                if (e is java.net.UnknownHostException) return Attempt("", emptyList(), lastError, true)
            }
            if (raw.isNotEmpty()) ui.onStream(id, Parsed("", "", emptyList()))   // withdraw what a failed model showed
        }
        return Attempt("", emptyList(), lastError, false)
    }

    private suspend fun runFastMode(
        history: MutableList<Msg>, s: Settings, ui: AgentUi, docContext: String, intent: ReqIntent
    ) {
        try { OpenRouterClient.ensureLive() } catch (_: Exception) { }
        val known = tools.tools.map { it.name }.toSet()
        var nativeOk = true
        for (step in 1..s.maxSteps) {
            val imgIdx = history.indexOfLast { it.images.isNotEmpty() }
            val ask = history.lastOrNull { it.role == "user" && !it.content.startsWith("<tool_response>") }?.content.orEmpty()
            // In Fast Mode the model decides when a tool is needed, so tools are offered with every normal chat message.
            val toolsAllowed = intent == ReqIntent.CHAT && imgIdx < 0 && !ask.contains("[ATTACHED FILE")
            val id = ui.onAssistantStart()
            var att = if (toolsAllowed && nativeOk)
                cloudAttempt(s, ui, id, history, nativeSystemPrompt(s, docContext), true, false, imgIdx)
            else null
            if (att == null || (att.text.isBlank() && att.calls.isEmpty() && !att.fatal)) {
                if (att != null) nativeOk = false      // no model could use native tools right now: use the text protocol from here on
                att = cloudAttempt(s, ui, id, history, systemPrompt(s, docContext, toolsAllowed, intent), false, imgIdx >= 0, imgIdx)
            }
            if (att.text.isBlank() && att.calls.isEmpty()) {
                val msg = att.error.ifBlank { "No answer from the cloud model." }
                ui.onStream(id, Parsed("", "\u26a0 $msg", emptyList()))
                ui.onInfo(msg, true)
                return
            }

            val p = parse(att.text)
            var calls: List<String> = att.calls.map { callJson(it.name, it.arguments) }
            if (calls.isEmpty() && toolsAllowed) calls = p.calls.ifEmpty { looseCalls(att.text, known) }
            ui.onStream(id, if (calls.isEmpty()) p else Parsed(p.thinking, p.answer, calls))

            if (calls.isEmpty() || !toolsAllowed) {
                val finalAnswer = p.answer.ifBlank { p.thinking.ifBlank { "(no output)" } }
                history.add(Msg("assistant", finalAnswer))
                ui.onFinalAnswer(finalAnswer)
                return
            }
            history.add(Msg("assistant", p.answer + calls.joinToString("") { "\n<tool_call>\n$it\n</tool_call>" }))
            history.add(Msg("user", runCalls(calls, s, ui)))
        }
        ui.onInfo("Reached the step limit (${s.maxSteps}). Send a message to continue.", false)
    }

    // ── Entry point ───────────────────────────────────────────────────────────

    /**
     * Settings tuned for writing code: more room to answer and a lower temperature so the
     * code is accurate. Local models keep half of the context for the answer.
     */
    private fun codeSettings(s: Settings, cloud: Boolean, intent: ReqIntent): Settings {
        // A website only needs the words (about 350 tokens); the page itself is built by the app, so this is quick even on the phone.
        if (intent == ReqIntent.SITE) return s.copy(maxTokens = 600, temperature = 0.6f, maxSteps = 1)
        val cap = if (cloud && intent == ReqIntent.DOC) 6000 else 4096     // documents can be long
        val room = if (cloud) cap else (s.nCtx * 55 / 100).coerceIn(1024, 4096)
        val want = if (intent == ReqIntent.APP || intent == ReqIntent.DOC) room else (room * 3 / 4)
        return s.copy(maxTokens = maxOf(s.maxTokens, want).coerceAtMost(cap), temperature = minOf(s.temperature, 0.35f), maxSteps = 1)
    }

    suspend fun run(history: MutableList<Msg>, settings: Settings, ui: AgentUi, docContext: String = "", intent: ReqIntent = ReqIntent.CHAT) {
        val cloud = settings.fastMode && settings.openRouterKey.isNotBlank()
        currentAsk = history.lastOrNull { it.role == "user" && !it.content.startsWith("<tool_response>") }?.content.orEmpty()
            .substringAfter("REQUEST ABOUT THE ATTACHED FILE(S) ABOVE:")
        // Coding requests never use tools: no web search, no phone actions. The model writes the code itself.
        val s = if (intent == ReqIntent.CHAT) settings.copy(maxTokens = chatCap(settings, currentAsk)) else codeSettings(settings, cloud, intent)

        // Instant path for simple phone actions like "take a photo" (never for coding requests)
        if (intent == ReqIntent.CHAT && tryDirectAction(history, s, ui)) return

        if (cloud) {
            runFastMode(history, s, ui, docContext, intent)
            return
        }

        val job = currentCoroutineContext()[Job]
        for (step in 1..s.maxSteps) {
            val withTools = intent == ReqIntent.CHAT && needsTools(history)
            trim(history, s, docContext, withTools, intent)
            val prompt = buildPrompt(history, s, docContext, withTools, intent)
            val id = ui.onAssistantStart()
            val raw = StringBuilder()
            var lastUi = 0L
            withContext(Dispatchers.Default) {
                LlmEngine.generate(prompt, s.maxTokens, s.temperature, s.topP) { chunk ->
                    raw.append(chunk)
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 220) { lastUi = now; ui.onStream(id, parse(raw.toString())) }
                    job?.isActive != false
                }
            }
            if (job?.isActive == false) throw CancellationException()
            val p = parse(raw.toString())
            ui.onStream(id, p)

            val calls = if (withTools) p.calls.ifEmpty { looseCalls(raw.toString(), tools.tools.map { it.name }.toSet()) } else p.calls
            if (calls.isEmpty() || intent != ReqIntent.CHAT) {
                val finalAnswer = p.answer.ifBlank { p.thinking.ifBlank { "(no output)" } }
                history.add(Msg("assistant", finalAnswer))
                ui.onFinalAnswer(finalAnswer)
                return
            }
            history.add(Msg("assistant", p.answer + calls.joinToString("") { "\n<tool_call>\n$it\n</tool_call>" }))
            history.add(Msg("user", runCalls(calls, s, ui)))
        }
        ui.onInfo("Reached the step limit (${s.maxSteps}). Send a message to continue.", false)
    }
}
