package com.astra.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

// Model types (CatalogModel, ModelKind, ModelCat, Fit, UseCase) live in ModelCatalog.kt

sealed interface DlState {
    data class Progress(val done: Long, val total: Long) : DlState
    data class Failed(val msg: String) : DlState
}

// ── ModelManager ─────────────────────────────────────────────────────────────

class ModelManager(ctx: Context) {
    private val appCtx = ctx.applicationContext
    val dir = File(appCtx.filesDir, "models").apply { mkdirs() }

    // ── Catalogue (all 87 models from the Excel guide) ───────────────────────
    val allModels: List<CatalogModel> = ModelCatalog.all
    /** Chat-type models (text) */
    val catalog: List<CatalogModel> = allModels.filter { it.kind == ModelKind.TEXT }
    /** Vision models */
    val visionCatalog: List<CatalogModel> = allModels.filter { it.kind == ModelKind.VISION }

    // ── Device info ──────────────────────────────────────────────────────────
    val ramGb: Double = run {
        val mi = ActivityManager.MemoryInfo()
        (appCtx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        mi.totalMem / 1_073_741_824.0
    }

    /** Free disk space in GB */
    val freeDiskGb: Double get() {
        return try { StatFs(dir.path).let { it.availableBlocksLong * it.blockSizeLong / 1_073_741_824.0 } } catch (_: Throwable) { 99.0 }
    }

    fun fit(m: CatalogModel): Fit = ModelCatalog.fit(m, ramGb)

    /** Ranked picks for a use case on THIS phone (best first). */
    fun recommendFor(uc: UseCase, limit: Int = 4): List<CatalogModel> = ModelCatalog.recommend(uc, ramGb, limit)

    fun phoneTier(): String = ModelCatalog.phoneTier(ramGb)

    /** Best model ZENIQ can download and run for everyday chat on this phone. */
    fun recommended(): CatalogModel =
        recommendFor(UseCase.GENERAL, 8).firstOrNull { it.downloadable && it.runnable }
            ?: allModels.filter { it.downloadable && it.runnable }.minByOrNull { it.sizeGb }
            ?: allModels.first()

    fun recommendedVision(): CatalogModel =
        recommendFor(UseCase.VISION, 8).firstOrNull() ?: allModels.first { it.kind == ModelKind.VISION }

    // ── Download state ───────────────────────────────────────────────────────
    val downloads = MutableStateFlow<Map<String, DlState>>(emptyMap())

    fun fileFor(url: String): File {
        val name = url.substringAfterLast('/').substringBefore('?').let {
            // Sanitise filename — remove any characters that could cause issues
            it.replace(Regex("[^a-zA-Z0-9._\\-]"), "_").take(120)
        }
        return File(dir, name.ifBlank { "model.gguf" })
    }

    fun installed(): List<File> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") && f.length() > 1_000_000L }
            ?.sortedBy { it.name } ?: emptyList()

    /**
     * Opens a connection following redirects (including HTTP→HTTPS).
     * Android's HttpURLConnection follows same-protocol redirects but NOT
     * HTTP→HTTPS ones. HuggingFace always redirects to its CDN over HTTPS,
     * so we must follow manually.
     */
    private fun openWithRedirects(startUrl: String, rangeHeader: String?): Pair<HttpURLConnection, Int> {
        var url = startUrl
        repeat(8) { hop ->
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false   // we handle it ourselves
            conn.connectTimeout = 60_000
            conn.readTimeout    = 120_000
            conn.setRequestProperty("User-Agent", "ZENIQ/1.0")
            if (rangeHeader != null && hop == 0) conn.setRequestProperty("Range", rangeHeader)
            conn.connect()
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw RuntimeException("Redirect with no Location header (HTTP $code)")
                conn.disconnect()
                url = if (location.startsWith("http")) location
                      else URL(URL(url), location).toString()
            } else {
                return Pair(conn, code)
            }
        }
        throw RuntimeException("Too many redirects — download failed")
    }

    private val hfUrlRe = Regex("^https://huggingface\\.co/([^/]+/[^/]+)/resolve/main/.+$")
    private val shardRe = Regex("-\\d{5}-of-\\d{5}")

