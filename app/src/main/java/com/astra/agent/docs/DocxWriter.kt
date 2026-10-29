package com.astra.agent.docs

/** Writes a real .docx (WordprocessingML) with styles, lists, tables, hyperlinks, a themed title banner and a footer with page numbers. */
object DocxWriter {

    private const val NS = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" " +
        "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\""
    private const val CONTENT_W = 9026   // A4 width minus 2 x 1 inch margins, in twips

    fun write(blocks0: List<Block>, title: String, theme: Theme = Themes.all[0], images: List<DocImage> = emptyList()): ByteArray {
        val blocks = ImagePlacement.place(blocks0, images, slides = false)
        val ctx = Ctx()
        val body = StringBuilder()
        var firstHeading = true
        var orderedActive = false
        for (b in blocks) {
            if (b !is ListItem) orderedActive = false
            when (b) {
                is Heading -> {
                    if (b.level == 1 && firstHeading) {
                        firstHeading = false
                        body.append(banner(runs(b.runs, ctx), theme))
                    } else {
                        if (b.level == 1) firstHeading = false
                        body.append("<w:p><w:pPr><w:pStyle w:val=\"Heading${b.level.coerceIn(1, 4)}\"/></w:pPr>").append(runs(b.runs, ctx)).append("</w:p>")
                    }
                }
                is Para -> body.append("<w:p>").append(runs(b.runs, ctx)).append("</w:p>")
                is ListItem -> {
                    if (b.ordered && !orderedActive) ctx.newOrderedList()
                    if (b.ordered) orderedActive = true else if (b.level == 0) orderedActive = false
                    val numId = if (b.ordered) ctx.currentOrdered else 1
                    body.append("<w:p><w:pPr><w:pStyle w:val=\"ListParagraph\"/><w:numPr><w:ilvl w:val=\"${b.level.coerceIn(0, 8)}\"/>")
                        .append("<w:numId w:val=\"$numId\"/></w:numPr></w:pPr>").append(runs(b.runs, ctx)).append("</w:p>")
                }
                is Quote -> body.append("<w:p><w:pPr><w:pStyle w:val=\"Quote\"/></w:pPr>").append(runs(b.runs, ctx)).append("</w:p>")
                is CodeBlock -> {
                    val lines = b.text.split("\n")
                    for (l in lines) body.append("<w:p><w:pPr><w:pStyle w:val=\"Code\"/></w:pPr><w:r><w:t xml:space=\"preserve\">")
                        .append(Xml.esc(l.replace("\t", "    "))).append("</w:t></w:r></w:p>")
                    body.append("<w:p><w:pPr><w:spacing w:after=\"60\"/></w:pPr></w:p>")
                }
                is Table -> { body.append(table(b, ctx, theme)); body.append("<w:p><w:pPr><w:spacing w:after=\"80\"/></w:pPr></w:p>") }
                Rule -> body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"${theme.accent}\"/></w:pBdr></w:pPr></w:p>")
                PageBreak -> body.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>")
                is ImageBlock -> images.getOrNull(b.index)?.let { body.append(imageParagraph(ctx, it, b.caption)) }
            }
        }
        if (blocks.isEmpty()) body.append("<w:p/>")

        val sect = "<w:sectPr><w:footerReference w:type=\"default\" r:id=\"rIdFooter\"/>" +
            "<w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" " +
            "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr>"
        val document = Xml.HEADER + "<w:document $NS><w:body>" + body + sect + "</w:body></w:document>"

