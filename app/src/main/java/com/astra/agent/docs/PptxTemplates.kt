package com.astra.agent.docs

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Fills the bundled, professionally designed PowerPoint templates with the AI's content.
 *
 * The AI writes Markdown, [plan] turns it into logical slides (cover, content slides, closing), every logical slide is
 * matched to the template slide that fits it best (number of cards / list entries), that slide is cloned, its sample
 * text is replaced, and the font size is reduced so the text fits the shape it sits in. Slides of the template that
 * are not needed are dropped together with their pictures, so the result is a normal, editable .pptx.
 *
 * Pure JVM code (java.util.zip + org.w3c.dom): no Android dependency.
 */
internal object PptxTemplates {

    private const val NS_A = "http://schemas.openxmlformats.org/drawingml/2006/main"
    private const val NS_P = "http://schemas.openxmlformats.org/presentationml/2006/main"
    private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private const val NS_REL = "http://schemas.openxmlformats.org/package/2006/relationships"
    private const val NS_CT = "http://schemas.openxmlformats.org/package/2006/content-types"
    private const val REL_SLIDE = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val CT_SLIDE = "application/vnd.openxmlformats-officedocument.presentationml.slide+xml"
    private const val MAX_SLIDES = 40

    // ── Content planning ─────────────────────────────────────────────────────

    class Item(var head: String?, var body: String)
    class LSlide(val title: String, var lead: String?, val items: MutableList<Item> = ArrayList(), val pics: MutableList<Int> = ArrayList())
    class Plan(val coverTitle: String, val coverSub: String?, val slides: List<LSlide>, val closingTitle: String)

    private val closingRe = Regex("^(thank|thanks|questions|q ?& ?a|contact|get in touch|the end)", RegexOption.IGNORE_CASE)
    private val colonRe = Regex("^([^:]{2,42}):\\s+(.{3,})$")
    private val dashRe = Regex("^(.{2,42}?)\\s[\\u2013\\u2014-]\\s(.{3,})$")

    internal fun toItem(runs: List<Run>): Item {
        val text = runs.plain().trim()
        if (runs.size > 1 && runs[0].bold) {
            val h = runs[0].text.trim().trimEnd(':', '-', '\u2013', '\u2014', '.').trim()
            val rest = runs.drop(1).plain().trim().trimStart(':', '-', '\u2013', '\u2014').trim()
            if (h.isNotEmpty() && h.length <= 60 && rest.isNotEmpty()) return Item(h, rest)
        }
        val c = colonRe.find(text)
        if (c != null && c.groupValues[1].trim().split(" ").size <= 7) return Item(c.groupValues[1].trim(), c.groupValues[2].trim())
        val d = dashRe.find(text)
        if (d != null && d.groupValues[1].trim().split(" ").size <= 7) return Item(d.groupValues[1].trim(), d.groupValues[2].trim())
        return Item(null, text)
    }

    fun plan(blocks: List<Block>, docTitle: String): Plan {
        var coverTitle = docTitle
        var coverSub: String? = null
        val slides = ArrayList<LSlide>()
        var cur: LSlide? = null
        var seenH1 = false
        var group: Item? = null

        fun ensure(): LSlide { val c = cur ?: LSlide(coverTitle, null).also { slides.add(it) }; cur = c; return c }

        for (b in blocks) {
            when (b) {
                is Heading -> {
                    val t = b.runs.plain().trim()
                    if (t.isEmpty()) continue
                    if (b.level == 1 && !seenH1 && slides.isEmpty()) { seenH1 = true; coverTitle = t; cur = null; group = null }
                    else if (b.level <= 2) { cur = LSlide(t, null).also { slides.add(it) }; group = null }
                    else { group = Item(t, "").also { ensure().items.add(it) } }
                }
                is Para -> {
                    val t = b.runs.plain().trim()
                    if (t.isEmpty()) continue
                    val g = group
                    if (cur == null && slides.isEmpty() && coverSub == null) { coverSub = t; continue }
                    val s = ensure()
                    if (g != null) g.body = (g.body + " " + t).trim()
                    else if (s.items.isEmpty() && s.lead == null) s.lead = t
                    else s.items.add(Item(null, t))
                }
                is ListItem -> {
                    val s = ensure()
                    val g = group
                    val t = b.runs.plain().trim()
                    if (t.isEmpty()) continue
                    if (g != null) g.body = if (g.body.isEmpty()) t else g.body + "; " + t
                    else if (b.level > 0 && s.items.isNotEmpty()) { val last = s.items.last(); last.body = (last.body + "; " + t).trim(';', ' ') }
                    else s.items.add(toItem(b.runs))
                }
                is Table -> {
                    val s = ensure(); group = null
                    val cols = b.header.size
                    for (r in b.rows.take(8)) {
                        val head = r.getOrNull(0)?.plain()?.trim().orEmpty()
                        val rest = if (cols == 2) r.getOrNull(1)?.plain()?.trim().orEmpty()
                        else (1 until cols).mapNotNull { c ->
                            val v = r.getOrNull(c)?.plain()?.trim().orEmpty()
                            if (v.isEmpty()) null else b.header[c].plain().trim().let { h -> if (h.isEmpty()) v else "$h: $v" }
                        }.joinToString("; ")
                        if (head.isNotEmpty() || rest.isNotEmpty()) s.items.add(if (rest.isEmpty()) Item(null, head) else Item(head.ifEmpty { null }, rest))
                    }
                }
                is ImageBlock -> ensure().pics.add(b.index)
                is Quote -> { val t = b.runs.plain().trim(); if (t.isNotEmpty()) ensure().items.add(Item(null, t)) }
                is CodeBlock -> { val t = b.text.lines().take(6).joinToString("  ").trim(); if (t.isNotEmpty()) ensure().items.add(Item(null, t.take(240))) }
                else -> {}
            }
        }
        slides.removeAll { it.items.isEmpty() && it.lead == null && it.pics.isEmpty() }
        var closing = "Thank You"
        if (slides.isNotEmpty() && closingRe.containsMatchIn(slides.last().title.trim())) closing = slides.removeAt(slides.size - 1).title.trim()
        for (s in slides) if (s.items.isEmpty() && s.lead != null) { s.items.add(Item(null, s.lead!!)); s.lead = null }
        for (s in slides) for (it in s.items) if (it.body.isBlank() && !it.head.isNullOrBlank()) { it.body = it.head!!; it.head = null }
        return Plan(coverTitle, coverSub, slides, closing)
    }

