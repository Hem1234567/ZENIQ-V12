package com.astra.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun interface TokenCallback { fun onToken(bytes: ByteArray): Boolean }

object LlamaBridge {
    init { System.loadLibrary("astra_llama") }
    external fun nativeLoad(path: String, nCtx: Int, threads: Int): Boolean
    external fun nativeGenerate(prompt: ByteArray, maxTokens: Int, temp: Float, topP: Float, cb: TokenCallback)
    external fun nativeWarmup(prompt: ByteArray)
    external fun nativeTemplate(): ByteArray
    external fun nativeStop()
    external fun nativeFree()
}

/** Thin safe wrapper — all native calls are serialised through this object. */
object LlmEngine {
    @Volatile var loadedName: String? = null
    @Volatile private var loading = false
    /** The native context can only do one thing at a time: load, warm-up and generate all go through this lock. */
    private val gate = java.util.concurrent.locks.ReentrantLock()
    @Volatile private var warming = false

    /**
     * Loads a model. Automatically unloads any previously loaded model first.
     * Returns false if: already loading, OOM, native error, or file missing.
     */
    suspend fun load(file: File, nCtx: Int, threads: Int): Boolean = loadDetailed(file, nCtx, threads) is LoadResult.Ok

    /** What happened when loading a model: the real reason, so the app never blames RAM when something else went wrong. */
    sealed class LoadResult {
        class Ok(val ctx: Int) : LoadResult()
        object Busy : LoadResult()
        object Missing : LoadResult()
        object Failed : LoadResult()
    }

    /** Context sizes to try: the wanted one, then smaller ones (a smaller context needs less memory). */
    private fun ctxLadder(wanted: Int): List<Int> = listOf(wanted.coerceIn(512, 8192), 3072, 2048, 1536, 1024).filter { it <= wanted.coerceIn(512, 8192) }.distinct()

    suspend fun loadDetailed(file: File, nCtx: Int, threads: Int): LoadResult = withContext(Dispatchers.Default) {
        if (loading) return@withContext LoadResult.Busy
        if (!file.exists() || file.length() == 0L) return@withContext LoadResult.Missing
        loading = true
        try {
            // Use tryLock with a 30-second timeout to avoid permanently blocking the Load button
            val acquired = gate.tryLock(30, java.util.concurrent.TimeUnit.SECONDS)
            if (!acquired) {
                // Force-stop anything running and try to acquire immediately
                try { LlamaBridge.nativeStop() } catch (_: Throwable) {}
                if (!gate.tryLock(5, java.util.concurrent.TimeUnit.SECONDS))
                    return@withContext LoadResult.Failed
            }
            try {
                // Free current model first to release RAM before loading the new one
                if (loadedName != null) {
                    try { LlamaBridge.nativeFree() } catch (_: Throwable) {}
                    loadedName = null
                }
                // The model file is memory-mapped by the native code, so the Java heap limit says nothing about it.
                // If loading fails, try again with a smaller context before giving up.
                var used = 0
                for (c in ctxLadder(nCtx)) {
                    val ok = try { LlamaBridge.nativeLoad(file.absolutePath, c, threads.coerceIn(1, 8)) } catch (_: Throwable) { false }
                    if (ok) { used = c; break }
                    try { LlamaBridge.nativeFree() } catch (_: Throwable) {}
                }
                if (used > 0) {
                    // pick the chat layout this model expects (Qwen, Llama, Gemma, Phi, DeepSeek, Mistral ...)
                    val tpl = try { String(LlamaBridge.nativeTemplate(), Charsets.UTF_8) } catch (_: Throwable) { "" }
                    PromptFormat.configure(tpl, file.name)
                    loadedName = file.name; LoadResult.Ok(used)
                } else LoadResult.Failed
            } finally {
                if (gate.isHeldByCurrentThread) gate.unlock()
            }
        } finally {
            loading = false   // Always reset — even if gate.unlock was never reached
        }
    }

    /** Words (token pieces) per second of the last answer, and how long the phone read the prompt before the first word. 0 = not measured yet. */
    @Volatile var lastTps: Double = 0.0
    @Volatile var lastFirstMs: Long = 0

