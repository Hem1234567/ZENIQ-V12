package com.astra.agent.code

import android.content.Context
import org.json.JSONObject
import java.io.File

/** The kinds of workspace ZENIQ keeps. Each one has its OWN folder inside the app's data, and they are never mixed. */
enum class CodeMode(val label: String, val folder: String) {
    IDE("IDE", "ide"),
    CLAUDE("Claude Code", "claude-code"),
    OPENCLAW("Open Claw", "openclaw")
}

/** What a project card shows. */
class ProjectInfo(
    val name: String,
    val title: String,
    val prompt: String,
    val stack: String,
    val lastEdited: Long,
    val fileCount: Int,
    val complete: Boolean
)

/**
 * Where projects live:
 *     <app data>/zeniq/ide/<project>          (IDE projects, Lovable style)
 *     <app data>/zeniq/claude-code/<project>  (Claude Code projects)
 * Everything is plain files, so projects survive closing and reopening the app. A small `.zeniq/project.json` in each project
 * keeps the title, the original prompt, the stack and whether the build finished.
 */
object ProjectStore {
    private const val META = "project.json"

    fun root(ctx: Context, mode: CodeMode): File = File(File(ctx.filesDir, "zeniq"), mode.folder).apply { mkdirs() }

    /** Projects made before the two folders existed lived in app data / code. They are moved (once) into the IDE folder. */
    fun migrate(ctx: Context) {
        try {
            val old = File(ctx.filesDir, "code")
            if (!old.isDirectory) return
            val dest = root(ctx, CodeMode.IDE)
            old.listFiles()?.filter { it.isDirectory }?.forEach { d ->
                var target = File(dest, d.name); var i = 2
                while (target.exists()) { target = File(dest, d.name + "-" + i); i++ }
                if (!d.renameTo(target)) { d.copyRecursively(target, overwrite = false); d.deleteRecursively() }
            }
            if (old.listFiles().isNullOrEmpty()) old.delete()
        } catch (_: Throwable) { /* a failed move must never stop the app; the old folder simply stays where it is */ }
    }

    fun slug(text: String): String =
        text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifBlank { "project" }

    fun uniqueName(root: File, base: String): String {
        val b = slug(base)
        var n = b; var i = 2
        while (File(root, n).exists()) { n = "$b-$i"; i++ }
        return n
    }

    private fun metaFile(dir: File) = File(File(dir, ".zeniq"), META)

    fun writeMeta(dir: File, title: String, prompt: String, stack: String, complete: Boolean) {
        try {
            metaFile(dir).parentFile?.mkdirs()
            metaFile(dir).writeText(JSONObject().put("title", title).put("prompt", prompt.take(2000)).put("stack", stack).put("complete", complete).toString())
        } catch (_: Throwable) {}
    }

    private fun readMeta(dir: File): JSONObject? = try { JSONObject(metaFile(dir).readText()) } catch (_: Throwable) { null }

    /** Newest modification time of any real project file (ignores our own bookkeeping folders). */
    fun lastEdited(dir: File): Long {
        var best = dir.lastModified()
        dir.walkTopDown().onEnter { it.name != ".zeniq" && it.name != ".git" && it.name != "node_modules" }
            .forEach { if (it.isFile && it.lastModified() > best) best = it.lastModified() }
        return best
    }

    private fun countFiles(dir: File): Int =
        dir.walkTopDown().onEnter { it.name != ".zeniq" && it.name != ".git" && it.name != "node_modules" && it.name != "dist" }.count { it.isFile }

    fun list(ctx: Context, mode: CodeMode): List<ProjectInfo> =
        root(ctx, mode).listFiles()?.filter { it.isDirectory }?.map { d ->
            val m = readMeta(d)
            ProjectInfo(d.name, m?.optString("title")?.takeIf { it.isNotBlank() } ?: d.name.replace('-', ' ').replaceFirstChar { it.uppercase() },
                m?.optString("prompt") ?: "", m?.optString("stack") ?: "", lastEdited(d), countFiles(d), m?.optBoolean("complete", true) ?: true)
        }?.sortedByDescending { it.lastEdited } ?: emptyList()

    /** Deletes the project folder and everything in it. Returns true when it is really gone. */
    fun delete(ctx: Context, mode: CodeMode, name: String): Boolean {
        val dir = File(root(ctx, mode), name)
        val base = root(ctx, mode).canonicalFile
        if (dir.canonicalFile.parentFile != base) return false      // never delete anything outside the mode folder
        dir.deleteRecursively()
        return !dir.exists()
    }

    /** The whole project as ONE self-contained .html page (CSS and JS put inside), for sharing as a single file. Null when there is no page. */
    fun singleHtml(ws: Workspace): String? {
        val rel = ws.tree().firstOrNull { !it.dir && it.rel == "dist/index.html" }?.rel
            ?: ws.tree().firstOrNull { !it.dir && it.rel.endsWith("index.html") }?.rel
            ?: ws.tree().firstOrNull { !it.dir && it.rel.endsWith(".html") }?.rel ?: return null
        val dir = rel.substringBeforeLast('/', "")
        fun resolve(p: String) = (if (dir.isEmpty() || p.startsWith("/")) p.trimStart('/') else "$dir/$p").removePrefix("./")
        var html = ws.read(rel)
        html = Regex("<link[^>]*href=[\"']([^\"']+\\.css)[\"'][^>]*>", RegexOption.IGNORE_CASE).replace(html) { m ->
            try { "<style>\n" + ws.read(resolve(m.groupValues[1])) + "\n</style>" } catch (_: Exception) { m.value } }
        html = Regex("<script([^>]*)src=[\"']([^\"']+)[\"']([^>]*)>\\s*</script>", RegexOption.IGNORE_CASE).replace(html) { m ->
            if (m.groupValues[2].startsWith("http")) m.value
            else try { "<script" + m.groupValues[1] + m.groupValues[3] + ">\n" + ws.read(resolve(m.groupValues[2])).replace("</script", "<\\/script") + "\n</script>" } catch (_: Exception) { m.value } }
        return html
    }
}
