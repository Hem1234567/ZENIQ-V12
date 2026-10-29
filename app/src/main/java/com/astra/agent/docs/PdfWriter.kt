package com.astra.agent.docs

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import java.io.ByteArrayOutputStream

/**
 * Writes a real, selectable-text PDF with Android's built-in PdfDocument: A4, headings, lists, tables with
 * repeating header rows, code blocks, quotes, page numbers. System fonts are used, so Tamil, Hindi, emoji etc. work.
 */
object PdfWriter {

    private const val PW = 595            // A4 in points
    private const val PH = 842
    private const val MX = 50f
    private const val MT = 54f
    private const val MB = 62f
    private const val CW = PW - 2 * MX

    private val TEXT = Color.parseColor("#222222")
    private val GREY = Color.parseColor("#595959")
    private val LINK = Color.parseColor("#0563C1")
    private val GRID = Color.parseColor("#BFBFBF")
    private val CODE_BG = Color.parseColor("#F2F2F2")

    fun write(blocks0: List<Block>, title: String, theme: Theme = Themes.all[0], images: List<DocImage> = emptyList()): ByteArray {
        val blocks = ImagePlacement.place(blocks0, images, slides = false)
        val doc = PdfDocument()
        try {
            val r = Renderer(doc, theme, title, images)
            r.newPage()
            r.render(blocks)
            r.finishPage()
            val bos = ByteArrayOutputStream()
            doc.writeTo(bos)
            return bos.toByteArray()
        } finally {
            doc.close()
        }
    }

    private fun hex(h: String): Int = Color.parseColor("#" + h)

    private class Renderer(val doc: PdfDocument, val theme: Theme, val docTitle: String, val images: List<DocImage>) {
        private val NAVY = hex(theme.heading)
        private val BLUE = hex(theme.accent)
        private val TABLE_HEAD = hex(theme.primary)
        private val ZEBRA = hex(theme.light)
        private val PRIMARY = hex(theme.primary)
        private val ACCENT = hex(theme.accent)
        private val premium = theme.tier == Tier.PREMIUM
        var page: PdfDocument.Page? = null
        var canvas: Canvas = Canvas()
        var y = MT
        var pageNo = 0
        var firstHeading = true
        var inTable: Table? = null          // header is repeated when a table continues on a new page

        private val footer = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#808080"); textSize = 9f; typeface = Typeface.SANS_SERIF }
        private val linePaint = Paint().apply { color = GRID; strokeWidth = 0.6f; style = Paint.Style.STROKE }
        private val fillPaint = Paint().apply { style = Paint.Style.FILL }

        fun newPage() {
            finishPage()
            pageNo++
            val info = PdfDocument.PageInfo.Builder(PW, PH, pageNo).create()
            val p = doc.startPage(info)
            page = p
            canvas = p.canvas
            y = MT
            if (pageNo > 1) {
                val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; textSize = 8.5f; typeface = Typeface.SANS_SERIF }
                canvas.drawText(docTitle.take(70), MX, 34f, hp)
                val lp = Paint().apply { color = ACCENT; strokeWidth = 0.8f; style = Paint.Style.STROKE }
                canvas.drawLine(MX, 41f, MX + CW, 41f, lp)
            }
        }

        fun finishPage() {
            val p = page ?: return
            val label = "Page $pageNo"
            val fl = Paint().apply { color = ACCENT; strokeWidth = 0.8f; style = Paint.Style.STROKE }
            canvas.drawLine(MX, PH - 42f, MX + CW, PH - 42f, fl)
            footer.color = ACCENT
            canvas.drawText(docTitle.take(60), MX, PH - 28f, footer)
            canvas.drawText(label, MX + CW - footer.measureText(label), PH - 28f, footer)
            doc.finishPage(p)
            page = null
        }

        private fun remaining() = PH - MB - y

        fun render(blocks: List<Block>) {
            for ((i, b) in blocks.withIndex()) {
                val next = blocks.getOrNull(i + 1)
                when (b) {
                    is Heading -> heading(b, next)
                    is Para -> paragraph(b.runs, 11f, TEXT, after = 7f)
                    is ListItem -> listItem(b)
                    is Table -> table(b)
                    is CodeBlock -> code(b)
                    is Quote -> quote(b)
                    Rule -> { ensure(14f); y += 5f; linePaint.strokeWidth = 0.8f; canvas.drawLine(MX, y, MX + CW, y, linePaint); y += 9f }
                    PageBreak -> if (y > MT + 1f) newPage()
                    is ImageBlock -> picture(b)
                }
            }
        }