    // ── Choosing layouts ─────────────────────────────────────────────────────

    /** Splits [k] items over slides: fewest slides, exact layouts preferred, padding a layout costs extra. */
    internal fun partition(k: Int, content: List<TSlide>): List<Int> {
        if (k <= 0 || content.isEmpty()) return emptyList()
        val maxN = content.maxOf { it.maxCount }
        val cost = DoubleArray(k + 1) { Double.MAX_VALUE }
        val take = IntArray(k + 1)
        cost[0] = 0.0
        for (n in 1..k) for (p in 1..minOf(n, maxN)) {
            if (cost[n - p] == Double.MAX_VALUE) continue
            val exact = content.any { it.minCount <= p && p <= it.maxCount }
            val c = cost[n - p] + if (exact) 1.0 else 1.6 + (1.0 / p) * 0.1
            if (c < cost[n] - 1e-9 || (Math.abs(c - cost[n]) < 1e-9 && p > take[n])) { cost[n] = c; take[n] = p }
        }
        val out = ArrayList<Int>()
        var n = k
        while (n > 0) { out.add(take[n]); n -= take[n] }
        // keep chunk sizes balanced: biggest first looks best, but never leave a single orphan if avoidable
        return out.sortedDescending()
    }

    private fun pickLayout(p: Int, content: List<TSlide>, used: MutableMap<Int, Int>, rot: Int): TSlide {
        var cands = content.filter { it.minCount <= p && p <= it.maxCount }
        if (cands.isEmpty()) cands = content.filter { it.maxCount >= p }.sortedBy { it.maxCount }.take(2)
        if (cands.isEmpty()) cands = content
        val best = cands.minWithOrNull(compareBy<TSlide>({ used[it.idx] ?: 0 }, { (it.idx + rot) % 97 }))!!
        used[best.idx] = (used[best.idx] ?: 0) + 1
        return best
    }

    // ── Public entry ─────────────────────────────────────────────────────────

