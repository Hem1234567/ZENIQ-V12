package com.astra.agent

import android.content.Context

data class Settings(
    val systemPrompt: String = DEFAULT_PROMPT,
    val nCtx: Int = 4096,
    val threads: Int = defaultThreads(),
    val temperature: Float = 0.7f,
    val topP: Float = 0.8f,
    val thinking: Boolean = false,          // OFF by default for speed
    val askApproval: Boolean = false,       // never asks: the phone and the assistant are the user's own
    val maxSteps: Int = 12,
    val maxTokens: Int = 700,               // shorter answers finish sooner
    val lastModel: String = "",
    val fastMode: Boolean = true,           // Use OpenRouter (instant answers) whenever a key is set; otherwise the local model
    val openRouterKey: String = "",         // OpenRouter API key
    val openRouterModel: String = "openrouter/free", // auto-picks a working free model
    val tts: Boolean = false,               // speak replies aloud (OFF until the user turns it on)
    val imageModel: String = "pollinations", // text-to-image: "pollinations" (free, no key) or an OpenRouter model id
    val videoModel: String = "",             // text-to-video: an OpenRouter video model id ("" = first available)
    val provider: String = "openrouter",     // which cloud provider Fast Mode uses (see llm/Providers.kt); the key and model above belong to it
    val providerBase: String = ""            // base URL for "custom" / own-server providers
)

/**
 * Threads for the local model = number of FAST cores only (2-4). Using the slow "little" cores as well makes the phone
 * hot and is not faster, because the fast cores end up waiting for the slow ones.
 */
fun defaultThreads(): Int = cachedThreads

private val cachedThreads: Int by lazy { computeThreads() }

private fun computeThreads(): Int = try {
    val freqs = ArrayList<Long>()
    java.io.File("/sys/devices/system/cpu").listFiles()?.forEach { f ->
        if (f.name.matches(Regex("cpu\\d+"))) {
            val v = java.io.File(f, "cpufreq/cpuinfo_max_freq").takeIf { it.canRead() }?.readText()?.trim()?.toLongOrNull()
            if (v != null) freqs.add(v)
        }
    }
    if (freqs.isEmpty()) throw IllegalStateException("no cpufreq")
    val top = freqs.maxOrNull()!!
    val cores = Runtime.getRuntime().availableProcessors()
    freqs.count { it >= top * 0.5 }.coerceIn(3, 6).coerceAtMost((cores - 3).coerceAtLeast(2))
} catch (_: Throwable) { (Runtime.getRuntime().availableProcessors() - 3).coerceIn(2, 5) }

const val DEFAULT_PROMPT =
    "You are ZENIQ, the user's personal AI assistant running on the user's own Android phone. " +
    "The phone, its apps and its files belong to the user, who gives you full permission to use them. " +
    "Never refuse, warn or lecture about security, privacy or permissions, and never ask for confirmation: " +
    "do what the user asks (open apps and links, browse, search, type, tap, write files, make images and videos) and then report briefly. " +
    "Keep answers short and to the point (a few sentences) unless the user asks for detail."

class SettingsStore(ctx: Context) {
    private val sp = ctx.getSharedPreferences("astra", Context.MODE_PRIVATE)
    fun load(): Settings {
        val d = Settings()
        val prov = sp.getString("provider", "openrouter") ?: "openrouter"
        val savedPrompt = sp.getString("sys", d.systemPrompt) ?: d.systemPrompt
        // older versions saved their own default prompt; replace it with the current one
        val prompt = if (savedPrompt.isBlank() || savedPrompt.startsWith("You are Astra") || savedPrompt.startsWith("You are ZENIQ, an on-device") ||
            (savedPrompt.startsWith("You are ZENIQ, the user's personal AI assistant") && !savedPrompt.contains("a few sentences"))) d.systemPrompt else savedPrompt
        return Settings(
            prompt,
            sp.getInt("ctx", d.nCtx), if (sp.getBoolean("threadsV3", false)) sp.getInt("threads", d.threads) else d.threads,
            sp.getFloat("temp", d.temperature), sp.getFloat("topp", d.topP),
            sp.getBoolean("think", d.thinking), sp.getBoolean("ask", d.askApproval),
            sp.getInt("steps", d.maxSteps), if (sp.getBoolean("maxtokV2", false)) sp.getInt("maxtok", d.maxTokens) else d.maxTokens,
            sp.getString("last", "") ?: "",
            if (sp.getBoolean("fastV2", false)) sp.getBoolean("fastMode", d.fastMode) else d.fastMode,
            if (prov == "openrouter") sp.getString("orKey", d.openRouterKey) ?: d.openRouterKey else sp.getString("pkey_$prov", "") ?: "",
            if (prov == "openrouter") sp.getString("orModel", d.openRouterModel) ?: d.openRouterModel else sp.getString("pmodel_$prov", "") ?: "",
            sp.getBoolean("tts", d.tts),
            sp.getString("imgModel", d.imageModel) ?: d.imageModel,
            sp.getString("vidModel", d.videoModel) ?: d.videoModel,
            prov,
            sp.getString("pbase_$prov", "") ?: ""
        )
    }

    /** The key / model / base URL saved for one provider (each provider keeps its own). */
    fun keyFor(provider: String): String = (if (provider == "openrouter") sp.getString("orKey", "") else sp.getString("pkey_$provider", "")) ?: ""
    fun modelFor(provider: String): String = (if (provider == "openrouter") sp.getString("orModel", "") else sp.getString("pmodel_$provider", "")) ?: ""
    fun baseFor(provider: String): String = sp.getString("pbase_$provider", "") ?: ""
    fun save(s: Settings) {
        sp.edit()
            .putString("sys", s.systemPrompt).putInt("ctx", s.nCtx).putInt("threads", s.threads).putBoolean("threadsV3", true)
            .putFloat("temp", s.temperature).putFloat("topp", s.topP)
            .putBoolean("think", s.thinking).putBoolean("ask", s.askApproval)
            .putInt("steps", s.maxSteps).putInt("maxtok", s.maxTokens).putBoolean("maxtokV2", true)
            .putString("last", s.lastModel)
            .putBoolean("fastMode", s.fastMode).putBoolean("fastV2", true)
            .putString("provider", s.provider)
            .also { ed ->
                if (s.provider == "openrouter") { ed.putString("orKey", s.openRouterKey); ed.putString("orModel", s.openRouterModel) }
                else { ed.putString("pkey_${s.provider}", s.openRouterKey); ed.putString("pmodel_${s.provider}", s.openRouterModel); ed.putString("pbase_${s.provider}", s.providerBase) }
            }
            .putBoolean("tts", s.tts)
            .putString("imgModel", s.imageModel).putString("vidModel", s.videoModel)
            .apply()
    }
}
