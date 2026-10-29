package com.astra.agent.docs

import com.astra.agent.llm.LoadedDoc

/**
 * What the user wants done with files.
 * @param formats   file formats to produce
 * @param edit      true when an existing (uploaded or earlier generated) document must be changed
 * @param convertOnly true when the document only changes format ("convert this PDF to Word"): no AI needed
 * @param target    the document being edited / converted
 */
data class DocRequest(
    val formats: List<DocFormat>,
    val edit: Boolean,
    val convertOnly: Boolean,
    val target: LoadedDoc?
)

object DocIntent {
    private val IC = RegexOption.IGNORE_CASE

    private val createVerbs = Regex("\\b(create|make|generate|prepare|build|design|produce|compose|draft|write|give|provide|need|want|get|export|save|download|convert|turn|put|send)\\b", IC)
    private val strongCreate = Regex("\\b(create|make|generate|prepare|build|design|produce|compose|draft|write)\\b", IC)

    private val pdfRe = Regex("\\bpdf\\b", IC)
    private val wordRe = Regex("\\.?docx\\b|\\.doc\\b|\\bword[ -](document|doc|file|format|version|report|resume|r\u00e9sum\u00e9|cv|letter|proposal|invoice|contract|template|essay|summary|notes?)\\b|\\b(in|as|to|into) (a |an |the )?(ms |microsoft )?word\\b(?! count)|\\b(ms|microsoft) word\\b", IC)
    private val excelRe = Regex("\\b(excel|xlsx?|spreadsheet|work ?sheet|work ?book|google sheets?)\\b", IC)
    private val csvRe = Regex("\\bcsv\\b", IC)
    private val pptRe = Regex("\\b(powerpoint|power point|pptx?)\\b", IC)
    private val deckRe = Regex("\\b(slides?|slide ?deck|presentation|deck)\\b", IC)
    private val txtRe = Regex("\\b(\\.txt|text file|txt file|notepad)\\b", IC)
    private val mdRe = Regex("\\b(markdown file|\\.md|md file)\\b", IC)

    private val startsWithFormat = Regex("^\\s*(a |an |the )?(pdf|word|excel|powerpoint|power point|pptx?|docx|xlsx?|spreadsheet|csv)\\b(?! (is|are|was|can|does|do|vs)\\b)", IC)

    private val genericNoun = Regex("\\b(report|resume|r\u00e9sum\u00e9|cv|invoice|letter|certificate|document|proposal|brochure|newsletter|contract|agreement|syllabus|handout|manual|quotation|flyer|worksheet|minutes|case study|lesson plan)\\b", IC)

    private val codeGuard = Regex("\\b(python|java|javascript|typescript|kotlin|c\\+\\+|c#|php|node(\\.?js)?|react|library|api|sql|regex|html|css|script|function|source code|program|code)\\b", IC)
    private val questionStart = Regex("^\\s*(how|what|why|which|explain|difference|compare|tell me|is |are |does |do )", IC)

    private val editVerbs = Regex("\\b(change|edit|update|modify|fix|correct|replace|add|remove|delete|rewrite|rephrase|translate|shorten|lengthen|expand|improve|format|reformat|insert|rename|proofread|revise|adjust|reorder|sort|highlight|capitali[sz]e|polish|enhance|redo|swap|merge|split|append|make (it|this|them|the))\\b", IC)
    private val notEdit = Regex("\\b(update me|updates? on|fix me|add me|tell me)\\b", IC)
    private val strictEdit = Regex("\\b(add|remove|delete|replace|fix|edit|update|modify|translate|rewrite|rephrase|shorten|lengthen|expand|improve|correct|insert|proofread|revise|summari[sz]e|bold|font|colou?r|rename|reorder|sort|highlight|capitali[sz]e|spell|grammar|polish|enhance|append|swap|merge|split)\\b", IC)
    private val convertWords = Regex("\\b(convert|export|save|turn|change|transform|make|put|give|download|send|get|want|need)\\b[^.\\n]{0,45}\\b(to|into|as|in)\\b", IC)
    private val refWords = Regex("\\b(file|document|doc|pdf|word|excel|sheet|spreadsheet|slides?|ppt|presentation|resume|cv|report|table|column|row|cell|section|paragraph|heading|title|page|it|this|that|these|attached|above|uploaded|my)\\b", IC)

