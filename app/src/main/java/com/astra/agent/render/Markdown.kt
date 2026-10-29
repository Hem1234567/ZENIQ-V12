package com.astra.agent.render

sealed interface MdBlock
data class MdCode(val lang: String, val code: String, val closed: Boolean) : MdBlock
data class MdHeading(val level: Int, val text: String) : MdBlock
data class MdPara(val text: String) : MdBlock
data class MdBullet(val text: String, val depth: Int) : MdBlock
data class MdNumber(val num: String, val text: String, val depth: Int) : MdBlock
data class MdQuote(val text: String) : MdBlock
data class MdTable(val header: List<String>, val rows: List<List<String>>) : MdBlock
object MdRule : MdBlock

/** One styled piece of inline text. */
data class Run(val text: String, val bold: Boolean = false, val italic: Boolean = false,
               val code: Boolean = false, val link: String? = null, val strike: Boolean = false)

/** Tiny markdown reader tuned for chat answers. Safe on half-streamed text (unclosed ``` blocks are kept as code). */
object Markdown {

    private val fenceRe = Regex("^\\s{0,3}(`{3,}|~{3,})\\s*([^\\s`]*)[^`]*$")
    private val headingRe = Regex("^\\s{0,3}(#{1,6})\\s+(.*?)\\s*#*\\s*$")
    private val bulletRe = Regex("^(\\s*)[-*+•]\\s+(.*)$")
    private val numberRe = Regex("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$")
    private val ruleRe = Regex("^\\s{0,3}([-*_])(\\s*\\1){2,}\\s*$")
    private val tableSepRe = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(src: String): List<MdBlock> {
        val lines = src.replace("\r\n", "\n").split("\n")
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        fun flush() { if (para.isNotEmpty()) { out.add(MdPara(para.toString().trimEnd())); para.setLength(0) } }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val f = fenceRe.find(line)
            if (f != null) {
                flush()
                val fence = f.groupValues[1]
                val lang = f.groupValues[2]
                val body = ArrayList<String>()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    val t = lines[j].trim()
                    if (t.length >= fence.length && t.all { it == fence[0] }) { closed = true; break }
                    body.add(lines[j]); j++
                }
                out.add(MdCode(lang, body.joinToString("\n"), closed))
                i = if (closed) j + 1 else j
                continue
            }
            if (line.isBlank()) { flush(); i++; continue }
            val h = headingRe.find(line)
            if (h != null) { flush(); out.add(MdHeading(h.groupValues[1].length, h.groupValues[2])); i++; continue }
            if (ruleRe.matches(line)) { flush(); out.add(MdRule); i++; continue }
            // table: header row + separator row
            if (line.contains('|') && i + 1 < lines.size && tableSepRe.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                flush()
                val header = cells(line)
                val rows = ArrayList<List<String>>()
                var j = i + 2
                while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) { rows.add(cells(lines[j])); j++ }
                out.add(MdTable(header, rows)); i = j; continue
            }
            val b = bulletRe.find(line)
            if (b != null) { flush(); out.add(MdBullet(b.groupValues[2], b.groupValues[1].length / 2)); i++; continue }
            val n = numberRe.find(line)
            if (n != null) { flush(); out.add(MdNumber(n.groupValues[2], n.groupValues[3], n.groupValues[1].length / 2)); i++; continue }
            if (line.trimStart().startsWith(">")) {
                flush()
                val q = StringBuilder()
                var j = i
                while (j < lines.size && lines[j].trimStart().startsWith(">")) { q.append(lines[j].trimStart().removePrefix(">").trim()).append('\n'); j++ }
                out.add(MdQuote(q.toString().trim())); i = j; continue
            }
            if (para.isNotEmpty()) para.append('\n')
            para.append(line.trim())
            i++
        }
        flush()
        return out
    }

    private fun cells(line: String): List<String> =
        line.trim().trim('|').split('|').map { it.trim() }

    /** Inline: `code`, **bold**, __bold__, *italic*, _italic_, ~~strike~~, [text](url). */
    fun inline(s: String): List<Run> {
        val out = ArrayList<Run>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(Run(buf.toString())); buf.setLength(0) } }
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            if (c == '\\' && i + 1 < n && s[i + 1] in "\\`*_{}[]()#+-.!|~") { buf.append(s[i + 1]); i += 2; continue }
            if (c == '`') {
                val j = s.indexOf('`', i + 1)
                if (j > i + 1) { flush(); out.add(Run(s.substring(i + 1, j), code = true)); i = j + 1; continue }
            }
            if ((c == '*' || c == '_') && i + 1 < n && s[i + 1] == c) {
                val j = s.indexOf("" + c + c, i + 2)
                if (j > i + 2 && !s[i + 2].isWhitespace()) {
                    flush()
                    for (r in inline(s.substring(i + 2, j))) out.add(r.copy(bold = true))
                    i = j + 2; continue
                }
            }
            if (c == '~' && i + 1 < n && s[i + 1] == '~') {
                val j = s.indexOf("~~", i + 2)
                if (j > i + 2) { flush(); for (r in inline(s.substring(i + 2, j))) out.add(r.copy(strike = true)); i = j + 2; continue }
            }
            if ((c == '*' || c == '_') && i + 1 < n && !s[i + 1].isWhitespace() && s[i + 1] != c) {
                val boundaryOk = c == '*' || i == 0 || !s[i - 1].isLetterOrDigit()
                if (boundaryOk) {
                    var j = s.indexOf(c, i + 1)
                    while (j > 0 && j + 1 < n && c == '_' && s[j + 1].isLetterOrDigit()) j = s.indexOf(c, j + 1)
                    if (j > i + 1 && !s[j - 1].isWhitespace()) {
                        flush(); for (r in inline(s.substring(i + 1, j))) out.add(r.copy(italic = true)); i = j + 1; continue
                    }
                }
            }
            if (c == '[') {
                val close = s.indexOf(']', i + 1)
                if (close > i && close + 1 < n && s[close + 1] == '(') {
                    val end = s.indexOf(')', close + 2)
                    if (end > close) {
                        flush()
                        out.add(Run(s.substring(i + 1, close), link = s.substring(close + 2, end).trim()))
                        i = end + 1; continue
                    }
                }
            }
            buf.append(c); i++
        }
        flush()
        return out
    }
}