    /** The single-file GGUF names of a Hugging Face repo (no vision projector files, no split shards), or null when the lookup fails. */
    private fun listGguf(repo: String): List<String>? {
        try {
            val c = URL("https://huggingface.co/api/models/$repo/tree/main").openConnection() as HttpURLConnection
            c.connectTimeout = 20_000
            c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", "ZENIQ/1.0")
            if (c.responseCode != 200) { c.disconnect(); return null }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect()
            val arr = org.json.JSONArray(body)
            val files = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val path = o.optString("path")
                if (o.optString("type") == "file" && path.endsWith(".gguf", ignoreCase = true) &&
                    !path.contains("mmproj", ignoreCase = true) && !shardRe.containsMatchIn(path)) files.add(path)
            }
            return files
        } catch (_: Exception) {
            return null
        }
    }

    /** Used only when the stored file name returns 404: picks one single-file GGUF of the repo (Q4_K_M preferred). */
    private fun resolveHfFile(url: String): String? {
        val repo = hfUrlRe.find(url)?.groupValues?.get(1) ?: return null
        val files = listGguf(repo) ?: return null
        var pick: String? = null
        for (q in listOf("q4_k_m", "q4_k_s", "q4_0", "q5_k_m", "q8_0")) {
            pick = files.firstOrNull { it.contains(q, ignoreCase = true) }
            if (pick != null) break
        }
        if (pick == null) pick = files.firstOrNull()
        return if (pick == null) null else "https://huggingface.co/$repo/resolve/main/$pick"
    }

    /**
     * Phones run Q4_0 files faster than Q4_K_M files (the CPU code repacks Q4_0 weights for ARM). When the same repository also has a plain
     * Q4_0 file, that one is downloaded instead of a Q4_K_M file. Returns null to keep the original.
     */
    private fun resolveFastBuild(url: String): String? {
        if (!url.contains("q4_k_m", ignoreCase = true)) return null
        val repo = hfUrlRe.find(url)?.groupValues?.get(1) ?: return null
        val files = listGguf(repo) ?: return null
        val q40 = Regex("(?i)[-._]q4_0\\.gguf$")
        val pick = files.firstOrNull { q40.containsMatchIn(it) } ?: return null
        return "https://huggingface.co/$repo/resolve/main/$pick"
    }

    /**
     * Downloads a model with resume support and automatic redirect following.
     * - Follows HTTP redirects (required for HuggingFace → CDN redirects).
     * - Retries up to 3 times with 2 s backoff on DNS/network failures.
     * - Checks free disk space before starting.
     * - Uses a .part temp file so a partial download never looks complete.
     * - Updates progress at most every 500 ms to avoid flooding StateFlow.
     */
    suspend fun download(url: String) = withContext(Dispatchers.IO) {
        val target = fileFor(url)
        val part   = File(target.path + ".part")

        var lastError: Exception? = null
        var effUrl = url
        var resolvedOnce = false
        // Which file this download really is. A partial download must be continued from the SAME file, so the choice is remembered.
        val src = File(target.path + ".src")
        try {
            if (part.exists() && src.exists()) effUrl = src.readText().trim().ifBlank { url }
            else if (!part.exists()) {
                val fast = try { resolveFastBuild(url) } catch (_: Exception) { null }
                if (fast != null) effUrl = fast
                src.writeText(effUrl)
            }
        } catch (_: Exception) { }
        repeat(3) { attempt ->
            if (attempt > 0) {
                try { Thread.sleep(2000L) } catch (_: InterruptedException) {}
            }
            var conn: HttpURLConnection? = null
            try {
                var have = if (part.exists()) part.length() else 0L
                val rangeHeader = if (have > 0) "bytes=$have-" else null

                var (c, code) = openWithRedirects(effUrl, rangeHeader)
                if (code == 404 && !resolvedOnce && have == 0L) {
                    // The exact file name may have changed on Hugging Face: look up the repo's real file list.
                    resolvedOnce = true
                    c.disconnect()
                    val alt = resolveHfFile(url)
                    if (alt != null) {
                        effUrl = alt
                        try { src.writeText(alt) } catch (_: Exception) { }
                        val r2 = openWithRedirects(effUrl, rangeHeader)
                        c = r2.first
                        code = r2.second
                    }
                }
                conn = c
                if (code == 200) have = 0       // server ignored Range, restart from 0
                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")

                val total = have + conn.contentLengthLong.coerceAtLeast(0L)

                val needed = total - have
                if (needed > 0 && freeDiskGb < (needed / 1_073_741_824.0) * 1.1) {
                    throw RuntimeException("Not enough storage. Free up space and try again.")
                }

                RandomAccessFile(part, "rw").use { raf ->
                    if (code == 200) raf.setLength(0)
                    raf.seek(have)
                    val buf  = ByteArray(256 * 1024)
                    var done = have
                    var last = 0L
                    conn.inputStream.use { ins ->
                        while (isActive) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 500) {
                                last = now
                                downloads.update { it + (url to DlState.Progress(done, total)) }
                            }
                        }
                    }
                    // Final progress update
                    downloads.update { it + (url to DlState.Progress(done, total)) }
                }

                if (!isActive) throw CancellationException()

                // Atomic rename — only replace target if download completed fully
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) {
                    part.copyTo(target, overwrite = true)
                    part.delete()
                }
                downloads.update { it - url }
                try { src.delete() } catch (_: Exception) { }
                return@withContext   // ✅ success

            } catch (e: CancellationException) {
                downloads.update { it - url }
                conn?.disconnect()
                throw e
            } catch (e: UnknownHostException) {
                conn?.disconnect()
                lastError = RuntimeException(
                    "Cannot reach download server — check your internet connection " +
                    "(Wi-Fi or mobile data) and try again.", e)
            } catch (e: Exception) {
                conn?.disconnect()
                lastError = e
            }
        }

        // All retries failed — report error
        downloads.update { it + (url to DlState.Failed(
            lastError?.message?.take(180) ?: "Download failed. Check your internet connection."
        )) }
    }

    fun delete(f: File) { try { f.delete() } catch (_: Throwable) {} }

    fun deletePartial(url: String) {
        try { File(fileFor(url).path + ".part").delete(); File(fileFor(url).path + ".src").delete() } catch (_: Throwable) {}
        downloads.update { it - url }
    }

    fun partialSize(url: String): Long =
        try { File(fileFor(url).path + ".part").let { if (it.exists()) it.length() else 0L } }
        catch (_: Throwable) { 0L }

    /** Total GB used by all model files + partial downloads */
    fun usedDiskGb(): Double =
        (dir.listFiles()?.sumOf { it.length() } ?: 0L) / 1_073_741_824.0
}
