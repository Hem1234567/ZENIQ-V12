package com.astra.agent.llm

// Pure Kotlin (no Android imports) so it is trivially safe to compile.
// Generated from "Local_AI_Models_Under_12GB_RAM.xlsx" (Model Catalog sheet, 87 models).

enum class ModelKind { TEXT, VISION, OTHER }

/** Primary category of a model (taken from the Excel "Category" column). */
enum class ModelCat(val label: String) {
    GENERAL("Chat"), CODING("Coding"), REASONING("Reasoning"), AGENT("Agent / tools"),
    INDIAN("Indian languages"), VISION("Vision"), EMBED("Embeddings"), SPEECH("Speech-to-text"),
    TTS("Text-to-speech"), IMAGE("Image generation")
}

enum class Fit { Great, Ok, TooBig }

data class CatalogModel(
    val name: String,
    val sizeLabel: String,
    val sizeGb: Double,
    val url: String,
    val note: String,
    val kind: ModelKind = ModelKind.TEXT,
    val mmprojUrl: String? = null,
    val family: String = "",
    val params: String = "",
    val cat: ModelCat = ModelCat.GENERAL,
    val bestUse: String = "",
    val ramLabel: String = "",
    val ramNeedGb: Double = sizeGb + 1.6,
    val suitability: String = "",
    val pageUrl: String = "",
    val score: Int = 50,
    val runnable: Boolean = true
) {
    /** True when ZENIQ can download the file itself (one direct GGUF link). */
    val downloadable: Boolean get() = url.isNotBlank()
}

private val AGENT_FAMILIES = setOf("Qwen3", "Qwen2.5", "Llama 3.1", "Llama 3.2", "Phi-4-mini", "Granite 3.1")
private val MULTILINGUAL_FAMILIES = setOf("Qwen3", "Gemma 3")

/** "What do you want to use it for?" choices shown on the Models page. */
enum class UseCase(val label: String, val blurb: String) {
    GENERAL("Everyday chat", "Questions, writing, summaries and general help."),
    CODING("Coding", "Write, explain and debug code."),
    REASONING("Reasoning & math", "Step-by-step thinking, logic and maths."),
    LIGHT("Fast & light", "Small and quick. Best for older or low-RAM phones."),
    AGENT("Agent & tools", "Tool use and multi-step agent tasks."),
    INDIAN("Indian languages", "Multilingual models that cover Indian languages. Bigger models understand them better."),
    VISION("Images & documents", "Models that understand pictures. ZENIQ reads pictures with Fast Mode today; local vision models are listed for reference."),
    EXTRAS("Voice, search & images", "Speech-to-text, voices, document search, image generation.");

    fun matches(m: CatalogModel): Boolean = when (this) {
        GENERAL -> m.cat == ModelCat.GENERAL
        CODING -> m.cat == ModelCat.CODING
        REASONING -> m.cat == ModelCat.REASONING
        LIGHT -> m.runnable && m.kind == ModelKind.TEXT && m.ramNeedGb <= 2.2 &&
                (m.cat == ModelCat.GENERAL || m.cat == ModelCat.CODING || m.cat == ModelCat.REASONING)
        AGENT -> m.cat == ModelCat.AGENT ||
                (m.runnable && m.cat == ModelCat.GENERAL && m.ramNeedGb >= 2.0 && m.family in AGENT_FAMILIES)
        INDIAN -> m.cat == ModelCat.INDIAN ||
                (m.runnable && m.cat == ModelCat.GENERAL && m.ramNeedGb >= 2.0 && m.family in MULTILINGUAL_FAMILIES)
        VISION -> m.cat == ModelCat.VISION
        EXTRAS -> m.cat == ModelCat.EMBED || m.cat == ModelCat.SPEECH ||
                m.cat == ModelCat.TTS || m.cat == ModelCat.IMAGE
    }
}

