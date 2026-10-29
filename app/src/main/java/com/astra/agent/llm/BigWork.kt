package com.astra.agent.llm

import com.astra.agent.Settings

/**
 * Big files and big data. A model can only read a few thousand words at a time, so a long document is handled part by part:
 *  - summarize: every part is condensed, then the notes are combined into one answer (map-reduce)
 *  - rewrite:   the instruction is applied to every part and the parts are joined into a new document
 * Tables keep their header row in every part, so each part still makes sense on its own.
 */
object BigWork {
    class Chunk(val text: String, val headerLines: Int)

    private val summaryRe = Regex("\\b(summari[sz]e|summary|overview|key points|main points|tl;?dr|what is this (document|file|pdf)|explain this (document|file|pdf)|gist|highlights)\\b", RegexOption.IGNORE_CASE)
    fun isSummaryRequest(text: String) = summaryRe.containsMatchIn(text)

    /** Characters per part: small enough that part + answer fit the model's context. */
    fun chunkSize(s: Settings): Int =
        if (Completion.usesCloud(s)) 9000 else (((s.nCtx - 900) * 3 / 2) / 2).coerceIn(1200, 3200)

    private fun isTableLine(l: String) = l.trimStart().startsWith("|")

    /** Splits on paragraph and table boundaries; a long table is cut into row groups that each repeat the header. */
    fun chunks(text: String, size: Int): List<Chunk> {
        val lines = text.replace("\r\n", "\n").split("\n")
        val out = ArrayList<Chunk>()
        val cur = StringBuilder()
        fun flush() { if (cur.isNotBlank()) out.add(Chunk(cur.toString().trimEnd(), 0)); cur.setLength(0) }
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (isTableLine(l)) {
                var j = i
                while (j < lines.size && isTableLine(lines[j])) j++
                val table = lines.subList(i, j)
                val hasHeader = table.size >= 2 && table[1].replace("|", "").replace(":", "").replace("-", "").isBlank()
                val head = if (hasHeader) table.take(2) else emptyList()
                val rows = if (hasHeader) table.drop(2) else table
                val whole = table.joinToString("\n")
                if (whole.length <= size) {
                    if (cur.length + whole.length + 1 > size) flush()
                    cur.append(whole).append("\n")
                } else {
                    flush()
                    val hs = head.joinToString("\n")
                    var buf = StringBuilder(hs)
                    var count = 0
                    for (r in rows) {
                        if (count > 0 && buf.length + r.length + 1 > size) {
                            out.add(Chunk(buf.toString(), head.size)); buf = StringBuilder(hs); count = 0
                        }
                        if (buf.isNotEmpty()) buf.append("\n"); buf.append(r); count++
                    }
                    if (count > 0) out.add(Chunk(buf.toString(), head.size))
                }
                i = j
                continue
            }
            // normal text: add line by line; a single huge line is cut at sentence ends
            val pieces = if (l.length <= size) listOf(l) else l.split(Regex("(?<=[.!?])\\s+")).flatMap { p -> if (p.length <= size) listOf(p) else p.chunked(size) }
            for (p in pieces) {
                if (cur.length + p.length + 1 > size) flush()
                cur.append(p).append("\n")
            }
            i++
        }
        flush()
        return out
    }

    /** Applies [instruction] to every part and joins the results. Parts after the first of a table lose the repeated header. */
    suspend fun rewrite(s: Settings, text: String, instruction: String, progress: (Int, Int) -> Unit): String {
        val parts = chunks(text, chunkSize(s))
        val sb = StringBuilder()
        var prevHeader = false
        parts.forEachIndexed { idx, c ->
            progress(idx + 1, parts.size)
            val system = "You edit documents. Apply the user's instruction to the TEXT and return ONLY the complete edited text, " +
                "keeping the same structure (Markdown headings, lists and tables, with the same number of columns). " +
                "No explanations, no comments. If the instruction does not apply to this part, return the part unchanged."
            val user = "INSTRUCTION: $instruction\n\nTEXT:\n${c.text}"
            val cap = (c.text.length / 2 + 200).coerceIn(200, if (Completion.usesCloud(s)) 4000 else 1400)
            var edited = try { Completion.ask(s, system, user, cap) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (idx == 0) throw e else c.text }
            if (edited.isBlank()) edited = c.text
            if (c.headerLines > 0 && prevHeader) edited = edited.lines().drop(c.headerLines).joinToString("\n")
            prevHeader = c.headerLines > 0
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(edited.trimEnd()).append("\n")
        }
        return sb.toString()
    }

    /** Notes for every part, combined into one answer to [request]. */
    suspend fun summarize(s: Settings, text: String, request: String, progress: (Int, Int) -> Unit): String {
        val size = chunkSize(s)
        val parts = chunks(text, size)
        val notes = ArrayList<String>()
        parts.forEachIndexed { idx, c ->
            progress(idx + 1, parts.size + 1)
            val n = try {
                Completion.ask(s, "You read one part of a long document and take notes. Write 3 to 6 short bullet points with the key facts, names, numbers and dates. Nothing else.",
                    "PART ${idx + 1} OF ${parts.size}:\n${c.text}", if (Completion.usesCloud(s)) 500 else 260)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { if (idx == 0) throw e else "" }
            if (n.isNotBlank()) notes.add("Part ${idx + 1}: " + n)
        }
        progress(parts.size + 1, parts.size + 1)
        var joined = notes.joinToString("\n")
        // very long documents: condense the notes once more until they fit
        var guard = 0
        while (joined.length > size && guard++ < 3) {
            val sub = chunks(joined, size)
            val condensed = ArrayList<String>()
            for (c in sub) {
                condensed.add(try { Completion.ask(s, "Condense these notes to the most important bullet points. Nothing else.", c.text, if (Completion.usesCloud(s)) 500 else 260) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { c.text.take(size / 2) })
            }
            joined = condensed.joinToString("\n")
        }
        return Completion.ask(s,
            "You answer questions about a long document using notes taken from every part of it. Use only the notes. Be clear and well organised.",
            "NOTES FROM THE WHOLE DOCUMENT:\n$joined\n\nREQUEST: $request", if (Completion.usesCloud(s)) 1200 else 520)
    }
}