    fun write(blocks0: List<Block>, docTitle: String, tpl: TplDef, load: (String) -> ByteArray?, today: LocalDate = LocalDate.now(), seed: Int = 0, images: List<DocImage> = emptyList()): ByteArray? {
        val blocks = ImagePlacement.place(blocks0, images, slides = true)
        val bytes = load(tpl.file) ?: return null
        val pkg = readZip(bytes)
        val pl = plan(blocks, docTitle)
        val short = shortTitle(pl.coverTitle)

        val coverT = tpl.slides.first { it.kind == K.COVER }
        val closeT = tpl.slides.firstOrNull { it.kind == K.CLOSING } ?: coverT
        val content = tpl.slides.filter { it.kind == K.PAIRS || it.kind == K.LIST }
        val used = HashMap<Int, Int>()

        class Out(val t: TSlide, val pic: DocImage? = null, val fill: (Document, Int) -> Unit)
        val outs = ArrayList<Out>()
        outs.add(Out(coverT) { d, n -> fillCover(d, coverT, pl.coverTitle, pl.coverSub ?: longDate(today), short, today, n) })

        if (content.isNotEmpty()) {
            for (ls in pl.slides) {
                if (outs.size >= MAX_SLIDES - 1) break
                val items = ls.items.toMutableList()
                if (items.isEmpty() && ls.lead == null) items.clear()
                val sizes = partition(items.size, content)
                var pos = 0
                for ((ci, p) in sizes.withIndex()) {
                    val chunk = items.subList(pos, minOf(items.size, pos + p)).toList(); pos += p
                    val lay = pickLayout(p, content, used, seed)
                    val title = if (ci == 0) ls.title else ls.title + " (cont.)"
                    val lead = if (ci == 0) ls.lead else null
                    outs.add(Out(lay) { d, n -> fillContent(d, lay, title, lead, chunk, short, today, n) })
                    if (outs.size >= MAX_SLIDES - 1) break
                }
                // each attached picture gets its own slide: the layout's text is cleared and the picture is centred below the title
                for (pi in ls.pics) {
                    val im = images.getOrNull(pi) ?: continue
                    if (outs.size >= MAX_SLIDES - 1) break
                    val lay = pickPictureLayout(content, used)
                    outs.add(Out(lay, im) { d, n -> fillPicture(d, lay, ls.title, short, today, n) })
                }
            }
        }
        outs.add(Out(closeT) { d, n -> fillCover(d, closeT, pl.closingTitle, null, short, today, n) })

        // template slide parts in presentation order
        val srcParts = slideParts(pkg)
        val (slideW, slideH) = slideSize(pkg)
        val slideXml = outs.mapIndexed { i, o ->
            val part = srcParts.getOrNull(o.t.idx - 1) ?: error("Template slide ${o.t.idx} missing in ${tpl.file}")
            val doc = parseXml(pkg[part] ?: error("missing $part"))
            o.fill(doc, i + 1)
            if (o.pic != null) addPicture(doc, o.pic, slideW, slideH)
            SlideOut(part, serialize(doc), o.pic)
        }
        return assemble(pkg, srcParts, slideXml, pl.coverTitle)
    }

    // ── Filling slides ───────────────────────────────────────────────────────

    private fun fillCover(d: Document, t: TSlide, title: String, sub: String?, short: String, today: LocalDate, page: Int) {
        setText(d, t.title, listOf(cased(title, t.title)), 0.28f)
        if (t.sub != null) setText(d, t.sub, if (sub.isNullOrBlank()) emptyList() else listOf(cased(sub, t.sub)), 0.45f)
        applyDeco(d, t.deco, short, today, page)
    }

    private fun fillContent(d: Document, t: TSlide, title: String, lead: String?, items: List<Item>, short: String, today: LocalDate, page: Int) {
        setText(d, t.title, listOf(cased(title, t.title)), 0.32f)
        var rest = items
        if (t.lead != null) setText(d, t.lead, if (lead.isNullOrBlank()) emptyList() else listOf(lead), 0.45f)
        else if (!lead.isNullOrBlank()) rest = listOf(Item(null, lead)) + items
        if (t.kind == K.PAIRS) {
            val n = t.pairs.size
            rest = expand(rest, n)
            if (rest.size > n && t.multi) rest = group(rest, n)
            for ((i, ps) in t.pairs.withIndex()) {
                val it = rest.getOrNull(i)
                if (it == null) {
                    ps.h?.let { setText(d, it, emptyList(), 0.5f) }
                    setText(d, ps.b, emptyList(), 0.5f)
                    continue
                }
                if (ps.h != null) {
                    if (it.head == null && wordCount(it.body) <= 7) {
                        // a short bullet becomes a card title on its own
                        setText(d, ps.h, listOf(cased(it.body, ps.h)), 0.5f)
                        setText(d, ps.b, emptyList(), 0.5f)
                    } else {
                        val head = it.head ?: derivedHead(it.body.trimStart('\u2022', ' '))
                        setText(d, ps.h, listOf(cased(head, ps.h)), 0.5f)
                        setText(d, ps.b, bodyLines(it.body), 0.45f)
                    }
                } else {
                    val text = if (it.head != null) it.head + ": " + it.body else it.body
                    setText(d, ps.b, bodyLines(text), 0.45f)
                }
            }
        } else {
            val n = t.items.size
            rest = expand(rest, n)
            for ((i, box) in t.items.withIndex()) {
                val it = rest.getOrNull(i)
                val text = if (it == null) "" else (it.head ?: it.body)
                setText(d, box, if (text.isEmpty()) emptyList() else listOf(cased(text, box)), 0.4f)
            }
        }
        applyDeco(d, t.deco, short, today, page)
    }

    /** More items than text boxes on a plain text slide: several items share a box as bullet lines. */
    private fun group(items: List<Item>, n: Int): List<Item> {
        val out = ArrayList<Item>()
        val base = items.size / n
        val extra = items.size % n
        var pos = 0
        for (i in 0 until n) {
            val c = base + if (i < extra) 1 else 0
            val g = items.subList(pos, pos + c); pos += c
            if (g.size == 1) { out.add(g[0]); continue }
            val head = g[0].head
            val lines = ArrayList<String>()
            lines.add(if (head != null) g[0].body else "\u2022 " + g[0].body)
            for (x in g.drop(1)) lines.add("\u2022 " + (if (x.head != null) x.head + ": " else "") + x.body)
            out.add(Item(head, lines.joinToString("\n")))
        }
        return out
    }