    fun formatsIn(t: String): List<DocFormat> {
        val f = ArrayList<DocFormat>()
        if (pdfRe.containsMatchIn(t)) f.add(DocFormat.PDF)
        if (wordRe.containsMatchIn(t)) f.add(DocFormat.DOCX)
        if (excelRe.containsMatchIn(t)) f.add(DocFormat.XLSX)
        if (pptRe.containsMatchIn(t) || (deckRe.containsMatchIn(t) && strongCreate.containsMatchIn(t))) f.add(DocFormat.PPTX)
        if (csvRe.containsMatchIn(t)) f.add(DocFormat.CSV)
        if (txtRe.containsMatchIn(t)) f.add(DocFormat.TXT)
        if (mdRe.containsMatchIn(t)) f.add(DocFormat.MD)
        // "pdf and word and excel": a bare "word" next to other formats means Microsoft Word
        if (DocFormat.DOCX !in f && f.isNotEmpty() && Regex("\\bword\\b(?!s)", IC).containsMatchIn(t) &&
            !Regex("\\b(a|one|the|each|every|this|that|key|single) word\\b|word count|\\bwords\\b", IC).containsMatchIn(t)) f.add(DocFormat.DOCX)
        return f.sortedBy { it.ordinal }
    }

    /** Picks which document the request is about: attached now, named in the message, or the most recent one. */
    fun pickTarget(text: String, pending: List<LoadedDoc>, existing: List<LoadedDoc>): LoadedDoc? {
        val all = (existing + pending).filter { it.readable }
        if (all.isEmpty()) return null
        val lower = text.lowercase()
        pending.lastOrNull { it.readable }?.let { return it }
        all.lastOrNull { d ->
            val base = d.name.substringBeforeLast('.').lowercase()
            base.length >= 3 && lower.contains(base)
        }?.let { return it }
        return all.last()
    }

    fun detect(text: String, pending: List<LoadedDoc>, existing: List<LoadedDoc>): DocRequest? {
        val t = text.trim()
        if (t.isEmpty() || t.contains("```")) return null
        val hasDocs = (pending + existing).any { it.readable }
        val formats = formatsIn(t)

        // 1) work on an existing document
        if (hasDocs && refWords.containsMatchIn(t)) {
            val target = pickTarget(t, pending, existing)
            if (target != null) {
                val wantsConvert = formats.isNotEmpty() && convertWords.containsMatchIn(t) && !strictEdit.containsMatchIn(t)
                if (wantsConvert) return DocRequest(formats, edit = false, convertOnly = true, target = target)
                if (editVerbs.containsMatchIn(t) && !notEdit.containsMatchIn(t) && !questionStart.containsMatchIn(t)) {
                    val out = if (formats.isNotEmpty()) formats else listOf(sameFormatAs(target))
                    return DocRequest(out, edit = true, convertOnly = false, target = target)
                }
            }
        }

        // 2) create something new
        if (!hasDocs && codeGuard.containsMatchIn(t)) return null
        if (hasDocs && codeGuard.containsMatchIn(t) && formats.isEmpty()) return null
        if (questionStart.containsMatchIn(t) && !strongCreate.containsMatchIn(t)) return null
        if (formats.isNotEmpty() && createVerbs.containsMatchIn(t)) return DocRequest(formats, edit = false, convertOnly = false, target = null)
        // "PowerPoint on AI, 8 slides" / "Excel budget for 2026": the format name leads the request, no verb needed
        if (formats.isNotEmpty() && startsWithFormat.containsMatchIn(t)) return DocRequest(formats, edit = false, convertOnly = false, target = null)
        if (strongCreate.containsMatchIn(t) && genericNoun.containsMatchIn(t) &&
            !Regex("\\b(app|website|web ?site|web ?page|game|software)\\b", IC).containsMatchIn(t))
            return DocRequest(listOf(DocFormat.PDF), edit = false, convertOnly = false, target = null)
        return null
    }

    /** "put this image in a ppt" / "add these photos to a Word document": a file format named while pictures are attached. */
    fun detectWithImages(text: String): DocRequest? {
        val t = text.trim()
        if (t.isEmpty() || t.contains("```") || questionStart.containsMatchIn(t) && !strongCreate.containsMatchIn(t)) return null
        val formats = formatsIn(t)
        return if (formats.isEmpty()) null else DocRequest(formats, edit = false, convertOnly = false, target = null)
    }