        /** An attached picture, centred, scaled to the text width, with its caption. */
        private fun picture(b: ImageBlock) {
            val im = images.getOrNull(b.index) ?: return
            val bmp = try { android.graphics.BitmapFactory.decodeByteArray(im.bytes, 0, im.bytes.size) } catch (_: Throwable) { null } ?: return
            val maxH = PH - MT - MB - 60f
            val (dw, dh) = im.fit(CW.toDouble(), minOf(420.0, maxH.toDouble()))
            val w = dw.toFloat(); val h = dh.toFloat()
            ensure(h + 34f)
            val left = MX + (CW - w) / 2f
            canvas.drawBitmap(bmp, null, android.graphics.RectF(left, y, left + w, y + h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            y += h + 6f
            val cap = b.caption.ifBlank { "" }
            if (cap.isNotBlank()) {
                val lay = layout(spannable(listOf(Run(cap, italic = true))), basePaint(9.5f, GREY), CW)
                drawLayout(lay, MX)
            }
            y += 12f
            bmp.recycle()
        }

        private fun ensure(h: Float) { if (y + h > PH - MB && y > MT + 1f) newPage() }

        // ── text building ────────────────────────────────────────────────

        private fun basePaint(size: Float, color: Int, bold: Boolean = false, mono: Boolean = false): TextPaint {
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
            tp.textSize = size
            tp.color = color
            tp.typeface = when {
                mono -> Typeface.MONOSPACE
                bold -> Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                else -> Typeface.SANS_SERIF
            }
            return tp
        }

        private fun spannable(runs: List<Run>, forceBold: Boolean = false, color: Int? = null): SpannableStringBuilder {
            val sb = SpannableStringBuilder()
            for (r in runs) {
                if (r.text.isEmpty()) continue
                val start = sb.length
                sb.append(r.text)
                val end = sb.length
                val bold = r.bold || forceBold
                if (bold && r.italic) sb.setSpan(StyleSpan(Typeface.BOLD_ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else if (bold) sb.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                else if (r.italic) sb.setSpan(StyleSpan(Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.code) {
                    sb.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(CODE_BG), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (r.strike) sb.setSpan(StrikethroughSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.link != null) {
                    sb.setSpan(ForegroundColorSpan(LINK), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(UnderlineSpan(), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else if (color != null) {
                    sb.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            return sb
        }

        private fun layout(text: CharSequence, paint: TextPaint, width: Float, align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL, spacing: Float = 1.22f): StaticLayout {
            val w = width.toInt().coerceAtLeast(10)
            val src: CharSequence = if (text.isEmpty()) " " else text
            return StaticLayout.Builder.obtain(src, 0, src.length, paint, w)
                .setAlignment(align)
                .setLineSpacing(0f, spacing)
                .setIncludePad(false)
                .build()
        }

        /** Draws [lay] at x, splitting it over pages line by line. */
        private fun drawLayout(lay: StaticLayout, x: Float, bg: Int? = null, bgX: Float = x, bgW: Float = lay.width.toFloat()) {
            var line = 0
            val n = lay.lineCount
            while (line < n) {
                val avail = remaining()
                var end = line
                while (end < n && lay.getLineBottom(end) - lay.getLineTop(line) <= avail) end++
                if (end == line) {
                    if (y > MT + 1f) { newPage(); continue }
                    end = line + 1
                }
                val h = (lay.getLineBottom(end - 1) - lay.getLineTop(line)).toFloat()
                if (bg != null) { fillPaint.color = bg; canvas.drawRect(bgX, y, bgX + bgW, y + h, fillPaint) }
                canvas.save()
                canvas.clipRect(x, y, x + lay.width, y + h)
                canvas.translate(x, y - lay.getLineTop(line))
                lay.draw(canvas)
                canvas.restore()
                y += h
                line = end
                if (line < n) newPage()
            }
        }

        // ── blocks ───────────────────────────────────────────────────────

        private fun paragraph(runs: List<Run>, size: Float, color: Int, after: Float) {
            val lay = layout(spannable(runs), basePaint(size, color), CW)
            drawLayout(lay, MX)
            y += after
        }

        private fun heading(h: Heading, next: Block?) {
            val isTitle = h.level == 1 && firstHeading
            if (h.level == 1) firstHeading = false
            if (isTitle && pageNo == 1 && y <= MT + 1f) { cover(h); return }
            val size = when { isTitle -> 24f; h.level == 1 -> 18f; h.level == 2 -> 14.5f; h.level == 3 -> 12.5f; else -> 11.5f }
            val color = when { h.level <= 1 -> NAVY; h.level == 2 -> BLUE; else -> Color.parseColor("#404040") }
            val lay = layout(spannable(h.runs, forceBold = true), basePaint(size, color, bold = true), CW, spacing = 1.12f)
            val before = if (isTitle) 0f else when (h.level) { 1 -> 16f; 2 -> 13f; else -> 9f }
            if (y > MT + 1f) y += before
            ensure(lay.height + 46f)      // keep heading together with the text after it
            drawLayout(lay, MX)
            if (isTitle || h.level == 1) {
                y += 4f; linePaint.color = if (isTitle) TABLE_HEAD else ACCENT; linePaint.strokeWidth = if (isTitle) 1.6f else 1.0f
                canvas.drawLine(MX, y, MX + CW, y, linePaint); linePaint.color = GRID; linePaint.strokeWidth = 0.6f
                y += 10f
            } else y += 5f
        }

        /** Designed title block at the top of the first page. */
        private fun cover(h: Heading) {
            val textColor = if (premium) Color.WHITE else PRIMARY
            val size = if (premium) 30f else 26f
            val pad = if (premium) 64f else 40f
            val lay = layout(spannable(h.runs, forceBold = true), basePaint(size, textColor, bold = true), CW - 10f, spacing = 1.1f)
            val date = java.time.LocalDate.now().let { "${it.month.name.lowercase().replaceFirstChar { c -> c.uppercase() }} ${it.dayOfMonth}, ${it.year}" }
            val bandH = pad + lay.height + 30f + pad * 0.6f
            fillPaint.color = if (premium) PRIMARY else ZEBRA
            canvas.drawRect(0f, 0f, PW.toFloat(), bandH, fillPaint)
            fillPaint.color = ACCENT
            if (premium) canvas.drawRect(0f, bandH, PW.toFloat(), bandH + 5f, fillPaint)
            else canvas.drawRect(0f, 0f, 9f, bandH, fillPaint)
            y = pad
            drawLayout(lay, MX)
            val dp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (premium) ACCENT else GREY; textSize = 10.5f; typeface = Typeface.SANS_SERIF }
            canvas.drawText(date, MX, y + 18f, dp)
            y = bandH + 26f
        }

        private fun listItem(li: ListItem) {
            val indent = 8f + li.level * 18f
            val markerW = if (li.ordered) 22f else 14f
            val paint = basePaint(11f, TEXT)
            val lay = layout(spannable(li.runs), paint, CW - indent - markerW)
            ensure(minOf(lay.height.toFloat(), 40f))
            val marker = if (li.ordered) "${li.number}." else if (li.level % 2 == 0) "\u2022" else "\u2013"
            val mx = if (li.ordered) MX + indent + markerW - 5f - paint.measureText(marker) else MX + indent
            val baseline = y + lay.getLineBaseline(0)
            canvas.drawText(marker, mx, baseline, paint)
            drawLayout(lay, MX + indent + markerW)
            y += 3f
        }

        private fun quote(q: Quote) {
            val paint = basePaint(11f, GREY)
            val lay = layout(spannable(q.runs.map { it.copy(italic = true) }), paint, CW - 18f)
            ensure(minOf(lay.height.toFloat(), 40f))
            val top = y
            drawLayout(lay, MX + 14f)
            if (pageNo > 0) {
                linePaint.color = Color.parseColor("#9DB7D5"); linePaint.strokeWidth = 2.4f
                val from = if (y >= top) top else MT
                canvas.drawLine(MX + 4f, from, MX + 4f, y, linePaint)
                linePaint.color = GRID; linePaint.strokeWidth = 0.6f
            }
            y += 8f
        }

        private fun code(c: CodeBlock) {
            val paint = basePaint(9f, Color.parseColor("#1E1E1E"), mono = true)
            val text = c.text.replace("\t", "    ")
            val lay = layout(text, paint, CW - 16f, spacing = 1.15f)
            y += 3f
            ensure(minOf(lay.height.toFloat() + 8f, 60f))
            drawLayout(lay, MX + 8f, bg = CODE_BG, bgX = MX, bgW = CW)
            y += 9f
        }

        // ── tables ───────────────────────────────────────────────────────

        private val numRe = Regex("^[-+(]?[$€£₹]?\\s?[\\d,]+(\\.\\d+)?%?\\)?$")

        private fun table(t: Table) {
            val n = t.header.size
            if (n == 0) return
            val fs = if (n > 6) 8.5f else if (n > 4) 9.5f else 10f
            val pad = 5f
            val headPaint = basePaint(fs, Color.WHITE, bold = true)
            val bodyPaint = basePaint(fs, TEXT)

            // column widths: between the longest word and the longest line, filling the page width
            val minW = FloatArray(n); val prefW = FloatArray(n)
            fun measure(runs: List<Run>, c: Int, p: TextPaint) {
                val text = runs.plain()
                var longestLine = 0f; var longestWord = 0f
                for (ln in text.split("\n")) {
                    longestLine = maxOf(longestLine, p.measureText(ln))
                    for (w in ln.split(" ")) longestWord = maxOf(longestWord, p.measureText(w))
                }
                minW[c] = maxOf(minW[c], minOf(longestWord, 120f)); prefW[c] = maxOf(prefW[c], longestLine)
            }
            t.header.forEachIndexed { c, r -> measure(r, c, headPaint) }
            t.rows.forEach { row -> row.forEachIndexed { c, r -> measure(r, c, bodyPaint) } }
            for (c in 0 until n) { minW[c] += 2 * pad + 2f; prefW[c] += 2 * pad + 2f; if (prefW[c] < minW[c]) prefW[c] = minW[c] }
            val widths = FloatArray(n)
            val sumPref = prefW.sum(); val sumMin = minW.sum()
            if (sumPref <= CW) {
                for (c in 0 until n) widths[c] = prefW[c] + (CW - sumPref) * (prefW[c] / sumPref)
            } else if (sumMin >= CW) {
                for (c in 0 until n) widths[c] = minW[c] * CW / sumMin
            } else {
                val extra = CW - sumMin
                val want = FloatArray(n) { prefW[it] - minW[it] }
                val sumWant = want.sum().coerceAtLeast(1f)
                for (c in 0 until n) widths[c] = minW[c] + extra * want[c] / sumWant
            }

            val numeric = BooleanArray(n) { c ->
                t.rows.isNotEmpty() && t.rows.any { it[c].plain().isNotBlank() } &&
                    t.rows.all { r -> val s = r[c].plain().trim(); s.isEmpty() || numRe.matches(s) }
            }
            fun align(c: Int, header: Boolean): Layout.Alignment = when (t.aligns.getOrElse(c) { ' ' }) {
                'c' -> Layout.Alignment.ALIGN_CENTER
                'r' -> Layout.Alignment.ALIGN_OPPOSITE
                'l' -> Layout.Alignment.ALIGN_NORMAL
                else -> if (numeric[c]) Layout.Alignment.ALIGN_OPPOSITE else Layout.Alignment.ALIGN_NORMAL
            }

            fun buildRow(cells: List<List<Run>>, header: Boolean): Pair<List<StaticLayout>, Float> {
                val lays = cells.mapIndexed { c, runs ->
                    layout(spannable(runs, forceBold = header), if (header) headPaint else bodyPaint, widths[c] - 2 * pad, align(c, header), spacing = 1.15f)
                }
                return lays to (lays.maxOf { it.height } + 2 * 4f)
            }

            fun drawRow(lays: List<StaticLayout>, h: Float, header: Boolean, zebra: Boolean) {
                // a row taller than a full page is cut at the page end rather than looping forever
                val rowH = minOf(h, PH - MB - MT)
                var x = MX
                for (c in 0 until n) {
                    val fill = if (header) TABLE_HEAD else if (zebra) ZEBRA else Color.WHITE
                    fillPaint.color = fill
                    canvas.drawRect(x, y, x + widths[c], y + rowH, fillPaint)
                    canvas.drawRect(x, y, x + widths[c], y + rowH, linePaint)
                    canvas.save()
                    canvas.clipRect(x, y, x + widths[c], y + rowH)
                    canvas.translate(x + pad, y + 4f)
                    lays[c].draw(canvas)
                    canvas.restore()
                    x += widths[c]
                }
                y += rowH
            }

            val (hl, hh) = buildRow(t.header, true)
            y += 2f
            ensure(hh + 40f)
            drawRow(hl, hh, header = true, zebra = false)
            t.rows.forEachIndexed { i, row ->
                val (lays, h) = buildRow(row, false)
                if (y + h > PH - MB) {
                    newPage()
                    drawRow(hl, hh, header = true, zebra = false)       // repeat header row
                }
                drawRow(lays, h, header = false, zebra = i % 2 == 1)
            }
            y += 10f
        }
    }
}