    private fun bodyLines(s: String): List<String> = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** More slots than items: split the longest items at sentence boundaries so fewer slots stay empty. */
    private fun expand(items: List<Item>, n: Int): List<Item> {
        val list = items.toMutableList()
        var guard = 0
        while (list.size < n && guard++ < 12) {
            var bi = -1; var best = 0
            for ((i, it) in list.withIndex()) {
                val parts = sentences(it.body)
                if (parts.size >= 2 && it.body.length > best) { best = it.body.length; bi = i }
            }
            if (bi < 0) break
            val it = list[bi]
            val parts = sentences(it.body)
            val half = (parts.size + 1) / 2
            val a = parts.subList(0, half).joinToString(" ")
            val b = parts.subList(half, parts.size).joinToString(" ")
            list[bi] = Item(it.head, a)
            list.add(bi + 1, Item(null, b))
        }
        return list
    }

    private fun sentences(s: String): List<String> = s.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    private val stopWords = setOf("a", "an", "the", "is", "are", "was", "were", "of", "to", "in", "on", "for", "and", "or", "with", "by", "that", "it", "its", "as", "at", "from", "be", "can", "will", "this", "these", "those", "has", "have")

    private fun derivedHead(body: String): String {
        val first = body.split(Regex("[.:;!?,]")).first().trim()
        val w = first.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(4).toMutableList()
        while (w.size > 1 && w.last().lowercase().trim(',', ';') in stopWords) w.removeAt(w.size - 1)
        return w.joinToString(" ").trimEnd(',', '-', ';')
    }

    private fun wordCount(s: String) = s.trim().split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun cased(s: String, b: Box): String {
        val t = s.trim()
        return if (b.up) t.uppercase() else t
    }

    private val dateRe = Regex(
        "\\b\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}\\b|" +
        "\\b(\\d{1,2}\\s+)?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s*(\\d{1,2},?\\s*)?\\d{4}\\b",
        RegexOption.IGNORE_CASE)
    private val yearRe = Regex("(19|20)\\d\\d")
    private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    internal fun longDate(d: LocalDate) = "${months[d.monthValue - 1]} ${d.dayOfMonth}, ${d.year}"

    private fun formatDateLike(orig: String, d: LocalDate): String {
        val upper = orig.any { it.isLetter() } && orig.filter { it.isLetter() } == orig.filter { it.isLetter() }.uppercase()
        val s = when {
            Regex("^\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4}$").matches(orig.trim()) -> {
                val sep = orig.first { it == '/' || it == '.' || it == '-' }
                "%02d%s%02d%s%d".format(d.dayOfMonth, sep, d.monthValue, sep, d.year)
            }
            Regex("^\\d{1,2}\\s", RegexOption.IGNORE_CASE).containsMatchIn(orig.trim()) -> "${d.dayOfMonth} ${months[d.monthValue - 1]} ${d.year}"
            Regex("[A-Za-z]+\\.?\\s*\\d{1,2},", RegexOption.IGNORE_CASE).containsMatchIn(orig) -> longDate(d)
            else -> {
                val m = months[d.monthValue - 1]
                val letters = Regex("[A-Za-z]+").find(orig)?.value?.length ?: 4
                (if (letters <= 3) m.take(3) else m) + " " + d.year
            }
        }
        return if (upper) s.uppercase() else s
    }

    private fun shortTitle(t: String): String {
        val clean = t.trim()
        if (clean.length <= 24) return clean
        val cut = clean.take(24)
        val sp = cut.lastIndexOf(' ')
        return (if (sp >= 8) cut.substring(0, sp) else cut).trim()
    }

    private fun applyDeco(d: Document, deco: List<Deco>, short: String, today: LocalDate, page: Int) {
        for (x in deco) {
            val sp = findSp(d, x.id) ?: continue
            val orig = shapeText(sp)
            val upper = orig.any { it.isLetter() } && orig.filter { it.isLetter() } == orig.filter { it.isLetter() }.uppercase()
            val text: String = when {
                x.tok == "BLANK" -> ""
                x.tok == "TITLE" -> if (upper) short.uppercase() else short
                x.tok == "DATE" -> dateRe.replace(orig) { m -> formatDateLike(m.value, today) }
                x.tok == "YEAR" -> yearRe.replace(orig, today.year.toString())
                x.tok == "PAGE" -> if (orig.trim().startsWith("0")) "%02d".format(page) else page.toString()
                x.tok.startsWith("TXT:") -> x.tok.substring(4)
                else -> orig
            }
            replaceKeepingStyle(d, sp, text)
        }
    }

    // ── Text in shapes ───────────────────────────────────────────────────────

    private fun clean(s: String): String = buildString { for (c in s) if (c >= ' ' || c == '\t') append(c) else if (c == '\n') append(' ') }

    private fun charWidth(text: String, bold: Boolean): Float {
        val letters = text.filter { it.isLetter() }
        val upper = letters.isNotEmpty() && letters.count { it.isUpperCase() } >= letters.length * 0.7
        return (if (upper) 0.76f else 0.62f) + (if (bold) 0.04f else 0f)
    }