object ModelCatalog {
    val all: List<CatalogModel> = listOf(
        CatalogModel(
            name = "DeepSeek-R1-Distill-Qwen 1.5B (Q4_K_M)", sizeLabel = "1.1 GB", sizeGb = 1.1, url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            note = "Use Q4 GGUF; smaller reasoning model.", kind = ModelKind.TEXT, family = "DeepSeek-R1-Distill-Qwen", params = "1.5B",
            cat = ModelCat.REASONING, bestUse = "Math, logic, explanations", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good on 4–12 GB devices", pageUrl = "https://ollama.com/library/deepseek-r1", score = 76, runnable = true
        ),
        CatalogModel(
            name = "DeepSeek-R1-Distill-Qwen 7B (Q4_K_M)", sizeLabel = "4.7 GB", sizeGb = 4.7, url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            note = "Q4 model file around 4–5 GB; runtime/context adds memory.", kind = ModelKind.TEXT, family = "DeepSeek-R1-Distill-Qwen", params = "7B",
            cat = ModelCat.REASONING, bestUse = "Reasoning, maths, code analysis", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB; not ideal on phones", pageUrl = "https://ollama.com/library/deepseek-r1", score = 88, runnable = true
        ),
        CatalogModel(
            name = "DeepSeek-R1-Distill-Qwen 8B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check exact release and quantization; variants differ.", kind = ModelKind.TEXT, family = "DeepSeek-R1-Distill-Qwen", params = "8B",
            cat = ModelCat.REASONING, bestUse = "Reasoning, code analysis", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB; not ideal on phones", pageUrl = "https://ollama.com/library/deepseek-r1", score = 50, runnable = false
        ),
        CatalogModel(
            name = "DeepSeek-R1-Distill-Llama 8B (Q4_K_M)", sizeLabel = "4.9 GB", sizeGb = 4.9, url = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Llama-8B-GGUF/resolve/main/DeepSeek-R1-Distill-Llama-8B-Q4_K_M.gguf",
            note = "Use quantized version; check exact model card.", kind = ModelKind.TEXT, family = "DeepSeek-R1-Distill-Llama", params = "8B",
            cat = ModelCat.REASONING, bestUse = "Reasoning, explanations", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB; not ideal on phones", pageUrl = "https://huggingface.co/models?search=DeepSeek-R1-Distill-Llama", score = 86, runnable = true
        ),
        CatalogModel(
            name = "DeepSeek-Coder 1.3B (Q4_K_M)", sizeLabel = "900 MB", sizeGb = 0.9, url = "https://huggingface.co/TheBloke/deepseek-coder-1.3b-instruct-GGUF/resolve/main/deepseek-coder-1.3b-instruct.Q4_K_M.gguf",
            note = "Legacy family; verify format availability.", kind = ModelKind.TEXT, family = "DeepSeek-Coder", params = "1.3B",
            cat = ModelCat.CODING, bestUse = "Code completion, programming help", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good on many phones", pageUrl = "https://huggingface.co/models?search=DeepSeek-Coder", score = 52, runnable = true
        ),
        CatalogModel(
            name = "DeepSeek-Coder 6.7B (Q4_K_M)", sizeLabel = "4.1 GB", sizeGb = 4.1, url = "https://huggingface.co/TheBloke/deepseek-coder-6.7B-instruct-GGUF/resolve/main/deepseek-coder-6.7b-instruct.Q4_K_M.gguf",
            note = "Q4 quantization recommended.", kind = ModelKind.TEXT, family = "DeepSeek-Coder", params = "6.7B",
            cat = ModelCat.CODING, bestUse = "Code generation, programming", ramLabel = "5–8+ GB", ramNeedGb = 6.5,
            suitability = "Heavy for phones", pageUrl = "https://huggingface.co/models?search=DeepSeek-Coder", score = 68, runnable = true
        ),
        CatalogModel(
            name = "DeepSeek-R1-0528-Qwen3 8B (Q4_K_M)", sizeLabel = "5.0 GB", sizeGb = 5.0, url = "https://huggingface.co/unsloth/DeepSeek-R1-0528-Qwen3-8B-GGUF/resolve/main/DeepSeek-R1-0528-Qwen3-8B-Q4_K_M.gguf",
            note = "Check current quantized builds.", kind = ModelKind.TEXT, family = "DeepSeek-R1-0528-Qwen3", params = "8B",
            cat = ModelCat.REASONING, bestUse = "Reasoning, math", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB", pageUrl = "https://huggingface.co/models?search=DeepSeek-R1-0528-Qwen3", score = 94, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 0.5B (Q4_K_M)", sizeLabel = "400 MB", sizeGb = 0.4, url = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            note = "Q4 GGUF where available.", kind = ModelKind.TEXT, family = "Qwen2.5", params = "0.5B",
            cat = ModelCat.GENERAL, bestUse = "Light chat, short answers", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Excellent lightweight option", pageUrl = "https://ollama.com/library/qwen2.5", score = 50, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 1.5B (Q4_K_M)", sizeLabel = "1.1 GB", sizeGb = 1.1, url = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            note = "Good low-memory baseline.", kind = ModelKind.TEXT, family = "Qwen2.5", params = "1.5B",
            cat = ModelCat.GENERAL, bestUse = "Chat, simple coding", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/qwen2.5", score = 64, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 3B (Q4_K_M)", sizeLabel = "2.0 GB", sizeGb = 2.0, url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            note = "Q4 quantization recommended.", kind = ModelKind.TEXT, family = "Qwen2.5", params = "3B",
            cat = ModelCat.GENERAL, bestUse = "Chat, summaries, simple code", ramLabel = "3–4 GB", ramNeedGb = 3.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/qwen2.5", score = 74, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 7B (Q4_K_M)", sizeLabel = "4.7 GB", sizeGb = 4.7, url = "https://huggingface.co/bartowski/Qwen2.5-7B-Instruct-GGUF/resolve/main/Qwen2.5-7B-Instruct-Q4_K_M.gguf",
            note = "Can be slow; context length matters.", kind = ModelKind.TEXT, family = "Qwen2.5", params = "7B",
            cat = ModelCat.GENERAL, bestUse = "Chat, coding, reasoning", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://ollama.com/library/qwen2.5", score = 84, runnable = true
        ),
        CatalogModel(
            name = "Qwen3 0.6B (Q8_0)", sizeLabel = "700 MB", sizeGb = 0.7, url = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            note = "Exact tags vary by platform.", kind = ModelKind.TEXT, family = "Qwen3", params = "0.6B",
            cat = ModelCat.GENERAL, bestUse = "Light chat, basic reasoning", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Excellent lightweight option", pageUrl = "https://ollama.com/library/qwen3", score = 58, runnable = true
        ),
        CatalogModel(
            name = "Qwen3 1.7B (Q8_0)", sizeLabel = "1.8 GB", sizeGb = 1.8, url = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
            note = "Good compromise for mobile.", kind = ModelKind.TEXT, family = "Qwen3", params = "1.7B",
            cat = ModelCat.GENERAL, bestUse = "Chat, coding basics", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/qwen3", score = 74, runnable = true
        ),
        CatalogModel(
            name = "Qwen3 4B (Q4_K_M)", sizeLabel = "2.5 GB", sizeGb = 2.5, url = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf",
            note = "Try Q4; use shorter context if needed.", kind = ModelKind.TEXT, family = "Qwen3", params = "4B",
            cat = ModelCat.GENERAL, bestUse = "Chat, coding, reasoning", ramLabel = "4–6 GB", ramNeedGb = 5.0,
            suitability = "Strong candidate on 12 GB devices", pageUrl = "https://ollama.com/library/qwen3", score = 88, runnable = true
        ),
        CatalogModel(
            name = "Qwen3 8B (Q4_K_M)", sizeLabel = "5.0 GB", sizeGb = 5.0, url = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/Qwen3-8B-Q4_K_M.gguf",
            note = "Not guaranteed to fit with other apps open.", kind = ModelKind.TEXT, family = "Qwen3", params = "8B",
            cat = ModelCat.GENERAL, bestUse = "Chat, coding, reasoning", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://ollama.com/library/qwen3", score = 92, runnable = true
        ),
        CatalogModel(
            name = "Qwen3 Coder Varies by release", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Confirm parameter count and context before download.", kind = ModelKind.TEXT, family = "Qwen3 Coder", params = "Varies by release",
            cat = ModelCat.AGENT, bestUse = "Coding agents, repository tasks", ramLabel = "Varies; larger versions exceed phone limits", ramNeedGb = 4.0,
            suitability = "Only small compatible quantizations", pageUrl = "https://huggingface.co/models?search=Qwen3-Coder", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Qwen2.5 Coder 0.5B (Q4_K_M)", sizeLabel = "400 MB", sizeGb = 0.4, url = "https://huggingface.co/Qwen/Qwen2.5-Coder-0.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-0.5b-instruct-q4_k_m.gguf",
            note = "Good for simple snippets.", kind = ModelKind.TEXT, family = "Qwen2.5 Coder", params = "0.5B",
            cat = ModelCat.CODING, bestUse = "Basic code completion", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Excellent lightweight option", pageUrl = "https://ollama.com/library/qwen2.5-coder", score = 55, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 Coder 1.5B (Q4_K_M)", sizeLabel = "1.1 GB", sizeGb = 1.1, url = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
            note = "Q4 recommended.", kind = ModelKind.TEXT, family = "Qwen2.5 Coder", params = "1.5B",
            cat = ModelCat.CODING, bestUse = "Code explanations, snippets", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/qwen2.5-coder", score = 72, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 Coder 3B (Q4_K_M)", sizeLabel = "2.0 GB", sizeGb = 2.0, url = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
            note = "Good balance for mobile.", kind = ModelKind.TEXT, family = "Qwen2.5 Coder", params = "3B",
            cat = ModelCat.CODING, bestUse = "Programming, debugging", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Recommended coding candidate", pageUrl = "https://ollama.com/library/qwen2.5-coder", score = 86, runnable = true
        ),
        CatalogModel(
            name = "Qwen2.5 Coder 7B (Q4_K_M)", sizeLabel = "4.7 GB", sizeGb = 4.7, url = "https://huggingface.co/bartowski/Qwen2.5-Coder-7B-Instruct-GGUF/resolve/main/Qwen2.5-Coder-7B-Instruct-Q4_K_M.gguf",
            note = "May be slow or trigger app reloads.", kind = ModelKind.TEXT, family = "Qwen2.5 Coder", params = "7B",
            cat = ModelCat.CODING, bestUse = "More capable code generation", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://ollama.com/library/qwen2.5-coder", score = 92, runnable = true
        ),
        CatalogModel(
            name = "Llama 3.2 1B (Q4_K_M)", sizeLabel = "800 MB", sizeGb = 0.8, url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            note = "Use instruct variant if available.", kind = ModelKind.TEXT, family = "Llama 3.2", params = "1B",
            cat = ModelCat.GENERAL, bestUse = "Short chat, rewriting", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/llama3.2", score = 60, runnable = true
        ),
        CatalogModel(
            name = "Llama 3.2 3B (Q4_K_M)", sizeLabel = "2.0 GB", sizeGb = 2.0, url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            note = "Vision is a separate model variant.", kind = ModelKind.TEXT, family = "Llama 3.2", params = "3B",
            cat = ModelCat.GENERAL, bestUse = "Conversation, writing, summaries", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good", pageUrl = "https://ollama.com/library/llama3.2", score = 78, runnable = true
        ),
        CatalogModel(
            name = "Llama 3.1 8B (Q4_K_M)", sizeLabel = "4.9 GB", sizeGb = 4.9, url = "https://huggingface.co/bartowski/Meta-Llama-3.1-8B-Instruct-GGUF/resolve/main/Meta-Llama-3.1-8B-Instruct-Q4_K_M.gguf",
            note = "Q4 may fit, but runtime headroom is limited.", kind = ModelKind.TEXT, family = "Llama 3.1", params = "8B",
            cat = ModelCat.GENERAL, bestUse = "General assistant", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://ollama.com/library/llama3.1", score = 82, runnable = true
        ),
        CatalogModel(
            name = "Llama 3.2 Vision 11B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Multimodal runtime adds overhead.", kind = ModelKind.VISION, family = "Llama 3.2 Vision", params = "11B",
            cat = ModelCat.VISION, bestUse = "Image understanding", ramLabel = "Usually too much for 12 GB phone", ramNeedGb = 12.0,
            suitability = "Not recommended for typical phones", pageUrl = "https://huggingface.co/models?search=Llama-3.2-11B-Vision", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Code Llama 7B (Q4_K_M)", sizeLabel = "4.1 GB", sizeGb = 4.1, url = "https://huggingface.co/TheBloke/CodeLlama-7B-Instruct-GGUF/resolve/main/codellama-7b-instruct.Q4_K_M.gguf",
            note = "Older model family; check current support.", kind = ModelKind.TEXT, family = "Code Llama", params = "7B",
            cat = ModelCat.CODING, bestUse = "Code generation, explanations", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=CodeLlama-7b", score = 55, runnable = true
        ),
        CatalogModel(
            name = "StarCoder2 3B (Q4_K_M)", sizeLabel = "1.9 GB", sizeGb = 1.9, url = "https://huggingface.co/bartowski/starcoder2-3b-GGUF/resolve/main/starcoder2-3b-Q4_K_M.gguf",
            note = "Q4 recommended.", kind = ModelKind.TEXT, family = "StarCoder2", params = "3B",
            cat = ModelCat.CODING, bestUse = "Code completion", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=starcoder2-3b", score = 55, runnable = true
        ),
        CatalogModel(
            name = "StarCoder2 7B (Q4_K_M)", sizeLabel = "4.4 GB", sizeGb = 4.4, url = "https://huggingface.co/bartowski/starcoder2-7b-GGUF/resolve/main/starcoder2-7b-Q4_K_M.gguf",
            note = "May be slow on mobile CPU.", kind = ModelKind.TEXT, family = "StarCoder2", params = "7B",
            cat = ModelCat.CODING, bestUse = "Code completion", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=starcoder2-7b", score = 60, runnable = true
        ),
        CatalogModel(
            name = "Gemma 2 2B (Q4_K_M)", sizeLabel = "1.7 GB", sizeGb = 1.7, url = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            note = "Use an instruction-tuned variant.", kind = ModelKind.TEXT, family = "Gemma 2", params = "2B",
            cat = ModelCat.GENERAL, bestUse = "Chat, writing", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/gemma2", score = 62, runnable = true
        ),
        CatalogModel(
            name = "Gemma 2 9B (Q4_K_M)", sizeLabel = "5.8 GB", sizeGb = 5.8, url = "https://huggingface.co/bartowski/gemma-2-9b-it-GGUF/resolve/main/gemma-2-9b-it-Q4_K_M.gguf",
            note = "May exceed practical memory with context.", kind = ModelKind.TEXT, family = "Gemma 2", params = "9B",
            cat = ModelCat.GENERAL, bestUse = "General chat", ramLabel = "7–10+ GB", ramNeedGb = 8.5,
            suitability = "Usually too heavy for phone use", pageUrl = "https://ollama.com/library/gemma2", score = 80, runnable = true
        ),
        CatalogModel(
            name = "Gemma 3 270M (Q4_K_M)", sizeLabel = "250 MB", sizeGb = 0.25, url = "https://huggingface.co/unsloth/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q4_K_M.gguf",
            note = "Capability is limited compared with larger models.", kind = ModelKind.TEXT, family = "Gemma 3", params = "270M",
            cat = ModelCat.GENERAL, bestUse = "Very lightweight tasks", ramLabel = "Under 1 GB", ramNeedGb = 1.0,
            suitability = "Excellent lightweight option", pageUrl = "https://ollama.com/library/gemma3", score = 40, runnable = true
        ),
        CatalogModel(
            name = "Gemma 3 1B (Q4_K_M)", sizeLabel = "800 MB", sizeGb = 0.8, url = "https://huggingface.co/unsloth/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            note = "Small model option.", kind = ModelKind.TEXT, family = "Gemma 3", params = "1B",
            cat = ModelCat.GENERAL, bestUse = "Basic chat", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Good", pageUrl = "https://ollama.com/library/gemma3", score = 62, runnable = true
        ),
        CatalogModel(
            name = "Gemma 3 4B (Q4_K_M)", sizeLabel = "2.5 GB", sizeGb = 2.5, url = "https://huggingface.co/unsloth/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            note = "Vision support depends on app and model packaging.", kind = ModelKind.VISION, family = "Gemma 3", params = "4B",
            cat = ModelCat.VISION, bestUse = "Chat, image questions", ramLabel = "4–6 GB", ramNeedGb = 5.0,
            suitability = "Recommended candidate", pageUrl = "https://ollama.com/library/gemma3", score = 85, runnable = true
        ),
        CatalogModel(
            name = "Gemma 3 12B (Q4_K_M)", sizeLabel = "7.3 GB", sizeGb = 7.3, url = "https://huggingface.co/unsloth/gemma-3-12b-it-GGUF/resolve/main/gemma-3-12b-it-Q4_K_M.gguf",
            note = "Weight file alone may consume most available memory.", kind = ModelKind.VISION, family = "Gemma 3", params = "12B",
            cat = ModelCat.VISION, bestUse = "Chat, image understanding", ramLabel = "9–12+ GB", ramNeedGb = 10.5,
            suitability = "Not recommended for 12 GB phone", pageUrl = "https://ollama.com/library/gemma3", score = 90, runnable = true
        ),
        CatalogModel(
            name = "Gemma 3n E2B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Runtime and app support are important.", kind = ModelKind.VISION, family = "Gemma 3n", params = "E2B",
            cat = ModelCat.VISION, bestUse = "Efficient multimodal assistance", ramLabel = "Varies by runtime", ramNeedGb = 2.4,
            suitability = "Potentially good mobile option", pageUrl = "https://huggingface.co/models?search=Gemma-3n-E2B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Gemma 3n E4B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check supported mobile runtime.", kind = ModelKind.VISION, family = "Gemma 3n", params = "E4B",
            cat = ModelCat.VISION, bestUse = "Vision/audio and chat tasks", ramLabel = "Varies by runtime", ramNeedGb = 3.6,
            suitability = "Potentially good on 12 GB devices", pageUrl = "https://huggingface.co/models?search=Gemma-3n-E4B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Gemma 4 E2B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Exact model availability and quantization vary.", kind = ModelKind.VISION, family = "Gemma 4", params = "E2B",
            cat = ModelCat.VISION, bestUse = "Chat and supported multimodal tasks", ramLabel = "Varies by runtime", ramNeedGb = 2.4,
            suitability = "Check device-specific build", pageUrl = "https://huggingface.co/models?search=Gemma-4-E2B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Gemma 4 E4B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Confirm official release, runtime and quantization.", kind = ModelKind.VISION, family = "Gemma 4", params = "E4B",
            cat = ModelCat.VISION, bestUse = "Chat and supported multimodal tasks", ramLabel = "Varies by runtime", ramNeedGb = 3.6,
            suitability = "Candidate for testing", pageUrl = "https://huggingface.co/models?search=Gemma-4-E4B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Gemma 4 12B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Use smaller variant on mobile.", kind = ModelKind.VISION, family = "Gemma 4", params = "12B",
            cat = ModelCat.VISION, bestUse = "Reasoning and multimodal tasks", ramLabel = "9–12+ GB", ramNeedGb = 10.5,
            suitability = "Not recommended for typical 12 GB phones", pageUrl = "https://huggingface.co/models?search=Gemma-4-12B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Phi-3.5 Mini 3.8B (Q4_K_M)", sizeLabel = "2.4 GB", sizeGb = 2.4, url = "https://huggingface.co/bartowski/Phi-3.5-mini-instruct-GGUF/resolve/main/Phi-3.5-mini-instruct-Q4_K_M.gguf",
            note = "Quantization and context affect memory.", kind = ModelKind.TEXT, family = "Phi-3.5 Mini", params = "3.8B",
            cat = ModelCat.GENERAL, bestUse = "Reasoning and coding", ramLabel = "3.5–5 GB", ramNeedGb = 4.2,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=Phi-3.5-mini-instruct", score = 76, runnable = true
        ),
        CatalogModel(
            name = "Phi-4-mini 3.8B (Q4_K_M)", sizeLabel = "2.5 GB", sizeGb = 2.5, url = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
            note = "Q4 version recommended.", kind = ModelKind.TEXT, family = "Phi-4-mini", params = "3.8B",
            cat = ModelCat.GENERAL, bestUse = "Math, code, structured output", ramLabel = "3.5–5 GB", ramNeedGb = 4.2,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=Phi-4-mini-instruct", score = 84, runnable = true
        ),
        CatalogModel(
            name = "Phi-4-mini-reasoning 3.8B (Q4_K_M)", sizeLabel = "2.5 GB", sizeGb = 2.5, url = "https://huggingface.co/unsloth/Phi-4-mini-reasoning-GGUF/resolve/main/Phi-4-mini-reasoning-Q4_K_M.gguf",
            note = "Check official model card and runtime.", kind = ModelKind.TEXT, family = "Phi-4-mini-reasoning", params = "3.8B",
            cat = ModelCat.REASONING, bestUse = "Reasoning-focused tasks", ramLabel = "3.5–5 GB", ramNeedGb = 4.2,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=Phi-4-mini-reasoning", score = 86, runnable = true
        ),
        CatalogModel(
            name = "SmolLM2 135M (Q4_K_M)", sizeLabel = "100 MB", sizeGb = 0.1, url = "https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/resolve/main/SmolLM2-135M-Instruct-Q4_K_M.gguf",
            note = "Limited capability.", kind = ModelKind.TEXT, family = "SmolLM2", params = "135M",
            cat = ModelCat.GENERAL, bestUse = "Tiny tasks", ramLabel = "Under 1 GB", ramNeedGb = 1.0,
            suitability = "Excellent lightweight option", pageUrl = "https://huggingface.co/models?search=SmolLM2", score = 28, runnable = true
        ),
        CatalogModel(
            name = "SmolLM2 360M (Q4_K_M)", sizeLabel = "250 MB", sizeGb = 0.25, url = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
            note = "Limited capability.", kind = ModelKind.TEXT, family = "SmolLM2", params = "360M",
            cat = ModelCat.GENERAL, bestUse = "Lightweight chat", ramLabel = "Under 1 GB", ramNeedGb = 1.0,
            suitability = "Excellent lightweight option", pageUrl = "https://huggingface.co/models?search=SmolLM2", score = 38, runnable = true
        ),
        CatalogModel(
            name = "SmolLM2 1.7B (Q4_K_M)", sizeLabel = "1.1 GB", sizeGb = 1.1, url = "https://huggingface.co/bartowski/SmolLM2-1.7B-Instruct-GGUF/resolve/main/SmolLM2-1.7B-Instruct-Q4_K_M.gguf",
            note = "Q4 recommended.", kind = ModelKind.TEXT, family = "SmolLM2", params = "1.7B",
            cat = ModelCat.GENERAL, bestUse = "Light chat, short summaries", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=SmolLM2", score = 56, runnable = true
        ),
        CatalogModel(
            name = "SmolLM3 3B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check available quantized builds.", kind = ModelKind.TEXT, family = "SmolLM3", params = "3B",
            cat = ModelCat.GENERAL, bestUse = "Chat and reasoning", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=SmolLM3", score = 50, runnable = false
        ),
        CatalogModel(
            name = "TinyLlama 1.1B (Q4_K_M)", sizeLabel = "700 MB", sizeGb = 0.7, url = "https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf",
            note = "Older, less capable than newer models.", kind = ModelKind.TEXT, family = "TinyLlama", params = "1.1B",
            cat = ModelCat.GENERAL, bestUse = "Basic chat", ramLabel = "1–2 GB", ramNeedGb = 1.5,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=TinyLlama-1.1B", score = 35, runnable = true
        ),
        CatalogModel(
            name = "StableLM 2 1.6B (Q4_K_M)", sizeLabel = "1.0 GB", sizeGb = 1.0, url = "https://huggingface.co/stabilityai/stablelm-2-zephyr-1_6b/resolve/main/stablelm-2-zephyr-1_6b-Q4_K_M.gguf",
            note = "Check instruction-tuned variant.", kind = ModelKind.TEXT, family = "StableLM 2", params = "1.6B",
            cat = ModelCat.GENERAL, bestUse = "Light text generation", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=StableLM-2-1.6B", score = 45, runnable = true
        ),
        CatalogModel(
            name = "MobileLLM 125M–1.5B variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Model variants differ.", kind = ModelKind.TEXT, family = "MobileLLM", params = "125M–1.5B variants",
            cat = ModelCat.GENERAL, bestUse = "Small on-device tasks", ramLabel = "Under 1–2.5 GB", ramNeedGb = 2.5,
            suitability = "Good for lightweight tasks", pageUrl = "https://huggingface.co/models?search=MobileLLM", score = 50, runnable = false
        ),
        CatalogModel(
            name = "OpenELM 270M–3B variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Verify runtime compatibility.", kind = ModelKind.TEXT, family = "OpenELM", params = "270M–3B variants",
            cat = ModelCat.GENERAL, bestUse = "Efficient text generation", ramLabel = "Under 1–4.5 GB", ramNeedGb = 4.5,
            suitability = "Good for lightweight tasks", pageUrl = "https://huggingface.co/models?search=OpenELM", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Granite 3.1 2B (Q4_K_M)", sizeLabel = "1.6 GB", sizeGb = 1.6, url = "https://huggingface.co/bartowski/granite-3.1-2b-instruct-GGUF/resolve/main/granite-3.1-2b-instruct-Q4_K_M.gguf",
            note = "Check instruction-tuned model.", kind = ModelKind.TEXT, family = "Granite 3.1", params = "2B",
            cat = ModelCat.GENERAL, bestUse = "Assistant tasks", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=Granite-3.1-2B", score = 64, runnable = true
        ),
        CatalogModel(
            name = "Granite 4 Small variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Family includes different sizes.", kind = ModelKind.TEXT, family = "Granite 4", params = "Small variants",
            cat = ModelCat.AGENT, bestUse = "Chat and tool use", ramLabel = "Varies by variant", ramNeedGb = 4.0,
            suitability = "Check exact size", pageUrl = "https://huggingface.co/models?search=Granite-4", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Granite 4.1 3B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Verify model release and size.", kind = ModelKind.TEXT, family = "Granite 4.1", params = "3B",
            cat = ModelCat.AGENT, bestUse = "Assistant and tool-oriented tasks", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Candidate if exact variant available", pageUrl = "https://huggingface.co/models?search=Granite-4.1", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Granite 4.1 8B-class", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Exact variant may vary.", kind = ModelKind.TEXT, family = "Granite 4.1", params = "8B-class",
            cat = ModelCat.GENERAL, bestUse = "General assistance", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=Granite-4.1", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Granite Code 3B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Q4 recommended.", kind = ModelKind.TEXT, family = "Granite Code", params = "3B",
            cat = ModelCat.CODING, bestUse = "Programming assistance", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=Granite-Code-3B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Granite Code 8B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Q4 and short context.", kind = ModelKind.TEXT, family = "Granite Code", params = "8B",
            cat = ModelCat.CODING, bestUse = "Programming assistance", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=Granite-Code-8B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "LFM2 350M–1.2B variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Variant-specific sizes.", kind = ModelKind.TEXT, family = "LFM2", params = "350M–1.2B variants",
            cat = ModelCat.GENERAL, bestUse = "Fast lightweight tasks", ramLabel = "Under 2 GB", ramNeedGb = 1.0,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=LFM2", score = 50, runnable = false
        ),
        CatalogModel(
            name = "LFM2.5 1.2B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check supported build.", kind = ModelKind.TEXT, family = "LFM2.5", params = "1.2B",
            cat = ModelCat.GENERAL, bestUse = "Lightweight instruction following", ramLabel = "1.5–2.5 GB", ramNeedGb = 2.0,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=LFM2.5", score = 50, runnable = false
        ),
        CatalogModel(
            name = "LFM2.5 8B-A1B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Total model weights still matter for memory.", kind = ModelKind.TEXT, family = "LFM2.5", params = "8B-A1B",
            cat = ModelCat.GENERAL, bestUse = "Efficient assistant tasks", ramLabel = "Varies; active parameters aren't total file size", ramNeedGb = 6.0,
            suitability = "Experimental", pageUrl = "https://huggingface.co/models?search=LFM2.5", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Danube 2 1.8B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check quantized version.", kind = ModelKind.TEXT, family = "Danube 2", params = "1.8B",
            cat = ModelCat.GENERAL, bestUse = "General text generation", ramLabel = "2–3 GB", ramNeedGb = 2.5,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=Danube2-1.8B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "OpenChat 3.5B variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Availability depends on quantization.", kind = ModelKind.TEXT, family = "OpenChat", params = "3.5B variants",
            cat = ModelCat.GENERAL, bestUse = "Conversation", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good", pageUrl = "https://huggingface.co/models?search=OpenChat-3.5", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Mistral 7B (Q4_K_M)", sizeLabel = "4.4 GB", sizeGb = 4.4, url = "https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF/resolve/main/mistral-7b-instruct-v0.2.Q4_K_M.gguf",
            note = "Q4 recommended; older family.", kind = ModelKind.TEXT, family = "Mistral", params = "7B",
            cat = ModelCat.GENERAL, bestUse = "Chat and code", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://ollama.com/library/mistral", score = 70, runnable = true
        ),
        CatalogModel(
            name = "Mistral Nemo 12B (Q4_K_M)", sizeLabel = "7.5 GB", sizeGb = 7.5, url = "https://huggingface.co/bartowski/Mistral-Nemo-Instruct-2407-GGUF/resolve/main/Mistral-Nemo-Instruct-2407-Q4_K_M.gguf",
            note = "Runtime overhead likely causes memory pressure.", kind = ModelKind.TEXT, family = "Mistral Nemo", params = "12B",
            cat = ModelCat.GENERAL, bestUse = "General chat and coding", ramLabel = "9–12+ GB", ramNeedGb = 10.5,
            suitability = "Not recommended for typical 12 GB phones", pageUrl = "https://ollama.com/library/mistral-nemo", score = 84, runnable = true
        ),
        CatalogModel(
            name = "Ministral 3 3B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check exact version and runtime.", kind = ModelKind.TEXT, family = "Ministral 3", params = "3B",
            cat = ModelCat.GENERAL, bestUse = "General assistant", ramLabel = "3–4.5 GB", ramNeedGb = 3.8,
            suitability = "Good if compatible build exists", pageUrl = "https://huggingface.co/models?search=Ministral-3", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Ministral 3 8B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Use Q4 and short context.", kind = ModelKind.TEXT, family = "Ministral 3", params = "8B",
            cat = ModelCat.GENERAL, bestUse = "General chat and coding", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=Ministral-3", score = 50, runnable = false
        ),
        CatalogModel(
            name = "GLM-4 9B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Quantization may fit weights but leaves little runtime headroom.", kind = ModelKind.TEXT, family = "GLM-4", params = "9B",
            cat = ModelCat.GENERAL, bestUse = "Reasoning and assistance", ramLabel = "7–10+ GB", ramNeedGb = 8.5,
            suitability = "Usually too heavy for phone use", pageUrl = "https://huggingface.co/models?search=GLM-4-9B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "GLM-4-9B-0414 9B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Verify exact build and quantization.", kind = ModelKind.TEXT, family = "GLM-4-9B-0414", params = "9B",
            cat = ModelCat.GENERAL, bestUse = "Instruction following", ramLabel = "7–10+ GB", ramNeedGb = 8.5,
            suitability = "Usually too heavy for phone use", pageUrl = "https://huggingface.co/models?search=GLM-4-9B-0414", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Falcon3 10B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Very limited memory headroom.", kind = ModelKind.TEXT, family = "Falcon3", params = "10B",
            cat = ModelCat.GENERAL, bestUse = "General language tasks", ramLabel = "8–11+ GB", ramNeedGb = 9.5,
            suitability = "Not recommended for typical 12 GB phone", pageUrl = "https://huggingface.co/models?search=Falcon3-10B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Nemotron Nano 9B-class", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check specific release/quantization.", kind = ModelKind.TEXT, family = "Nemotron Nano", params = "9B-class",
            cat = ModelCat.AGENT, bestUse = "Reasoning and agent workflows", ramLabel = "7–10+ GB", ramNeedGb = 8.5,
            suitability = "Usually too heavy for phone use", pageUrl = "https://huggingface.co/models?search=Nemotron-Nano", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Nemotron 3 Nano 4B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Confirm model card and format.", kind = ModelKind.TEXT, family = "Nemotron 3 Nano", params = "4B",
            cat = ModelCat.AGENT, bestUse = "Reasoning and agent tasks", ramLabel = "4–6 GB", ramNeedGb = 5.0,
            suitability = "Candidate if exact release is available", pageUrl = "https://huggingface.co/models?search=Nemotron-3-Nano", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Ornith 9B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Name/version availability may vary.", kind = ModelKind.TEXT, family = "Ornith", params = "9B",
            cat = ModelCat.GENERAL, bestUse = "General assistant", ramLabel = "7–10+ GB", ramNeedGb = 8.5,
            suitability = "Uncertain; verify exact model", pageUrl = "https://huggingface.co/models?search=Ornith-9B", score = 50, runnable = false
        ),
        CatalogModel(
            name = "Qwen2.5-VL 3B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Vision runtime adds overhead.", kind = ModelKind.VISION, family = "Qwen2.5-VL", params = "3B",
            cat = ModelCat.VISION, bestUse = "Images, screenshots, documents", ramLabel = "3.5–5.5 GB", ramNeedGb = 4.5,
            suitability = "Candidate on 12 GB device", pageUrl = "https://huggingface.co/models?search=Qwen2.5-VL-3B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Qwen2.5-VL 7B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "May be slow and memory-constrained.", kind = ModelKind.VISION, family = "Qwen2.5-VL", params = "7B",
            cat = ModelCat.VISION, bestUse = "Visual Q&A and documents", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=Qwen2.5-VL-7B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Qwen3-VL 2B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check runtime multimodal support.", kind = ModelKind.VISION, family = "Qwen3-VL", params = "2B",
            cat = ModelCat.VISION, bestUse = "Image and screenshot understanding", ramLabel = "2.5–4 GB", ramNeedGb = 3.2,
            suitability = "Good candidate if supported", pageUrl = "https://huggingface.co/models?search=Qwen3-VL-2B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Qwen3-VL 4B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Q4 and supported vision runtime required.", kind = ModelKind.VISION, family = "Qwen3-VL", params = "4B",
            cat = ModelCat.VISION, bestUse = "Visual reasoning", ramLabel = "4–6 GB", ramNeedGb = 5.0,
            suitability = "Candidate on 12 GB device", pageUrl = "https://huggingface.co/models?search=Qwen3-VL-4B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Qwen3-VL 8B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Large vision model; may be slow.", kind = ModelKind.VISION, family = "Qwen3-VL", params = "8B",
            cat = ModelCat.VISION, bestUse = "Visual reasoning and document images", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=Qwen3-VL-8B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "MiniCPM-V 1.3B-class variants", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Variant sizes differ; check card.", kind = ModelKind.VISION, family = "MiniCPM-V", params = "1.3B-class variants",
            cat = ModelCat.VISION, bestUse = "Image understanding and OCR-like tasks", ramLabel = "2–4 GB", ramNeedGb = 3.0,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=MiniCPM-V", score = 45, runnable = false
        ),
        CatalogModel(
            name = "LLaVA 7B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Requires a compatible multimodal runtime.", kind = ModelKind.VISION, family = "LLaVA", params = "7B",
            cat = ModelCat.VISION, bestUse = "Image conversations", ramLabel = "6–9+ GB", ramNeedGb = 7.5,
            suitability = "Experimental on 12 GB phones", pageUrl = "https://huggingface.co/models?search=LLaVA-7B", score = 45, runnable = false
        ),
        CatalogModel(
            name = "DeepSeek-OCR Varies by release", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Not every release is phone-friendly.", kind = ModelKind.VISION, family = "DeepSeek-OCR", params = "Varies by release",
            cat = ModelCat.VISION, bestUse = "Document text extraction", ramLabel = "Varies by build", ramNeedGb = 4.0,
            suitability = "Check exact model and runtime", pageUrl = "https://huggingface.co/models?search=DeepSeek-OCR", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Sarvam-1 2B", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Check supported language and runtime.", kind = ModelKind.TEXT, family = "Sarvam-1", params = "2B",
            cat = ModelCat.INDIAN, bestUse = "Indian-language assistance", ramLabel = "2–3.5 GB", ramNeedGb = 2.8,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=Sarvam-1", score = 45, runnable = false
        ),
        CatalogModel(
            name = "BGE-small Embedding model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Not a chat model.", kind = ModelKind.OTHER, family = "BGE-small", params = "Embedding model",
            cat = ModelCat.EMBED, bestUse = "Semantic search", ramLabel = "Under 1 GB", ramNeedGb = 1.0,
            suitability = "Excellent lightweight option", pageUrl = "https://huggingface.co/models?search=bge-small", score = 45, runnable = false
        ),
        CatalogModel(
            name = "all-MiniLM-L6-v2 Embedding model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Not a chat model.", kind = ModelKind.OTHER, family = "all-MiniLM-L6-v2", params = "Embedding model",
            cat = ModelCat.EMBED, bestUse = "Local document search", ramLabel = "Under 1 GB", ramNeedGb = 1.0,
            suitability = "Excellent lightweight option", pageUrl = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Nomic Embed Text Embedding model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Not a chat model.", kind = ModelKind.OTHER, family = "Nomic Embed Text", params = "Embedding model",
            cat = ModelCat.EMBED, bestUse = "RAG and semantic search", ramLabel = "Varies by version", ramNeedGb = 4.0,
            suitability = "Good with compatible runtime", pageUrl = "https://huggingface.co/models?search=nomic-embed-text", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Whisper tiny Speech model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Requires Whisper-compatible app/runtime.", kind = ModelKind.OTHER, family = "Whisper tiny", params = "Speech model",
            cat = ModelCat.SPEECH, bestUse = "Offline audio transcription", ramLabel = "Varies by runtime", ramNeedGb = 4.0,
            suitability = "Good lightweight option", pageUrl = "https://huggingface.co/models?search=whisper-tiny", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Whisper base Speech model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Larger than tiny; runtime matters.", kind = ModelKind.OTHER, family = "Whisper base", params = "Speech model",
            cat = ModelCat.SPEECH, bestUse = "Offline audio transcription", ramLabel = "Varies by runtime", ramNeedGb = 4.0,
            suitability = "Good candidate", pageUrl = "https://huggingface.co/models?search=whisper-base", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Piper Voice models", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Requires compatible TTS app/runtime.", kind = ModelKind.OTHER, family = "Piper", params = "Voice models",
            cat = ModelCat.TTS, bestUse = "Offline speech synthesis", ramLabel = "Usually small per voice", ramNeedGb = 4.0,
            suitability = "Good", pageUrl = "https://github.com/rhasspy/piper", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Kokoro TTS model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Confirm Android support and model size.", kind = ModelKind.OTHER, family = "Kokoro", params = "TTS model",
            cat = ModelCat.TTS, bestUse = "Natural-sounding speech", ramLabel = "Varies by quantization/runtime", ramNeedGb = 4.0,
            suitability = "Possible on capable devices", pageUrl = "https://huggingface.co/models?search=Kokoro", score = 45, runnable = false
        ),
        CatalogModel(
            name = "Stable Diffusion 1.5 Image model", sizeLabel = "Open page", sizeGb = 0.0, url = "",
            note = "Separate from LLMs; not a chat model.", kind = ModelKind.OTHER, family = "Stable Diffusion 1.5", params = "Image model",
            cat = ModelCat.IMAGE, bestUse = "Local text-to-image", ramLabel = "Several GB plus runtime memory", ramNeedGb = 4.0,
            suitability = "Difficult on phones; device/runtime-dependent", pageUrl = "https://huggingface.co/models?search=stable-diffusion-v1-5", score = 45, runnable = false
        )
    )

