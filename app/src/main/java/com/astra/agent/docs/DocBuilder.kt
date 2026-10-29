package com.astra.agent.docs

enum class DocFormat(val ext: String, val mime: String, val label: String) {
    PDF("pdf", "application/pdf", "PDF document"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word document"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel workbook"),
    PPTX("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "PowerPoint presentation"),
    CSV("csv", "text/csv", "CSV file"),
    MD("md", "text/markdown", "Markdown file"),
    TXT("txt", "text/plain", "Text file");

    companion object {
        fun fromName(name: String): DocFormat? {
            val e = name.substringAfterLast('.', "").lowercase()
            return values().firstOrNull { it.ext == e }
        }
        fun mimeFor(name: String): String = fromName(name)?.mime ?: "application/octet-stream"
    }
}

/** Turns the AI's Markdown answer into the bytes of a real file. */
object DocBuilder {

    /** Removes chatter, <think> blocks and an outer code fence the model may have added around the document. */
    fun clean(answer: String): String {
        var t = answer.replace(Regex("(?s)<think>.*?</think>"), "").replace(Regex("(?s)^.*?</think>"), "").trim()
        // an outer ```markdown ... ``` wrapper
        val lines = t.lines()
        if (lines.size >= 2 && Regex("^```\\s*(markdown|md|text|txt)?\\s*$", RegexOption.IGNORE_CASE).matches(lines.first().trim()) &&
            lines.last().trim() == "```" && lines.count { it.trim().startsWith("```") } == 2) {
            t = lines.drop(1).dropLast(1).joinToString("\n").trim()
        }
        // "Sure! Here's your document:" before the content
        val firstBreak = t.indexOf("\n\n")
        if (firstBreak in 1..220) {
            val first = t.substring(0, firstBreak)
            if (!first.startsWith("#") && !first.startsWith("|") && !first.startsWith("-") &&
                Regex("^(sure|certainly|of course|okay|ok|absolutely|great|here('s| is| are)|below is|i('ve| have) (created|prepared|written|made))\\b", RegexOption.IGNORE_CASE).containsMatchIn(first.trim()))
                t = t.substring(firstBreak).trim()
        }
        // "Let me know if you'd like changes." after the content
        val lastBreak = t.lastIndexOf("\n\n")
        if (lastBreak > 0) {
            val last = t.substring(lastBreak).trim()
            if (last.length < 260 && Regex("^(let me know|i hope|feel free|if you (need|would|want|'d)|hope this|would you like|do you want|please let me know)", RegexOption.IGNORE_CASE).containsMatchIn(last))
                t = t.substring(0, lastBreak).trim()
        }
        return t
    }

    internal fun build(format: DocFormat, markdown: String, title: String, style: StyleChoice = StyleChoice.DEFAULT, images: List<DocImage> = emptyList()): ByteArray {
        val blocks = MdParser.parse(markdown)
        return when (format) {
            DocFormat.PDF -> PdfWriter.write(blocks, title, style.theme, images)
            DocFormat.DOCX -> DocxWriter.write(blocks, title, style.theme, images)
            DocFormat.XLSX -> XlsxWriter.write(blocks, title, style.theme, images)
            DocFormat.PPTX -> pptx(blocks, title, style, images)
            DocFormat.TXT -> styledText(blocks, title, style.theme).toByteArray(Charsets.UTF_8)
            DocFormat.CSV -> csv(blocks).toByteArray(Charsets.UTF_8).let { byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + it }  // BOM so Excel reads UTF-8
            DocFormat.MD -> markdown.toByteArray(Charsets.UTF_8)
        }
    }

    /** Designed template when one is available; the plain built-in layout if anything about the template fails. */
    internal fun pptx(blocks: List<Block>, title: String, style: StyleChoice, images: List<DocImage> = emptyList()): ByteArray {
        val tpl = style.ppt
        val load = style.assets
        if (tpl != null && load != null) {
            try {
                val bytes = PptxTemplates.write(blocks, title, tpl, load, images = images)
                if (bytes != null) return bytes
            } catch (e: Exception) {
                android.util.Log.w("ZENIQ", "template ${tpl.id} failed, using built-in slides", e)
            }
        }
        return PptxWriter.write(blocks, title)
    }

