package com.astra.agent.site

/** One of the 20 built-in website designs. */
class Design(
    val id: String, val name: String, val premium: Boolean, val tags: List<String>, val dark: Boolean,
    val bg: String, val surface: String, val text: String, val muted: String, val primary: String, val accent: String, val border: String,
    val fontH: String, val fontB: String, val gfont: String, val hero: Int, val card: Int, val radius: Int
)

object SiteDesigns {
    private const val SANS = "-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif"
    private const val SERIF = "Georgia,'Times New Roman',serif"
    private fun d(id: String, name: String, premium: Boolean, tags: String, dark: Boolean, bg: String, surface: String, text: String, muted: String,
                  primary: String, accent: String, border: String, fh: String, fb: String, gf: String, hero: Int, card: Int, r: Int) =
        Design(id, name, premium, tags.split(' '), dark, bg, surface, text, muted, primary, accent, border, fh, fb, gf, hero, card, r)

    private fun poppins() = Triple("'Poppins',$SANS", "'Inter',$SANS", "family=Poppins:wght@500;600;700;800&family=Inter:wght@400;500;600")

    val all: List<Design> = listOf(
        // ── 10 normal designs: clean, friendly, light ────────────────────────────
        d("n1", "Clean Blue", false, "blue light clean", false, "#ffffff", "#f5f8ff", "#0f172a", "#52607a", "#2563eb", "#06b6d4", "#e3e9f5",
            "'Poppins',$SANS", "'Inter',$SANS", "family=Poppins:wght@500;600;700;800&family=Inter:wght@400;500;600", 0, 0, 16),
        d("n2", "Fresh Green", false, "green light fresh nature", false, "#fbfffc", "#f0faf3", "#10261a", "#4e6b5a", "#16a34a", "#84cc16", "#dcefe2",
            "'Outfit',$SANS", "'Inter',$SANS", "family=Outfit:wght@500;600;700&family=Inter:wght@400;500;600", 1, 1, 22),
        d("n3", "Warm Orange", false, "orange warm light", false, "#fffdfa", "#fff5eb", "#2a1707", "#7a5a40", "#ea580c", "#f59e0b", "#f6e3d0",
            "'Montserrat',$SANS", "'Open Sans',$SANS", "family=Montserrat:wght@600;700;800&family=Open+Sans:wght@400;600", 0, 2, 12),
        d("n4", "Soft Purple", false, "purple violet light", false, "#fdfcff", "#f6f3ff", "#1d1535", "#5f5784", "#7c3aed", "#ec4899", "#e7e0fb",
            "'Sora',$SANS", "'Inter',$SANS", "family=Sora:wght@500;600;700&family=Inter:wght@400;500;600", 1, 0, 20),
        d("n5", "Rose Petal", false, "pink rose light", false, "#fffafb", "#fff0f3", "#2b1018", "#80525f", "#e11d48", "#fb7185", "#f8dbe2",
            "'Playfair Display',$SERIF", "'Lato',$SANS", "family=Playfair+Display:wght@600;700;800&family=Lato:wght@400;700", 3, 1, 14),
        d("n6", "Teal Breeze", false, "teal aqua light", false, "#f9fefe", "#eefafa", "#0b2527", "#4a6c6f", "#0d9488", "#38bdf8", "#d5ecec",
            "'DM Sans',$SANS", "'DM Sans',$SANS", "family=DM+Sans:wght@400;500;700", 0, 3, 18),
        d("n7", "Slate Minimal", false, "grey gray minimal light black", false, "#fafafa", "#ffffff", "#111111", "#666666", "#111111", "#6b7280", "#e5e5e5",
            "'Space Grotesk',$SANS", "'Inter',$SANS", "family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500", 3, 1, 4),
        d("n8", "Sunny Yellow", false, "yellow gold sunny light", false, "#fffffa", "#fffbe6", "#262006", "#76692a", "#ca8a04", "#f97316", "#f3eab8",
            "'Raleway',$SANS", "'Source Sans 3',$SANS", "family=Raleway:wght@600;700;800&family=Source+Sans+3:wght@400;600", 1, 2, 24),
        d("n9", "Coral Sky", false, "coral red orange light", false, "#fffafa", "#fff1ef", "#2a1210", "#82554f", "#f43f5e", "#f97316", "#f8dcd8",
            "'Nunito',$SANS", "'Nunito',$SANS", "family=Nunito:wght@400;600;700;800", 0, 0, 26),
        d("n10", "Indigo Light", false, "indigo blue light", false, "#fcfcff", "#f1f2ff", "#12163a", "#585f8f", "#4f46e5", "#0ea5e9", "#dfe2fb",
            "'Plus Jakarta Sans',$SANS", "'Inter',$SANS", "family=Plus+Jakarta+Sans:wght@500;600;700;800&family=Inter:wght@400;500", 1, 1, 14),
        // ── 10 premium designs: rich, dark / glass / gradient, more sections ─────
        d("p1", "Midnight Gold", true, "black dark gold luxury yellow", true, "#0b0c10", "#14161d", "#f5f1e6", "#a9a79b", "#d4af37", "#f5d77a", "#262932",
            "'Playfair Display',$SERIF", "'Inter',$SANS", "family=Playfair+Display:wght@600;700;800&family=Inter:wght@400;500;600", 2, 2, 14),
        d("p2", "Aurora Violet", true, "purple violet dark pink", true, "#0c0a1d", "#15122e", "#f1eeff", "#a29cc9", "#8b5cf6", "#ec4899", "#2a2650",
            "'Sora',$SANS", "'Inter',$SANS", "family=Sora:wght@500;600;700;800&family=Inter:wght@400;500;600", 2, 2, 22),
        d("p3", "Neon Cyber", true, "cyan neon dark tech green blue", true, "#050b14", "#0b1626", "#e8fbff", "#86a8b8", "#22d3ee", "#a3e635", "#16304a",
            "'Space Grotesk',$SANS", "'Inter',$SANS", "family=Space+Grotesk:wght@500;600;700&family=Inter:wght@400;500;600", 2, 2, 10),
        d("p4", "Emerald Luxe", true, "green emerald dark luxury", true, "#06120d", "#0d1f17", "#eafbf1", "#8fb5a2", "#10b981", "#facc15", "#1b3a2c",
            "'DM Serif Display',$SERIF", "'DM Sans',$SANS", "family=DM+Serif+Display&family=DM+Sans:wght@400;500;700", 3, 2, 16),
        d("p5", "Sunset Gradient", true, "orange red pink sunset warm", true, "#140a0d", "#201014", "#fff1ec", "#c7a094", "#fb7185", "#f59e0b", "#3a1c22",
            "'Montserrat',$SANS", "'Open Sans',$SANS", "family=Montserrat:wght@600;700;800&family=Open+Sans:wght@400;600", 0, 2, 20),
        d("p6", "Ocean Glass", true, "blue ocean dark glass teal", true, "#06101f", "#0c1b33", "#eaf4ff", "#8aa8c8", "#38bdf8", "#6366f1", "#1a3355",
            "'Outfit',$SANS", "'Inter',$SANS", "family=Outfit:wght@500;600;700&family=Inter:wght@400;500;600", 1, 2, 24),
        d("p7", "Royal Crimson", true, "red crimson dark royal", true, "#10070a", "#1c0d12", "#fdeef0", "#c29aa2", "#e11d48", "#f59e0b", "#361620",
            "'Cormorant Garamond',$SERIF", "'Lato',$SANS", "family=Cormorant+Garamond:wght@600;700&family=Lato:wght@400;700", 3, 1, 8),
        d("p8", "Mono Elegance", true, "black white monochrome minimal", false, "#ffffff", "#f4f4f5", "#09090b", "#63636b", "#09090b", "#52525b", "#e4e4e7",
            "'Playfair Display',$SERIF", "'Inter',$SANS", "family=Playfair+Display:wght@600;700;800&family=Inter:wght@400;500;600", 3, 0, 6),
        d("p9", "Rose Gold", true, "pink rose gold light elegant", false, "#fff9f7", "#ffffff", "#2b1519", "#85626a", "#d6798b", "#e8b86d", "#f3dfe0",
            "'Playfair Display',$SERIF", "'Lato',$SANS", "family=Playfair+Display:wght@600;700;800&family=Lato:wght@400;700", 1, 0, 28),
        d("p10", "Electric Blue", true, "blue electric dark", true, "#050816", "#0d1230", "#eef1ff", "#8e98c8", "#3b82f6", "#a855f7", "#1b2350",
            "'Plus Jakarta Sans',$SANS", "'Inter',$SANS", "family=Plus+Jakarta+Sans:wght@500;600;700;800&family=Inter:wght@400;500", 2, 2, 18),
    )

    fun tier(premium: Boolean) = all.filter { it.premium == premium }

    /** Picks a design of the right tier; a colour word in the request wins, and the last one used is skipped. */
    fun pick(premium: Boolean, request: String, lastId: String?, rnd: java.util.Random = java.util.Random()): Design {
        val list = tier(premium)
        val q = request.lowercase()
        val colorWords = listOf("blue", "green", "red", "orange", "purple", "violet", "pink", "yellow", "gold", "teal", "black", "white", "dark", "light", "cyan", "grey", "gray", "indigo", "coral", "rose")
        val wanted = colorWords.filter { Regex("\\b$it\\b").containsMatchIn(q) }
        if (wanted.isNotEmpty()) {
            val hits = list.filter { dsg -> wanted.any { it in dsg.tags } }.filter { it.id != lastId }.ifEmpty { list.filter { dsg -> wanted.any { it in dsg.tags } } }
            if (hits.isNotEmpty()) return hits[rnd.nextInt(hits.size)]
        }
        val choices = list.filter { it.id != lastId }.ifEmpty { list }
        return choices[rnd.nextInt(choices.size)]
    }
}
