package com.astra.agent.docs

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory

/**
 * Reads .docx / .xlsx / .pptx files and turns them into Markdown (headings, lists, tables, bold/italic),
 * which is the format the AI reads and edits. Pure JVM + javax.xml, so it works on Android and in unit tests.
 */
object OfficeReader {

    class ReadError(msg: String) : Exception(msg)

    private const val MAX_PART = 40_000_000

    private fun unzip(bytes: ByteArray, want: (String) -> Boolean): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory || !want(e.name)) continue
                    val data = zin.readBytes()
                    if (data.size > MAX_PART) throw ReadError("A part of this file is too large to read.")
                    out[e.name] = data
                }
            }
        } catch (e: ReadError) { throw e } catch (e: Exception) {
            throw ReadError("This doesn't look like a valid Office file (${e.message ?: "bad zip"}).")
        }
        return out
    }

    private fun dom(bytes: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.isExpandEntityReferences = false
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false) } catch (_: Throwable) {}
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) } catch (_: Throwable) {}
        return f.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(bytes))).documentElement
    }

    // ── DOM helpers (prefix-agnostic: we compare the part after the colon) ─────

    private fun local(n: Node): String = (n.nodeName ?: "").substringAfter(':')
    private fun kids(e: Element, name: String? = null): List<Element> {
        val out = ArrayList<Element>()
        var c = e.firstChild
        while (c != null) {
            if (c is Element && (name == null || local(c) == name)) out.add(c)
            c = c.nextSibling
        }
        return out
    }
    private fun kid(e: Element, name: String): Element? = kids(e, name).firstOrNull()
    private fun attr(e: Element?, name: String): String? {
        if (e == null) return null
        val a = e.attributes ?: return null
        for (i in 0 until a.length) { val n = a.item(i); if (local(n) == name) return n.nodeValue }
        return null
    }
    private fun descendants(e: Element, name: String, out: MutableList<Element> = ArrayList()): MutableList<Element> {
        var c = e.firstChild
        while (c != null) {
            if (c is Element) { if (local(c) == name) out.add(c); descendants(c, name, out) }
            c = c.nextSibling
        }
        return out
    }

    // ── Markdown escaping ────────────────────────────────────────────────────

    private fun mdEscape(s: String): String {
        val sb = StringBuilder()
        for ((i, ch) in s.withIndex()) {
            when (ch) {
                '*', '`', '[', '~' -> sb.append('\\').append(ch)
                '_' -> {
                    val prev = if (i > 0) s[i - 1] else ' '
                    val next = if (i + 1 < s.length) s[i + 1] else ' '
                    if (!prev.isLetterOrDigit() || !next.isLetterOrDigit()) sb.append("\\_") else sb.append('_')
                }
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun unbold(t: String): String = t.replace(Regex("^\\*\\*(.+)\\*\\*$"), "$1")

    private fun escapeLineStart(s: String): String {
        val t = s
        return when {
            Regex("^\\s{0,3}#{1,6}\\s").containsMatchIn(t) -> "\\" + t.trimStart()
            Regex("^\\s{0,3}[-+>]\\s").containsMatchIn(t) -> "\\" + t.trimStart()
            Regex("^\\s{0,3}(-{3,}|\\*{3,}|_{3,})\\s*$").containsMatchIn(t) -> "\\" + t.trimStart()
            Regex("^\\s{0,3}\\d{1,3}[.)]\\s").containsMatchIn(t) -> t.trimStart().replaceFirst(Regex("([.)])"), "\\\\$1")
            t.startsWith("|") -> "\\" + t
            else -> t
        }
    }

    private class Piece(val text: String, val bold: Boolean, val italic: Boolean, val raw: Boolean = false)

    private fun piecesToMd(pieces: List<Piece>): String {
        // merge neighbours of the same format
        val merged = ArrayList<Piece>()
        for (p in pieces) {
            if (p.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && !last.raw && !p.raw && last.bold == p.bold && last.italic == p.italic) merged[merged.size - 1] = Piece(last.text + p.text, p.bold, p.italic)
            else merged.add(p)
        }
        val sb = StringBuilder()
        for (p in merged) {
            if (p.raw) { sb.append(p.text); continue }
            val core = p.text.trim(' ', '\t')
            if (core.isEmpty()) { sb.append(p.text); continue }
            val lead = p.text.takeWhile { it == ' ' || it == '\t' }
            val trail = p.text.takeLastWhile { it == ' ' || it == '\t' }
            val marker = when { p.bold && p.italic -> "***"; p.bold -> "**"; p.italic -> "*"; else -> "" }
            // markers cannot wrap text containing line breaks, wrap each line
            val body = core.split("\n").joinToString("\n") { if (marker.isEmpty() || it.isBlank()) mdEscape(it) else marker + mdEscape(it.trim()) + marker }
            sb.append(lead).append(body).append(trail)
        }
        return sb.toString()
    }

    // ═══ DOCX ═══════════════════════════════════════════════════════════════

    fun docxToMarkdown(bytes: ByteArray): String {
        val parts = unzip(bytes) { it == "word/document.xml" || it == "word/numbering.xml" || it == "word/styles.xml" || it == "word/_rels/document.xml.rels" }
        val linkTargets = HashMap<String, String>()
        parts["word/_rels/document.xml.rels"]?.let { rb ->
            for (r in kids(dom(rb), "Relationship")) {
                if ((attr(r, "Type") ?: "").endsWith("/hyperlink")) linkTargets[attr(r, "Id") ?: continue] = attr(r, "Target") ?: continue
            }
        }
        val docBytes = parts["word/document.xml"] ?: throw ReadError("Not a Word (.docx) file: word/document.xml is missing.")
        val body = kid(dom(docBytes), "body") ?: throw ReadError("The Word file has no body.")

        val styleNames = HashMap<String, String>()
        parts["word/styles.xml"]?.let { sx ->
            for (st in kids(dom(sx), "style")) {
                val id = attr(st, "styleId") ?: continue
                styleNames[id] = (attr(kid(st, "name"), "val") ?: id).lowercase()
            }
        }
        // numbering: numId -> abstractNumId, abstractNum -> level -> format
        val numToAbs = HashMap<String, String>()
        val absFmt = HashMap<String, MutableMap<Int, String>>()
        parts["word/numbering.xml"]?.let { nx ->
            val root = dom(nx)
            for (an in kids(root, "abstractNum")) {
                val id = attr(an, "abstractNumId") ?: continue
                val m = HashMap<Int, String>()
                for (lvl in kids(an, "lvl")) m[(attr(lvl, "ilvl") ?: "0").toIntOrNull() ?: 0] = attr(kid(lvl, "numFmt"), "val") ?: "decimal"
                absFmt[id] = m
            }
            for (n in kids(root, "num")) {
                val id = attr(n, "numId") ?: continue
                numToAbs[id] = attr(kid(n, "abstractNumId"), "val") ?: continue
            }
        }

        val out = StringBuilder()
        fun blank() { if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append(if (out.endsWith("\n")) "\n" else "\n\n") }
        var lastWasList = false
        val counters = IntArray(10)
        var inCode = false

        fun paragraphPieces(p: Element, pieces: MutableList<Piece>, flags: BooleanArray) {
            for (ch in kids(p)) {
                when (local(ch)) {
                    "r" -> runPieces(ch, pieces, flags)
                    "hyperlink" -> {
                        val url = attr(ch, "id")?.let { linkTargets[it] }
                        if (url == null) paragraphPieces(ch, pieces, flags)
                        else {
                            val inner = ArrayList<Piece>()
                            paragraphPieces(ch, inner, flags)
                            val label = piecesToMd(inner).replace("\n", " ")
                            if (label.isNotBlank()) pieces.add(Piece("[" + label + "](" + url + ")", false, false, raw = true))
                        }
                    }
                    "ins", "smartTag", "fldSimple" -> paragraphPieces(ch, pieces, flags)
                    "sdt" -> kid(ch, "sdtContent")?.let { paragraphPieces(it, pieces, flags) }
                }
            }
        }

        fun paragraphToMd(p: Element, inTable: Boolean): Pair<String, Boolean>? {
            val pPr = kid(p, "pPr")
            val styleId = attr(kid(pPr ?: p, "pStyle"), "val")
            val styleName = styleId?.let { styleNames[it] } ?: styleId?.lowercase() ?: ""
            val flags = BooleanArray(1)   // [0] = contains a page break
            val pieces = ArrayList<Piece>()
            paragraphPieces(p, pieces, flags)
            var text = piecesToMd(pieces)
            if (inTable) text = text.replace("\n", "<br>").replace("|", "\\|")
            val numPr = pPr?.let { kid(it, "numPr") }
            val numId = attr(numPr?.let { kid(it, "numId") }, "val")
            if (text.isBlank()) return if (flags[0]) "---pagebreak---" to false else null
            if (!inTable && (numPr == null || numId == null || numId == "0")) java.util.Arrays.fill(counters, 0)
            if (!inTable && numPr != null && numId != null && numId != "0") {
                val ilvl = (attr(kid(numPr, "ilvl"), "val") ?: "0").toIntOrNull() ?: 0
                val fmt = absFmt[numToAbs[numId] ?: ""]?.get(ilvl) ?: "bullet"
                val lv = ilvl.coerceIn(0, 5)
                val marker: String
                if (fmt == "bullet" || fmt == "none") { marker = "-"; for (k in lv until counters.size) counters[k] = 0 }
                else { counters[lv]++; for (k in lv + 1 until counters.size) counters[k] = 0; marker = counters[lv].toString() + "." }
                return ("  ".repeat(ilvl.coerceIn(0, 5)) + marker + " " + text.replace("\n", " ")) to true
            }
            if (!inTable) {
                val level = when {
                    styleName == "title" -> 1
                    styleName.startsWith("heading ") -> styleName.removePrefix("heading ").trim().toIntOrNull()
                    styleId != null && styleId.lowercase().startsWith("heading") -> styleId.filter { it.isDigit() }.toIntOrNull()
                    else -> null
                }
                if (level != null && level in 1..6) {
                    val h = text.replace("\n", " ").replace(Regex("^\\*\\*(.*)\\*\\*$"), "$1")
                    return ("#".repeat(level) + " " + h) to false
                }
                if (styleName == "quote" || styleName == "intense quote") return ("> " + text.replace("\n", " ")) to false
                if (flags[0]) return (escapeLineStart(text) + "\n\n---pagebreak---") to false
                return escapeLineStart(text) to false
            }
            return text to false
        }

        fun tableToMd(tbl: Element) {
            val rows = ArrayList<List<String>>()
            for (tr in kids(tbl, "tr")) {
                val cells = ArrayList<String>()
                for (tc in kids(tr, "tc")) {
                    val texts = ArrayList<String>()
                    for (p in kids(tc, "p")) paragraphToMd(p, true)?.first?.takeIf { it.isNotBlank() }?.let { texts.add(if (rows.isEmpty()) unbold(it) else it) }
                    for (nested in kids(tc, "tbl")) texts.add(descendants(nested, "t").joinToString(" ") { it.textContent })
                    cells.add(texts.joinToString("<br>"))
                    val span = (attr(kid(kid(tc, "tcPr") ?: tc, "gridSpan"), "val") ?: "1").toIntOrNull() ?: 1
                    repeat((span - 1).coerceIn(0, 30)) { cells.add("") }
                }
                if (cells.isNotEmpty()) rows.add(cells)
            }
            if (rows.isEmpty()) return
            val width = rows.maxOf { it.size }
            fun row(r: List<String>) = "| " + List(width) { r.getOrElse(it) { "" } }.joinToString(" | ") + " |"
            blank()
            out.append(row(rows[0])).append('\n')
            out.append("|").append(" --- |".repeat(width)).append('\n')
            rows.drop(1).forEach { out.append(row(it)).append('\n') }
            out.append('\n')
        }

        for (el in kids(body)) {
            when (local(el)) {
                "p" -> {
                    val sid = attr(kid(kid(el, "pPr") ?: el, "pStyle"), "val")?.lowercase() ?: ""
                    if (sid == "code" || sid == "sourcecode" || sid == "htmlcode") {
                        val line = descendants(el, "t").joinToString("") { it.textContent }
                        if (!inCode) { blank(); out.append("```\n"); inCode = true }
                        out.append(line).append('\n'); lastWasList = false
                        continue
                    }
                    if (inCode) { out.append("```\n\n"); inCode = false }
                    val r = paragraphToMd(el, false) ?: continue
                    if (r.second) { if (!lastWasList) blank(); out.append(r.first).append('\n') }
                    else { blank(); out.append(r.first).append("\n\n") }
                    lastWasList = r.second
                }
                "tbl" -> { if (inCode) { out.append("```\n\n"); inCode = false }; tableToMd(el); lastWasList = false }
                "sdt" -> {
                    kid(el, "sdtContent")?.let { c ->
                        for (p in kids(c, "p")) { val r = paragraphToMd(p, false) ?: continue; blank(); out.append(r.first).append("\n\n") }
                    }
                    lastWasList = false
                }
            }
        }
        if (inCode) out.append("```\n")
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun runPieces(r: Element, pieces: MutableList<Piece>, flags: BooleanArray) {
        val rPr = kid(r, "rPr")
        fun on(name: String): Boolean {
            val e = rPr?.let { kid(it, name) } ?: return false
            val v = attr(e, "val")
            return v == null || (v != "0" && v != "false" && v != "off")
        }
        val bold = on("b"); val italic = on("i")
        for (c in kids(r)) {
            when (local(c)) {
                "t" -> pieces.add(Piece(c.textContent ?: "", bold, italic))
                "tab" -> pieces.add(Piece(" ", false, false))
                "noBreakHyphen" -> pieces.add(Piece("-", bold, italic))
                "br" -> if (attr(c, "type") == "page") flags[0] = true else pieces.add(Piece("\n", false, false))
                "cr" -> pieces.add(Piece("\n", false, false))
            }
        }
    }

    // ═══ XLSX ═══════════════════════════════════════════════════════════════

    private class SheetRef(val name: String, val path: String)

    fun xlsxToMarkdown(bytes: ByteArray, maxRows: Int = 2000, maxCols: Int = 60): String {
        val parts = unzip(bytes) { it.endsWith(".xml") || it.endsWith(".rels") }
        val wbBytes = parts["xl/workbook.xml"] ?: throw ReadError("Not an Excel (.xlsx) file: xl/workbook.xml is missing.")
        val wb = dom(wbBytes)
        val rels = HashMap<String, String>()
        parts["xl/_rels/workbook.xml.rels"]?.let { rb ->
            for (r in kids(dom(rb), "Relationship")) {
                val id = attr(r, "Id") ?: continue
                var t = attr(r, "Target") ?: continue
                t = if (t.startsWith("/")) t.removePrefix("/") else "xl/" + t
                rels[id] = t.replace("xl/../", "")
            }
        }
        val sheets = ArrayList<SheetRef>()
        kid(wb, "sheets")?.let { ss ->
            for (s in kids(ss, "sheet")) {
                if (attr(s, "state") == "hidden" || attr(s, "state") == "veryHidden") continue
                val rid = attr(s, "id") ?: continue
                val path = rels[rid] ?: continue
                sheets.add(SheetRef(attr(s, "name") ?: "Sheet${sheets.size + 1}", path))
            }
        }
        val date1904 = kid(wb, "workbookPr")?.let { attr(it, "date1904") == "1" } ?: false

        val shared = ArrayList<String>()
        parts["xl/sharedStrings.xml"]?.let { sx ->
            for (si in kids(dom(sx), "si")) {
                val sb = StringBuilder()
                fun collect(e: Element) {
                    for (c in kids(e)) when (local(c)) {
                        "t" -> sb.append(c.textContent)
                        "r" -> kid(c, "t")?.let { sb.append(it.textContent) }
                    }
                }
                collect(si)
                shared.add(sb.toString())
            }
        }
        val dateXf = ArrayList<Boolean>()
        val pctXf = ArrayList<Boolean>()
        parts["xl/styles.xml"]?.let { st ->
            val root = dom(st)
            val custom = HashMap<Int, String>()
            kid(root, "numFmts")?.let { nf -> for (n in kids(nf, "numFmt")) custom[(attr(n, "numFmtId") ?: "").toIntOrNull() ?: -1] = attr(n, "formatCode") ?: "" }
            kid(root, "cellXfs")?.let { xfs ->
                for (xf in kids(xfs, "xf")) {
                    val id = (attr(xf, "numFmtId") ?: "0").toIntOrNull() ?: 0
                    dateXf.add(isDateFormat(id, custom[id]))
                    pctXf.add(id == 9 || id == 10 || (custom[id]?.contains('%') == true))
                }
            }
        }

        val out = StringBuilder()
        for ((si, sh) in sheets.withIndex()) {
            val data = parts[sh.path] ?: continue
            val grid = readSheet(data, shared, dateXf, pctXf, date1904, maxRows, maxCols)
            if (si > 0 || sheets.size > 1) out.append("## ").append(sh.name.replace("\n", " ")).append("\n\n")
            if (grid.rows.isEmpty()) { out.append("(empty sheet)\n\n"); continue }
            val width = grid.rows.maxOf { it.size }.coerceAtLeast(1)
            fun cell(s: String) = s.replace("\\", "\\\\").replace("|", "\\|").replace("\n", "<br>")
            fun row(r: List<String>) = "| " + List(width) { cell(r.getOrElse(it) { "" }) }.joinToString(" | ") + " |"
            out.append(row(grid.rows[0])).append('\n')
            out.append("|").append(" --- |".repeat(width)).append('\n')
            grid.rows.drop(1).forEach { out.append(row(it)).append('\n') }
            if (grid.truncated) out.append("\n(Sheet truncated: only the first ${maxRows} rows / ${maxCols} columns are shown.)\n")
            out.append('\n')
        }
        if (out.isBlank()) throw ReadError("The workbook has no readable sheets.")
        return out.toString().trim()
    }

    private fun isDateFormat(id: Int, code: String?): Boolean {
        if (id in 14..22 || id in 27..36 || id in 45..47 || id in 50..58) return true
        if (code == null) return false
        val c = code.lowercase().replace(Regex("\"[^\"]*\""), "").replace(Regex("\\[[^]]*]"), "")
        return Regex("[ymdhs]").containsMatchIn(c) && !c.contains("general")
    }

    private class Grid(val rows: List<List<String>>, val truncated: Boolean)
    private class StopParsing : RuntimeException()

    private fun readSheet(data: ByteArray, shared: List<String>, dateXf: List<Boolean>, pctXf: List<Boolean>, date1904: Boolean, maxRows: Int, maxCols: Int): Grid {
        val rows = java.util.TreeMap<Int, java.util.TreeMap<Int, String>>()
        var truncated = false
        val handler = object : DefaultHandler() {
            var ref = ""; var type = ""; var style = -1
            var inV = false; var inF = false; var inT = false; var inIs = false
            val v = StringBuilder(); val f = StringBuilder(); val t = StringBuilder()
            var rowIdx = 0; var nextCol = 1

            private fun name(q: String) = q.substringAfter(':')
            private fun colOf(r: String): Int {
                var n = 0
                for (ch in r) { if (ch.isLetter()) n = n * 26 + (ch.uppercaseChar() - 'A' + 1) else break }
                return n
            }
            private fun rowOf(r: String): Int = r.filter { it.isDigit() }.toIntOrNull() ?: 0

            override fun startElement(uri: String?, ln: String?, q: String, a: Attributes) {
                when (name(q)) {
                    "row" -> { rowIdx = a.getValue("r")?.toIntOrNull() ?: (rowIdx + 1); nextCol = 1
                        if (rowIdx > maxRows) { truncated = true; throw StopParsing() } }
                    "c" -> {
                        ref = a.getValue("r") ?: ""; type = a.getValue("t") ?: ""; style = a.getValue("s")?.toIntOrNull() ?: -1
                        v.setLength(0); f.setLength(0); t.setLength(0)
                        if (ref.isNotEmpty()) { nextCol = colOf(ref); if (rowOf(ref) > 0) rowIdx = rowOf(ref) }
                    }
                    "v" -> inV = true
                    "f" -> inF = true
                    "is" -> inIs = true
                    "t" -> if (inIs) inT = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inV) v.append(ch, start, length) else if (inF) f.append(ch, start, length) else if (inT) t.append(ch, start, length)
            }
            override fun endElement(uri: String?, ln: String?, q: String) {
                when (name(q)) {
                    "v" -> inV = false
                    "f" -> inF = false
                    "t" -> inT = false
                    "is" -> inIs = false
                    "c" -> {
                        val col = nextCol
                        if (col > maxCols) { truncated = true; return }
                        val value = cellValue()
                        if (value.isNotEmpty()) rows.getOrPut(rowIdx) { java.util.TreeMap() }[col] = value
                        nextCol = col + 1
                    }
                }
            }
            private fun cellValue(): String {
                val formula = f.toString().trim()
                if (formula.isNotEmpty()) return "=" + formula
                val raw = v.toString().trim()
                return when (type) {
                    "s" -> raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    "inlineStr" -> t.toString()
                    "str" -> raw
                    "b" -> if (raw == "1") "TRUE" else "FALSE"
                    "e" -> raw
                    else -> {
                        if (raw.isEmpty()) return ""
                        val d = raw.toDoubleOrNull() ?: return raw
                        if (style >= 0 && dateXf.getOrNull(style) == true) serialToDate(d, date1904) ?: fmtNum(d)
                        else if (style >= 0 && pctXf.getOrNull(style) == true) fmtNum(d * 100.0) + "%"
                        else fmtNum(d)
                    }
                }
            }
        }
        try {
            val spf = SAXParserFactory.newInstance()
            try { spf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Throwable) {}
            spf.newSAXParser().parse(ByteArrayInputStream(data), handler)
        } catch (_: StopParsing) { }
        catch (e: Exception) { throw ReadError("Couldn't read a worksheet: ${e.message}") }

        if (rows.isEmpty()) return Grid(emptyList(), truncated)
        val maxCol = rows.values.maxOf { it.lastKey() }
        val out = ArrayList<List<String>>()
        var prev = rows.firstKey() - 1
        for ((r, cells) in rows) {
            // keep one blank row between blocks of data so structure is not lost
            if (r - prev > 1 && out.isNotEmpty()) out.add(List(maxCol) { "" })
            out.add(List(maxCol) { c -> cells[c + 1] ?: "" })
            prev = r
        }
        return Grid(out, truncated)
    }

    private fun fmtNum(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }

    private fun serialToDate(serial: Double, date1904: Boolean): String? {
        if (serial < 1 || serial > 2_958_465) return null
        val epoch = if (date1904) java.time.LocalDate.of(1904, 1, 1) else java.time.LocalDate.of(1899, 12, 30)
        val days = Math.floor(serial).toLong()
        val date = epoch.plusDays(days)
        val frac = serial - days
        return if (frac > 0.0001) {
            val secs = Math.round(frac * 86400.0).toInt().coerceAtMost(86399)
            "%s %02d:%02d".format(date.toString(), secs / 3600, (secs % 3600) / 60)
        } else date.toString()
    }

    // ═══ PPTX ═══════════════════════════════════════════════════════════════

    fun pptxToMarkdown(bytes: ByteArray): String {
        val parts = unzip(bytes) { it.endsWith(".xml") || it.endsWith(".rels") }
        val pres = parts["ppt/presentation.xml"] ?: throw ReadError("Not a PowerPoint (.pptx) file: ppt/presentation.xml is missing.")
        val rels = HashMap<String, String>()
        parts["ppt/_rels/presentation.xml.rels"]?.let { rb ->
            for (r in kids(dom(rb), "Relationship")) {
                val id = attr(r, "Id") ?: continue
                val t = attr(r, "Target") ?: continue
                rels[id] = if (t.startsWith("/")) t.removePrefix("/") else "ppt/" + t
            }
        }
        val slidePaths = ArrayList<String>()
        kid(dom(pres), "sldIdLst")?.let { l -> for (s in kids(l, "sldId")) rels[attr(s, "id") ?: ""]?.let { slidePaths.add(it) } }
        if (slidePaths.isEmpty()) parts.keys.filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { it.filter { c -> c.isDigit() }.toInt() }.forEach { slidePaths.add(it) }

        val out = StringBuilder()
        for ((i, sp) in slidePaths.withIndex()) {
            val data = parts[sp] ?: continue
            val root = dom(data)
            val tree = kid(kid(root, "cSld") ?: root, "spTree") ?: continue
            var title = ""
            val body = StringBuilder()

            fun paragraphText(p: Element): String {
                val pieces = ArrayList<Piece>()
                for (c in kids(p)) when (local(c)) {
                    "r" -> {
                        val rPr = kid(c, "rPr")
                        val t = kid(c, "t")?.textContent ?: ""
                        pieces.add(Piece(t, attr(rPr, "b") == "1", attr(rPr, "i") == "1"))
                    }
                    "br" -> pieces.add(Piece("\n", false, false))
                    "fld" -> kid(c, "t")?.let { pieces.add(Piece(it.textContent, false, false)) }
                }
                return piecesToMd(pieces)
            }

            fun walk(parent: Element) {
                for (sh in kids(parent)) {
                    when (local(sh)) {
                        "sp" -> {
                            val ph = descendants(sh, "ph").firstOrNull()
                            val phType = attr(ph, "type")
                            val isTitle = phType == "title" || phType == "ctrTitle"
                            val tx = kid(sh, "txBody") ?: continue
                            val paras = kids(tx, "p")
                            if (isTitle) {
                                val t = paras.joinToString(" ") { unbold(paragraphText(it).replace("\n", " ")) }.trim()
                                if (t.isNotEmpty() && title.isEmpty()) title = t
                                continue
                            }
                            val isBodyPh = ph != null && phType != "subTitle" && phType != "dt" && phType != "ftr" && phType != "sldNum"
                            if (phType == "dt" || phType == "ftr" || phType == "sldNum") continue
                            for (p in paras) {
                                val t = paragraphText(p).trim()
                                if (t.isEmpty()) continue
                                val lvl = (attr(kid(p, "pPr"), "lvl") ?: "0").toIntOrNull() ?: 0
                                val hasBullet = kid(p, "pPr")?.let { kid(it, "buChar") != null || kid(it, "buAutoNum") != null } == true
                                if (isBodyPh || hasBullet) body.append("  ".repeat(lvl.coerceIn(0, 5))).append("- ").append(t.replace("\n", " ")).append('\n')
                                else body.append(escapeLineStart(t)).append("\n\n")
                            }
                        }
                        "graphicFrame" -> {
                            val tbl = descendants(sh, "tbl").firstOrNull()
                            if (tbl != null) {
                                val rows = kids(tbl, "tr").map { tr -> kids(tr, "tc").map { tc ->
                                    kid(tc, "txBody")?.let { tb -> kids(tb, "p").joinToString("<br>") { paragraphText(it).replace("\n", " ").replace("|", "\\|") }.trim() } ?: ""
                                } }
                                if (rows.isNotEmpty()) {
                                    val width = rows.maxOf { it.size }
                                    fun row(r: List<String>) = "| " + List(width) { r.getOrElse(it) { "" } }.joinToString(" | ") + " |"
                                    body.append('\n').append(row(rows[0])).append('\n').append("|").append(" --- |".repeat(width)).append('\n')
                                    rows.drop(1).forEach { body.append(row(it)).append('\n') }
                                    body.append('\n')
                                }
                            }
                        }
                        "grpSp" -> walk(sh)
                    }
                }
            }
            walk(tree)
            val heading = if (title.isNotEmpty()) unbold(title) else "Slide ${i + 1}"
            out.append(if (i == 0) "# " else "## ").append(heading.replace("\n", " ")).append("\n\n").append(body.toString().trimEnd()).append("\n\n")
        }
        if (out.isBlank()) throw ReadError("The presentation has no readable slides.")
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }
}