    /** Told to the model when pictures are attached to a file request: it decides where they go; the app inserts the real pictures. */
    fun imageNote(images: List<DocImage>): String =
        "\n\n[ATTACHED IMAGES: " + images.mapIndexed { i, im -> "${i + 1} = ${im.name}" }.joinToString(", ") +
        ". The app inserts the real pictures into the file. To choose where a picture goes, write ![short caption](image:N) on its own line at that spot " +
        "(N is the number above). Do not describe or invent pictures, and do not use any other image syntax.]"

    fun sameFormatAs(d: LoadedDoc): DocFormat = when (d.name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> DocFormat.PDF
        "docx" -> DocFormat.DOCX
        "xlsx" -> DocFormat.XLSX
        "pptx" -> DocFormat.PPTX
        "csv" -> DocFormat.CSV
        "md" -> DocFormat.MD
        else -> DocFormat.TXT
    }

    // ── Prompt ──────────────────────────────────────────────────────────────

    const val RULES = "\n\n---\n# DOCUMENT WRITER MODE\n" +
        "ZENIQ turns your reply into a real file the user can download. Follow these rules exactly:\n" +
        "1. Reply with ONLY the document content in Markdown. No greeting, no explanation, no \"Here is...\", no closing remarks, and do NOT wrap the whole thing in a code fence.\n" +
        "2. Never say you cannot create files. ZENIQ creates the file from your text. Do not use tools or web search. Write the content yourself.\n" +
        "3. Write real, specific, COMPLETE content. No placeholders like [Your Name] or \"...\" unless the user gave no details; then invent realistic sample details.\n" +
        "4. Write in the same language as the user's request.\n" +
        "Markdown you can use: '# Title' on the first line, '## Section', '### Sub-section', normal paragraphs, '- ' bullets (indent 2 spaces to nest), '1. ' numbered lists, " +
        "**bold**, *italic*, tables with | pipes and a |---| separator row under the header, '> ' quotes, ``` code blocks, '---' rule, and '---pagebreak---' alone on a line for a new page.\n"

    fun formatHint(formats: List<DocFormat>): String {
        val sb = StringBuilder()
        if (DocFormat.XLSX in formats || DocFormat.CSV in formats) sb.append(
            "SPREADSHEET: output ONLY tables. Each sheet is a '## Sheet name' line followed by a Markdown table whose first row is the header. " +
                "Put plain numbers in cells (e.g. 1250, 12.5, 45%); a currency symbol is allowed ($1,200). Use Excel formulas that start with = for totals and calculations, " +
                "with real cell references: the header is row 1, the first data row is row 2, columns are A, B, C... Example: | Total | =SUM(B2:B6) |.\n")
        if (DocFormat.PPTX in formats) sb.append(
            "PRESENTATION: '# Title' is the title slide (a short subtitle line may follow). Every '## Slide title' starts a new slide (titles of at most 5 words). " +
                "Under each title write 3 or 4 bullets in the form '- **Short heading**: one clear sentence of at most 14 words'. " +
                "A slide may instead hold one small table or one short paragraph. Create 6-8 slides unless asked otherwise. " +
                "Do not add a 'Thank you' slide - it is added automatically.\n")
        if (DocFormat.PDF in formats || DocFormat.DOCX in formats) sb.append(
            "DOCUMENT: make it look professional - a title, clear sections, short paragraphs, bullets for lists, and tables for data or comparisons. Resumes, letters and invoices must be fully filled in.\n")
        return sb.toString()
    }

    fun editBlock(doc: LoadedDoc, formats: List<DocFormat>): String =
        "\n---\n# DOCUMENT TO EDIT: ${doc.name}\n" +
            "This is the current content of the user's file, converted to Markdown. Apply EXACTLY the changes the user asks for and output the COMPLETE updated document " +
            "(every section, not just the changed part), keeping everything else unchanged, with the same structure" +
            (if (DocFormat.XLSX in formats) " (keep every sheet, '## Sheet name' headings and formulas that start with =)" else "") + ".\n" +
            "<document>\n${doc.text}\n</document>\n---\n"
}