    private fun lines(s: String, maxChars: Int): Int {
        if (s.isEmpty()) return 1
        var n = 1; var cur = 0
        for (w in s.split(' ')) {
            val l = w.length
            if (l == 0) continue
            if (l > maxChars) {
                val parts = (l + maxChars - 1) / maxChars
                if (cur > 0) n++
                n += parts - 1
                cur = l - (parts - 1) * maxChars
            } else if (cur == 0) cur = l
            else if (cur + 1 + l <= maxChars) cur += 1 + l
            else { n++; cur = l }
        }
        return n
    }

    private class Fit(val lines: List<String>, val fs: Float)

    private fun fitText(box: Box, input: List<String>, ln: Ln, minScale: Float): Fit {
        val src = input.map { clean(it) }.filter { it.isNotEmpty() }
        if (src.isEmpty()) return Fit(emptyList(), box.fs)
        val wBox = (box.w - 14.4f).coerceAtLeast(20f)
        val hBox = box.h * 1.04f
        fun fits(ls: List<String>, fs: Float): Boolean {
            var total = 0f
            for (l in ls) {
                val cw = charWidth(l, false) * fs
                val maxChars = (wBox / cw).toInt().coerceAtLeast(1)
                val longest = l.split(' ').maxOfOrNull { it.length } ?: 0
                if (longest > maxChars && longest > 5) return false
                val lineH = if (ln.pts != null) ln.pts * fs / box.fs else fs * 1.2f * ln.pct
                total += lines(l, maxChars) * lineH
            }
            total += (ls.size - 1) * fs * 0.25f
            return total <= hBox
        }
        var fs = box.fs
        val floor = box.fs * minScale
        while (!fits(src, fs) && fs > floor) fs = maxOf(fs * 0.93f, floor)
        if (fits(src, fs)) return Fit(src, fs)
        // still too long at the smallest size: cut words off the end
        var ls = src.toMutableList()
        var guard = 0
        while (!fits(ls, floor) && guard++ < 400) {
            val last = ls.last()
            if (ls.size > 1 && last.length < 12) { ls.removeAt(ls.size - 1); continue }
            val cut = (last.length * 0.88f).toInt().coerceAtLeast(1)
            val sp = last.lastIndexOf(' ', cut)
            val base = (if (sp > 3) last.substring(0, sp) else last.substring(0, cut)).trimEnd(',', ';', ':', ' ', '.')
            ls[ls.size - 1] = "$base\u2026"
            if (base.length <= 3) break
        }
        return Fit(ls, floor)
    }

    private fun setText(d: Document, box: Box, text: List<String>, minScale: Float) {
        val sp = findSp(d, box.id) ?: return
        val tx = childOf(sp, "txBody") ?: return
        val fit = fitText(box, text, lineSpacing(tx), minScale)
        writeParagraphs(d, tx, fit.lines, fit.fs, fit.fs / box.fs)
    }

    private fun replaceKeepingStyle(d: Document, sp: Element, text: String) {
        val tx = childOf(sp, "txBody") ?: return
        writeParagraphs(d, tx, if (text.isEmpty()) emptyList() else text.split('\n').map { clean(it) }, null, 1f)
    }

    private fun shapeText(sp: Element): String {
        val ts = sp.getElementsByTagNameNS(NS_A, "t")
        val sb = StringBuilder()
        for (i in 0 until ts.length) sb.append(ts.item(i).textContent)
        return sb.toString()
    }

    /** Line pitch of a shape: either a fixed number of points (scales with the font) or a percentage. */
    private class Ln(val pts: Float?, val pct: Float)

    private fun lineSpacing(tx: Element): Ln {
        val ls = tx.getElementsByTagNameNS(NS_A, "lnSpc")
        if (ls.length == 0) return Ln(null, 1f)
        val e = ls.item(0) as Element
        val pts = childOf(e, "spcPts")
        if (pts != null) { val v = pts.getAttribute("val").toFloatOrNull(); if (v != null && v > 0) return Ln(v / 100f, 1f) }
        val pc = childOf(e, "spcPct")
        if (pc != null) { val v = pc.getAttribute("val").toFloatOrNull(); if (v != null) return Ln(null, (v / 100000f).coerceIn(0.6f, 2.0f)) }
        return Ln(null, 1f)
    }

    private fun scalePts(pPr: Element, scale: Float) {
        if (scale >= 0.999f) return
        val l = pPr.getElementsByTagNameNS(NS_A, "spcPts")
        for (i in 0 until l.length) {
            val e = l.item(i) as Element
            val v = e.getAttribute("val").toFloatOrNull() ?: continue
            e.setAttribute("val", (v * scale).toInt().coerceAtLeast(0).toString())
        }
    }

