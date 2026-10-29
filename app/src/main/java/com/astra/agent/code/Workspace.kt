package com.astra.agent.code

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Node(val rel: String, val name: String, val depth: Int, val dir: Boolean, val size: Long)
class Hit(val path: String, val line: Int, val text: String)
class Checkpoint(val id: String, val message: String, val time: Long, val files: Int)
class FileChange(val path: String, val content: String)
class DiffLine(val kind: Char, val text: String)

/**
 * A project folder the user (and the AI) can work in: files, search and replace, checkpoints (git-style history),
 * diffs and ZIP export. Pure Kotlin / java.io: no Android needed, so it can be tested on a plain JVM.
 */
class Workspace(val root: File) {
    private val meta = File(root, ".zeniq")
    private val history = File(meta, "history")
    init { root.mkdirs() }

    companion object {
        const val MAX_FILE = 400_000
        private val textExt = setOf("txt", "md", "html", "htm", "css", "js", "ts", "json", "xml", "csv", "yml", "yaml", "kt", "java", "py", "c", "cpp", "h", "sh",
            "gradle", "kts", "properties", "sql", "svg", "ini", "toml", "php", "rb", "go", "rs", "swift", "dart", "jsx", "tsx", "vue", "bat", "gitignore", "env", "log")

        /** Starter files for a new project. */
        fun createStarter(root: File, kind: String) {
            root.mkdirs()
            when (kind) {
                "python" -> File(root, "main.py").writeText("def main():\n    print(\"Hello from ZENIQ\")\n\n\nif __name__ == \"__main__\":\n    main()\n")
                "blank" -> File(root, "README.md").writeText("# New project\n\nWrite your notes here.\n")
                else -> {
                    File(root, "index.html").writeText("<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n  <meta charset=\"utf-8\">\n  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
                        "  <title>My project</title>\n  <link rel=\"stylesheet\" href=\"style.css\">\n</head>\n<body>\n  <h1>Hello from ZENIQ</h1>\n  <button id=\"btn\">Click me</button>\n  <script src=\"script.js\"></script>\n</body>\n</html>\n")
                    File(root, "style.css").writeText("body {\n  font-family: system-ui, sans-serif;\n  display: grid;\n  place-items: center;\n  min-height: 100vh;\n  margin: 0;\n}\nbutton { padding: 12px 22px; font-size: 1rem; }\n")
                    File(root, "script.js").writeText("document.getElementById('btn').addEventListener('click', () => alert('It works!'));\n")
                }
            }
        }
    }

    fun file(rel: String): File {
        val f = File(root, rel).canonicalFile
        val base = root.canonicalFile
        require(f == base || f.path.startsWith(base.path + File.separator)) { "That path is outside the project." }
        return f
    }

    fun isText(f: File): Boolean {
        val ext = f.name.substringAfterLast('.', f.name.removePrefix(".")).lowercase()
        if (ext in textExt) return true
        if (f.length() > 1_000_000) return false
        return try { f.inputStream().use { s -> val b = ByteArray(minOf(f.length().toInt(), 2000)); s.read(b); b.none { it.toInt() == 0 } } } catch (_: Exception) { false }
    }

    fun tree(maxNodes: Int = 2000): List<Node> {
        val out = ArrayList<Node>()
        fun walk(dir: File, depth: Int) {
            if (depth > 8 || out.size >= maxNodes) return
            val kids = dir.listFiles()?.filter { it.name != ".zeniq" && it.name != ".git" && it.name != "node_modules" }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return
            for (k in kids) {
                if (out.size >= maxNodes) return
                out.add(Node(k.relativeTo(root).path.replace(File.separatorChar, '/'), k.name, depth, k.isDirectory, if (k.isFile) k.length() else 0))
                if (k.isDirectory) walk(k, depth + 1)
            }
        }
        walk(root, 0)
        return out
    }

    fun read(rel: String): String {
        val f = file(rel)
        require(f.isFile) { "Not a file." }
        require(isText(f)) { "This file is not text." }
        val t = f.readText()
        return if (t.length > MAX_FILE) t.take(MAX_FILE) + "\n\u2026 (file cut at $MAX_FILE characters)" else t
    }

