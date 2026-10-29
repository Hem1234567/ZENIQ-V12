package com.astra.agent.docs

/**
 * ZENIQ's neutral document model. The AI writes Markdown, [MdParser] turns it into these blocks,
 * and every writer (PDF, Word, Excel, PowerPoint ...) renders the same blocks. Uploaded files travel
 * the other way: OfficeReader turns them into Markdown so the AI can read and edit them.
 *
 * This file has no Android dependency on purpose, so it can be unit tested on a plain JVM.
 */
data class Run(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strike: Boolean = false,
    val link: String? = null
)

sealed interface Block
data class Heading(val level: Int, val runs: List<Run>) : Block
data class Para(val runs: List<Run>) : Block
data class ListItem(val level: Int, val ordered: Boolean, val number: Int, val runs: List<Run>) : Block
/** [aligns] holds 'l', 'c', 'r' or ' ' (not set) per column. Every row has exactly header.size cells. */
data class Table(val header: List<List<Run>>, val rows: List<List<List<Run>>>, val aligns: List<Char>) : Block
data class CodeBlock(val lang: String, val text: String) : Block
data class Quote(val runs: List<Run>) : Block
/** A picture the user attached: [index] counts from 0 into the list of attached images. */
data class ImageBlock(val index: Int, val caption: String) : Block
object Rule : Block
object PageBreak : Block

fun List<Run>.plain(): String = joinToString("") { it.text }

object MdParser {

