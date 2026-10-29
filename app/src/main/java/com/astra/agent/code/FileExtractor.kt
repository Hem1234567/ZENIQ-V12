package com.astra.agent.code

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One generated source file. */
data class GenFile(val path: String, val content: String)

/** Result of reading the model's answer. */
data class GenProject(val name: String, val files: List<GenFile>)

/**
 * Turns a model answer such as
 *
 *   Project: todo-app
 *   File: index.html
 *   ```html ... ```
 *
 * into real files. Small local models are sloppy, so this accepts many layouts:
 *  - "File: x", "**x**", "### x", "`x`", "x:" on the line above the block
 *  - a comment on the first line of the block naming the file (line comment, hash comment or HTML comment)
 *  - "```html:index.html" or "```html title=index.html"
 *  - falls back to a sensible name from the language (html -> index.html ...)
 */
object FileExtractor {

    private val fenceOpen = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^\\n]*)$")
    private val projectRe = Regex("(?im)^\\s*[*#_ ]*project(?: name)?\\s*[:=]\\s*[`*_\"']*([A-Za-z0-9][A-Za-z0-9 _.-]{0,40})")
    private val pathLike = Regex("([A-Za-z0-9_\\-./]+\\.[A-Za-z0-9]{1,6})")
    private val knownExt = setOf(
        "html", "htm", "css", "js", "mjs", "jsx", "ts", "tsx", "json", "md", "txt", "py", "java", "kt", "kts", "xml", "yml", "yaml",
        "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sh", "bat", "sql", "svg", "toml", "ini", "gradle", "swift",
        "dart", "vue", "env", "csv", "lock", "properties"
    )

    private val langDefault = mapOf(
        "html" to "index.html", "xml" to "data.xml", "css" to "style.css", "javascript" to "script.js", "js" to "script.js",
        "typescript" to "main.ts", "ts" to "main.ts", "jsx" to "App.jsx", "tsx" to "App.tsx", "json" to "data.json",
        "python" to "main.py", "py" to "main.py", "java" to "Main.java", "kotlin" to "Main.kt", "kt" to "Main.kt",
        "c" to "main.c", "cpp" to "main.cpp", "c++" to "main.cpp", "csharp" to "Program.cs", "cs" to "Program.cs",
        "go" to "main.go", "rust" to "main.rs", "rs" to "main.rs", "ruby" to "main.rb", "php" to "index.php",
        "bash" to "run.sh", "sh" to "run.sh", "shell" to "run.sh", "sql" to "query.sql", "swift" to "main.swift",
        "dart" to "main.dart", "yaml" to "config.yml", "yml" to "config.yml", "markdown" to "README.md", "md" to "README.md",
        "text" to "notes.txt", "txt" to "notes.txt", "svg" to "image.svg"
    )

    private val langExt = mapOf(
        "html" to "html", "css" to "css", "javascript" to "js", "js" to "js", "python" to "py", "py" to "py", "java" to "java",
        "kotlin" to "kt", "json" to "json", "typescript" to "ts", "ts" to "ts", "c" to "c", "cpp" to "cpp", "bash" to "sh",
        "sh" to "sh", "sql" to "sql", "xml" to "xml", "yaml" to "yml", "go" to "go", "rust" to "rs", "php" to "php",
        "ruby" to "rb", "swift" to "swift", "dart" to "dart", "csharp" to "cs", "markdown" to "md"
    )

    /** A block as it appears in the answer. */
    data class Block(val lang: String, val code: String, val hint: String?, val closed: Boolean)

    fun extractBlocks(answer: String): List<Block> {
        val lines = answer.replace("\r\n", "\n").split("\n")
        val out = ArrayList<Block>()
        var i = 0
        var lastTextLine: String? = null
        while (i < lines.size) {
            val m = fenceOpen.find(lines[i])
            if (m == null) {
                if (lines[i].isNotBlank()) lastTextLine = lines[i]
                i++; continue
            }
            val fence = m.groupValues[1]
            val info = m.groupValues[2].trim()
            val body = StringBuilder()
            var j = i + 1
            var closed = false
            while (j < lines.size) {
                val l = lines[j]
                if (l.trim().startsWith(fence.take(3)) && l.trim().all { it == fence[0] }) { closed = true; break }
                body.append(l).append('\n'); j++
            }
            val infoParts = info.split(Regex("[\\s:]+"), limit = 2)
            val lang = infoParts.firstOrNull().orEmpty().lowercase()
            var hint: String? = null
            if (infoParts.size > 1) hint = pathIn(infoParts[1].replace(Regex("(?i)^(title|file|filename|name)\\s*=\\s*"), "").trim('"', '\'', '`'))
            if (hint == null) hint = lastTextLine?.let { hintFromLine(it) }
            out.add(Block(lang, body.toString().trimEnd('\n'), hint, closed))
            lastTextLine = null
            i = if (closed) j + 1 else j
        }
        return out
    }

    private fun hintFromLine(line: String): String? {
        val l = line.trim()
        if (l.length > 120) return null
        val cleaned = l.replace(Regex("^[#>*\\-\\d.)\\s]+"), "").replace(Regex("(?i)^(file(name)?|path|create|save as|in)(\\s*[:\\-]\\s*|\\s+)"), "")
        return pathIn(cleaned.trim('*', '_', '`', ':', ' ', '"', '\''))
    }

    private fun pathIn(s: String): String? {
        for (m in pathLike.findAll(s)) {
            val p = m.groupValues[1].trim('.', '/')
            val ext = p.substringAfterLast('.', "").lowercase()
            if (ext !in knownExt) continue
            if (p.startsWith("http") || p.contains("://")) continue
            return p
        }
        return null
    }

    private val equivalent = listOf(setOf("html", "htm"), setOf("js", "mjs", "jsx"), setOf("ts", "tsx"),
        setOf("yml", "yaml"), setOf("sh", "bash"), setOf("xml", "svg"), setOf("md", "txt"))

    /** A file name taken from the text is only trusted when it fits the language of the code block. */
    private fun fits(lang: String, path: String): Boolean {
        val expected = langExt[lang] ?: return true
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext == expected) return true
        return equivalent.any { expected in it && ext in it }
    }

    private fun commentHint(code: String): Pair<String?, String> {
        val first = code.lineSequence().firstOrNull()?.trim() ?: return null to code
        val m = Regex("^(?://|#|--|<!--|/\\*)\\s*(?:file(?:name)?\\s*[:=-]\\s*)?([A-Za-z0-9_\\-./]+\\.[A-Za-z0-9]{1,6})\\s*(?:-->|\\*/)?$", RegexOption.IGNORE_CASE).find(first)
        if (m != null) {
            val p = pathIn(m.groupValues[1])
            if (p != null) return p to code.substringAfter('\n', "")
        }
        return null to code
    }

    fun sanitizePath(p: String): String {
        val parts = p.replace('\\', '/').split('/').filter { it.isNotBlank() && it != "." && it != ".." }
            .map { it.replace(Regex("[^A-Za-z0-9_.\\- ]"), "_").trim() }.filter { it.isNotEmpty() }
        return parts.take(5).joinToString("/")
    }

    fun parseProject(answer: String, request: String): GenProject {
        val blocks = extractBlocks(answer).filter { it.code.isNotBlank() }
        val name = projectRe.find(answer)?.groupValues?.get(1)?.trim()
            ?.lowercase()?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.take(32)
            ?.takeIf { it.length >= 2 } ?: CodeIntent.projectName(request)

        val used = LinkedHashMap<String, GenFile>()
        for (b in blocks) {
            val lang = b.lang
            // blocks that are only run instructions / shell commands are skipped, README text is kept
            val (cHint, code) = commentHint(b.code)
            var chosen: String? = (b.hint ?: cHint)?.takeIf { fits(lang, it) }
            if (chosen == null) {
                chosen = langDefault[lang]
                if (chosen == null) continue                   // unknown language, nothing sensible to name it
                if (lang in setOf("bash", "sh", "shell") && b.code.lines().size <= 3 && !b.code.contains("#!/")) continue
            }
            var path: String = sanitizePath(chosen)
            if (path.isBlank()) continue
            if (path.substringAfterLast('/').substringAfterLast('.', "") !in knownExt) {
                path += "." + (langExt[lang] ?: "txt")
            }
            var unique: String = path
            // The model re-printing the same file later replaces the earlier version
            val sameName = used.values.firstOrNull { it.path.equals(path, true) }
            if (sameName != null && sameName.content.length < code.length * 2) {
                used.remove(sameName.path.lowercase())
            } else {
                var n = 2
                while (used.containsKey(unique.lowercase())) {
                    val base = path.substringBeforeLast('.', path); val ext = path.substringAfterLast('.', "")
                    unique = if (ext.isEmpty()) "$base-$n" else "$base-$n.$ext"; n++
                }
            }
            used[unique.lowercase()] = GenFile(unique, code.trimEnd() + "\n")
        }
        val files = used.values.toMutableList()
        if (files.isNotEmpty() && files.none { it.path.equals("README.md", true) }) {
            val hasIndex = files.any { it.path.equals("index.html", true) }
            val run = if (hasIndex) "Open `index.html` in any web browser." else "Open the files in your editor and run them as usual."
            files.add(GenFile("README.md", "# $name\n\nGenerated by ZENIQ.\n\n## How to run\n$run\n\n## Files\n" +
                files.joinToString("\n") { "- `${it.path}`" } + "\n"))
        }
        return GenProject(name, files)
    }

    fun zip(project: GenProject): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            for (f in project.files) {
                z.putNextEntry(ZipEntry(project.name + "/" + f.path))
                z.write(f.content.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }
}