    fun write(rel: String, text: String) { val f = file(rel); f.parentFile?.mkdirs(); f.writeText(text) }
    fun newDir(rel: String) { file(rel).mkdirs() }
    fun newFile(rel: String) { val f = file(rel); f.parentFile?.mkdirs(); if (!f.exists()) f.writeText("") }
    fun delete(rel: String) { val f = file(rel); require(f != root.canonicalFile) { "Cannot delete the project root." }; f.deleteRecursively() }
    fun rename(from: String, to: String) { val a = file(from); val b = file(to); b.parentFile?.mkdirs(); require(a.renameTo(b)) { "Could not rename." } }

    fun search(q: String, ignoreCase: Boolean = true, maxHits: Int = 200): List<Hit> {
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>()
        for (n in tree()) {
            if (n.dir || n.size > 1_000_000) continue
            val f = File(root, n.rel)
            if (!isText(f)) continue
            f.useLines { lines ->
                var i = 0
                for (l in lines) { i++; if (l.contains(q, ignoreCase)) { hits.add(Hit(n.rel, i, l.trim().take(160))); if (hits.size >= maxHits) return hits } }
            }
        }
        return hits
    }

    /** Replaces text in the given files (or in every text file). Returns how many places were changed. */
    fun replaceAll(find: String, replacement: String, ignoreCase: Boolean = false, paths: List<String>? = null): Int {
        if (find.isEmpty()) return 0
        var count = 0
        val targets = paths ?: tree().filter { !it.dir && it.size <= 1_000_000 }.map { it.rel }
        for (rel in targets) {
            val f = file(rel)
            if (!f.isFile || !isText(f)) continue
            val t = f.readText()
            val re = Regex(Regex.escape(find), if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
            val n = re.findAll(t).count()
            if (n > 0) { f.writeText(re.replace(t) { replacement }); count += n }
        }
        return count
    }

    // ── Checkpoints (git-style history) ─────────────────────────────────────

    private fun projectFiles(): List<File> = tree(5000).filter { !it.dir }.map { File(root, it.rel) }

    fun commit(message: String): Checkpoint {
        val id = System.currentTimeMillis().toString()
        val dir = File(history, id); val snap = File(dir, "files")
        var n = 0
        for (f in projectFiles()) {
            if (f.length() > 5_000_000) continue
            val dst = File(snap, f.relativeTo(root).path); dst.parentFile?.mkdirs(); f.copyTo(dst, overwrite = true); n++
        }
        File(dir, "message.txt").writeText(message.ifBlank { "Checkpoint" } + "\n" + n)
        return Checkpoint(id, message.ifBlank { "Checkpoint" }, id.toLong(), n)
    }

    fun checkpoints(): List<Checkpoint> =
        (history.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { d ->
            val m = File(d, "message.txt").takeIf { it.exists() }?.readLines() ?: return@mapNotNull null
            Checkpoint(d.name, m.firstOrNull().orEmpty(), d.name.toLongOrNull() ?: 0, m.getOrNull(1)?.toIntOrNull() ?: 0)
        }.sortedByDescending { it.time }

    fun fileAt(id: String, rel: String): String? { val f = File(File(File(history, id), "files"), rel); return if (f.isFile) f.readText() else null }

    /** Files that differ from checkpoint [id]: "+ added", "~ changed", "- deleted". */
    fun changes(id: String?): List<String> {
        val base = if (id == null) File("/nonexistent") else File(File(history, id), "files")
        val now = tree(5000).filter { !it.dir }.map { it.rel }.toSet()
        val old = if (base.isDirectory) base.walkTopDown().filter { it.isFile }.map { it.relativeTo(base).path.replace(File.separatorChar, '/') }.toSet() else emptySet()
        val out = ArrayList<String>()
        for (r in now.sorted()) if (r !in old) out.add("+ $r") else if (File(root, r).readBytes().contentEquals(File(base, r).readBytes()).not()) out.add("~ $r")
        for (r in old.sorted()) if (r !in now) out.add("- $r")
        return out
    }

    fun restore(id: String) {
        val snap = File(File(history, id), "files")
        require(snap.isDirectory) { "Checkpoint not found." }
        for (f in projectFiles()) f.delete()
        snap.walkTopDown().filter { it.isFile }.forEach { s -> val dst = File(root, s.relativeTo(snap).path); dst.parentFile?.mkdirs(); s.copyTo(dst, overwrite = true) }
    }

    fun zip(): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (f in projectFiles()) { z.putNextEntry(ZipEntry(f.relativeTo(root).path.replace(File.separatorChar, '/'))); f.inputStream().use { it.copyTo(z) }; z.closeEntry() }
        }
        return bos.toByteArray()
    }

