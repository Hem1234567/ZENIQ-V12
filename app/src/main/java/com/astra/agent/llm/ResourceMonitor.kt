package com.astra.agent.llm

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.StatFs
import java.io.File
import java.util.Locale

fun fmtBytes(b: Long): String = when {
    b >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", b / 1_073_741_824.0)
    b >= 1_048_576L     -> String.format(Locale.US, "%.0f MB", b / 1_048_576.0)
    b >= 1024L          -> String.format(Locale.US, "%.0f KB", b / 1024.0)
    else                -> "$b B"
}

/** Reads how much storage and RAM the app is using right now. */
class ResourceMonitor(ctx: Context) {
    private val app = ctx.applicationContext

    data class Snap(
        val appTotal: Long,      // apk + data + cache
        val models: Long,
        val chatsAndData: Long,
        val cache: Long,
        val apk: Long,
        val diskFree: Long,
        val diskTotal: Long,
        val processRam: Long,    // PSS of this process
        val javaHeap: Long,
        val nativeHeap: Long,
        val modelInRam: Long,    // size of the loaded model file (approx. RAM it needs)
        val deviceRamTotal: Long,
        val deviceRamFree: Long
    )

    private var disk = LongArray(4)
    private var diskAt = 0L

    private fun dirSize(f: File): Long {
        if (!f.exists()) return 0L
        if (f.isFile) return f.length()
        val kids = f.listFiles() ?: return 0L
        return kids.fold(0L) { acc, k -> acc + dirSize(k) }
    }

    /** Disk numbers are cached for 4 s (walking folders is slower); RAM numbers are read every call. */
    fun snapshot(loadedModel: String?, modelsDir: File): Snap {
        val now = System.currentTimeMillis()
        if (now - diskAt > 4000L) {
            val models = dirSize(modelsDir)
            val data = dirSize(app.filesDir)
            val cache = dirSize(app.cacheDir)
            val apk = try { File(app.applicationInfo.sourceDir).length() } catch (_: Exception) { 0L }
            disk = longArrayOf(models, (data - models).coerceAtLeast(0L), cache, apk)
            diskAt = now
        }
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val dm = ActivityManager.MemoryInfo()
        am.getMemoryInfo(dm)
        val rt = Runtime.getRuntime()
        val st = try { StatFs(app.filesDir.path) } catch (_: Exception) { null }
        val modelBytes = if (loadedModel != null) File(modelsDir, loadedModel).length() else 0L

        return Snap(
            appTotal = disk[0] + disk[1] + disk[2] + disk[3],
            models = disk[0], chatsAndData = disk[1], cache = disk[2], apk = disk[3],
            diskFree = st?.let { it.availableBlocksLong * it.blockSizeLong } ?: 0L,
            diskTotal = st?.let { it.blockCountLong * it.blockSizeLong } ?: 0L,
            processRam = mi.totalPss * 1024L,
            javaHeap = rt.totalMemory() - rt.freeMemory(),
            nativeHeap = Debug.getNativeHeapAllocatedSize(),
            modelInRam = modelBytes,
            deviceRamTotal = dm.totalMem,
            deviceRamFree = dm.availMem
        )
    }

    fun clearCache(): Long {
        val before = dirSize(app.cacheDir)
        app.cacheDir.listFiles()?.forEach { try { it.deleteRecursively() } catch (_: Exception) {} }
        diskAt = 0L
        return before
    }
}