    /** A short test answer to measure this phone with the loaded model. Returns a readable result. */
    suspend fun speedTest(): String = withContext(Dispatchers.Default) {
        if (loadedName == null) return@withContext "Load a model first."
        lastTps = 0.0
        val sb = StringBuilder()
        generate(PromptFormat.single("You are a helpful assistant.", "Write a short paragraph about the ocean.", true), 64, 0.7f, 0.9f) { sb.append(it); true }
        if (lastTps <= 0.0) "The test could not measure the speed. Try again."
        else "%.1f words per second, first word after %.1f s. ".format(lastTps, lastFirstMs / 1000.0) + speedAdvice(lastTps)
    }

    /** What a measured speed means for the user and what to do about it. */
    fun speedAdvice(tps: Double): String = when {
        tps >= 15 -> "Fast: answers feel instant."
        tps >= 8 -> "Good: short answers take a few seconds."
        tps >= 4 -> "Slow: use a smaller model (Models > Fast & light) or turn on Fast Mode for long answers and apps."
        else -> "Very slow: use a model of 1B or smaller, or Fast Mode (cloud) for seconds-long answers."
    }

    /** Blocking text generation. Call from Dispatchers.Default only. */
    fun generate(prompt: String, maxTokens: Int, temp: Float, topP: Float, onText: (String) -> Boolean) {
        if (loadedName == null) return
        if (warming) stop()                       // a question is more important than the warm-up: cut it short
        gate.lock()
        // The model threads inherit this thread's priority. A slightly lower priority lets the screen always win the CPU,
        // so the app keeps responding while the model is thinking.
        val oldPrio = try { android.os.Process.getThreadPriority(android.os.Process.myTid()) } catch (_: Throwable) { 0 }
        try { android.os.Process.setThreadPriority(4) } catch (_: Throwable) {}
        try {
            if (loadedName == null) return
            val t0 = System.nanoTime()
            var tFirst = 0L
            var pieces = 0
            LlamaBridge.nativeGenerate(
                prompt.toByteArray(Charsets.UTF_8),
                maxTokens.coerceIn(64, 4096),
                temp.coerceIn(0f, 2f),
                topP.coerceIn(0f, 1f)
            ) { bytes ->
                if (pieces == 0) tFirst = System.nanoTime()
                pieces++
                onText(String(bytes, Charsets.UTF_8))
            }
            // speed of this answer (shown by the Speed test and used for advice)
            val tEnd = System.nanoTime()
            if (pieces > 1 && tEnd > tFirst) {
                lastTps = (pieces - 1) * 1e9 / (tEnd - tFirst)
                lastFirstMs = (tFirst - t0) / 1_000_000
            }
        } catch (e: OutOfMemoryError) {
            onText("\n[Out of memory — please load a smaller model]"); onText(""); false
        } catch (_: Throwable) {
        } finally {
            try { android.os.Process.setThreadPriority(oldPrio) } catch (_: Throwable) {}
            gate.unlock()
        }
    }

    /** Pre-reads the fixed start of the chat prompt so the next answer starts sooner. Safe to fail. */
    suspend fun warmup(prefix: String) = withContext(Dispatchers.Default) {
        if (loadedName == null) return@withContext
        if (!gate.tryLock()) return@withContext   // busy loading or answering: skip, it is only an optimisation
        warming = true
        val oldPrio = try { android.os.Process.getThreadPriority(android.os.Process.myTid()) } catch (_: Throwable) { 0 }
        try { android.os.Process.setThreadPriority(8) } catch (_: Throwable) {}
        try { if (loadedName != null) LlamaBridge.nativeWarmup(prefix.toByteArray(Charsets.UTF_8)) }
        catch (_: Throwable) {}
        finally { try { android.os.Process.setThreadPriority(oldPrio) } catch (_: Throwable) {}; warming = false; gate.unlock() }
    }

    fun stop() { try { LlamaBridge.nativeStop() } catch (_: Throwable) {} }

    fun unload() {
        gate.lock()
        try { if (loadedName != null) LlamaBridge.nativeFree() } catch (_: Throwable) {}
        finally { loadedName = null; gate.unlock() }
    }
}