    // ── What the AI is shown ────────────────────────────────────────────────

    /** Project tree, the open file and the files that look most relevant to the request, within [budget] characters. */
    fun contextFor(instruction: String, current: String?, budget: Int): String {
        val sb = StringBuilder()
        val nodes = tree(400)
        sb.append("PROJECT FILES:\n"); for (n in nodes) sb.append("  ".repeat(n.depth)).append(n.name).append(if (n.dir) "/" else "").append('\n')
        val words = instruction.lowercase().split(Regex("[^a-z0-9_.]+")).filter { it.length > 2 }.toSet()
        val files = nodes.filter { !it.dir && isText(File(root, it.rel)) && it.size < 200_000 }
        val ranked = files.sortedByDescending { n ->
            (if (n.rel == current) 1000 else 0) + words.count { w -> n.rel.lowercase().contains(w) } * 50 +
                (if (n.rel.endsWith("index.html") || n.rel.endsWith("main.py") || n.rel.endsWith("README.md")) 5 else 0)
        }
        var left = budget - sb.length
        for (n in ranked) {
            if (left < 300) break
            val t = try { read(n.rel) } catch (_: Exception) { continue }
            val piece = if (t.length > left - 120) t.take(left - 120) + "\n... (cut)" else t
            sb.append("\n=== ").append(n.rel).append(" ===\n").append(piece).append('\n')
            left -= piece.length + 40
        }
        return sb.toString()
    }
}

object Diff {
    /** Line diff (longest common subsequence). Very large inputs fall back to "everything replaced". */
    fun lines(old: String, new: String): List<DiffLine> {
        val a = if (old.isEmpty()) emptyList() else old.split("\n"); val b = if (new.isEmpty()) emptyList() else new.split("\n")
        if (a.size.toLong() * b.size > 4_000_000L) return a.map { DiffLine('-', it) } + b.map { DiffLine('+', it) }
        val lcs = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.size - 1 downTo 0) for (j in b.size - 1 downTo 0)
            lcs[i][j] = if (a[i] == b[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        val out = ArrayList<DiffLine>()
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) { out.add(DiffLine(' ', a[i])); i++; j++ }
            else if (lcs[i + 1][j] >= lcs[i][j + 1]) { out.add(DiffLine('-', a[i])); i++ }
            else { out.add(DiffLine('+', b[j])); j++ }
        }
        while (i < a.size) out.add(DiffLine('-', a[i++]))
        while (j < b.size) out.add(DiffLine('+', b[j++]))
        return out
    }

    /** Only the changed lines with [ctx] lines of context around them. */
    fun compact(all: List<DiffLine>, ctx: Int = 2): List<DiffLine> {
        val keep = BooleanArray(all.size)
        all.forEachIndexed { i, d -> if (d.kind != ' ') for (k in maxOf(0, i - ctx)..minOf(all.size - 1, i + ctx)) keep[k] = true }
        val out = ArrayList<DiffLine>(); var skipped = false
        all.forEachIndexed { i, d -> if (keep[i]) { out.add(d); skipped = false } else if (!skipped) { out.add(DiffLine('.', "\u2026")); skipped = true } }
        return out
    }
}

