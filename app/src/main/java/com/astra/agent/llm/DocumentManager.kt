package com.astra.agent.llm

import android.content.Context
import android.net.Uri
import com.astra.agent.docs.DocBuilder
import com.astra.agent.docs.OfficeReader
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

data class LoadedDoc(
    val uri: Uri,
    val name: String,
    val text: String,
    val pageCount: Int,
    val sizeKb: Int,
    /** false when no readable text could be extracted (e.g. scanned PDF) */
    val readable: Boolean = true
)

class DocumentManager(private val ctx: Context) {

    /** Documents that are part of the conversation (already sent with a message). */
    val docs = MutableStateFlow<List<LoadedDoc>>(emptyList())

    companion object {
        const val MAX_DOCS = 5
        private const val MAX_DOC_BYTES  = 25_000_000   // 25 MB per file
        private const val MAX_TEXT_CHARS = 400_000      // chars kept per doc
        private const val CHUNK          = 1200
        @Volatile private var pdfboxReady = false
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Reads + extracts a file. Does NOT add it to the conversation; call [add] when the message is sent. */
    suspend fun load(uri: Uri, name: String): Result<LoadedDoc> = withContext(Dispatchers.IO) {
        runCatching {
            val mime = try { ctx.contentResolver.getType(uri).orEmpty() } catch (_: Exception) { "" }

            val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Cannot open file \u2014 check that the file still exists.")
            if (bytes.size > MAX_DOC_BYTES) error("File is too large (max 25 MB).")

            val lower = name.lowercase()
            val isPdf = mime == "application/pdf" || lower.endsWith(".pdf") ||
                (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF")
            val isZip = bytes.size > 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()
            val isDocx = lower.endsWith(".docx") || mime.contains("wordprocessingml")
            val isXlsx = lower.endsWith(".xlsx") || lower.endsWith(".xlsm") || mime.contains("spreadsheetml")
            val isPptx = lower.endsWith(".pptx") || mime.contains("presentationml")

            val text: String
            var pages = 1
            if (isPdf) {
                val r = extractPdf(bytes)
                text = DocBuilder.reflowPdfText(r.first); pages = r.second
            } else if (isDocx && isZip) {
                text = OfficeReader.docxToMarkdown(bytes).take(MAX_TEXT_CHARS)
                pages = (text.length / 3000).coerceAtLeast(1)
            } else if (isXlsx && isZip) {
                text = OfficeReader.xlsxToMarkdown(bytes).take(MAX_TEXT_CHARS)
            } else if (isPptx && isZip) {
                text = OfficeReader.pptxToMarkdown(bytes).take(MAX_TEXT_CHARS)
                pages = Regex("(?m)^#{1,2} ").findAll(text).count().coerceAtLeast(1)
            } else if (lower.endsWith(".doc") || lower.endsWith(".xls") || lower.endsWith(".ppt")) {
                error("Old Office formats (.doc, .xls, .ppt) can't be read. Open the file and save it as .docx, .xlsx or .pptx, then upload it again.")
            } else if (mime.startsWith("text/") || lower.endsWith(".txt") || lower.endsWith(".md")
                || lower.endsWith(".csv") || lower.endsWith(".json") || lower.endsWith(".html") || lower.endsWith(".xml")) {
                text = bytes.toString(Charsets.UTF_8).take(MAX_TEXT_CHARS)
            } else {
                error("Unsupported file type. Upload a PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), .csv, .txt or .md file.")
            }

            val clean = text.replace(Regex("(?<=\\S)[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
            LoadedDoc(
                uri = uri, name = name.take(80), text = clean, pageCount = pages,
                sizeKb = (bytes.size / 1024).coerceAtLeast(1),
                readable = clean.length >= 20
            )
        }
    }

    fun add(doc: LoadedDoc) {
        docs.value = (docs.value.filter { it.name != doc.name } + doc).takeLast(MAX_DOCS)
    }

    fun remove(name: String) { docs.value = docs.value.filter { it.name != name } }
    fun clear()              { docs.value = emptyList() }
    fun hasDocuments(): Boolean = docs.value.isNotEmpty()

    /**
     * Builds the document context injected into the system prompt.
     * Short documents are included whole; long ones are reduced to the chunks that best match [question].
     */
    fun buildContext(question: String, maxChars: Int, exclude: Set<String> = emptySet()): String {
        val list = docs.value.filter { it.name !in exclude }
        if (list.isEmpty()) return ""
        val perDoc = (maxChars / list.size).coerceAtLeast(1000)
        val sb = StringBuilder("\n\n---\n# UPLOADED DOCUMENT CONTENT\n")
        sb.append("The user attached the document(s) below. Answer questions using this text. ")
        sb.append("You CAN read it. Never say you cannot access the file.\n")
        list.forEachIndexed { i, doc ->
            sb.append("\n### Document ${i + 1}: ${doc.name} (${doc.pageCount} pages)\n")
            if (!doc.readable) {
                sb.append("(No selectable text was found in this file \u2014 it is probably a scanned/image PDF. ")
                sb.append("Tell the user text extraction found nothing and suggest a text-based PDF.)\n")
            } else {
                sb.append("<document>\n").append(select(doc.text, question, perDoc)).append("\n</document>\n")
            }
        }
        sb.append("---\n")
        return sb.toString()
    }

    /**
     * The text of freshly attached files, placed INSIDE the user's message (not in the system prompt).
     * A short request such as "summarize this" then clearly refers to these files and not to the previous answer.
     */
    fun inlineBlock(list: List<LoadedDoc>, question: String, maxChars: Int): String {
        if (list.isEmpty()) return ""
        val perDoc = (maxChars / list.size).coerceAtLeast(1500)
        val sb = StringBuilder()
        list.forEachIndexed { i, doc ->
            sb.append("[ATTACHED FILE ${i + 1}: ${doc.name}, ${doc.pageCount} pages]\n")
            if (!doc.readable) {
                sb.append("(No selectable text was found in this file - it is probably a scanned or image-only PDF. Tell the user that no text could be read from it.)\n\n")
            } else {
                sb.append("<document>\n").append(select(doc.text, question, perDoc)).append("\n</document>\n\n")
            }
        }
        return sb.toString()
    }

    // ── Relevance selection ───────────────────────────────────────────────────

    private val stop = setOf("the", "and", "for", "with", "what", "this", "that", "about", "from", "are", "was",
        "who", "how", "why", "when", "where", "which", "does", "did", "can", "you", "tell", "give", "please",
        "pdf", "file", "document", "explain", "summarize", "summary", "me", "of", "in", "on", "is", "it", "to", "a")

    private fun select(text: String, question: String, limit: Int): String {
        if (text.length <= limit) return text
        val chunks = text.chunked(CHUNK)
        val words = question.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 && it !in stop }
        val scored = chunks.mapIndexed { idx, c ->
            val lc = c.lowercase()
            var score = 0
            for (w in words) { var from = 0; while (true) { val p = lc.indexOf(w, from); if (p < 0) break; score++; from = p + w.length } }
            Triple(idx, score, c)
        }
        val picked = HashSet<Int>()
        var used = 0
        // Always keep the beginning (title / abstract / intro)
        picked.add(0); used += chunks[0].length
        for ((idx, score, c) in scored.sortedByDescending { it.second }) {
            if (used + c.length > limit) break
            if (score == 0 && picked.size > 1 && words.isNotEmpty()) break
            if (picked.add(idx)) used += c.length
        }
        // No keyword hits at all (e.g. "summarize"): spread chunks evenly across the document
        if (picked.size == 1) {
            val step = (chunks.size.toFloat() / (limit / CHUNK).coerceAtLeast(1)).coerceAtLeast(1f)
            var f = 0f
            while (f < chunks.size && used < limit) {
                val i = f.toInt()
                if (picked.add(i)) used += chunks[i].length
                f += step
            }
        }
        return picked.sorted().joinToString("\n[...]\n") { chunks[it] }
    }

    // ── PDF text extraction (PdfBox handles compressed streams + fonts) ───────

    private fun extractPdf(bytes: ByteArray): Pair<String, Int> {
        try {
            if (!pdfboxReady) { PDFBoxResourceLoader.init(ctx.applicationContext); pdfboxReady = true }
            PDDocument.load(ByteArrayInputStream(bytes)).use { doc ->
                try { if (doc.isEncrypted) doc.setAllSecurityToBeRemoved(true) } catch (_: Exception) {}
                val pages = doc.numberOfPages
                val stripper = PDFTextStripper()
                stripper.sortByPosition = true
                stripper.endPage = pages.coerceAtMost(300)
                val t = stripper.getText(doc).take(MAX_TEXT_CHARS)
                return Pair(t, pages)
            }
        } catch (e: OutOfMemoryError) {
            error("PDF is too large to process on this device.")
        } catch (e: Exception) {
            // fall through to the crude fallback below
        }
        return Pair(crudeExtract(bytes), estimatePages(bytes))
    }

    /** Last-resort extraction for unusual PDFs: printable text runs. */
    private fun crudeExtract(bytes: ByteArray): String {
        val raw = String(bytes, Charsets.ISO_8859_1)
        val sb = StringBuilder()
        for (m in Regex("[\\x20-\\x7E]{12,}").findAll(raw)) {
            val v = m.value.trim()
            if (v.count { it.isLetter() } > v.length * 0.6 && !v.startsWith("/") && !v.contains("obj")) {
                sb.append(v).append('\n')
                if (sb.length > MAX_TEXT_CHARS) break
            }
        }
        return sb.toString()
    }

    private fun estimatePages(bytes: ByteArray): Int = try {
        Regex("/Type\\s*/Page\\b").findAll(String(bytes, Charsets.ISO_8859_1)).count().coerceAtLeast(1)
    } catch (_: Exception) { 1 }
}
