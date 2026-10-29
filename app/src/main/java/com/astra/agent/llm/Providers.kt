package com.astra.agent.llm

// Pure Kotlin (no Android imports).
// Cloud providers that speak the OpenAI "chat completions" protocol. Fast Mode, the IDE and the agent work with any of them.

class Provider(
    val id: String,
    val name: String,
    val base: String,          // the URL that ends right before /chat/completions
    val keyUrl: String,        // where to create an API key
    val defaultModel: String,
    val note: String,          // short help text shown in the connect dialog
    val needsBase: Boolean = false   // true = the user types the base URL (custom / own server)
)

object Providers {
    const val OPENROUTER = "openrouter"

    val all: List<Provider> = listOf(
        Provider(OPENROUTER, "OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys", "openrouter/free",
            "One key for many models. The model \"openrouter/free\" picks a free model that works right now."),
        Provider("groq", "Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys", "llama-3.3-70b-versatile",
            "Very fast answers. Has a free tier with daily limits."),
        Provider("gemini", "Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey", "gemini-2.5-flash",
            "Google AI Studio key. Has a free tier with daily limits."),
        Provider("cerebras", "Cerebras", "https://api.cerebras.ai/v1", "https://cloud.cerebras.ai", "llama-3.3-70b",
            "Extremely fast. Has a free tier. Use Load models to see the current model names."),
        Provider("mistral", "Mistral", "https://api.mistral.ai/v1", "https://console.mistral.ai/api-keys", "mistral-small-latest",
            "Mistral La Plateforme. Has a free experiment tier."),
        Provider("github", "GitHub Models", "https://models.github.ai/inference", "https://github.com/settings/personal-access-tokens", "openai/gpt-4.1-mini",
            "Use a GitHub token with the Models permission. Free rate-limited use."),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "https://build.nvidia.com", "meta/llama-3.3-70b-instruct",
            "Free developer credits at build.nvidia.com."),
        Provider("together", "Together AI", "https://api.together.xyz/v1", "https://api.together.ai/settings/api-keys", "meta-llama/Llama-3.3-70B-Instruct-Turbo",
            "Many open models."),
        Provider("huggingface", "Hugging Face", "https://router.huggingface.co/v1", "https://huggingface.co/settings/tokens", "meta-llama/Llama-3.3-70B-Instruct",
            "Use a Hugging Face token with inference permission."),
        Provider("sambanova", "SambaNova", "https://api.sambanova.ai/v1", "https://cloud.sambanova.ai/apis", "Meta-Llama-3.3-70B-Instruct",
            "Fast inference with a free tier."),
        Provider("deepseek", "DeepSeek", "https://api.deepseek.com", "https://platform.deepseek.com/api_keys", "deepseek-chat",
            "Low-cost API."),
        Provider("openai", "OpenAI", "https://api.openai.com/v1", "https://platform.openai.com/api-keys", "gpt-4o-mini",
            "Paid API."),
        Provider("ollama", "Ollama / LM Studio (your PC)", "http://192.168.1.10:11434/v1", "https://ollama.com/download", "llama3.2",
            "A model server on your own computer in the same Wi-Fi. Replace the address with your computer's IP. Type any text as the key.", needsBase = true),
        Provider("custom", "Custom (OpenAI compatible)", "https://", "", "",
            "Any service that offers /v1/chat/completions. Type its base URL, key and model name.", needsBase = true)
    )

    fun byId(id: String): Provider = all.firstOrNull { it.id == id } ?: all.first()

    /** The base URL to use: the preset's, or the one the user typed for custom / own-server providers. */
    fun baseFor(id: String, typed: String): String {
        val p = byId(id)
        val b = if (p.needsBase) typed.trim().ifBlank { p.base } else p.base
        return b.trim().trimEnd('/')
    }
}
