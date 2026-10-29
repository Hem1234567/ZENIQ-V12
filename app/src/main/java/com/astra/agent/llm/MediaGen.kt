package com.astra.agent.llm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A cloud model that can be chosen on the Models page. */
data class CloudModel(val id: String, val name: String, val kind: Kind, val free: Boolean, val note: String = "") {
    enum class Kind { CHAT, IMAGE, VIDEO, ANY }
}

class MediaResult(val bytes: ByteArray, val ext: String, val mime: String)

/**
 * Text-to-image and text-to-video.
 *  - Images: "pollinations" is free and needs no key; any OpenRouter image model needs the OpenRouter key.
 *  - Videos: OpenRouter video models (asynchronous: submit, poll, download).
 * Also lists the cloud models (paid GPT and others, image, video, any-to-any) for the Models page.
 */
object MediaGen {
    private const val BASE = "https://openrouter.ai/api/v1"
    const val POLLINATIONS = "pollinations"

    // Shown immediately and when OpenRouter cannot be reached; refreshed from the live list.
    val defaultModels: List<CloudModel> = listOf(
        CloudModel("openai/gpt-5", "GPT-5", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("openai/gpt-5-mini", "GPT-5 mini", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("openai/gpt-4.1", "GPT-4.1", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("openai/gpt-4.1-mini", "GPT-4.1 mini", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("openai/gpt-4o", "GPT-4o", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("openai/gpt-4o-mini", "GPT-4o mini", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("anthropic/claude-sonnet-4.5", "Claude Sonnet 4.5", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel("google/gemini-2.5-flash", "Gemini 2.5 Flash", CloudModel.Kind.CHAT, false, "paid"),
        CloudModel(POLLINATIONS, "Pollinations (free, no key)", CloudModel.Kind.IMAGE, true, "free"),
        CloudModel("google/gemini-2.5-flash-image", "Gemini 2.5 Flash Image", CloudModel.Kind.ANY, false, "paid \u2022 text + image"),
    )

    private fun open(url: String, method: String, key: String?, timeoutMs: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            if (!key.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $key")
            setRequestProperty("HTTP-Referer", "https://github.com/Hem1234567/Hems-ZENIQ-04")
            setRequestProperty("X-Title", "ZENIQ Android")
            connectTimeout = 20_000
            readTimeout = timeoutMs
        }

    private fun fail(code: Int, body: String): Nothing {
        val raw = try { JSONObject(body).optJSONObject("error")?.optString("message").orEmpty() } catch (_: Exception) { "" }.ifBlank { body.take(200) }
        throw ORException(code, when (code) {
            401 -> "Invalid OpenRouter API key. Check the key in Settings."
            402 -> "This model needs OpenRouter credits. Add credits at openrouter.ai or pick another model."
            404 -> "This model is not available right now (${raw.take(120)})."
            429 -> "Rate limited right now. Try again in a minute."
            else -> "Error $code: ${raw.take(200)}"
        })
    }

    private fun readAll(c: HttpURLConnection): ByteArray {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
        if (code !in 200..299) fail(code, String(bytes, Charsets.UTF_8))
        return bytes
    }

    // ── Images ───────────────────────────────────────────────────────────────

    suspend fun image(prompt: String, model: String, key: String): MediaResult = withContext(Dispatchers.IO) {
        if (model.isBlank() || model == POLLINATIONS || key.isBlank()) pollinations(prompt) else openRouterImage(prompt, model, key)
    }

    private fun pollinations(prompt: String): MediaResult {
        val seed = (System.currentTimeMillis() % 100000).toInt()
        val url = "https://image.pollinations.ai/prompt/" + URLEncoder.encode(prompt, "UTF-8").replace("+", "%20") +
            "?width=1024&height=1024&nologo=true&model=flux&seed=$seed"
        val c = open(url, "GET", null, 120_000)
        val bytes = readAll(c)
        val type = c.contentType.orEmpty()
        if (!type.startsWith("image/") || bytes.size < 500) throw ORException(502, "The free image service did not return a picture. Try again in a moment.")
        return MediaResult(bytes, if (type.contains("png")) "png" else "jpg", if (type.contains("png")) "image/png" else "image/jpeg")
    }

    private fun openRouterImage(prompt: String, model: String, key: String): MediaResult {
        val body = JSONObject()
            .put("model", model)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            .put("modalities", JSONArray().put("image").put("text"))
            .toString()
        val c = open("$BASE/chat/completions", "POST", key, 180_000).apply {
            setRequestProperty("Content-Type", "application/json"); doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
        }
        val json = JSONObject(String(readAll(c), Charsets.UTF_8))
        val msg = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ORException(502, "The model returned no picture.")
        var url: String? = null
        val imgs = msg.optJSONArray("images")
        if (imgs != null && imgs.length() > 0) {
            val o = imgs.optJSONObject(0)
            url = o?.optJSONObject("image_url")?.optString("url") ?: o?.optString("url")
        }
        if (url.isNullOrBlank()) {
            val content = msg.optJSONArray("content")
            if (content != null) for (i in 0 until content.length()) {
                val o = content.optJSONObject(i) ?: continue
                if (o.optString("type") == "image_url") { url = o.optJSONObject("image_url")?.optString("url"); break }
            }
        }
        if (url.isNullOrBlank()) throw ORException(502, "This model did not return a picture. Pick an image model on the Models page.")
        return if (url.startsWith("data:")) {
            val mime = url.substringAfter("data:").substringBefore(";")
            val data = android.util.Base64.decode(url.substringAfter("base64,"), android.util.Base64.DEFAULT)
            MediaResult(data, if (mime.contains("png")) "png" else if (mime.contains("webp")) "webp" else "jpg", mime)
        } else {
            val d = open(url, "GET", null, 120_000)
            val type = d.contentType.orEmpty()
            MediaResult(readAll(d), if (type.contains("png")) "png" else "jpg", if (type.startsWith("image/")) type else "image/jpeg")
        }
    }

    // ── Video (OpenRouter, asynchronous) ─────────────────────────────────────

    suspend fun video(prompt: String, model: String, key: String, status: (String) -> Unit): MediaResult = withContext(Dispatchers.IO) {
        if (key.isBlank()) throw ORException(401, "Video generation needs your OpenRouter API key (Settings) and credits.")
        val m = model.ifBlank { videoModels().firstOrNull()?.id ?: "google/veo-3.1" }
        val body = JSONObject().put("model", m).put("prompt", prompt).put("aspect_ratio", "16:9").toString()
        val c = open("$BASE/videos", "POST", key, 60_000).apply {
            setRequestProperty("Content-Type", "application/json"); doOutput = true
            outputStream.use { it.write(body.toByteArray()) }
        }
        val sub = JSONObject(String(readAll(c), Charsets.UTF_8))
        val id = sub.optString("id")
        var poll = sub.optString("polling_url").ifBlank { "$BASE/videos/$id" }
        if (poll.startsWith("/")) poll = "https://openrouter.ai$poll"
        status("Video queued\u2026")
        val deadline = System.currentTimeMillis() + 12 * 60_000
        while (System.currentTimeMillis() < deadline) {
            delay(5_000)
            val j = try { JSONObject(String(readAll(open(poll, "GET", key, 30_000)), Charsets.UTF_8)) }
                    catch (e: CancellationException) { throw e } catch (e: ORException) { throw e } catch (_: Exception) { continue }
            when (val st = j.optString("status")) {
                "completed" -> {
                    status("Downloading the video\u2026")
                    val v = open("$BASE/videos/$id/content?index=0", "GET", key, 180_000)
                    return@withContext MediaResult(readAll(v), "mp4", "video/mp4")
                }
                "failed", "cancelled", "expired" -> throw ORException(500, "Video generation $st: " + j.optString("error").ifBlank { "no details" })
                else -> status("Making the video\u2026 (${st.ifBlank { "working" }}) \u2014 this can take a few minutes")
            }
        }
        throw ORException(504, "The video is still being made after 12 minutes. Try again later.")
    }

    // ── Model lists ──────────────────────────────────────────────────────────

    suspend fun videoModels(): List<CloudModel> = withContext(Dispatchers.IO) {
        try {
            val arr = JSONObject(String(readAll(open("$BASE/videos/models", "GET", null, 20_000)), Charsets.UTF_8)).optJSONArray("data") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id"); if (id.isBlank()) null
                else CloudModel(id, o.optString("name").ifBlank { id }, CloudModel.Kind.VIDEO, false, "paid \u2022 video")
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Live list: chat models (paid ones included), image models, any-to-any models and video models. */
    suspend fun fetchAll(): List<CloudModel> = withContext(Dispatchers.IO) {
        val out = ArrayList<CloudModel>()
        try {
            val arr = JSONObject(String(readAll(open("$BASE/models?output_modalities=all", "GET", null, 25_000)), Charsets.UTF_8)).optJSONArray("data") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id"); if (id.isBlank() || id == "openrouter/free") continue
                val arch = o.optJSONObject("architecture")
                val outs = arch?.optJSONArray("output_modalities")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("text")
                val ins = arch?.optJSONArray("input_modalities")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf("text")
                val pr = o.optJSONObject("pricing")
                val free = (pr?.optString("prompt")?.toDoubleOrNull() ?: 1.0) == 0.0 && (pr?.optString("completion")?.toDoubleOrNull() ?: 1.0) == 0.0
                val name = o.optString("name").ifBlank { id }
                val kind = when {
                    "video" in outs -> CloudModel.Kind.VIDEO
                    "image" in outs && "text" in outs -> CloudModel.Kind.ANY
                    "image" in outs -> CloudModel.Kind.IMAGE
                    "audio" in outs && "text" in outs -> CloudModel.Kind.ANY
                    else -> CloudModel.Kind.CHAT
                }
                if (kind == CloudModel.Kind.CHAT && free) continue          // free chat models are listed in Settings already
                out.add(CloudModel(id, name, kind, free, (if (free) "free" else "paid") + " \u2022 " + ins.joinToString("+") + " \u2192 " + outs.joinToString("+")))
            }
        } catch (_: Exception) {}
        val vids = videoModels()
        val all = out + vids.filter { v -> out.none { it.id == v.id } }
        val chat = all.filter { it.kind == CloudModel.Kind.CHAT }
        val gpt = chat.filter { it.id.startsWith("openai/") }
        val other = chat.filter { !it.id.startsWith("openai/") }.take(12)
        val rest = all.filter { it.kind != CloudModel.Kind.CHAT }
        (gpt.take(14) + other + rest + defaultModels.filter { d -> all.none { it.id == d.id } && d.kind != CloudModel.Kind.CHAT }).distinctBy { it.id + it.kind }
    }
}