    private fun writeParagraphs(d: Document, tx: Element, lines: List<String>, fs: Float?, scale: Float) {
        val paras = children(tx, "p")
        val tp = paras.firstOrNull { childOf(it, "r") != null } ?: paras.firstOrNull()
        val pPr = tp?.let { childOf(it, "pPr") }
        val runPr = tp?.let { p -> childOf(p, "r")?.let { r -> childOf(r, "rPr") } ?: childOf(p, "endParaRPr") }
        for (p in paras) tx.removeChild(p)
        // a text shape that grows by itself keeps doing so; a stored fontScale would shrink the text twice
        val body = childOf(tx, "bodyPr")
        if (body != null) {
            for (n in children(body, "normAutofit")) body.removeChild(n)
            if (body.getAttribute("wrap") == "none") body.setAttribute("wrap", "square")
        }
        val list = if (lines.isEmpty()) listOf("") else lines
        for (line in list) {
            val p = d.createElementNS(NS_A, "a:p")
            if (pPr != null) { val c = pPr.cloneNode(true) as Element; scalePts(c, scale); p.appendChild(c) }
            if (line.isNotEmpty()) {
                val r = d.createElementNS(NS_A, "a:r")
                val rp = d.createElementNS(NS_A, "a:rPr")
                if (runPr != null) copyInto(runPr, rp)
                if (fs != null) rp.setAttribute("sz", (fs * 100).toInt().coerceIn(100, 400000).toString())
                r.appendChild(rp)
                val t = d.createElementNS(NS_A, "a:t")
                t.textContent = line
                r.appendChild(t)
                p.appendChild(r)
            } else {
                val e = d.createElementNS(NS_A, "a:endParaRPr")
                if (runPr != null) copyInto(runPr, e)
                if (fs != null) e.setAttribute("sz", (fs * 100).toInt().coerceIn(100, 400000).toString())
                p.appendChild(e)
            }
            tx.appendChild(p)
        }
    }

    private fun copyInto(src: Element, dst: Element) {
        val at = src.attributes
        for (i in 0 until at.length) { val a = at.item(i); dst.setAttribute(a.nodeName, a.nodeValue) }
        var c = src.firstChild
        while (c != null) { dst.appendChild(c.cloneNode(true)); c = c.nextSibling }
    }

    // ── DOM helpers ──────────────────────────────────────────────────────────

    private fun children(e: Element, local: String): List<Element> {
        val out = ArrayList<Element>()
        var c: Node? = e.firstChild
        while (c != null) { if (c is Element && c.localName == local) out.add(c); c = c.nextSibling }
        return out
    }

    private fun childOf(e: Element, local: String): Element? {
        var c: Node? = e.firstChild
        while (c != null) { if (c is Element && c.localName == local) return c; c = c.nextSibling }
        return null
    }

