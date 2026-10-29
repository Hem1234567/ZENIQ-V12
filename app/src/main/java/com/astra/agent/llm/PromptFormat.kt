package com.astra.agent.llm

// Pure Kotlin (no Android imports).
// Every model family expects its own chat layout. ZENIQ used to write Qwen's layout (ChatML) for every model, which made Llama, Gemma,
// Phi, DeepSeek, Mistral ... answer badly. The layout is now detected from the model's own template (or its file name) when it loads.

enum class ChatFmt { CHATML, LLAMA3, GEMMA, PHI, ZEPHYR, MISTRAL, DEEPSEEK, GRANITE }

object PromptFormat {
    @Volatile var fmt: ChatFmt = ChatFmt.CHATML
        private set
    /** Qwen3 style models have a "/no_think" switch and an empty think block that skips the slow thinking phase. */
    @Volatile var thinkSwitch: Boolean = true
        private set
    /** DeepSeek R1 style models always start with <think>; an empty block makes them answer straight away. */
    @Volatile var emptyThinkBlock: Boolean = true
        private set

    private const val BAR = "\uFF5C"        // fullwidth vertical line used by DeepSeek special tokens
    private const val BLK = "\u2581"        // lower one eighth block used by DeepSeek special tokens

    fun detect(template: String?, fileName: String): ChatFmt {
        val t = template ?: ""
        val n = fileName.lowercase()
        return when {
            t.contains("<|im_start|>") -> ChatFmt.CHATML
            t.contains("<|start_header_id|>") -> ChatFmt.LLAMA3
            t.contains("<start_of_turn>") -> ChatFmt.GEMMA
            t.contains("<|start_of_role|>") -> ChatFmt.GRANITE
            t.contains("<" + BAR + "User" + BAR + ">") -> ChatFmt.DEEPSEEK
            t.contains("[INST]") -> ChatFmt.MISTRAL
            t.contains("<|user|>") && t.contains("<|end|>") -> ChatFmt.PHI
            t.contains("<|user|>") -> ChatFmt.ZEPHYR
            n.contains("gemma") -> ChatFmt.GEMMA
            n.contains("llama-3") || n.contains("llama3") || n.contains("llama_3") -> ChatFmt.LLAMA3
            n.contains("phi-3") || n.contains("phi-4") || n.contains("phi3") || n.contains("phi4") -> ChatFmt.PHI
            n.contains("deepseek-r1") -> ChatFmt.DEEPSEEK
            n.contains("granite") -> ChatFmt.GRANITE
            n.contains("tinyllama") || n.contains("stablelm") -> ChatFmt.ZEPHYR
            n.contains("mistral") || n.contains("codellama") -> ChatFmt.MISTRAL
            else -> ChatFmt.CHATML
        }
    }

    /** Called when a model finishes loading. */
    fun configure(template: String?, fileName: String) {
        val f = detect(template, fileName)
        val n = fileName.lowercase()
        fmt = f
        thinkSwitch = f == ChatFmt.CHATML && (n.contains("qwen3") || n.contains("qwen-3"))
        emptyThinkBlock = thinkSwitch || f == ChatFmt.DEEPSEEK
    }

    /** For tests and previews: force a layout. */
    fun forceForTest(f: ChatFmt, think: Boolean) { fmt = f; thinkSwitch = think && f == ChatFmt.CHATML; emptyThinkBlock = think }

    /** Roles: "user" / "assistant". A user turn that carries a tool result is passed through unchanged. */
    class Turn(val role: String, val content: String)

    /**
     * The full prompt, ending where the assistant starts to write.
     * [noThink] = the "thinking" setting is OFF: ask thinking models to skip their thinking phase.
     */
    fun render(system: String, turns: List<Turn>, noThink: Boolean): String {
        val sb = StringBuilder()
        val skipThink = noThink && emptyThinkBlock
        val emptyBlock = "<think>\n\n</think>\n\n"
        fun userText(t: Turn): String =
            if (noThink && thinkSwitch && t.role == "user" && !t.content.startsWith("<tool_response>")) t.content + " /no_think" else t.content
        fun asstText(t: Turn): String = (if (skipThink && fmt != ChatFmt.CHATML || skipThink && thinkSwitch) emptyBlock else "") + t.content

        when (fmt) {
            ChatFmt.CHATML -> {
                sb.append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                for (t in turns) sb.append("<|im_start|>").append(t.role).append("\n")
                    .append(if (t.role == "assistant") asstText(t) else userText(t)).append("<|im_end|>\n")
                sb.append("<|im_start|>assistant\n")
                if (skipThink && thinkSwitch) sb.append(emptyBlock)
            }
            ChatFmt.LLAMA3 -> {
                sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(system).append("<|eot_id|>")
                for (t in turns) sb.append("<|start_header_id|>").append(t.role).append("<|end_header_id|>\n\n").append(t.content).append("<|eot_id|>")
                sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            ChatFmt.GEMMA -> {
                var first = true
                for (t in turns) {
                    sb.append("<start_of_turn>").append(if (t.role == "assistant") "model" else "user").append("\n")
                    if (first && t.role == "user") sb.append(system).append("\n\n")
                    first = false
                    sb.append(t.content).append("<end_of_turn>\n")
                }
                sb.append("<start_of_turn>model\n")
            }
            ChatFmt.PHI -> {
                sb.append("<|system|>\n").append(system).append("<|end|>\n")
                for (t in turns) sb.append("<|").append(t.role).append("|>\n").append(t.content).append("<|end|>\n")
                sb.append("<|assistant|>\n")
            }
            ChatFmt.ZEPHYR -> {
                sb.append("<|system|>\n").append(system).append("</s>\n")
                for (t in turns) sb.append("<|").append(t.role).append("|>\n").append(t.content).append("</s>\n")
                sb.append("<|assistant|>\n")
            }
            ChatFmt.MISTRAL -> {
                var first = true
                for (t in turns) {
                    if (t.role == "user") {
                        sb.append("[INST] ")
                        if (first) sb.append(system).append("\n\n")
                        first = false
                        sb.append(t.content).append(" [/INST]")
                    } else sb.append(" ").append(t.content).append("</s>")
                }
            }
            ChatFmt.DEEPSEEK -> {
                sb.append(system)
                for (t in turns) {
                    if (t.role == "user") sb.append("<" + BAR + "User" + BAR + ">").append(t.content)
                    else sb.append("<" + BAR + "Assistant" + BAR + ">").append(t.content).append("<" + BAR + "end" + BLK + "of" + BLK + "sentence" + BAR + ">")
                }
                sb.append("<" + BAR + "Assistant" + BAR + ">")
                if (skipThink) sb.append(emptyBlock)
            }
            ChatFmt.GRANITE -> {
                sb.append("<|start_of_role|>system<|end_of_role|>").append(system).append("<|end_of_text|>\n")
                for (t in turns) sb.append("<|start_of_role|>").append(t.role).append("<|end_of_role|>").append(t.content).append("<|end_of_text|>\n")
                sb.append("<|start_of_role|>assistant<|end_of_role|>")
            }
        }
        return sb.toString()
    }

    /** The fixed start every chat prompt shares (system text + the opening of the first user turn). Reading it early makes the first answer start sooner. */
    fun chatPrefix(system: String): String {
        val mark = "\u0001MARK\u0001"
        val full = render(system, listOf(Turn("user", mark)), false)
        return full.substringBefore(mark)
    }

    /** One-shot question without history: system + user. */
    fun single(system: String, user: String, noThink: Boolean = true): String = render(system, listOf(Turn("user", user)), noThink)
}
