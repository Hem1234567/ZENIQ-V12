package com.astra.agent.docs

/**
 * Writes a real .pptx. "# Title" becomes the title slide, every "## Heading" starts a slide,
 * bullets / paragraphs / tables fill it. Long slides are split automatically.
 */
object PptxWriter {

    private const val W = 12192000L
    private const val H = 6858000L
    private const val NS_A = "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\""
    private const val NS_R = "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private const val NS_P = "xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\""

    private class Slide(val title: String, val isTitle: Boolean) {
        val items = ArrayList<Block>()
    }

    fun write(blocks: List<Block>, title: String): ByteArray {
        val slides = buildSlides(blocks, title)
        val z = ZipBuilder()
        z.add("[Content_Types].xml", Xml.HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml\"/>" +
            "<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/slideLayouts/slideLayout2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>" +
            "<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>" +
            slides.indices.joinToString("") { "<Override PartName=\"/ppt/slides/slide${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>" } +
            "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
            "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
            "</Types>")
        z.add("_rels/.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"ppt/presentation.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        val now = java.time.Instant.now().toString().substringBefore('.') + "Z"
        z.add("docProps/core.xml", Xml.HEADER + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.esc(title)}</dc:title><dc:creator>ZENIQ</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>")
        z.add("docProps/app.xml", Xml.HEADER + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ZENIQ</Application><Slides>${slides.size}</Slides></Properties>")

        z.add("ppt/presentation.xml", Xml.HEADER + "<p:presentation $NS_A $NS_R $NS_P saveSubsetFonts=\"1\">" +
            "<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>" +
            slides.indices.joinToString("") { "<p:sldId id=\"${256 + it}\" r:id=\"rId${it + 2}\"/>" } +
            "</p:sldIdLst><p:sldSz cx=\"$W\" cy=\"$H\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
        z.add("ppt/_rels/presentation.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>" +
            slides.indices.joinToString("") { "<Relationship Id=\"rId${it + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${it + 1}.xml\"/>" } +
            "<Relationship Id=\"rId${slides.size + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"theme/theme1.xml\"/>" +
            "</Relationships>")
        z.add("ppt/theme/theme1.xml", theme())
        z.add("ppt/slideMasters/slideMaster1.xml", master())
        z.add("ppt/slideMasters/_rels/slideMaster1.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout1.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout2.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme\" Target=\"../theme/theme1.xml\"/></Relationships>")
        for (n in 1..2) {
            z.add("ppt/slideLayouts/slideLayout$n.xml", layout(n))
            z.add("ppt/slideLayouts/_rels/slideLayout$n.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"../slideMasters/slideMaster1.xml\"/></Relationships>")
        }
        slides.forEachIndexed { i, s ->
            z.add("ppt/slides/slide${i + 1}.xml", slideXml(s))
            z.add("ppt/slides/_rels/slide${i + 1}.xml.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout\" Target=\"../slideLayouts/slideLayout${if (s.isTitle) 1 else 2}.xml\"/></Relationships>")
        }
        return z.finish()
    }

    // ── Blocks -> slides ────────────────────────────────────────────────────

    private fun buildSlides(blocks: List<Block>, docTitle: String): List<Slide> {
        val slides = ArrayList<Slide>()
        var cur: Slide? = null
        var seenH1 = false
        for (b in blocks) {
            if (b is Heading && (b.level <= 2)) {
                val t = b.runs.plain().trim()
                if (b.level == 1 && !seenH1 && slides.isEmpty()) {
                    seenH1 = true
                    cur = Slide(t, true); slides.add(cur)
                } else {
                    cur = Slide(t, false); slides.add(cur)
                }
                continue
            }
            if (b is PageBreak || b is Rule) continue
            if (cur == null) { cur = Slide(docTitle, false); slides.add(cur) }
            cur.items.add(b)
        }
        if (slides.isEmpty()) slides.add(Slide(docTitle, true))
        // split overlong slides
        val out = ArrayList<Slide>()
        for (s in slides) {
            if (s.isTitle) { out.add(s); continue }
            var part = Slide(s.title, false); var weight = 0
            fun flush() { if (part.items.isNotEmpty() || out.none { it.title == s.title }) out.add(part) }
            for (it in s.items) {
                val w = when (it) {
                    is Table -> 2 + it.rows.size
                    is CodeBlock -> 1 + it.text.count { c -> c == '\n' }
                    is Para -> 1 + it.runs.plain().length / 90
                    is ListItem -> 1 + it.runs.plain().length / 90
                    else -> 1
                }
                if (weight + w > 9 && part.items.isNotEmpty()) {
                    flush()
                    part = Slide(s.title + " (cont.)", false); weight = 0
                }
                // a big table gets its own slides
                if (it is Table && it.rows.size > 9) {
                    if (part.items.isNotEmpty()) { flush(); part = Slide(s.title + " (cont.)", false); weight = 0 }
                    var idx = 0
                    while (idx < it.rows.size) {
                        val chunk = it.rows.subList(idx, minOf(idx + 9, it.rows.size))
                        part.items.add(Table(it.header, chunk, it.aligns)); idx += 9
                        if (idx < it.rows.size) { flush(); part = Slide(s.title + " (cont.)", false) }
                    }
                    weight = 9
                    continue
                }
                part.items.add(it); weight += w
            }
            flush()
        }
        return out
    }

    // ── Slide XML ───────────────────────────────────────────────────────────

    private class Ids { var n = 1; fun next() = ++n }

    private fun rPr(sz: Int, color: String, bold: Boolean, italic: Boolean, mono: Boolean, tag: String = "a:rPr", u: Boolean = false, strike: Boolean = false): String =
        "<$tag lang=\"en-US\" sz=\"$sz\"${if (bold) " b=\"1\"" else ""}${if (italic) " i=\"1\"" else ""}${if (u) " u=\"sng\"" else ""}${if (strike) " strike=\"sngStrike\"" else ""} dirty=\"0\">" +
            "<a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill>" + (if (mono) "<a:latin typeface=\"Consolas\"/><a:cs typeface=\"Consolas\"/>" else "") + "</$tag>"

    private fun runsXml(list: List<Run>, sz: Int, color: String, forceBold: Boolean = false): String {
        val sb = StringBuilder()
        for (r in list) {
            val parts = r.text.split("\n")
            parts.forEachIndexed { i, part ->
                if (i > 0) sb.append("<a:br>").append(rPr(sz, color, false, false, false)).append("</a:br>")
                if (part.isNotEmpty()) sb.append("<a:r>")
                    .append(rPr(sz, if (r.link != null) "0563C1" else color, r.bold || forceBold, r.italic, r.code, u = r.link != null, strike = r.strike))
                    .append("<a:t>").append(Xml.esc(part)).append("</a:t></a:r>")
            }
        }
        return sb.toString()
    }

    private fun para(runs: List<Run>, sz: Int, color: String, bullet: Boolean, level: Int, algn: String = "l", bold: Boolean = false, spcBef: Int = 600): String {
        val mar = if (bullet) 342900 + level * 342900 else 0
        val ind = if (bullet) -342900 else 0
        val char = if (level % 2 == 0) "\u2022" else "\u2013"
        return "<a:p><a:pPr marL=\"$mar\" indent=\"$ind\" lvl=\"${if (bullet) level else 0}\" algn=\"$algn\"><a:spcBef><a:spcPts val=\"$spcBef\"/></a:spcBef>" +
            (if (bullet) "<a:buFont typeface=\"Arial\"/><a:buChar char=\"$char\"/>" else "<a:buNone/>") + "</a:pPr>" +
            runsXml(runs, sz, color, bold) + "<a:endParaRPr lang=\"en-US\" sz=\"$sz\" dirty=\"0\"/></a:p>"
    }

    private fun slideXml(s: Slide): String {
        val ids = Ids()
        val sb = StringBuilder()
        sb.append(Xml.HEADER).append("<p:sld $NS_A $NS_R $NS_P><p:cSld>")
        if (s.isTitle) sb.append("<p:bg><p:bgPr><a:solidFill><a:srgbClr val=\"1F3864\"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>")
        sb.append("<p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>")
        sb.append("<p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>")

        if (s.isTitle) {
            val sub = s.items.filter { it is Para || it is ListItem || it is Quote }
            val titleSz = if (s.title.length > 60) 3600 else 4400
            // accent line
            sb.append(rect(ids.next(), "Accent", 914400, 3429000, 1828800, 54864, "5B9BD5"))
            sb.append(textShape(ids.next(), "Title", "ctrTitle", null, 914400, 1371600, 10363200, 2000000, "b",
                para(inline(s.title), titleSz, "FFFFFF", false, 0, bold = true, spcBef = 0)))
            if (sub.isNotEmpty()) {
                val body = sub.joinToString("") { b ->
                    when (b) {
                        is Para -> para(b.runs, 2000, "D9E2F3", false, 0)
                        is ListItem -> para(b.runs, 2000, "D9E2F3", false, 0)
                        is Quote -> para(b.runs, 2000, "D9E2F3", false, 0)
                        else -> ""
                    }
                }
                sb.append(textShape(ids.next(), "Subtitle", "subTitle", 1, 914400, 3657600, 10363200, 1600200, "t", body))
            }
        } else {
            sb.append(rect(ids.next(), "Top bar", 0, 0, W, 137160, "1F4E79"))
            sb.append(textShape(ids.next(), "Title", "title", null, 609600, 320040, 10972800, 960120, "ctr",
                para(inline(s.title), if (s.title.length > 55) 2600 else 3200, "1F3864", false, 0, bold = true, spcBef = 0)))
            val textItems = s.items.filter { it !is Table }
            val table = s.items.filterIsInstance<Table>().firstOrNull()
            val chars = textItems.sumOf { itx ->
                when (itx) { is Para -> itx.runs.plain().length; is ListItem -> itx.runs.plain().length + 4; is Quote -> itx.runs.plain().length; is CodeBlock -> itx.text.length; else -> 0 }
            }
            val sz = when { table != null -> 1800; chars > 800 -> 1400; chars > 550 -> 1600; chars > 350 -> 1800; chars > 200 -> 2200; else -> 2400 }
            if (textItems.isNotEmpty()) {
                val body = StringBuilder()
                for (b in textItems) when (b) {
                    is ListItem -> body.append(para(b.runs, sz, "333333", true, b.level.coerceIn(0, 4)))
                    is Para -> body.append(para(b.runs, sz, "333333", false, 0))
                    is Quote -> body.append(para(b.runs.map { it.copy(italic = true) }, sz, "595959", false, 0))
                    is CodeBlock -> b.text.split("\n").forEach { body.append(para(listOf(Run(it, code = true)), (sz - 200).coerceAtLeast(1000), "333333", false, 0, spcBef = 0)) }
                    is Heading -> body.append(para(b.runs, sz + 200, "2E74B5", false, 0, bold = true, spcBef = 900))
                    else -> {}
                }
                val h = if (table != null) 1500000L else 4846320L
                sb.append(textShape(ids.next(), "Content", null, 1, 609600, 1417320, 10972800, h, "t", body.toString()))
            }
            if (table != null) sb.append(tableFrame(ids.next(), table, if (textItems.isEmpty()) 1417320L else 3017320L))
        }
        sb.append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
        return sb.toString()
    }

    private fun inline(t: String): List<Run> = MdParser.inline(t)

    private fun rect(id: Int, name: String, x: Long, y: Long, w: Long, h: Long, color: String) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr/><p:nvPr userDrawn=\"1\"/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:solidFill><a:srgbClr val=\"$color\"/></a:solidFill><a:ln><a:noFill/></a:ln></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:endParaRPr lang=\"en-US\"/></a:p></p:txBody></p:sp>"

    private fun textShape(id: Int, name: String, phType: String?, phIdx: Int?, x: Long, y: Long, w: Long, h: Long, anchor: String, paragraphs: String): String {
        val ph = when {
            phType != null && phIdx != null -> "<p:ph type=\"$phType\" idx=\"$phIdx\"/>"
            phType != null -> "<p:ph type=\"$phType\"/>"
            phIdx != null -> "<p:ph idx=\"$phIdx\"/>"
            else -> ""
        }
        return "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name $id\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm></p:spPr>" +
            "<p:txBody><a:bodyPr wrap=\"square\" lIns=\"91440\" tIns=\"45720\" rIns=\"91440\" bIns=\"45720\" anchor=\"$anchor\"><a:normAutofit/></a:bodyPr><a:lstStyle/>$paragraphs</p:txBody></p:sp>"
    }

    private fun tableFrame(id: Int, t: Table, y: Long): String {
        val n = t.header.size
        val totalW = 10972800L
        val weights = DoubleArray(n) { c ->
            var m = t.header[c].plain().length
            for (r in t.rows) m = maxOf(m, r[c].plain().length)
            m.coerceIn(5, 40).toDouble()
        }
        val sum = weights.sum()
        val widths = LongArray(n) { (weights[it] / sum * totalW).toLong() }
        widths[widths.indices.maxByOrNull { widths[it] }!!] += totalW - widths.sum()
        val sz = if (n > 6) 1100 else if (n > 4) 1300 else 1500
        val rowH = 370840L
        val border = "<a:lnL w=\"6350\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:lnL><a:lnR w=\"6350\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:lnR>" +
            "<a:lnT w=\"6350\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:lnT><a:lnB w=\"6350\"><a:solidFill><a:srgbClr val=\"BFBFBF\"/></a:solidFill></a:lnB>"
        fun numeric(c: Int) = t.rows.isNotEmpty() && t.rows.all { r -> r[c].plain().trim().let { s -> s.isEmpty() || Regex("^[-+(]?[$€£₹]?\\s?[\\d,]+(\\.\\d+)?%?\\)?$").matches(s) } }
        fun cell(runs: List<Run>, header: Boolean, zebra: Boolean, c: Int): String {
            val fill = if (header) "1F4E79" else if (zebra) "EAF0F8" else "FFFFFF"
            val algn = when (t.aligns.getOrElse(c) { ' ' }) { 'c' -> "ctr"; 'r' -> "r"; 'l' -> "l"; else -> if (!header && numeric(c)) "r" else "l" }
            val txt = runsXml(runs, sz, if (header) "FFFFFF" else "333333", forceBold = header)
            return "<a:tc><a:txBody><a:bodyPr/><a:lstStyle/><a:p><a:pPr algn=\"$algn\"/>$txt<a:endParaRPr lang=\"en-US\" sz=\"$sz\" dirty=\"0\"/></a:p></a:txBody>" +
                "<a:tcPr marL=\"91440\" marR=\"91440\" marT=\"45720\" marB=\"45720\" anchor=\"ctr\">$border<a:solidFill><a:srgbClr val=\"$fill\"/></a:solidFill></a:tcPr></a:tc>"
        }
        val sb = StringBuilder()
        sb.append("<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id=\"$id\" name=\"Table $id\"/><p:cNvGraphicFramePr><a:graphicFrameLocks noGrp=\"1\"/></p:cNvGraphicFramePr><p:nvPr/></p:nvGraphicFramePr>")
        sb.append("<p:xfrm><a:off x=\"609600\" y=\"$y\"/><a:ext cx=\"$totalW\" cy=\"${rowH * (t.rows.size + 1)}\"/></p:xfrm>")
        sb.append("<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/table\"><a:tbl><a:tblPr firstRow=\"1\" bandRow=\"1\"/><a:tblGrid>")
        widths.forEach { sb.append("<a:gridCol w=\"$it\"/>") }
        sb.append("</a:tblGrid><a:tr h=\"$rowH\">")
        t.header.forEachIndexed { c, r -> sb.append(cell(r, true, false, c)) }
        sb.append("</a:tr>")
        t.rows.forEachIndexed { i, row ->
            sb.append("<a:tr h=\"$rowH\">")
            row.forEachIndexed { c, r -> sb.append(cell(r, false, i % 2 == 1, c)) }
            sb.append("</a:tr>")
        }
        sb.append("</a:tbl></a:graphicData></a:graphic></p:graphicFrame>")
        return sb.toString()
    }

    // ── Master, layouts, theme ──────────────────────────────────────────────

    private fun phShape(id: Int, name: String, ph: String, x: Long, y: Long, w: Long, h: Long) =
        "<p:sp><p:nvSpPr><p:cNvPr id=\"$id\" name=\"$name\"/><p:cNvSpPr><a:spLocks noGrp=\"1\"/></p:cNvSpPr><p:nvPr>$ph</p:nvPr></p:nvSpPr>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"$w\" cy=\"$h\"/></a:xfrm></p:spPr>" +
            "<p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang=\"en-US\"/><a:t>Click to edit</a:t></a:r></a:p></p:txBody></p:sp>"

    private const val GRP = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"

    private fun master(): String =
        Xml.HEADER + "<p:sldMaster $NS_A $NS_R $NS_P><p:cSld><p:bg><p:bgRef idx=\"1001\"><a:schemeClr val=\"bg1\"/></p:bgRef></p:bg><p:spTree>$GRP" +
            phShape(2, "Title Placeholder", "<p:ph type=\"title\"/>", 609600, 320040, 10972800, 960120) +
            phShape(3, "Text Placeholder", "<p:ph type=\"body\" idx=\"1\"/>", 609600, 1417320, 10972800, 4846320) +
            "</p:spTree></p:cSld>" +
            "<p:clrMap bg1=\"lt1\" tx1=\"dk1\" bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/><p:sldLayoutId id=\"2147483650\" r:id=\"rId2\"/></p:sldLayoutIdLst>" +
            "<p:txStyles><p:titleStyle><a:lvl1pPr algn=\"l\"><a:defRPr sz=\"3200\" b=\"1\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mj-lt\"/></a:defRPr></a:lvl1pPr></p:titleStyle>" +
            "<p:bodyStyle><a:lvl1pPr marL=\"342900\" indent=\"-342900\"><a:buFont typeface=\"Arial\"/><a:buChar char=\"\u2022\"/><a:defRPr sz=\"2000\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:bodyStyle>" +
            "<p:otherStyle><a:lvl1pPr><a:defRPr sz=\"1800\"><a:solidFill><a:schemeClr val=\"tx1\"/></a:solidFill><a:latin typeface=\"+mn-lt\"/></a:defRPr></a:lvl1pPr></p:otherStyle></p:txStyles></p:sldMaster>"

    private fun layout(n: Int): String {
        val body = if (n == 1)
            phShape(2, "Title", "<p:ph type=\"ctrTitle\"/>", 914400, 1371600, 10363200, 2000000) +
                phShape(3, "Subtitle", "<p:ph type=\"subTitle\" idx=\"1\"/>", 914400, 3657600, 10363200, 1600200)
        else
            phShape(2, "Title", "<p:ph type=\"title\"/>", 609600, 320040, 10972800, 960120) +
                phShape(3, "Content", "<p:ph idx=\"1\"/>", 609600, 1417320, 10972800, 4846320)
        return Xml.HEADER + "<p:sldLayout $NS_A $NS_R $NS_P type=\"${if (n == 1) "title" else "obj"}\" preserve=\"1\"><p:cSld name=\"${if (n == 1) "Title Slide" else "Title and Content"}\"><p:spTree>$GRP$body</p:spTree></p:cSld>" +
            "<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"
    }

    private fun theme(): String {
        val fill = "<a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill>"
        fun ln(w: Int) = "<a:ln w=\"$w\" cap=\"flat\" cmpd=\"sng\" algn=\"ctr\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill><a:prstDash val=\"solid\"/></a:ln>"
        return Xml.HEADER + "<a:theme $NS_A name=\"ZENIQ\"><a:themeElements>" +
            "<a:clrScheme name=\"ZENIQ\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"1F3864\"/></a:dk2><a:lt2><a:srgbClr val=\"EAF0F8\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"1F4E79\"/></a:accent1><a:accent2><a:srgbClr val=\"2E74B5\"/></a:accent2><a:accent3><a:srgbClr val=\"5B9BD5\"/></a:accent3><a:accent4><a:srgbClr val=\"ED7D31\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"70AD47\"/></a:accent5><a:accent6><a:srgbClr val=\"FFC000\"/></a:accent6><a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
            "<a:fontScheme name=\"ZENIQ\"><a:majorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont><a:latin typeface=\"Calibri\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"ZENIQ\"><a:fillStyleLst>$fill$fill$fill</a:fillStyleLst><a:lnStyleLst>${ln(6350)}${ln(12700)}${ln(19050)}</a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst>" +
            "<a:bgFillStyleLst>$fill$fill$fill</a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>"
    }
}