    private fun findSp(d: Document, id: Int): Element? {
        val nodes = d.getElementsByTagNameNS(NS_P, "cNvPr")
        val want = id.toString()
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as Element
            if (e.getAttribute("id") != want) continue
            var p: Node? = e.parentNode
            while (p != null && !(p is Element && p.localName == "sp")) p = p.parentNode
            return p as? Element
        }
        return null
    }

    private fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    private fun serialize(d: Document): ByteArray {
        d.xmlStandalone = true
        val t = TransformerFactory.newInstance().newTransformer()
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        t.setOutputProperty(OutputKeys.INDENT, "no")
        val bos = ByteArrayOutputStream()
        t.transform(DOMSource(d), StreamResult(bos))
        return bos.toByteArray()
    }

    // ── Package handling ─────────────────────────────────────────────────────

    private fun readZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) map[e.name] = z.readBytes()
            }
        }
        return map
    }

    private fun resolve(base: String, target: String): String {
        if (target.startsWith("/")) return target.trimStart('/')
        val dir = base.substringBeforeLast('/', "")
        val parts = ArrayList<String>()
        if (dir.isNotEmpty()) parts.addAll(dir.split('/'))
        for (seg in target.split('/')) when (seg) { "", "." -> {}; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts.add(seg) }
        return parts.joinToString("/")
    }

    /** Slide part names in presentation order. */
    private fun slideParts(pkg: Map<String, ByteArray>): List<String> {
        val pres = parseXml(pkg["ppt/presentation.xml"] ?: error("not a presentation"))
        val rels = parseXml(pkg["ppt/_rels/presentation.xml.rels"] ?: error("no presentation rels"))
        val target = HashMap<String, String>()
        val rl = rels.getElementsByTagNameNS(NS_REL, "Relationship")
        for (i in 0 until rl.length) { val e = rl.item(i) as Element; target[e.getAttribute("Id")] = e.getAttribute("Target") }
        val ids = pres.getElementsByTagNameNS(NS_P, "sldId")
        val out = ArrayList<String>()
        for (i in 0 until ids.length) {
            val rid = (ids.item(i) as Element).getAttributeNS(NS_R, "id")
            val t = target[rid] ?: continue
            out.add(resolve("ppt/presentation.xml", t))
        }
        return out
    }

    private class SlideOut(val part: String, val xml: ByteArray, val pic: DocImage?)

    private fun slideSize(pkg: Map<String, ByteArray>): Pair<Long, Long> {
        return try {
            val d = parseXml(pkg["ppt/presentation.xml"]!!)
            val e = d.getElementsByTagNameNS(NS_P, "sldSz").item(0) as Element
            Pair(e.getAttribute("cx").toLong(), e.getAttribute("cy").toLong())
        } catch (_: Exception) { Pair(12192000L, 6858000L) }
    }

    /** The template layout with the least clutter, used for picture slides. */
    private fun pickPictureLayout(content: List<TSlide>, used: MutableMap<Int, Int>): TSlide {
        val pool = content.filter { it.kind == K.PAIRS && it.minCount <= 1 }.ifEmpty { content }
        val best = pool.minWithOrNull(compareBy<TSlide>({ used[it.idx] ?: 0 }, { it.pairs.size }))!!
        used[best.idx] = (used[best.idx] ?: 0) + 1
        return best
    }

    private fun fillPicture(d: Document, t: TSlide, title: String, short: String, today: LocalDate, page: Int) {
        setText(d, t.title, listOf(cased(title, t.title)), 0.32f)
        t.lead?.let { setText(d, it, emptyList(), 0.45f) }
        for (ps in t.pairs) { ps.h?.let { h -> setText(d, h, emptyList(), 0.5f) }; setText(d, ps.b, emptyList(), 0.5f) }
        for (b in t.items) setText(d, b, emptyList(), 0.4f)
        applyDeco(d, t.deco, short, today, page)
    }

    private fun addPicture(d: Document, im: DocImage, slideW: Long, slideH: Long) {
        val boxW = slideW * 0.86; val boxH = slideH * 0.57
        val (fw, fh) = im.fit(boxW, boxH)
        val x = ((slideW - fw) / 2).toLong(); val y = (slideH * 0.35 + (boxH - fh) / 2).toLong()
        val xml = "<p:pic xmlns:p=\"$NS_P\" xmlns:a=\"$NS_A\" xmlns:r=\"$NS_R\"><p:nvPicPr><p:cNvPr id=\"9001\" name=\"Picture\" descr=\"${Xml.esc(im.caption)}\"/>" +
            "<p:cNvPicPr><a:picLocks noChangeAspect=\"1\"/></p:cNvPicPr><p:nvPr/></p:nvPicPr><p:blipFill><a:blip r:embed=\"rIdZeniqPic\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>" +
            "<p:spPr><a:xfrm><a:off x=\"$x\" y=\"$y\"/><a:ext cx=\"${fw.toLong()}\" cy=\"${fh.toLong()}\"/></a:xfrm><a:prstGeom prst=\"roundRect\"><a:avLst><a:gd name=\"adj\" fmla=\"val 3000\"/></a:avLst></a:prstGeom></p:spPr></p:pic>"
        val frag = parseXml(xml.toByteArray(Charsets.UTF_8))
        val node = d.importNode(frag.documentElement, true)
        (d.getElementsByTagNameNS(NS_P, "spTree").item(0) as Element).appendChild(node)
    }

    private fun assemble(pkg: LinkedHashMap<String, ByteArray>, srcParts: List<String>, slides: List<SlideOut>, title: String): ByteArray {
        val out = LinkedHashMap<String, ByteArray>()
        val srcRels = HashMap<String, ByteArray?>()
        for (p in srcParts) srcRels[p] = pkg[relsName(p)]
        val oldSlides = srcParts.toSet()
        for ((k, v) in pkg) {
            if (k in oldSlides || k.startsWith("ppt/slides/_rels/") || k.startsWith("ppt/notesSlides/")) continue
            out[k] = v
        }
        val names = ArrayList<String>()
        slides.forEachIndexed { i, s ->
            val name = "ppt/slides/slide${i + 1}.xml"
            names.add(name)
            out[name] = s.xml
            val rels = srcRels[s.part]
            val picName = "ppt/media/zeniq_picture${i + 1}.jpg"
            if (s.pic != null) out[picName] = s.pic.bytes
            if (rels != null) out[relsName(name)] = stripNotesRel(rels, if (s.pic != null) "../media/zeniq_picture${i + 1}.jpg" else null)
        }
        // presentation.xml + rels
        val pres = parseXml(out["ppt/presentation.xml"]!!)
        val prels = parseXml(out["ppt/_rels/presentation.xml.rels"]!!)
        val rroot = prels.documentElement
        val old = ArrayList<Element>()
        val rl = prels.getElementsByTagNameNS(NS_REL, "Relationship")
        for (i in 0 until rl.length) { val e = rl.item(i) as Element; if (e.getAttribute("Type") == REL_SLIDE) old.add(e) }
        for (e in old) rroot.removeChild(e)
        val lst = pres.getElementsByTagNameNS(NS_P, "sldIdLst").item(0) as Element
        while (lst.firstChild != null) lst.removeChild(lst.firstChild)
        names.forEachIndexed { i, n ->
            val rid = "rIdAstraSlide${i + 1}"
            val r = prels.createElementNS(NS_REL, "Relationship")
            r.setAttribute("Id", rid); r.setAttribute("Type", REL_SLIDE); r.setAttribute("Target", n.removePrefix("ppt/"))
            rroot.appendChild(r)
            val s = pres.createElementNS(NS_P, "p:sldId")
            s.setAttribute("id", (256 + i).toString())
            s.setAttributeNS(NS_R, "r:id", rid)
            lst.appendChild(s)
        }
        // notes master references would point at removed parts
        out["ppt/presentation.xml"] = serialize(pres)
        out["ppt/_rels/presentation.xml.rels"] = serialize(prels)

        // content types
        val ct = parseXml(out["[Content_Types].xml"]!!)
        val croot = ct.documentElement
        val defs = ct.getElementsByTagNameNS(NS_CT, "Default")
        var hasJpg = false
        for (i in 0 until defs.length) { val e = defs.item(i) as Element; if (e.getAttribute("Extension").equals("jpg", true)) hasJpg = true }
        if (!hasJpg) {
            val e = ct.createElementNS(NS_CT, "Default")
            e.setAttribute("Extension", "jpg"); e.setAttribute("ContentType", "image/jpeg")
            croot.insertBefore(e, croot.firstChild)
        }
        val ov = ct.getElementsByTagNameNS(NS_CT, "Override")
        val drop = ArrayList<Element>()
        for (i in 0 until ov.length) {
            val e = ov.item(i) as Element
            val pn = e.getAttribute("PartName")
            if (pn.startsWith("/ppt/slides/slide") || pn.startsWith("/ppt/notesSlides/")) drop.add(e)
        }
        for (e in drop) croot.removeChild(e)
        names.forEach { n ->
            val e = ct.createElementNS(NS_CT, "Override")
            e.setAttribute("PartName", "/$n"); e.setAttribute("ContentType", CT_SLIDE)
            croot.appendChild(e)
        }
        out["[Content_Types].xml"] = serialize(ct)

        // document properties
        val now = java.time.Instant.now().toString().substringBefore('.') + "Z"
        if (out.containsKey("docProps/app.xml")) out["docProps/app.xml"] = (Xml.HEADER + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"><Application>ZENIQ</Application><Slides>${names.size}</Slides></Properties>").toByteArray(Charsets.UTF_8)
        if (out.containsKey("docProps/core.xml")) out["docProps/core.xml"] = (Xml.HEADER + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">" +
            "<dc:title>${Xml.esc(title)}</dc:title><dc:creator>ZENIQ</dc:creator>" +
            "<dcterms:created xsi:type=\"dcterms:W3CDTF\">$now</dcterms:created><dcterms:modified xsi:type=\"dcterms:W3CDTF\">$now</dcterms:modified></cp:coreProperties>").toByteArray(Charsets.UTF_8)

        pruneMedia(out)
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            // [Content_Types].xml first, as Office expects
            val first = out["[Content_Types].xml"]!!
            z.putNextEntry(ZipEntry("[Content_Types].xml")); z.write(first); z.closeEntry()
            for ((k, v) in out) {
                if (k == "[Content_Types].xml") continue
                z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun relsName(part: String): String = part.substringBeforeLast('/') + "/_rels/" + part.substringAfterLast('/') + ".rels"

    private fun stripNotesRel(rels: ByteArray, picture: String? = null): ByteArray {
        val d = parseXml(rels)
        val rl = d.getElementsByTagNameNS(NS_REL, "Relationship")
        val drop = ArrayList<Element>()
        for (i in 0 until rl.length) { val e = rl.item(i) as Element; if (e.getAttribute("Type").endsWith("/notesSlide")) drop.add(e) }
        for (e in drop) e.parentNode.removeChild(e)
        if (picture != null) {
            val r = d.createElementNS(NS_REL, "Relationship")
            r.setAttribute("Id", "rIdZeniqPic")
            r.setAttribute("Type", "http://schemas.openxmlformats.org/officeDocument/2006/relationships/image")
            r.setAttribute("Target", picture)
            d.documentElement.appendChild(r)
        }
        return serialize(d)
    }

    /** Removes pictures that only belonged to template slides that were not used. */
    private fun pruneMedia(out: MutableMap<String, ByteArray>) {
        val referenced = HashSet<String>()
        for ((k, v) in out) {
            if (!k.endsWith(".rels")) continue
            val owner = if (k == "_rels/.rels") "" else k.replace("/_rels/", "/").removeSuffix(".rels")
            val d = try { parseXml(v) } catch (_: Exception) { continue }
            val rl = d.getElementsByTagNameNS(NS_REL, "Relationship")
            for (i in 0 until rl.length) {
                val e = rl.item(i) as Element
                if (e.getAttribute("TargetMode") == "External") continue
                referenced.add(resolve(if (owner.isEmpty()) "x" else owner, e.getAttribute("Target")))
            }
        }
        val drop = out.keys.filter { it.startsWith("ppt/media/") && it !in referenced }
        for (k in drop) out.remove(k)
    }
}