    private val fenceRe = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^`]*)$")
    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val listRe = Regex("^(\\s*)([-*+\\u2022\\u25AA\\u25CF]|\\d{1,3}[.)])\\s+(.*)$")
    private val quoteRe = Regex("^\\s{0,3}>\\s?(.*)$")
    private val sepRe = Regex("^\\s*\\|?\\s*:?-{1,}:?\\s*(\\|\\s*:?-{1,}:?\\s*)*\\|?\\s*$")
    private val pageBreakRe = Regex("^\\s*(-{2,}\\s*page\\s*-?\\s*break\\s*-{2,}|<!--\\s*page\\s*-?\\s*break\\s*-->|\\\\pagebreak|\\\\newpage|<div style=\"page-break-after:\\s*always;?\">\\s*</div>)\\s*$", RegexOption.IGNORE_CASE)

    private val imgRe = Regex("^\\s*!\\[([^\\]]*)\\]\\(\\s*(?:image|img|picture|photo)?\\s*[:#_ -]?\\s*(\\d{1,2})\\s*\\)\\s*$", RegexOption.IGNORE_CASE)

    fun parse(markdown: String): List<Block> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').replace("\u000C", "\n---pagebreak---\n").split("\n")
        val out = ArrayList<Block>()
        val counters = IntArray(12)
        var i = 0
        val para = ArrayList<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            val text = para.joinToString("\n") { it.trim() }
            para.clear()
            if (text.isNotBlank()) out.add(Para(inline(text)))
        }
        fun resetCounters() { java.util.Arrays.fill(counters, 0) }

        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { flushPara(); i++; continue }

            // an attached picture:  ![caption](image:1)
            val im = imgRe.find(line)
            if (im != null) { flushPara(); out.add(ImageBlock(im.groupValues[2].toInt() - 1, im.groupValues[1].trim())); i++; continue }

            // fenced code
            val f = fenceRe.find(line)
            if (f != null) {
                flushPara(); resetCounters()
                val fence = f.groupValues[1]
                val lang = f.groupValues[2].trim().substringBefore(' ').lowercase()
                val body = ArrayList<String>()
                var j = i + 1
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t.startsWith(fence.take(3)) && t.all { it == fence[0] }) break
                    body.add(lines[j]); j++
                }
                out.add(CodeBlock(lang, body.joinToString("\n").trimEnd('\n')))
                i = j + 1
                continue
            }
            if (pageBreakRe.matches(line)) { flushPara(); resetCounters(); out.add(PageBreak); i++; continue }
            if (ruleRe.matches(line)) { flushPara(); resetCounters(); out.add(Rule); i++; continue }

            val h = headingRe.find(line)
            if (h != null) {
                flushPara(); resetCounters()
                val txt = h.groupValues[2].trim()
                if (txt.isNotEmpty()) out.add(Heading(h.groupValues[1].length, inline(txt)))
                i++; continue
            }

            // table: a line with a pipe followed by a separator line
            if (line.contains('|') && i + 1 < lines.size && lines[i + 1].contains('-') &&
                (lines[i + 1].contains('|') || lines[i + 1].trim().startsWith("-")) && sepRe.matches(lines[i + 1])) {
                val headerCells = splitRow(line)
                if (headerCells.size >= 1 && lines[i + 1].contains('|')) {
                    flushPara(); resetCounters()
                    val aligns = splitRow(lines[i + 1]).map { c ->
                        val t = c.trim()
                        when {
                            t.startsWith(":") && t.endsWith(":") && t.length > 1 -> 'c'
                            t.endsWith(":") -> 'r'
                            t.startsWith(":") -> 'l'
                            else -> ' '
                        }
                    }
                    val rowsRaw = ArrayList<List<String>>()
                    var j = i + 2
                    while (j < lines.size && lines[j].isNotBlank() && lines[j].contains('|')) {
                        rowsRaw.add(splitRow(lines[j])); j++
                    }
                    val width = maxOf(headerCells.size, rowsRaw.maxOfOrNull { it.size } ?: 0)
                    fun cellRuns(raw: String): List<Run> {
                        val t = cellText(raw)
                        // spreadsheet formulas such as =A2*B2*3 must stay exactly as written (no italics from the asterisks)
                        return if (t.startsWith("=") && t.length > 1) listOf(Run(t)) else inline(t)
                    }
                    fun norm(r: List<String>) = List(width) { k -> cellRuns(r.getOrElse(k) { "" }) }
                    out.add(Table(norm(headerCells), rowsRaw.map { norm(it) }, List(width) { aligns.getOrElse(it) { ' ' } }))
                    i = j
                    continue
                }
            }

            val l = listRe.find(line)
            if (l != null && !(l.groupValues[2] == "*" && l.groupValues[3].startsWith("*"))) {
                flushPara()
                val indent = l.groupValues[1].replace("\t", "    ").length
                val level = (indent / 2).coerceIn(0, 5)
                val marker = l.groupValues[2]
                val ordered = marker.first().isDigit()
                var body = l.groupValues[3]
                body = body.replace(Regex("^\\[( |x|X)\\]\\s+")) { m -> if (m.groupValues[1] == " ") "\u2610 " else "\u2611 " }
                if (ordered) {
                    counters[level]++
                    for (k in level + 1 until counters.size) counters[k] = 0
                } else {
                    for (k in level until counters.size) counters[k] = 0
                }
                out.add(ListItem(level, ordered, if (ordered) counters[level] else 0, inline(body.trim())))
                i++; continue
            }

            val q = quoteRe.find(line)
            if (q != null) {
                flushPara(); resetCounters()
                val sb = ArrayList<String>()
                var j = i
                while (j < lines.size) { val m = quoteRe.find(lines[j]) ?: break; sb.add(m.groupValues[1]); j++ }
                out.add(Quote(inline(sb.joinToString("\n").trim())))
                i = j; continue
            }

            resetCounters()
            para.add(line)
            i++
        }
        flushPara()
        return out
    }

    /** Title of the document: the first heading, or null. */
    fun title(blocks: List<Block>): String? =
        blocks.filterIsInstance<Heading>().firstOrNull()?.runs?.plain()?.trim()?.takeIf { it.isNotEmpty() }

    private fun cellText(s: String) = s.trim().replace(Regex("(?i)<br\\s*/?>"), "\n")

    /** Splits a table row on pipes that are not escaped. */
    fun splitRow(line: String): List<String> {
        var s = line.trim()
        if (s.startsWith("|")) s = s.substring(1)
        if (s.endsWith("|") && !s.endsWith("\\|")) s = s.dropLast(1)
        val cells = ArrayList<String>()
        val cur = StringBuilder()
        var k = 0
        while (k < s.length) {
            val ch = s[k]
            if (ch == '\\' && k + 1 < s.length && s[k + 1] == '|') { cur.append('|'); k += 2; continue }
            if (ch == '|') { cells.add(cur.toString()); cur.setLength(0); k++; continue }
            cur.append(ch); k++
        }
        cells.add(cur.toString())
        return cells
    }

    // ── Inline markdown: **bold**, *italic*, _italic_, `code`, ~~strike~~, [text](url), <br> ──────────

    fun inline(src: String): List<Run> {
        val text = src.replace(Regex("(?i)<br\\s*/?>"), "\n")
        val runs = ArrayList<Run>()
        val buf = StringBuilder()
        var bold = false; var italic = false; var strike = false
        fun flush() {
            if (buf.isNotEmpty()) { runs.add(Run(buf.toString(), bold, italic, false, strike)); buf.setLength(0) }
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in "\\`*_{}[]()#+-.!|~<>" -> { buf.append(text[i + 1]); i += 2 }
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { flush(); runs.add(Run(text.substring(i + 1, end), bold, italic, true, strike)); i = end + 1 }
                    else { buf.append(c); i++ }
                }
                (text.startsWith("**", i) || text.startsWith("__", i)) && closes(text, i, text.substring(i, i + 2), bold) -> {
                    flush(); bold = !bold; i += 2
                }
                text.startsWith("~~", i) && closes(text, i, "~~", strike) -> { flush(); strike = !strike; i += 2 }
                c == '*' && closes(text, i, "*", italic) && (italic || (i + 1 < text.length && !text[i + 1].isWhitespace())) -> {
                    flush(); italic = !italic; i++
                }
                c == '_' && closes(text, i, "_", italic) && wordEdge(text, i, italic) -> { flush(); italic = !italic; i++ }
                c == '[' -> {
                    val close = text.indexOf("](", i + 1)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close > 0 && end > close && text.substring(i + 1, close).none { it == '\n' }) {
                        flush()
                        val label = text.substring(i + 1, close)
                        val url = text.substring(close + 2, end).trim()
                        runs.add(Run(label, bold, italic, false, strike, url.ifBlank { null }))
                        i = end + 1
                    } else { buf.append(c); i++ }
                }
                else -> { buf.append(c); i++ }
            }
        }
        flush()
        // merge neighbours with the same format
        val merged = ArrayList<Run>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && last.bold == r.bold && last.italic == r.italic && last.code == r.code &&
                last.strike == r.strike && last.link == r.link) merged[merged.size - 1] = last.copy(text = last.text + r.text)
            else merged.add(r)
        }
        return merged
    }

    /** A marker only toggles formatting when it is already open, or when a matching closer exists later. */
    private fun closes(text: String, at: Int, marker: String, open: Boolean): Boolean {
        if (open) return true
        val next = text.indexOf(marker, at + marker.length)
        if (next < 0) return false
        // "** bold **" with spaces right inside the markers is not bold
        return next > at + marker.length && !text[at + marker.length].isWhitespace()
    }

    private fun wordEdge(text: String, i: Int, open: Boolean): Boolean {
        val prev = if (i > 0) text[i - 1] else ' '
        val next = if (i + 1 < text.length) text[i + 1] else ' '
        return if (open) !next.isLetterOrDigit() else !prev.isLetterOrDigit()
    }
}
