package com.astra.agent.docs

/**
 * Visual style of a generated file. "Normal" is used when the user just asks for a file; "premium" when the
 * request says premium / beautiful / professional / stunning and so on. Each tier has several themes and the
 * app rotates through them so consecutive files do not all look the same.
 */
enum class Tier { NORMAL, PREMIUM }

/** Colours are 6-digit hex without '#'. [dark] themes get a full-colour cover. */
class Theme(
    val id: String, val name: String, val tier: Tier,
    val primary: String, val accent: String, val light: String, val text: String = "222222",
    val heading: String = primary, val dark: Boolean = false, val serif: Boolean = false
)

object Themes {
    val all: List<Theme> = listOf(
        Theme("classic-blue", "Classic Blue", Tier.NORMAL, "1F3864", "2E74B5", "EAF1FA"),
        Theme("forest", "Forest Green", Tier.NORMAL, "1E5631", "4C9A2A", "EAF5E4"),
        Theme("slate", "Slate Grey", Tier.NORMAL, "2F3B4C", "6B7B8F", "EEF1F5"),
        Theme("royal", "Royal Navy Gold", Tier.PREMIUM, "0B1F3A", "C9A227", "F4EFE0", heading = "0B1F3A", dark = true, serif = true),
        Theme("midnight", "Midnight Violet", Tier.PREMIUM, "1B1340", "7C4DFF", "ECE8FB", heading = "1B1340", dark = true),
        Theme("crimson", "Crimson Modern", Tier.PREMIUM, "7A0C1E", "E4572E", "FBEDE9", heading = "7A0C1E", dark = true),
    )

    fun tier(t: Tier): List<Theme> = all.filter { it.tier == t }
    fun byId(id: String?): Theme? = all.firstOrNull { it.id == id }

    /** Picks a theme of [tier], never the one used last time when there is a choice. */
    fun pick(tier: Tier, lastId: String?, rnd: java.util.Random = java.util.Random()): Theme {
        val list = tier(tier)
        val choices = list.filter { it.id != lastId }.ifEmpty { list }
        return choices[rnd.nextInt(choices.size)]
    }
}

object StyleDetect {
    private val premiumRe = Regex(
        "\\b(premium|beautiful|beautifully|stunning|gorgeous|attractive|elegant|stylish|fancy|luxury|luxurious|" +
            "eye[- ]?catching|high[- ]?quality|best[- ]?looking|well[- ]?designed|designer|creative|aesthetic|" +
            "professional|professionally|polished|impressive|modern look|nice[- ]looking)\\b", RegexOption.IGNORE_CASE)

    /** PREMIUM for words like premium / beautiful / professional, otherwise NORMAL. */
    fun tier(request: String): Tier = if (premiumRe.containsMatchIn(request)) Tier.PREMIUM else Tier.NORMAL

    /** Picks a PowerPoint template of the right tier, avoiding [lastId]. */
    internal fun pptTemplate(tier: Tier, lastId: String?, rnd: java.util.Random = java.util.Random()): TplDef {
        val list = TemplateCatalog.tier(tier == Tier.PREMIUM)
        val choices = list.filter { it.id != lastId }.ifEmpty { list }
        return choices[rnd.nextInt(choices.size)]
    }
}

/** What the writers get: the chosen theme and (for PowerPoint) the template and a way to read it from the app assets. */
internal class StyleChoice(val theme: Theme, internal val ppt: TplDef?, val assets: ((String) -> ByteArray?)?) {
    companion object {
        val DEFAULT = StyleChoice(Themes.all[0], null, null)
    }
}