    /** A text file with a title banner, numbered / ruled sections and a footer, instead of a bare dump of text. */
    fun styledText(blocks: List<Block>, title: String, theme: Theme): String {
        val premium = theme.tier == Tier.PREMIUM
        val w = 48
        val date = java.time.LocalDate.now().let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}, ${it.year}" }
        val firstH1 = blocks.firstOrNull { it is Heading && it.level == 1 } as? Heading
        val docTitle = (firstH1?.runs?.plain()?.trim()).takeUnless { it.isNullOrEmpty() } ?: title
        fun wrap(t: String, width: Int): List<String> {
            val out = ArrayList<String>(); var cur = StringBuilder()
            for (word in t.split(' ')) {
                if (cur.isNotEmpty() && cur.length + 1 + word.length > width) { out.add(cur.toString()); cur = StringBuilder() }
                if (cur.isNotEmpty()) cur.append(' ')
                cur.append(word)
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
        fun center(t: String, width: Int): String { val pad = ((width - t.length) / 2).coerceAtLeast(0); return " ".repeat(pad) + t }
        val sb = StringBuilder()
        val tl = wrap(docTitle.uppercase(), w - 6)
        if (premium) {
            sb.append('\u2554').append("\u2550".repeat(w - 2)).append("\u2557\n")
            for (l in tl) { val c = center(l, w - 2); sb.append('\u2551').append(c).append(" ".repeat((w - 2 - c.length).coerceAtLeast(0))).append("\u2551\n") }
            val d = center(date, w - 2)
            sb.append('\u2551').append(d).append(" ".repeat((w - 2 - d.length).coerceAtLeast(0))).append("\u2551\n")
            sb.append('\u255A').append("\u2550".repeat(w - 2)).append("\u255D\n\n")
        } else {
            sb.append("=".repeat(w)).append('\n')
            for (l in tl) sb.append(center(l, w)).append('\n')
            sb.append(center(date, w)).append('\n')
            sb.append("=".repeat(w)).append("\n\n")
        }
        var section = 0
        var skippedTitle = false
        var prevList = false
        for (b in blocks) {
            if (b !is ListItem && prevList) { sb.append('\n'); prevList = false }
            if (b is ListItem) {
                sb.append("  ".repeat(b.level)).append(if (b.ordered) "${b.number}. " else "\u2022 ").append(b.runs.plain().trim()).append('\n')
                prevList = true
                continue
            }
            if (b is Heading) {
                val t = b.runs.plain().trim()
                if (b.level == 1 && !skippedTitle && firstH1 === b) { skippedTitle = true; continue }
                if (b.level <= 2) {
                    section++
                    if (premium) sb.append("\u25A0 ").append(t.uppercase()).append('\n').append("\u2500".repeat(w)).append("\n\n")
                    else { val h = "$section. " + t.uppercase(); sb.append(h).append('\n').append("-".repeat(minOf(h.length, w))).append("\n\n") }
                } else sb.append(if (premium) "\u25B8 " else "").append(t).append(if (premium) "" else ":").append("\n\n")
            } else if (b is Rule) sb.append(if (premium) "\u2500".repeat(w) else "-".repeat(w)).append("\n\n")
            else sb.append(plainText(listOf(b))).append('\n')
        }
        val body = sb.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd()
        val rule = if (premium) "\u2500".repeat(w) else "=".repeat(w)
        return body + "\n\n" + rule + "\n" + center("Created with ZENIQ", w) + "\n"
    }

    private fun csvCell(s: String): String {
        val v = s.replace("\r", "")
        return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }

    fun csv(blocks: List<Block>): String {
        val sb = StringBuilder()
        val tables = blocks.filterIsInstance<Table>()
        if (tables.isNotEmpty()) {
            for ((i, t) in tables.withIndex()) {
                if (i > 0) sb.append("\r\n")
                sb.append(t.header.joinToString(",") { csvCell(it.plain().trim()) }).append("\r\n")
                for (r in t.rows) sb.append(r.joinToString(",") { csvCell(it.plain().trim()) }).append("\r\n")
            }
        } else {
            for (line in plainText(blocks).lines()) if (line.isNotBlank()) sb.append(csvCell(line)).append("\r\n")
        }
        return sb.toString()
    }

    fun plainText(blocks: List<Block>): String {
        val sb = StringBuilder()
        for (b in blocks) when (b) {
            is Heading -> sb.append(b.runs.plain().let { if (b.level <= 2) it.uppercase() else it }).append("\n\n")
            is Para -> sb.append(b.runs.plain()).append("\n\n")
            is ListItem -> sb.append("  ".repeat(b.level)).append(if (b.ordered) "${b.number}. " else "\u2022 ").append(b.runs.plain()).append('\n')
            is Quote -> sb.append("\"").append(b.runs.plain()).append("\"\n\n")
            is CodeBlock -> sb.append(b.text).append("\n\n")
            is Table -> {
                sb.append(b.header.joinToString("\t") { it.plain() }).append('\n')
                b.rows.forEach { r -> sb.append(r.joinToString("\t") { it.plain() }).append('\n') }
                sb.append('\n')
            }
            Rule -> sb.append("----------------------------------------\n\n")
            PageBreak -> sb.append("\n")
            is ImageBlock -> sb.append("[Image: ").append(b.caption.ifBlank { "picture ${b.index + 1}" }).append("]\n\n")
        }
        return sb.toString().replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
    }

    /** Safe, readable file name (without extension) from a title. */
    fun fileBase(title: String): String {
        val s = title.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), "-").trim('-', '.', '_')
        val cut = s.take(48).trim('-', '.', '_')
        return if (cut.length >= 2) cut else "document"
    }

    /** Joins PDF lines that were wrapped by the page layout back into paragraphs and recognises bullets. */
    fun reflowPdfText(text: String): String {
        val out = StringBuilder()
        val lines = text.replace("\r", "").split("\n")
        var buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.append(buf.toString().trim()).append("\n\n"); buf = StringBuilder() } }
        for (raw in lines) {
            var l = raw.trim()
            if (l.isEmpty()) { flush(); continue }
            val bullet = Regex("^[\u2022\u25CF\u25AA\u25E6\u2023\u2043\u00B7]\\s*").find(l)
            if (bullet != null) { flush(); out.append("- ").append(l.substring(bullet.value.length)).append('\n'); continue }
            if (Regex("^\\d{1,3}[.)]\\s+\\S").containsMatchIn(l)) { flush(); out.append(l).append('\n'); continue }
            if (buf.isNotEmpty()) {
                val prev = buf.toString()
                val wraps = prev.length >= 45 && !Regex("[.!?:;]\\s*$").containsMatchIn(prev) && l.first().isLowerCase()
                if (wraps) buf.append(' ').append(l) else { flush(); buf.append(l) }
            } else buf.append(l)
        }
        flush()
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