        val z = ZipBuilder()
        z.add("[Content_Types].xml", contentTypes())
        z.add("_rels/.rels", Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
            "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
            "</Relationships>")
        z.add("word/document.xml", document)
        ctx.usedImages.forEachIndexed { i, im -> z.addBytes("word/media/image${i + 1}.jpg", im.bytes) }
        val rels = StringBuilder(Xml.HEADER + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
            "<Relationship Id=\"rIdNum\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>" +
            "<Relationship Id=\"rIdFooter\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>")
        ctx.links.forEachIndexed { i, url ->
            rels.append("<Relationship Id=\"rIdLink${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
                .append(Xml.esc(url)).append("\" TargetMode=\"External\"/>")
        }
        ctx.usedImages.forEachIndexed { i, _ ->
            rels.append("<Relationship Id=\"rIdImg${i + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image${i + 1}.jpg\"/>")
        }
        rels.append("</Relationships>")
        z.add("word/_rels/document.xml.rels", rels.toString())
        z.add("word/styles.xml", styles(theme))
        z.add("word/numbering.xml", numbering(ctx))
        val fc = "<w:color w:val=\"${theme.accent}\"/><w:sz w:val=\"18\"/>"
        z.add("word/footer1.xml", Xml.HEADER + "<w:ftr $NS><w:p><w:pPr><w:pBdr><w:top w:val=\"single\" w:sz=\"8\" w:space=\"6\" w:color=\"${theme.accent}\"/></w:pBdr>" +
            "<w:tabs><w:tab w:val=\"right\" w:pos=\"$CONTENT_W\"/></w:tabs></w:pPr>" +
            "<w:r><w:rPr>$fc</w:rPr><w:t xml:space=\"preserve\">${Xml.esc(title.take(60))}</w:t></w:r><w:r><w:tab/></w:r>" +
            "<w:r><w:rPr>$fc</w:rPr><w:t xml:space=\"preserve\">Page </w:t></w:r><w:fldSimple w:instr=\" PAGE \"><w:r><w:rPr>$fc</w:rPr><w:t>1</w:t></w:r></w:fldSimple></w:p></w:ftr>")
        val now = java.time.Instant.now().toString().substringBefore('.') + "Z"
        z.add("docProps/core.xml", Xml.HEADER + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.esc(title)}</dc:title><dc:creator>ZENIQ</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>")
        z.add("docProps/app.xml", Xml.HEADER + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ZENIQ</Application></Properties>")
        return z.finish()
    }

    private fun imageParagraph(ctx: Ctx, im: DocImage, caption: String): String {
        val (wIn, hIn) = im.fit(6.0, 6.2)                       // inches; the text column is 6 inches wide
        val cx = (wIn * 914400).toLong(); val cy = (hIn * 914400).toLong()
        val n = ctx.imageNumber(im)
        val cap = caption.ifBlank { "" }
        val sb = StringBuilder()
        sb.append("<w:p><w:pPr><w:keepNext/><w:spacing w:before=\"120\" w:after=\"60\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing>")
        sb.append("<wp:inline xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">")
        sb.append("<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Picture $n\" descr=\"${Xml.esc(cap.ifBlank { im.caption })}\"/>")
        sb.append("<wp:cNvGraphicFramePr><a:graphicFrameLocks xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" noChangeAspect=\"1\"/></wp:cNvGraphicFramePr>")
        sb.append("<a:graphic xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">")
        sb.append("<pic:pic xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpg\"/><pic:cNvPicPr/></pic:nvPicPr>")
        sb.append("<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>")
        sb.append("<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>")
        sb.append("</a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>")
        if (cap.isNotBlank()) sb.append("<w:p><w:pPr><w:spacing w:after=\"200\"/><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:i/><w:color w:val=\"666666\"/><w:sz w:val=\"19\"/></w:rPr><w:t xml:space=\"preserve\">")
            .append(Xml.esc(cap)).append("</w:t></w:r></w:p>")
        else sb.append("<w:p><w:pPr><w:spacing w:after=\"120\"/></w:pPr></w:p>")
        return sb.toString()
    }

    private class Ctx {
        val usedImages = ArrayList<DocImage>()
        fun imageNumber(im: DocImage): Int { var i = usedImages.indexOf(im); if (i < 0) { usedImages.add(im); i = usedImages.size - 1 }; return i + 1 }
        val links = ArrayList<String>()
        val orderedNumIds = ArrayList<Int>()
        var currentOrdered = 2
        fun newOrderedList() { currentOrdered = 2 + orderedNumIds.size; orderedNumIds.add(currentOrdered) }
        fun linkId(url: String): String {
            var idx = links.indexOf(url)
            if (idx < 0) { links.add(url); idx = links.size - 1 }
            return "rIdLink${idx + 1}"
        }
    }

    private fun contentTypes() = Xml.HEADER + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Default Extension=\"jpg\" ContentType=\"image/jpeg\"/>" +
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
        "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
        "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>" +
        "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    // ── Runs ────────────────────────────────────────────────────────────────

    private fun runs(list: List<Run>, ctx: Ctx, forceBold: Boolean = false, color: String? = null): String {
        val sb = StringBuilder()
        for (r in list) {
            if (r.text.isEmpty()) continue
            val pr = StringBuilder()
            if (r.link != null) pr.append("<w:rStyle w:val=\"Hyperlink\"/>")
            if (r.code) pr.append("<w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/>")
            if (r.bold || forceBold) pr.append("<w:b/>")
            if (r.italic) pr.append("<w:i/>")
            if (r.strike) pr.append("<w:strike/>")
            if (color != null) pr.append("<w:color w:val=\"$color\"/>")
            if (r.code) pr.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/>")
            val rPr = if (pr.isEmpty()) "" else "<w:rPr>$pr</w:rPr>"
            val inner = StringBuilder()
            val parts = r.text.split("\n")
            parts.forEachIndexed { i, part ->
                if (i > 0) inner.append("<w:r>").append(rPr).append("<w:br/></w:r>")
                if (part.isNotEmpty()) inner.append("<w:r>").append(rPr).append("<w:t xml:space=\"preserve\">").append(Xml.esc(part)).append("</w:t></w:r>")
            }
            if (r.link != null && (r.link.startsWith("http", true) || r.link.startsWith("mailto:", true))) {
                sb.append("<w:hyperlink r:id=\"").append(ctx.linkId(r.link)).append("\" w:history=\"1\">").append(inner).append("</w:hyperlink>")
            } else sb.append(inner)
        }
        return sb.toString()
    }

    // ── Tables ──────────────────────────────────────────────────────────────

    private fun colWidths(t: Table): List<Int> {
        val n = t.header.size
        if (n == 0) return emptyList()
        val weights = DoubleArray(n) { c ->
            var m = t.header[c].plain().length
            for (r in t.rows) m = maxOf(m, r[c].plain().split("\n").maxOf { it.length })
            m.coerceIn(4, 40).toDouble()
        }
        val sum = weights.sum()
        val w = IntArray(n) { ((weights[it] / sum) * CONTENT_W).toInt().coerceAtLeast(500) }
        val diff = CONTENT_W - w.sum()
        w[w.indices.maxByOrNull { w[it] }!!] += diff
        return w.toList()
    }

    private fun table(t: Table, ctx: Ctx, theme: Theme): String {
        val widths = colWidths(t)
        if (widths.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("<w:tbl><w:tblPr><w:tblStyle w:val=\"TableGrid\"/><w:tblW w:w=\"$CONTENT_W\" w:type=\"dxa\"/>")
            .append("<w:tblBorders>")
        for (side in listOf("top", "left", "bottom", "right", "insideH", "insideV"))
            sb.append("<w:$side w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"${theme.accent}\"/>")
        sb.append("</w:tblBorders><w:tblLayout w:type=\"fixed\"/>")
            .append("<w:tblCellMar><w:top w:w=\"60\" w:type=\"dxa\"/><w:left w:w=\"100\" w:type=\"dxa\"/><w:bottom w:w=\"60\" w:type=\"dxa\"/><w:right w:w=\"100\" w:type=\"dxa\"/></w:tblCellMar>")
            .append("</w:tblPr><w:tblGrid>")
        widths.forEach { sb.append("<w:gridCol w:w=\"$it\"/>") }
        sb.append("</w:tblGrid>")

        fun numeric(c: Int) = t.rows.isNotEmpty() && t.rows.all { r -> val s = r[c].plain().trim(); s.isEmpty() || NUM.matches(s) } &&
            t.rows.any { r -> r[c].plain().isNotBlank() }
        val jc = List(widths.size) { c ->
            when (t.aligns.getOrElse(c) { ' ' }) { 'c' -> "center"; 'r' -> "right"; 'l' -> "left"; else -> if (numeric(c)) "right" else "left" }
        }

        fun row(cells: List<List<Run>>, header: Boolean, zebra: Boolean) {
            sb.append("<w:tr><w:trPr><w:cantSplit/>").append(if (header) "<w:tblHeader/>" else "").append("</w:trPr>")
            cells.forEachIndexed { c, runsList ->
                val fill = when { header -> theme.primary; zebra -> theme.light; else -> null }
                sb.append("<w:tc><w:tcPr><w:tcW w:w=\"${widths[c]}\" w:type=\"dxa\"/>")
                if (fill != null) sb.append("<w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/>")
                sb.append("</w:tcPr><w:p><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/><w:jc w:val=\"${if (header) (if (jc[c] == "right") "right" else "left") else jc[c]}\"/></w:pPr>")
                sb.append(runs(runsList, ctx, forceBold = header, color = if (header) "FFFFFF" else null))
                sb.append("</w:p></w:tc>")
            }
            sb.append("</w:tr>")
        }
        row(t.header, true, false)
        t.rows.forEachIndexed { i, r -> row(r, false, i % 2 == 1) }
        sb.append("</w:tbl>")
        return sb.toString()
    }

    private val NUM = Regex("^[-+(]?[$€£₹]?\\s?[\\d,]+(\\.\\d+)?%?\\)?$")

    // ── Styles & numbering ──────────────────────────────────────────────────

    private fun banner(titleRuns: String, theme: Theme): String {
        val premium = theme.tier == Tier.PREMIUM
        val fill = if (premium) theme.primary else theme.light
        val edge = if (premium) "<w:bottom w:val=\"single\" w:sz=\"36\" w:space=\"0\" w:color=\"${theme.accent}\"/>"
        else "<w:left w:val=\"single\" w:sz=\"48\" w:space=\"0\" w:color=\"${theme.accent}\"/>"
        val date = java.time.LocalDate.now().let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}, ${it.year}" }
        return "<w:tbl><w:tblPr><w:tblW w:w=\"$CONTENT_W\" w:type=\"dxa\"/><w:tblBorders>$edge</w:tblBorders><w:tblLayout w:type=\"fixed\"/>" +
            "<w:tblCellMar><w:top w:w=\"${if (premium) 560 else 320}\" w:type=\"dxa\"/><w:left w:w=\"340\" w:type=\"dxa\"/><w:bottom w:w=\"${if (premium) 560 else 320}\" w:type=\"dxa\"/><w:right w:w=\"340\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr>" +
            "<w:tblGrid><w:gridCol w:w=\"$CONTENT_W\"/></w:tblGrid><w:tr><w:tc><w:tcPr><w:tcW w:w=\"$CONTENT_W\" w:type=\"dxa\"/><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"$fill\"/></w:tcPr>" +
            "<w:p><w:pPr><w:pStyle w:val=\"BannerTitle\"/></w:pPr>$titleRuns</w:p>" +
            "<w:p><w:pPr><w:pStyle w:val=\"BannerSub\"/></w:pPr><w:r><w:t>$date</w:t></w:r></w:p></w:tc></w:tr></w:tbl>" +
            "<w:p><w:pPr><w:spacing w:after=\"200\"/></w:pPr></w:p>"
    }

    private fun styles(theme: Theme): String {
        val font = if (theme.serif) "Georgia" else "Calibri"
        val premium = theme.tier == Tier.PREMIUM
        val titleColor = if (premium) "FFFFFF" else theme.primary
        val subColor = if (premium) theme.accent else theme.accent
        fun heading(id: Int, size: Int, color: String, before: Int, after: Int, rule: String? = null) =
            "<w:style w:type=\"paragraph\" w:styleId=\"Heading$id\"><w:name w:val=\"heading $id\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"9\"/><w:qFormat/>" +
                "<w:pPr><w:keepNext/><w:keepLines/>${if (rule != null) "<w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"2\" w:color=\"$rule\"/></w:pBdr>" else ""}<w:spacing w:before=\"$before\" w:after=\"$after\"/><w:outlineLvl w:val=\"${id - 1}\"/></w:pPr>" +
                "<w:rPr><w:b/><w:color w:val=\"$color\"/><w:sz w:val=\"$size\"/><w:szCs w:val=\"$size\"/></w:rPr></w:style>"
        return Xml.HEADER + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"$font\" w:hAnsi=\"$font\" w:eastAsia=\"$font\" w:cs=\"$font\"/><w:color w:val=\"${theme.text}\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"276\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>" +
            "<w:style w:type=\"character\" w:default=\"1\" w:styleId=\"DefaultParagraphFont\"><w:name w:val=\"Default Paragraph Font\"/><w:uiPriority w:val=\"1\"/><w:semiHidden/></w:style>" +
            "<w:style w:type=\"table\" w:default=\"1\" w:styleId=\"TableNormal\"><w:name w:val=\"Normal Table\"/><w:uiPriority w:val=\"99\"/><w:semiHidden/><w:tblPr><w:tblInd w:w=\"0\" w:type=\"dxa\"/><w:tblCellMar><w:top w:w=\"0\" w:type=\"dxa\"/><w:left w:w=\"108\" w:type=\"dxa\"/><w:bottom w:w=\"0\" w:type=\"dxa\"/><w:right w:w=\"108\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr></w:style>" +
            "<w:style w:type=\"table\" w:styleId=\"TableGrid\"><w:name w:val=\"Table Grid\"/><w:basedOn w:val=\"TableNormal\"/><w:uiPriority w:val=\"39\"/><w:tblPr><w:tblBorders>" +
            listOf("top", "left", "bottom", "right", "insideH", "insideV").joinToString("") { "<w:$it w:val=\"single\" w:sz=\"4\" w:space=\"0\" w:color=\"auto\"/>" } +
            "</w:tblBorders></w:tblPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:uiPriority w:val=\"10\"/><w:qFormat/>" +
            "<w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"8\" w:space=\"4\" w:color=\"${theme.accent}\"/></w:pBdr><w:spacing w:before=\"0\" w:after=\"240\"/></w:pPr>" +
            "<w:rPr><w:b/><w:color w:val=\"${theme.heading}\"/><w:sz w:val=\"52\"/><w:szCs w:val=\"52\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"BannerTitle\"><w:name w:val=\"Banner Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:before=\"0\" w:after=\"80\"/></w:pPr><w:rPr><w:b/><w:color w:val=\"$titleColor\"/><w:sz w:val=\"${if (premium) 60 else 52}\"/><w:szCs w:val=\"${if (premium) 60 else 52}\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"BannerSub\"><w:name w:val=\"Banner Subtitle\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:spacing w:before=\"0\" w:after=\"0\"/></w:pPr><w:rPr><w:color w:val=\"$subColor\"/><w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/></w:rPr></w:style>" +
            heading(1, 34, theme.heading, 360, 120, theme.accent) + heading(2, 28, theme.accent, 280, 100) + heading(3, 24, "404040", 200, 80) + heading(4, 22, "595959", 160, 60) +
            "<w:style w:type=\"paragraph\" w:styleId=\"ListParagraph\"><w:name w:val=\"List Paragraph\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"34\"/><w:qFormat/><w:pPr><w:spacing w:after=\"60\"/><w:contextualSpacing/></w:pPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Quote\"><w:name w:val=\"Quote\"/><w:basedOn w:val=\"Normal\"/><w:uiPriority w:val=\"29\"/><w:qFormat/><w:pPr><w:pBdr><w:left w:val=\"single\" w:sz=\"18\" w:space=\"8\" w:color=\"${theme.accent}\"/></w:pBdr><w:ind w:left=\"360\"/></w:pPr><w:rPr><w:i/><w:color w:val=\"595959\"/></w:rPr></w:style>" +
            "<w:style w:type=\"paragraph\" w:styleId=\"Code\"><w:name w:val=\"Code\"/><w:basedOn w:val=\"Normal\"/><w:qFormat/><w:pPr><w:shd w:val=\"clear\" w:color=\"auto\" w:fill=\"F2F2F2\"/><w:spacing w:before=\"0\" w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:ind w:left=\"120\" w:right=\"120\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Consolas\" w:hAnsi=\"Consolas\" w:cs=\"Consolas\"/><w:sz w:val=\"19\"/><w:szCs w:val=\"19\"/></w:rPr></w:style>" +
            "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:uiPriority w:val=\"99\"/><w:rPr><w:color w:val=\"0563C1\"/><w:u w:val=\"single\"/></w:rPr></w:style>" +
            "</w:styles>"
    }

    private fun numbering(ctx: Ctx): String {
        val bullets = listOf("\u2022", "\u25E6", "\u25AA")
        val sb = StringBuilder(Xml.HEADER + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
        // abstract 0 = bullets
        sb.append("<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"${bullets[l % 3]}\"/><w:lvlJc w:val=\"left\"/>")
                .append("<w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:hint=\"default\"/></w:rPr></w:lvl>")
        }
        sb.append("</w:abstractNum>")
        // abstract 1 = decimal / letter / roman
        val fmts = listOf("decimal", "lowerLetter", "lowerRoman")
        sb.append("<w:abstractNum w:abstractNumId=\"1\"><w:multiLevelType w:val=\"hybridMultilevel\"/>")
        for (l in 0..8) {
            sb.append("<w:lvl w:ilvl=\"$l\"><w:start w:val=\"1\"/><w:numFmt w:val=\"${fmts[l % 3]}\"/><w:lvlText w:val=\"%${l + 1}.\"/><w:lvlJc w:val=\"left\"/>")
                .append("<w:pPr><w:ind w:left=\"${720 + l * 360}\" w:hanging=\"360\"/></w:pPr></w:lvl>")
        }
        sb.append("</w:abstractNum>")
        sb.append("<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num>")
        // numId 2.. = one numbered list each so numbering restarts at 1
        val ids = if (ctx.orderedNumIds.isEmpty()) listOf(2) else ctx.orderedNumIds
        for (id in ids) {
            sb.append("<w:num w:numId=\"$id\"><w:abstractNumId w:val=\"1\"/><w:lvlOverride w:ilvl=\"0\"><w:startOverride w:val=\"1\"/></w:lvlOverride></w:num>")
        }
        sb.append("</w:numbering>")
        return sb.toString()
    }
}
