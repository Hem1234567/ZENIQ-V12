package com.astra.agent.site

/**
 * "create a website for my bakery" -> built from one of the 20 ZENIQ designs as ONE self-contained HTML file.
 * Requests that really need code logic or several files (web apps, games, back ends, React ...) are left to the normal app builder.
 */
object SiteIntent {
    private val IC = RegexOption.IGNORE_CASE
    private val siteRe = Regex("\\b(build|create|make|generate|design|develop|prepare|give me|i want|i need|need|want|write|code)\\b[^.\\n?!]{0,70}\\b(website|web ?site|web ?page|webpage|web(?!\\s*(app|search|browser|scrap|service|server|crawler))|landing ?page|home ?page|portfolio( site| page)?|one ?page|single ?page|business page)\\b", IC)
    private val notRe = Regex("\\b(game|calculator|todo|to-do|quiz|timer|clock|converter|tracker|chat ?bot|dashboard|admin|login system|back-?end|database|api|react|vue|angular|next\\.?js|node\\.?js|php|django|flask|laravel|python|kotlin|full[- ]?stack|multi[- ]?page|multiple pages|several pages|zip|multiple files|separate files|web ?app|android app|mobile app)\\b", IC)

    fun detect(text: String): Boolean {
        val t = text.trim()
        if (t.length < 8 || t.length > 600) return false
        if (!siteRe.containsMatchIn(t)) return false
        return !notRe.containsMatchIn(t)
    }

    /** Asked of the model: only the words. The page itself (design, layout, scripts) is built by [SiteRenderer]. */
    const val RULES = """
# Website content (ZENIQ designs and builds the whole page itself; you only write the words)
- Reply with ONLY these lines, in this order. No markdown, no code, no explanation, nothing before or after.
NAME: brand or site name
CATEGORY: one of business, restaurant, portfolio, shop, education, fitness, travel, tech, health, realestate, event, charity
TAGLINE: a catchy headline, at most 8 words
ABOUT: two sentences about the brand
FEATURE: title | one short sentence
FEATURE: title | one short sentence
FEATURE: title | one short sentence
FEATURE: title | one short sentence
STAT: value | label
STAT: value | label
STAT: value | label
QUOTE: person name | role | one short praise
QUOTE: person name | role | one short praise
CTA: button text of 2 or 3 words
- Use specific, real-sounding words for the user's topic. Keep every line short."""
}