object CodeEdits {
    private val fence = Regex("```[A-Za-z0-9_+#.-]*[ \\t]*\\n(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
    private val marker = Regex("(?:FILE|PATH)\\s*:\\s*`?([\\w./ @+-]+\\.[A-Za-z0-9]+)`?", RegexOption.IGNORE_CASE)
    private val pathLine = Regex("^[*`#\\s]*([\\w./-]+\\.[A-Za-z0-9]{1,6})[*`:\\s]*$")

    /** Files the model wants to write: a "### FILE: path" line (or just the path) followed by a fenced code block. */
    fun parse(text: String): List<FileChange> {
        val out = LinkedHashMap<String, String>()
        for (m in fence.findAll(text)) {
            val before = text.substring(0, m.range.first).trimEnd().lines().takeLast(2).reversed()
            var path: String? = null
            for (l in before) {
                path = marker.find(l)?.groupValues?.get(1)?.trim() ?: pathLine.find(l.trim())?.groupValues?.get(1)
                    ?: Regex("`([\\w./-]+\\.[A-Za-z0-9]{1,6})`").find(l)?.groupValues?.get(1)
                if (path != null) break
            }
            if (path == null) path = Regex("^\\s*(?://|#|<!--)\\s*(?:file|path)\\s*:\\s*(\\S+)", RegexOption.IGNORE_CASE).find(m.groupValues[1])?.groupValues?.get(1)
            if (path == null) continue
            val clean = path.trim().trimStart('/').replace("\\", "/")
            if (clean.contains("..") || clean.isBlank()) continue
            out[clean] = m.groupValues[1] + "\n"
        }
        return out.map { FileChange(it.key, it.value) }
    }

    /** The model's explanation without the code blocks. */
    fun summary(text: String): String = text.replace(fence, "").replace(Regex("(?im)^.*(?:FILE|PATH)\\s*:.*$"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
}

object Terminal {
    @Volatile private var current: Process? = null
    /** Working directory kept between commands, so "cd src" works like in a real shell session. */
    @Volatile var cwd: File? = null

    /** Stops the command that is running now (Ctrl+C equivalent). */
    fun stop() { try { current?.destroyForcibly() } catch (_: Throwable) {} }

    /** Runs one shell command in [dir] (or the folder reached by earlier "cd" commands inside [dir]); stops after [timeoutSec] seconds. */
    fun run(command: String, dir: File, timeoutSec: Long = 30, maxChars: Int = 20_000): String {
        return try {
            val start = cwd?.takeIf { it.isDirectory && it.canonicalPath.startsWith(dir.canonicalPath) } ?: dir
            val marker = "__ZENIQ_CWD__"
            val wrapped = command + "\n__zc=\$?\necho \"" + marker + "\$PWD\"\nexit \$__zc"
            val pb = ProcessBuilder("sh", "-c", wrapped).directory(start).redirectErrorStream(true)
            pb.environment()["TERM"] = "xterm-256color"; pb.environment()["LANG"] = "en_US.UTF-8"
            val p = pb.start()
            current = p
            val sb = StringBuilder()
            val reader = Thread { try { p.inputStream.bufferedReader().use { r -> val buf = CharArray(2048); while (true) { val n = r.read(buf); if (n < 0) break; if (sb.length < maxChars + 400) sb.append(buf, 0, n) } } } catch (_: Exception) {} }
            reader.start()
            val done = p.waitFor(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) { p.destroyForcibly(); sb.append("\n[stopped after " + timeoutSec + "s]") }
            reader.join(500)
            current = null
            val code = if (done) p.exitValue() else -1
            var text = sb.toString()
            val at = text.lastIndexOf(marker)
            if (at >= 0) {
                val newDir = File(text.substring(at + marker.length).lineSequence().first().trim())
                if (newDir.isDirectory) cwd = newDir
                text = text.substring(0, at)
            }
            (text.take(maxChars) + if (done && code != 0) "\n[exit code " + code + "]" else "").trimEnd()
        } catch (e: Exception) { current = null; "Could not run: " + e.message }
    }
}
