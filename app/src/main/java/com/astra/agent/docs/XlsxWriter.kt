package com.astra.agent.docs

import com.astra.agent.docs.XlsxNumbers.Kind

/** Writes a real .xlsx workbook: one sheet per table, styled header, frozen top row, filters, formulas with stored results. */
object XlsxWriter {

    private class SheetData(val name: String, val rows: List<List<String>>, val aligns: List<Char>, val header: Boolean, val pictures: Boolean = false)

    private val kinds = Kind.values().toList()
    private fun xf(kind: Kind, zebra: Boolean) = 2 + kinds.indexOf(kind) * 2 + (if (zebra) 1 else 0)

    fun write(blocks: List<Block>, title: String, theme: Theme = Themes.all[0], images: List<DocImage> = emptyList()): ByteArray {
        val sheets = collectSheets(blocks) + (if (images.isNotEmpty()) listOf(SheetData("Pictures", emptyList(), emptyList(), false, pictures = true)) else emptyList())
        val z = ZipBuilder()
        val sst = SharedStrings()

        val sheetXml = sheets.mapIndexed { i, s ->
            if (s.pictures) Xml.HEADER + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                "<sheetPr><tabColor rgb=\"FF${theme.accent}\"/></sheetPr><sheetData/><pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/><drawing r:id=\"rId1\"/></worksheet>"
            else sheetXml(s, i == 0, sst, theme)
        }

        z.add("[Content_Types].xml", Xml.HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
            (if (images.isNotEmpty()) "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" else "") +
            "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            sheets.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
            "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
            "<Override PartName=\"/xl/sharedStrings.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml\"/>" +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>")
        z.add("_rels/.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        z.add("xl/workbook.xml", Xml.HEADER + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
            "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><bookViews><workbookView xWindow=\"0\" yWindow=\"0\" windowWidth=\"24000\" windowHeight=\"12000\"/></bookViews><sheets>" +
            sheets.mapIndexed { i, s -> "<sheet name=\"${Xml.esc(s.name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
            "</sheets><calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
        z.add("xl/_rels/workbook.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            sheets.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rId${sheets.size + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings\" Target=\"sharedStrings.xml\"/>" +
            "</Relationships>")
        z.add("xl/styles.xml", styles(theme))
        sheetXml.forEachIndexed { i, x -> z.add("xl/worksheets/sheet${i + 1}.xml", x) }
        z.add("xl/sharedStrings.xml", sst.xml())
        if (images.isNotEmpty()) {
            val sheetNo = sheets.size
            z.add("xl/worksheets/_rels/sheet$sheetNo.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing\" Target=\"../drawings/drawing1.xml\"/></Relationships>")
            val dr = StringBuilder(Xml.HEADER + "<xdr:wsDr xmlns:xdr=\"http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" " +
                "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
            val drRels = StringBuilder(Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            var row = 1
            images.forEachIndexed { i, im ->
                val (pw, ph) = im.fit(560.0, 420.0)                  // pixels
                val cx = (pw * 9525).toLong(); val cy = (ph * 9525).toLong()
                dr.append("<xdr:oneCellAnchor><xdr:from><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>$row</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:ext cx=\"$cx\" cy=\"$cy\"/>")
                    .append("<xdr:pic><xdr:nvPicPr><xdr:cNvPr id=\"${i + 2}\" name=\"Picture ${i + 1}\" descr=\"${Xml.esc(im.caption)}\"/><xdr:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></xdr:cNvPicPr></xdr:nvPicPr>")
                    .append("<xdr:blipFill><a:blip r:embed=\"rId${i + 1}\"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>")
                    .append("<xdr:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:oneCellAnchor>")
                drRels.append("<Relationship Id=\"rId${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"../media/image${i + 1}.jpg\"/>")
                z.addBytes("xl/media/image${i + 1}.jpg", im.bytes)
                row += Math.ceil(ph / 20.0).toInt() + 3
            }
            dr.append("</xdr:wsDr>"); drRels.append("</Relationships>")
            z.add("xl/drawings/drawing1.xml", dr.toString())
            z.add("xl/drawings/_rels/drawing1.xml.rels", drRels.toString())
        }
        val now = java.time.Instant.now().toString().substringBefore('.') + "Z"
        z.add("docProps/core.xml", Xml.HEADER + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.esc(title)}</dc:title><dc:creator>ZENIQ</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>")
        z.add("docProps/app.xml", Xml.HEADER + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ZENIQ</Application></Properties>")
        return z.finish()
    }

    // ── Sheets from blocks ──────────────────────────────────────────────────

    private fun cleanName(raw: String, used: MutableSet<String>, fallback: String): String {
        var n = raw.replace(Regex("^(?i)(sheet|tab|worksheet)\\s*\\d*\\s*[:\\-\\u2013]\\s*"), "")
            .replace(Regex("[\\[\\]:*?/\\\\]"), " ").replace(Regex("\\s+"), " ").trim().trim('\'')
        if (n.isBlank()) n = fallback
        n = n.take(31).trim()
        var cand = n; var k = 2
        while (!used.add(cand.lowercase())) {
            val suffix = " ($k)"; cand = n.take(31 - suffix.length).trim() + suffix; k++
        }
        return cand
    }

    private fun collectSheets(blocks: List<Block>): List<SheetData> {
        val used = HashSet<String>()
        val sheets = ArrayList<SheetData>()
        val notes = ArrayList<String>()
        var heading: String? = null
        for (b in blocks) {
            when (b) {
                is Heading -> heading = b.runs.plain().trim()
                is Table -> {
                    val rows = ArrayList<List<String>>()
                    rows.add(b.header.map { it.plain().trim() })
                    b.rows.forEach { r -> rows.add(r.map { it.plain().trim() }) }
                    sheets.add(SheetData(cleanName(heading ?: "", used, "Sheet ${sheets.size + 1}"), rows, b.aligns, true))
                    heading = null
                }
                is Para -> notes.add(b.runs.plain().trim())
                is ListItem -> notes.add("  ".repeat(b.level) + (if (b.ordered) "${b.number}. " else "\u2022 ") + b.runs.plain().trim())
                is Quote -> notes.add(b.runs.plain().trim())
                is CodeBlock -> notes.addAll(b.text.split("\n"))
                else -> {}
            }
        }
        if (sheets.isEmpty()) {
            // no tables at all: put every text line in column A
            val all = ArrayList<List<String>>()
            for (b in blocks) when (b) {
                is Heading -> all.add(listOf(b.runs.plain().trim()))
                is Para -> b.runs.plain().split("\n").forEach { all.add(listOf(it.trim())) }
                is ListItem -> all.add(listOf("  ".repeat(b.level) + (if (b.ordered) "${b.number}. " else "\u2022 ") + b.runs.plain().trim()))
                is Quote -> all.add(listOf(b.runs.plain().trim()))
                is CodeBlock -> b.text.split("\n").forEach { all.add(listOf(it)) }
                else -> {}
            }
            if (all.isEmpty()) all.add(listOf(""))
            sheets.add(SheetData(cleanName("", used, "Sheet1"), all, listOf(' '), false))
        } else if (notes.any { it.isNotBlank() }) {
            sheets.add(SheetData(cleanName("Notes", used, "Notes"), notes.filter { it.isNotBlank() }.take(500).map { listOf(it) }, listOf(' '), false))
        }
        return sheets
    }

    // ── Shared strings ──────────────────────────────────────────────────────

    private class SharedStrings {
        private val index = LinkedHashMap<String, Int>()
        var total = 0
        fun id(s: String): Int { total++; return index.getOrPut(s) { index.size } }
        fun xml(): String {
            val sb = StringBuilder(Xml.HEADER + "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"$total\" uniqueCount=\"${index.size}\">")
            for (s in index.keys) sb.append("<si><t xml:space=\"preserve\">").append(Xml.esc(s)).append("</t></si>")
            sb.append("</sst>")
            return sb.toString()
        }
    }

    // ── Sheet XML ───────────────────────────────────────────────────────────

    private fun colName(c: Int): String {   // 1-based
        var n = c; val sb = StringBuilder()
        while (n > 0) { val r = (n - 1) % 26; sb.append(('A' + r)); n = (n - 1) / 26 }
        return sb.reverse().toString()
    }

    private fun sheetXml(s: SheetData, first: Boolean, sst: SharedStrings, theme: Theme): String {
        val rows = s.rows.map { it.map { c -> c.take(32000) } }
        val nCols = rows.maxOfOrNull { it.size } ?: 1
        val nRows = rows.size
        val raw: (Int, Int) -> String? = { r, c -> rows.getOrNull(r - 1)?.getOrNull(c - 1) }
        val eval = FormulaEval(raw)

        // number format a formula inherits from the numbers above it in its column
        val colKind = Array(nCols + 1) { Kind.GENERAL }
        for (c in 1..nCols) {
            val counts = HashMap<Kind, Int>()
            for (r in (if (s.header) 2 else 1)..nRows) {
                val t = raw(r, c) ?: continue
                if (t.startsWith("=")) continue
                val p = XlsxNumbers.parse(t) ?: continue
                counts[p.kind] = (counts[p.kind] ?: 0) + 1
            }
            colKind[c] = counts.filterKeys { it != Kind.GENERAL }.maxByOrNull { it.value }?.key ?: Kind.GENERAL
        }

        val widths = DoubleArray(nCols + 1) { 8.0 }
        val sd = StringBuilder()
        for (r in 1..nRows) {
            val isHeader = s.header && r == 1
            sd.append("<row r=\"$r\"")
            if (isHeader) sd.append(" ht=\"24\" customHeight=\"1\"")
            sd.append(">")
            val zebra = s.header && r > 1 && r % 2 == 1
            val row = rows[r - 1]
            for (c in 1..nCols) {
                val text = row.getOrNull(c - 1) ?: ""
                val ref = colName(c) + r
                val longest = text.split("\n").maxOfOrNull { it.length } ?: 0
                widths[c] = maxOf(widths[c], (if (isHeader) longest * 1.15 else longest * 1.05) + 2.5)
                if (text.isEmpty()) {
                    if (s.header) sd.append("<c r=\"$ref\" s=\"${if (isHeader) 1 else xf(Kind.GENERAL, zebra)}\"/>")
                    continue
                }
                when {
                    isHeader -> sd.append("<c r=\"$ref\" s=\"1\" t=\"s\"><v>${sst.id(text)}</v></c>")
                    text.startsWith("=") && text.length > 1 -> {
                        val v = eval.cell(r, c)
                        val kind = if (Regex("^=\\s*(COUNT|COUNTA|LEN|IF|AND|OR|CONCAT)", RegexOption.IGNORE_CASE).containsMatchIn(text)) Kind.GENERAL else colKind[c]
                        val st = if (s.header) xf(kind, zebra) else 0
                        val f = Xml.esc(text.substring(1).trim())
                        when (v) {
                            is Double -> sd.append("<c r=\"$ref\" s=\"$st\"><f>$f</f><v>${XlsxNumbers.format(v)}</v></c>")
                            is Boolean -> sd.append("<c r=\"$ref\" s=\"$st\" t=\"b\"><f>$f</f><v>${if (v) 1 else 0}</v></c>")
                            is String -> sd.append("<c r=\"$ref\" s=\"$st\" t=\"str\"><f>$f</f><v>${Xml.esc(v)}</v></c>")
                            else -> sd.append("<c r=\"$ref\" s=\"$st\"><f>$f</f></c>")
                        }
                        widths[c] = maxOf(widths[c], 12.0)
                    }
                    else -> {
                        val p = XlsxNumbers.parse(text)
                        if (p != null && s.header) sd.append("<c r=\"$ref\" s=\"${xf(p.kind, zebra)}\"><v>${XlsxNumbers.format(p.value)}</v></c>")
                        else if (p != null) sd.append("<c r=\"$ref\"><v>${XlsxNumbers.format(p.value)}</v></c>")
                        else sd.append("<c r=\"$ref\" s=\"${if (s.header) textXf(zebra) else 0}\" t=\"s\"><v>${sst.id(text)}</v></c>")
                    }
                }
            }
            sd.append("</row>")
        }

        val sb = StringBuilder(Xml.HEADER)
        sb.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
        sb.append("<sheetPr><tabColor rgb=\"FF${theme.accent}\"/></sheetPr>")
        sb.append("<dimension ref=\"A1:${colName(nCols)}$nRows\"/>")
        sb.append("<sheetViews><sheetView workbookViewId=\"0\"").append(if (first) " tabSelected=\"1\"" else "").append(">")
        if (s.header && nRows > 1) sb.append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/>")
        sb.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/><cols>")
        for (c in 1..nCols) sb.append("<col min=\"$c\" max=\"$c\" width=\"${"%.1f".format(java.util.Locale.US, widths[c].coerceIn(8.0, 60.0))}\" customWidth=\"1\"/>")
        sb.append("</cols><sheetData>").append(sd).append("</sheetData>")
        if (s.header && nRows > 1) sb.append("<autoFilter ref=\"A1:${colName(nCols)}$nRows\"/>")
        sb.append("<pageMargins left=\"0.7\" right=\"0.7\" top=\"0.75\" bottom=\"0.75\" header=\"0.3\" footer=\"0.3\"/>")
        sb.append("<pageSetup orientation=\"${if (nCols > 6) "landscape" else "portrait"}\"/>")
        sb.append("</worksheet>")
        return sb.toString()
    }

    private fun textXf(zebra: Boolean) = 2 + kinds.size * 2 + (if (zebra) 1 else 0)

    // ── Styles ──────────────────────────────────────────────────────────────

    private fun styles(theme: Theme): String {
        val font = if (theme.serif) "Cambria" else "Calibri"
        val sb = StringBuilder(Xml.HEADER + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
        sb.append("<numFmts count=\"4\"><numFmt numFmtId=\"164\" formatCode=\"&quot;$&quot;#,##0.00\"/><numFmt numFmtId=\"165\" formatCode=\"[\$€-2] #,##0.00\"/>" +
            "<numFmt numFmtId=\"166\" formatCode=\"[\$£-809]#,##0.00\"/><numFmt numFmtId=\"167\" formatCode=\"[\$₹-4009] #,##0.00\"/></numFmts>")
        sb.append("<fonts count=\"2\"><font><sz val=\"11\"/><color rgb=\"FF000000\"/><name val=\"$font\"/><family val=\"2\"/></font>" +
            "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"$font\"/><family val=\"2\"/></font></fonts>")
        sb.append("<fills count=\"4\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${theme.primary}\"/><bgColor indexed=\"64\"/></patternFill></fill>" +
            "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF${theme.light}\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>")
        sb.append("<borders count=\"2\"><border><left/><right/><top/><bottom/><diagonal/></border>" +
            "<border><left style=\"thin\"><color rgb=\"FFD0D5DD\"/></left><right style=\"thin\"><color rgb=\"FFD0D5DD\"/></right>" +
            "<top style=\"thin\"><color rgb=\"FFD0D5DD\"/></top><bottom style=\"thin\"><color rgb=\"FFD0D5DD\"/></bottom><diagonal/></border></borders>")
        sb.append("<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>")
        val total = 2 + kinds.size * 2 + 2
        sb.append("<cellXfs count=\"$total\">")
        sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>")                                   // 0 default
        sb.append("<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"left\" vertical=\"center\" wrapText=\"1\"/></xf>")  // 1 header
        for (k in kinds) for (zebra in listOf(false, true)) {
            sb.append("<xf numFmtId=\"${k.numFmtId}\" fontId=\"0\" fillId=\"${if (zebra) 3 else 0}\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\"/></xf>")
        }
        for (zebra in listOf(false, true)) {   // text cells
            sb.append("<xf numFmtId=\"0\" fontId=\"0\" fillId=\"${if (zebra) 3 else 0}\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>")
        }
        sb.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>")
        return sb.toString()
    }
}