    /** Fit of a model for a phone with [ramGb] GB of RAM. */
    fun fit(m: CatalogModel, ramGb: Double): Fit = when {
        m.ramNeedGb <= ramGb * 0.55 -> Fit.Great
        m.ramNeedGb <= ramGb * 0.78 -> Fit.Ok
        else -> Fit.TooBig
    }

    /**
     * Ranked recommendations for a use case on this phone.
     * Models ZENIQ can download and run come first, then models that fit the RAM comfortably,
     * then the highest quality score. Models that are too big are only used when nothing else exists.
     */
    fun recommend(uc: UseCase, ramGb: Double, limit: Int = 4): List<CatalogModel> {
        val pool = all.filter { uc.matches(it) }
        val usable = pool.filter { fit(it, ramGb) != Fit.TooBig }
        val ranked = if (usable.isNotEmpty()) usable else pool.sortedBy { it.ramNeedGb }.take(limit)
        return ranked.sortedWith(
            compareByDescending<CatalogModel> { it.downloadable && it.runnable }
                .thenByDescending { fit(it, ramGb) == Fit.Great }
                .thenByDescending { it.score }
                .thenBy { it.ramNeedGb }
        ).take(limit)
    }

    fun phoneTier(ramGb: Double): String = when {
        ramGb < 3.5 -> "Entry phone: stick to tiny models (under 1 GB)."
        ramGb < 5.5 -> "Light phone: 0.5B to 2B models run well."
        ramGb < 7.5 -> "Mid-range phone: up to about 3B runs well."
        ramGb < 10.0 -> "Upper mid-range phone: 3B to 4B runs well, 7B is tight."
        else -> "Flagship phone: 4B runs great, 7B to 8B works but is tight."
    }
}
