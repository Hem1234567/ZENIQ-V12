package com.astra.agent.llm

/** Recognises "generate one apple image" / "make a clip for a thank-you screen" and extracts what to draw. */
object MediaIntent {
    enum class Type { IMAGE, VIDEO }
    class Req(val type: Type, val prompt: String)

    private val IC = RegexOption.IGNORE_CASE
    private val verbs = "(?:generate|create|make|draw|produce|paint|design|render|give me|show me|i want|i need|need|want)"
    private val imgRe = Regex("\\b$verbs\\b(.{0,30}?)\\b(images?|imgs?|pictures?|photos?|pics?|illustrations?|artworks?|drawings?|wallpapers?|logos?|posters?|portraits?|paintings?|icons?)\\b", IC)
    private val vidRe = Regex("\\b$verbs\\b(.{0,30}?)\\b(videos?|clips?|animations?|reels?|shorts?|intros?|outros?)\\b", IC)
    private val notMedia = Regex("\\b(pdf|word|docx?|excel|xlsx?|ppt|pptx|powerpoint|slides?|presentation|spreadsheet|document|report|code|script|program|website|app|html|python|java|csv)\\b", IC)
    private val lead = Regex("^\\s*(?:of|for|about|showing|that shows|which shows|with|where|:|-)\\s+", IC)

    fun detect(raw: String): Req? {
        val t = raw.trim()
        if (t.length < 8 || t.length > 400 || notMedia.containsMatchIn(t)) return null
        val v = vidRe.find(t)
        if (v != null) return Req(Type.VIDEO, "A short video clip: " + promptAfter(t, v))
        val i = imgRe.find(t) ?: return null
        return Req(Type.IMAGE, promptAfter(t, i) + ", high quality, detailed")
    }

    private val generic = Regex("^(images?|imgs?|pictures?|photos?|pics?|videos?|clips?|shorts?|reels?)$", IC)

    private fun promptAfter(t: String, m: MatchResult): String {
        val middle = m.groupValues[m.groupValues.size - 2].trim().replace(Regex("^(?:an?|one|some|the|me|a short|a small)\\s+", IC), "").trim()
        val noun = m.groupValues[m.groupValues.size - 1].lowercase()
        var after = t.substring(m.range.last + 1).trim().trim('.', '!', '?')
        val subject = after.replace(lead, "").trim()
        return when {
            !generic.matches(noun) && after.isNotBlank() ->        // "a logo for my coffee shop", "a poster about space"
                (if (middle.isNotBlank()) "$middle " else "") + noun + " " + (if (after.startsWith("of ", true)) after else after)
            subject.isNotBlank() -> (if (middle.isNotBlank() && !generic.matches(noun)) "$middle " else "") + subject
            middle.isNotBlank() -> middle
            else -> t
        }
    }
}
