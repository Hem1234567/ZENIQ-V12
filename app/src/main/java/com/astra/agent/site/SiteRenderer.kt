package com.astra.agent.site

/** Builds one complete, self-contained HTML page (HTML + CSS + JavaScript in a single file) from a [SiteSpec] and a [Design]. */
object SiteRenderer {

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun rgba(hex: String, a: Double): String {
        val h = hex.removePrefix("#")
        val r = h.substring(0, 2).toInt(16); val g = h.substring(2, 4).toInt(16); val b = h.substring(4, 6).toInt(16)
        return "rgba($r,$g,$b,$a)"
    }

    /** "Fresh flavours, made with love" -> "Fresh flavours, made with <span>love</span>" */
    private fun headline(t: String): String {
        val words = esc(t).split(" ")
        if (words.size < 3) return esc(t)
        return words.dropLast(1).joinToString(" ") + " <span class=\"hl\">" + words.last() + "</span>"
    }

    /** Text colour that stays readable on the primary/accent gradient. */
    private fun onColor(a: String, b: String): String {
        fun lum(hex: String): Double { val h = hex.removePrefix("#"); return (0.299 * h.substring(0, 2).toInt(16) + 0.587 * h.substring(2, 4).toInt(16) + 0.114 * h.substring(4, 6).toInt(16)) / 255.0 }
        return if ((lum(a) + lum(b)) / 2 > 0.62) "#111111" else "#ffffff"
    }

    private fun initials(name: String) = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }

    private fun dataUri(im: com.astra.agent.docs.DocImage): String =
        "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(im.bytes)

    fun render(spec: SiteSpec, d: Design, images: List<com.astra.agent.docs.DocImage> = emptyList()): String {
        val cat = SiteContent.category(spec.category, "")
        val emoji = cat.emoji
        val premium = d.premium
        val alt = if (d.dark) listOf("#f6f7fb", "#ffffff", "#111827", "#5b6475", "rgba(0,0,0,.1)") else listOf("#0e1117", "#171b24", "#f1f5f9", "#9aa4b2", "rgba(255,255,255,.12)")
        val sb = StringBuilder(24_000)
        sb.append("<!DOCTYPE html>\n<html lang=\"en\" data-mode=\"main\">\n<head>\n<meta charset=\"utf-8\">\n")
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
        sb.append("<title>").append(esc(spec.name)).append(" \u2014 ").append(esc(spec.tagline)).append("</title>\n")
        sb.append("<meta name=\"description\" content=\"").append(esc(spec.about.take(155))).append("\">\n")
        sb.append("<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\"><link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n")
        sb.append("<link href=\"https://fonts.googleapis.com/css2?").append(d.gfont).append("&display=swap\" rel=\"stylesheet\">\n")
        sb.append("<style>\n:root{--bg:${d.bg};--surface:${d.surface};--text:${d.text};--muted:${d.muted};--primary:${d.primary};--accent:${d.accent};--border:${d.border};")
        sb.append("--fh:${d.fontH};--fb:${d.fontB};--r:${d.radius}px;--soft:${rgba(d.primary, 0.13)};--navbg:${rgba(d.bg, 0.86)};")
        sb.append("--grad:linear-gradient(135deg,${d.primary},${d.accent});--shadow:0 18px 50px ${rgba(if (d.dark) "#000000" else d.primary, if (d.dark) 0.45 else 0.16)};--onp:${onColor(d.primary, d.accent)}}\n")
        sb.append("html[data-mode=alt]{--bg:${alt[0]};--surface:${alt[1]};--text:${alt[2]};--muted:${alt[3]};--border:${alt[4]};--navbg:${rgba(if (d.dark) "#f6f7fb" else "#0e1117", 0.86)}}\n")
        sb.append(CSS).append("\n</style>\n</head>\n<body id=\"top\">\n")
        if (premium) sb.append("<div class=\"progress\"></div>\n")

        // ── navigation ──
        val links = if (premium) listOf("about" to "About", "features" to cat.noun.replaceFirstChar { it.uppercase() }, "gallery" to "Gallery", "pricing" to "Pricing", "faq" to "FAQ", "contact" to "Contact")
                    else listOf("about" to "About", "features" to cat.noun.replaceFirstChar { it.uppercase() }, "gallery" to "Gallery", "contact" to "Contact")
        sb.append("<header class=\"nav\"><div class=\"wrap nav-in\"><a class=\"logo\" href=\"#top\"><span class=\"logo-mark\">").append(esc(initials(spec.name))).append("</span>").append(esc(spec.name)).append("</a>")
        sb.append("<nav class=\"links\">")
        for ((id, label) in links) sb.append("<a href=\"#$id\">").append(esc(label)).append("</a>")
        sb.append("<a class=\"btn btn-p nav-cta\" href=\"#contact\">").append(esc(spec.cta)).append("</a></nav>")
        if (premium) sb.append("<button class=\"mode\" type=\"button\" aria-label=\"Switch light and dark\">\u25D0</button>")
        sb.append("<button class=\"burger\" type=\"button\" aria-label=\"Menu\"><i></i><i></i><i></i></button></div></header>\n")

        // ── hero ──
        val top = spec.features.first()
        sb.append("<section class=\"hero hero-${d.hero}\"><div class=\"blob b1\"></div><div class=\"blob b2\"></div><div class=\"wrap hero-in\"><div class=\"hero-copy\">")
        sb.append("<span class=\"badge rv\">\u2728 ").append(esc(spec.name)).append(" \u2022 ").append(esc(cat.noun.replaceFirstChar { it.uppercase() })).append("</span>")
        sb.append("<h1 class=\"rv\">").append(headline(spec.tagline)).append("</h1>")
        sb.append("<p class=\"lead rv\">").append(esc(spec.about.split(Regex("(?<=[.!?])\\s+")).take(2).joinToString(" "))).append("</p>")
        sb.append("<div class=\"hero-btns rv\"><a class=\"btn btn-p\" href=\"#contact\">").append(esc(spec.cta)).append(" \u2192</a><a class=\"btn btn-g\" href=\"#features\">Explore more</a></div>")
        sb.append("<div class=\"trust rv\">")
        for (s in spec.stats.take(3)) sb.append("<div><b>").append(esc(s.value)).append("</b><span>").append(esc(s.label)).append("</span></div>")
        sb.append("</div></div>")
        if ((d.hero == 0 || d.hero == 3) && images.isNotEmpty())
            sb.append("<div class=\"hero-photo rv\"><img src=\"").append(dataUri(images[0])).append("\" alt=\"").append(esc(images[0].caption)).append("\"></div>")
        if (d.hero == 1 || d.hero == 2) {
            sb.append("<div class=\"hero-vis rv\">")
            if (images.isNotEmpty()) sb.append("<img class=\"hero-img\" src=\"").append(dataUri(images[0])).append("\" alt=\"").append(esc(images[0].caption)).append("\">")
            sb.append("<div class=\"glass g1\"><span class=\"gi\">").append(emoji[0]).append("</span><div><b>").append(esc(top.title)).append("</b><small>").append(esc(top.text.take(70))).append("</small></div></div>")
            sb.append("<div class=\"glass g2\"><b class=\"big\">").append(esc(spec.stats.first().value)).append("</b><small>").append(esc(spec.stats.first().label)).append("</small></div>")
            sb.append("<div class=\"glass g3\"><span class=\"stars\">\u2605\u2605\u2605\u2605\u2605</span><small>\u201C").append(esc(spec.quotes.first().text.take(58))).append("\u2026\u201D</small></div></div>")
        }
        sb.append("</div>")
        if (d.hero == 3) {
            sb.append("<div class=\"marquee\"><div class=\"track\">")
            repeat(2) { for (f in spec.features) sb.append("<span>").append(esc(f.title)).append("</span><span class=\"dot\">\u2726</span>") }
            sb.append("</div></div>")
        }
        sb.append("</section>\n")

        // ── stats band (premium) ──
        if (premium) {
            sb.append("<section class=\"band\"><div class=\"wrap stats\">")
            for (s in spec.stats) sb.append("<div class=\"stat rv\"><b class=\"num\">").append(esc(s.value)).append("</b><span>").append(esc(s.label)).append("</span></div>")
            sb.append("</div></section>\n")
        }

        // ── about ──
        sb.append("<section id=\"about\"><div class=\"wrap about\"><div class=\"about-vis rv\"><div class=\"orb\"><span>").append(emoji[0]).append("</span></div>")
        sb.append("<div class=\"chip c1\">").append(emoji[1]).append(" ").append(esc(spec.features[1].title)).append("</div><div class=\"chip c2\">").append(emoji[2]).append(" ").append(esc(spec.features[2].title)).append("</div></div>")
        sb.append("<div class=\"about-copy rv\"><span class=\"eyebrow\">About us</span><h2>Why choose ").append(esc(spec.name)).append("?</h2><p>").append(esc(spec.about)).append("</p><ul class=\"ticks\">")
        for (f in spec.features.take(if (premium) 4 else 3)) sb.append("<li><b>").append(esc(f.title)).append("</b> \u2014 ").append(esc(f.text)).append("</li>")
        sb.append("</ul><a class=\"btn btn-p\" href=\"#contact\">").append(esc(spec.cta)).append("</a></div></div></section>\n")

        // ── features ──
        sb.append("<section id=\"features\" class=\"alt\"><div class=\"wrap\"><div class=\"sec-head rv\"><span class=\"eyebrow\">What we offer</span><h2>Our ").append(esc(cat.noun)).append(", made for you</h2>")
        sb.append("<p>Everything ").append(esc(spec.name)).append(" does is built around quality, care and your goals.</p></div><div class=\"grid cards-${d.card}\">")
        spec.features.forEachIndexed { i, f ->
            sb.append("<article class=\"card rv\"><div class=\"ico\">").append(emoji[i % emoji.size]).append("</div><span class=\"no\">").append("%02d".format(i + 1)).append("</span><h3>")
                .append(esc(f.title)).append("</h3><p>").append(esc(f.text)).append("</p></article>")
        }
        sb.append("</div></div></section>\n")

        // ── process (premium) ──
        if (premium) {
            sb.append("<section id=\"process\"><div class=\"wrap\"><div class=\"sec-head rv\"><span class=\"eyebrow\">How it works</span><h2>Simple steps, great results</h2><p>From first hello to the finished result \u2014 clear, quick and stress-free.</p></div><div class=\"steps\">")
            spec.steps.forEachIndexed { i, s -> sb.append("<div class=\"step rv\"><span class=\"sn\">").append(i + 1).append("</span><h3>").append(esc(s.title)).append("</h3><p>").append(esc(s.text)).append("</p></div>") }
            sb.append("</div></div></section>\n")
        }

        // ── gallery ──
        sb.append("<section id=\"gallery\"").append(if (premium) " class=\"alt\"" else "").append("><div class=\"wrap\"><div class=\"sec-head rv\"><span class=\"eyebrow\">Gallery</span><h2>A closer look</h2><p>A few highlights from ").append(esc(spec.name)).append(".</p></div><div class=\"gal\">")
        spec.gallery.forEachIndexed { i, g ->
            val pic = images.getOrNull(i + (if (images.size > 1) 0 else 1))      // the first picture is already in the hero when it is the only one
            if (pic != null) sb.append("<figure class=\"tile photo rv\"><img src=\"").append(dataUri(pic)).append("\" alt=\"").append(esc(pic.caption)).append("\"><figcaption>").append(esc(pic.caption.replaceFirstChar { it.uppercase() })).append("</figcaption></figure>")
            else sb.append("<figure class=\"tile t").append(i % 6).append(" rv\"><span class=\"tile-e\">").append(esc(g.text)).append("</span><figcaption>").append(esc(g.title)).append("</figcaption></figure>")
        }
        sb.append("</div></div></section>\n")

        // ── pricing (premium) ──
        if (premium) {
            sb.append("<section id=\"pricing\"><div class=\"wrap\"><div class=\"sec-head rv\"><span class=\"eyebrow\">Pricing</span><h2>Plans for every need</h2><p>Clear prices, no surprises. Pick what suits you today and change anytime.</p></div><div class=\"plans\">")
            spec.plans.forEachIndexed { i, p ->
                sb.append("<div class=\"plan rv").append(if (i == 1) " pop" else "").append("\">").append(if (i == 1) "<span class=\"ribbon\">Most popular</span>" else "")
                sb.append("<h3>").append(esc(p.name)).append("</h3><div class=\"price\">").append(esc(p.price)).append("</div><ul>")
                for (it in p.items) sb.append("<li>").append(esc(it)).append("</li>")
                sb.append("</ul><a class=\"btn ").append(if (i == 1) "btn-p" else "btn-g").append("\" href=\"#contact\">Choose ").append(esc(p.name)).append("</a></div>")
            }
            sb.append("</div></div></section>\n")
        }

        // ── testimonials ──
        sb.append("<section id=\"reviews\"").append(if (!premium) " class=\"alt\"" else "").append("><div class=\"wrap\"><div class=\"sec-head rv\"><span class=\"eyebrow\">Reviews</span><h2>Loved by people like you</h2></div><div class=\"quotes\">")
        for (q in spec.quotes) {
            sb.append("<figure class=\"quote rv\"><div class=\"stars\">\u2605\u2605\u2605\u2605\u2605</div><blockquote>\u201C").append(esc(q.text)).append("\u201D</blockquote><figcaption><span class=\"av\">")
                .append(esc(initials(q.name))).append("</span><div><b>").append(esc(q.name)).append("</b><small>").append(esc(q.role)).append("</small></div></figcaption></figure>")
        }
        sb.append("</div></div></section>\n")

        // ── FAQ (premium) ──
        if (premium) {
            sb.append("<section id=\"faq\" class=\"alt\"><div class=\"wrap narrow\"><div class=\"sec-head rv\"><span class=\"eyebrow\">FAQ</span><h2>Questions, answered</h2></div>")
            spec.faqs.forEach { f -> sb.append("<details class=\"faq rv\"><summary>").append(esc(f.q)).append("</summary><p>").append(esc(f.a)).append("</p></details>") }
            sb.append("</div></section>\n")
            sb.append("<section class=\"cta\"><div class=\"wrap cta-in rv\"><h2>Ready to get started with ").append(esc(spec.name)).append("?</h2><p>Say hello today \u2014 we reply within one working day.</p><a class=\"btn btn-w\" href=\"#contact\">").append(esc(spec.cta)).append(" \u2192</a></div></section>\n")
        }

        // ── contact ──
        sb.append("<section id=\"contact\"><div class=\"wrap contact\"><div class=\"contact-info rv\"><span class=\"eyebrow\">Contact</span><h2>Let\u2019s talk</h2><p>Questions, bookings or ideas? Send a message and ")
            .append(esc(spec.name)).append(" will get back to you soon.</p><ul class=\"info\"><li><i>\uD83D\uDCCD</i><span>").append(esc(spec.address)).append("</span></li><li><i>\uD83D\uDCDE</i><span>")
            .append(esc(spec.phone)).append("</span></li><li><i>\u2709\uFE0F</i><span>").append(esc(spec.email)).append("</span></li><li><i>\u23F0</i><span>Mon\u2013Sat, 9 am \u2013 7 pm</span></li></ul></div>")
        sb.append("<form class=\"form rv\" novalidate><label>Your name<input name=\"name\" type=\"text\" placeholder=\"Jane Doe\" required></label><label>Email<input name=\"email\" type=\"email\" placeholder=\"you@example.com\" required></label>")
        sb.append("<label>Message<textarea name=\"msg\" rows=\"4\" placeholder=\"How can we help?\" required></textarea></label><button class=\"btn btn-p\" type=\"submit\">Send message</button><p class=\"ok\" role=\"status\"></p></form></div></section>\n")

        // ── footer ──
        sb.append("<footer class=\"foot\"><div class=\"wrap foot-in\"><div><a class=\"logo\" href=\"#top\"><span class=\"logo-mark\">").append(esc(initials(spec.name))).append("</span>").append(esc(spec.name)).append("</a><p>")
            .append(esc(spec.tagline)).append(".</p></div><div><h4>Explore</h4>")
        for ((id, label) in links) sb.append("<a href=\"#$id\">").append(esc(label)).append("</a>")
        sb.append("</div><div><h4>Contact</h4><a href=\"#contact\">").append(esc(spec.email)).append("</a><a href=\"#contact\">").append(esc(spec.phone)).append("</a></div></div>")
        sb.append("<div class=\"wrap copy\"><span>\u00A9 <b class=\"yr\">2026</b> ").append(esc(spec.name)).append(". All rights reserved.</span><span>Made with ZENIQ</span></div></footer>\n")
        sb.append("<a class=\"totop\" href=\"#top\" aria-label=\"Back to top\">\u2191</a>\n<script>\n").append(JS).append("\n</script>\n</body>\n</html>\n")
        return sb.toString()
    }

    private val CSS = """
*{box-sizing:border-box;margin:0;padding:0}
html{scroll-behavior:smooth;scroll-padding-top:76px}
body{font-family:var(--fb);background:var(--bg);color:var(--text);line-height:1.65;-webkit-font-smoothing:antialiased;overflow-x:hidden}
h1,h2,h3,h4{font-family:var(--fh);line-height:1.15;letter-spacing:-.02em}
a{color:inherit;text-decoration:none}
.wrap{width:min(1160px,92%);margin:0 auto}.narrow{width:min(820px,92%)}
section{padding:96px 0;position:relative}.alt{background:var(--surface)}
.eyebrow{display:inline-block;font-size:.76rem;font-weight:700;letter-spacing:.16em;text-transform:uppercase;color:var(--primary);margin-bottom:12px}
.sec-head{max-width:680px;margin:0 auto 52px;text-align:center}
.sec-head h2,.about-copy h2,.contact-info h2{font-size:clamp(1.9rem,4vw,2.8rem);margin-bottom:14px}
.sec-head p,.about-copy p,.contact-info p{color:var(--muted);font-size:1.05rem}
.btn{display:inline-flex;align-items:center;justify-content:center;gap:8px;padding:14px 28px;border-radius:calc(var(--r)*2.2);font-weight:600;font-size:.98rem;border:1.5px solid transparent;cursor:pointer;transition:transform .25s,box-shadow .25s,background .25s;font-family:var(--fb)}
.btn-p{background:var(--grad);color:var(--onp);box-shadow:0 10px 26px var(--soft)}.btn-p:hover{transform:translateY(-3px);box-shadow:var(--shadow)}
.btn-g{border-color:var(--border);background:transparent;color:var(--text)}.btn-g:hover{border-color:var(--primary);color:var(--primary);transform:translateY(-3px)}
.btn-w{background:#fff;color:#111;box-shadow:0 12px 30px rgba(0,0,0,.25)}.btn-w:hover{transform:translateY(-3px)}
/* nav */
.nav{position:fixed;top:0;left:0;right:0;z-index:60;transition:background .3s,box-shadow .3s,backdrop-filter .3s}
.nav.scrolled{background:var(--navbg);-webkit-backdrop-filter:blur(14px);backdrop-filter:blur(14px);box-shadow:0 1px 0 var(--border)}
.nav-in{display:flex;align-items:center;justify-content:space-between;height:72px;gap:18px}
.logo{display:flex;align-items:center;gap:10px;font-family:var(--fh);font-weight:700;font-size:1.15rem}
.logo-mark{display:grid;place-items:center;width:38px;height:38px;border-radius:calc(var(--r)*.7);background:var(--grad);color:var(--onp);font-size:.9rem;font-weight:800}
.links{display:flex;align-items:center;gap:26px;margin-left:auto}.links a:not(.btn){font-size:.94rem;font-weight:500;color:var(--muted);transition:color .2s}.links a:not(.btn):hover{color:var(--primary)}
.nav-cta{padding:10px 20px;font-size:.9rem}
.mode,.burger{background:none;border:1px solid var(--border);color:var(--text);width:40px;height:40px;border-radius:50%;cursor:pointer;font-size:1.1rem}
.burger{display:none;flex-direction:column;align-items:center;justify-content:center;gap:4px;border-radius:12px}.burger i{display:block;width:18px;height:2px;background:var(--text);transition:.3s}
.burger.on i:nth-child(1){transform:translateY(6px) rotate(45deg)}.burger.on i:nth-child(2){opacity:0}.burger.on i:nth-child(3){transform:translateY(-6px) rotate(-45deg)}
/* hero */
.hero{padding:150px 0 90px;overflow:hidden;position:relative}
.hero-in{position:relative;z-index:2;display:grid;gap:56px;align-items:center}
.hero-0 .hero-in,.hero-3 .hero-in{grid-template-columns:1fr}
.hero-0 .hero-copy{text-align:center;max-width:820px;margin:0 auto}.hero-0 .hero-btns,.hero-0 .trust{justify-content:center}
.hero-1 .hero-in,.hero-2 .hero-in{grid-template-columns:1.1fr .9fr}
.hero-3{padding-bottom:0}.hero-3 .hero-copy{max-width:900px}.hero-3 h1{font-size:clamp(3rem,8.5vw,6.6rem)!important;letter-spacing:-.04em}
.hero-2{background:radial-gradient(1200px 600px at 80% -10%,var(--soft),transparent 60%)}.hero-2 h1 .hl{filter:drop-shadow(0 0 22px var(--soft))}
.badge{display:inline-block;padding:7px 16px;border-radius:99px;background:var(--soft);color:var(--primary);font-weight:600;font-size:.82rem;margin-bottom:22px;border:1px solid var(--border)}
.hero h1{font-size:clamp(2.5rem,6vw,4.5rem);margin-bottom:22px}
.hl{background:var(--grad);-webkit-background-clip:text;background-clip:text;color:transparent;-webkit-text-fill-color:transparent}
.lead{font-size:1.15rem;color:var(--muted);max-width:620px;margin-bottom:34px}.hero-0 .lead{margin-left:auto;margin-right:auto}
.hero-btns{display:flex;gap:14px;flex-wrap:wrap;margin-bottom:40px}
.trust{display:flex;gap:34px;flex-wrap:wrap}.trust b{display:block;font-family:var(--fh);font-size:1.7rem}.trust span{font-size:.84rem;color:var(--muted)}
.blob{position:absolute;border-radius:50%;filter:blur(70px);opacity:.45;z-index:1;animation:float 14s ease-in-out infinite}
.b1{width:420px;height:420px;background:var(--primary);top:-120px;left:-100px}.b2{width:380px;height:380px;background:var(--accent);bottom:-120px;right:-80px;animation-delay:-6s}
@keyframes float{0%,100%{transform:translate(0,0)}50%{transform:translate(40px,30px)}}
.hero-vis{position:relative;height:420px}
.glass{position:absolute;display:flex;align-items:center;gap:14px;padding:18px 22px;border-radius:var(--r);background:var(--surface);border:1px solid var(--border);box-shadow:var(--shadow);-webkit-backdrop-filter:blur(10px);backdrop-filter:blur(10px);animation:bob 6s ease-in-out infinite}
.glass b{display:block;font-family:var(--fh)}.glass small{display:block;color:var(--muted);font-size:.8rem;max-width:230px;line-height:1.4}
.g1{top:10px;left:0;max-width:330px}.g2{top:170px;right:0;flex-direction:column;align-items:flex-start;animation-delay:-2s}.g2 .big{font-size:2.5rem;background:var(--grad);-webkit-background-clip:text;background-clip:text;color:transparent}
.g3{bottom:0;left:40px;flex-direction:column;align-items:flex-start;max-width:310px;animation-delay:-4s}
.gi{display:grid;place-items:center;width:52px;height:52px;border-radius:calc(var(--r)*.7);background:var(--soft);font-size:1.5rem;flex:none}
.stars{color:#f5b301;letter-spacing:2px}
@keyframes bob{0%,100%{transform:translateY(0)}50%{transform:translateY(-12px)}}
.marquee{margin-top:70px;overflow:hidden;border-block:1px solid var(--border);padding:18px 0;background:var(--surface)}
.track{display:flex;gap:34px;width:max-content;animation:slide 30s linear infinite;font-family:var(--fh);font-size:1.5rem;font-weight:700;white-space:nowrap}.dot{color:var(--primary)}
@keyframes slide{to{transform:translateX(-50%)}}
/* stats band */
.band{padding:46px 0;background:var(--grad);color:var(--onp)}
.stats{display:grid;grid-template-columns:repeat(4,1fr);gap:20px;text-align:center}.stat b{display:block;font-family:var(--fh);font-size:2.4rem}.stat span{opacity:.88;font-size:.92rem}
/* about */
.about{display:grid;grid-template-columns:1fr 1.1fr;gap:70px;align-items:center}
.about-vis{position:relative;height:420px;display:grid;place-items:center}
.orb{width:320px;height:320px;border-radius:50%;background:var(--grad);display:grid;place-items:center;font-size:6rem;box-shadow:var(--shadow);animation:bob 8s ease-in-out infinite}
.chip{position:absolute;background:var(--surface);border:1px solid var(--border);padding:10px 18px;border-radius:99px;font-weight:600;font-size:.9rem;box-shadow:var(--shadow)}.c1{top:30px;right:0}.c2{bottom:40px;left:0}
.ticks{list-style:none;margin:22px 0 30px;display:grid;gap:12px}.ticks li{position:relative;padding-left:32px;color:var(--muted)}.ticks li b{color:var(--text)}
.ticks li:before{content:"\2713";position:absolute;left:0;top:1px;width:22px;height:22px;border-radius:50%;background:var(--soft);color:var(--primary);display:grid;place-items:center;font-size:.8rem;font-weight:800}
/* cards */
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:24px}
.card{position:relative;background:var(--bg);border:1px solid var(--border);border-radius:var(--r);padding:32px 28px;transition:transform .35s,box-shadow .35s,border-color .35s}
.alt .card{background:var(--bg)}
.card:hover{transform:translateY(-8px);box-shadow:var(--shadow);border-color:var(--primary)}
.ico{display:grid;place-items:center;width:58px;height:58px;border-radius:calc(var(--r)*.7);background:var(--soft);font-size:1.7rem;margin-bottom:20px}
.card h3{font-size:1.2rem;margin-bottom:10px}.card p{color:var(--muted);font-size:.97rem}.no{position:absolute;top:22px;right:26px;font-family:var(--fh);font-weight:800;font-size:1.5rem;color:var(--border)}
.cards-1 .card{border-left:4px solid var(--primary)}.cards-1 .ico{background:transparent;padding:0;justify-content:start;font-size:2rem}
.cards-2 .card{background:linear-gradient(160deg,var(--soft),transparent);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px)}
.cards-3 .grid,.grid.cards-3{grid-template-columns:repeat(auto-fit,minmax(340px,1fr))}.cards-3 .card{display:grid;grid-template-columns:auto 1fr;gap:4px 20px;align-items:start}.cards-3 .ico{grid-row:span 2;margin:0}.cards-3 .no{display:none}
/* steps */
.steps{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:28px;counter-reset:s}
.step{text-align:center;padding:10px 18px}.sn{display:grid;place-items:center;width:62px;height:62px;margin:0 auto 18px;border-radius:50%;background:var(--grad);color:var(--onp);font-family:var(--fh);font-weight:800;font-size:1.4rem;box-shadow:0 12px 28px var(--soft)}
.step h3{font-size:1.1rem;margin-bottom:8px}.step p{color:var(--muted);font-size:.95rem}
/* gallery */
.gal{display:grid;grid-template-columns:repeat(3,1fr);gap:20px}
.tile{position:relative;aspect-ratio:4/3;min-height:200px;border-radius:var(--r);overflow:hidden;display:grid;place-items:center;color:#fff;transition:transform .4s}
.tile:hover{transform:scale(1.03) rotate(-.4deg)}.tile-e{font-size:4rem;filter:drop-shadow(0 8px 14px rgba(0,0,0,.3));transition:transform .4s}.tile:hover .tile-e{transform:scale(1.2)}
.tile figcaption{position:absolute;left:0;right:0;bottom:0;padding:18px 20px;font-weight:600;background:linear-gradient(transparent,rgba(0,0,0,.55))}
.t0{background:linear-gradient(135deg,var(--primary),var(--accent))}.t1{background:linear-gradient(225deg,var(--accent),var(--primary))}.t2{background:linear-gradient(45deg,var(--primary),#111)}
.t3{background:linear-gradient(315deg,var(--accent),#222)}.t4{background:radial-gradient(circle at 30% 30%,var(--accent),var(--primary))}.t5{background:radial-gradient(circle at 70% 70%,var(--primary),var(--accent))}
.tile.photo{background:var(--surface)}.tile.photo img{position:absolute;top:0;left:0;width:100%;height:100%;object-fit:cover;transition:transform .5s}.tile.photo:hover img{transform:scale(1.07)}
.hero-photo{margin-top:46px}.hero-photo img{width:100%;max-height:460px;object-fit:cover;border-radius:var(--r);box-shadow:var(--shadow)}
.hero-img{position:absolute;top:24px;bottom:24px;right:0;left:46px;width:calc(100% - 46px);height:calc(100% - 48px);object-fit:cover;border-radius:var(--r);box-shadow:var(--shadow)}
/* pricing */
.plans{display:grid;grid-template-columns:repeat(auto-fit,minmax(270px,1fr));gap:26px;align-items:stretch}
.plan{position:relative;padding:38px 30px;border-radius:var(--r);background:var(--surface);border:1px solid var(--border);display:flex;flex-direction:column;gap:16px;transition:transform .35s}
.plan:hover{transform:translateY(-8px)}.plan.pop{background:linear-gradient(var(--surface),var(--surface)) padding-box,var(--grad) border-box;border:2px solid transparent;box-shadow:var(--shadow);transform:scale(1.03)}
.ribbon{position:absolute;top:-14px;left:50%;transform:translateX(-50%);background:var(--grad);color:var(--onp);padding:5px 16px;border-radius:99px;font-size:.76rem;font-weight:700;white-space:nowrap}
.price{font-family:var(--fh);font-size:2.6rem;font-weight:800}.plan ul{list-style:none;display:grid;gap:10px;flex:1;color:var(--muted)}.plan li:before{content:"\2713 ";color:var(--primary);font-weight:800}
/* quotes */
.quotes{display:grid;grid-template-columns:repeat(auto-fit,minmax(290px,1fr));gap:24px}
.quote{background:var(--bg);border:1px solid var(--border);border-radius:var(--r);padding:30px;display:flex;flex-direction:column;gap:16px}.alt .quote{background:var(--bg)}
.quote blockquote{font-size:1.02rem;color:var(--text)}.quote figcaption{display:flex;align-items:center;gap:12px;margin-top:auto}
.av{display:grid;place-items:center;width:44px;height:44px;border-radius:50%;background:var(--grad);color:var(--onp);font-weight:700}.quote small{display:block;color:var(--muted);font-size:.82rem}
/* faq */
.faq{background:var(--bg);border:1px solid var(--border);border-radius:var(--r);padding:20px 24px;margin-bottom:14px;transition:border-color .3s}.faq[open]{border-color:var(--primary)}
.faq summary{cursor:pointer;font-family:var(--fh);font-weight:600;font-size:1.05rem;list-style:none;display:flex;justify-content:space-between;gap:12px}
.faq summary::-webkit-details-marker{display:none}.faq summary:after{content:"+";font-size:1.5rem;color:var(--primary);line-height:1}.faq[open] summary:after{content:"\2212"}
.faq p{color:var(--muted);margin-top:12px}
/* cta */
.cta{background:var(--grad);color:var(--onp);text-align:center;padding:84px 0}.cta h2{font-size:clamp(1.8rem,4vw,2.7rem);margin-bottom:12px}.cta p{opacity:.9;margin-bottom:28px}
/* contact */
.contact{display:grid;grid-template-columns:1fr 1.1fr;gap:56px;align-items:start}
.info{list-style:none;margin-top:26px;display:grid;gap:16px}.info li{display:flex;gap:14px;align-items:center}.info i{display:grid;place-items:center;width:44px;height:44px;border-radius:calc(var(--r)*.6);background:var(--soft);font-style:normal}
.form{background:var(--surface);border:1px solid var(--border);border-radius:var(--r);padding:34px;display:grid;gap:18px;box-shadow:var(--shadow)}
.form label{display:grid;gap:7px;font-weight:600;font-size:.9rem}
.form input,.form textarea{font:inherit;padding:13px 16px;border-radius:calc(var(--r)*.6);border:1.5px solid var(--border);background:var(--bg);color:var(--text);outline:none;transition:border-color .2s,box-shadow .2s}
.form input:focus,.form textarea:focus{border-color:var(--primary);box-shadow:0 0 0 4px var(--soft)}.form .bad{border-color:#e11d48}
.ok{color:var(--primary);font-weight:600;min-height:1.4em}
/* footer */
.foot{background:var(--surface);border-top:1px solid var(--border);padding:64px 0 24px}
.foot-in{display:grid;grid-template-columns:1.6fr 1fr 1fr;gap:40px;margin-bottom:40px}.foot p{color:var(--muted);margin-top:14px;max-width:340px}
.foot h4{margin-bottom:14px;font-size:1rem}.foot-in a:not(.logo){display:block;color:var(--muted);margin-bottom:9px;font-size:.94rem;transition:color .2s}.foot-in a:not(.logo):hover{color:var(--primary)}
.copy{display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;padding-top:22px;border-top:1px solid var(--border);color:var(--muted);font-size:.85rem}
.totop{position:fixed;right:22px;bottom:22px;width:46px;height:46px;border-radius:50%;background:var(--grad);color:var(--onp);display:grid;place-items:center;font-weight:700;box-shadow:var(--shadow);opacity:0;pointer-events:none;transform:translateY(12px);transition:.3s;z-index:55}.totop.show{opacity:1;pointer-events:auto;transform:none}
.progress{position:fixed;top:0;left:0;height:3px;width:0;background:var(--grad);z-index:80}
/* reveal */
.js .rv{opacity:0;transform:translateY(26px);transition:opacity .8s ease,transform .8s ease}.js .rv.in{opacity:1;transform:none}
@media(max-width:900px){
section{padding:70px 0}.hero{padding:120px 0 60px}
.hero-1 .hero-in,.hero-2 .hero-in,.about,.contact{grid-template-columns:1fr}.hero-vis{height:380px}.about-vis{height:340px}.orb{width:250px;height:250px;font-size:4.5rem}
.stats{grid-template-columns:repeat(2,1fr)}.gal{grid-template-columns:repeat(2,1fr)}.foot-in{grid-template-columns:1fr}
.burger{display:flex}.mode{margin-left:auto}
.links{position:fixed;top:72px;left:0;right:0;flex-direction:column;align-items:stretch;gap:0;background:var(--bg);border-bottom:1px solid var(--border);padding:10px 5% 22px;transform:translateY(-130%);transition:transform .35s;margin-left:0;max-height:calc(100vh - 72px);overflow:auto}
.links.open{transform:none}.links a:not(.btn){padding:14px 4px;border-bottom:1px solid var(--border);color:var(--text)}.nav-cta{margin-top:16px}
.plan.pop{transform:none}.cards-3 .grid,.grid.cards-3{grid-template-columns:1fr}
}
@media(max-width:560px){.gal{grid-template-columns:1fr}.hero h1{font-size:2.3rem}.hero-3 h1{font-size:2.9rem!important}.g1{max-width:260px}.g3{left:0;max-width:250px}.trust{gap:22px}.form{padding:24px}}
@media(prefers-reduced-motion:reduce){*{animation:none!important;transition:none!important}.js .rv{opacity:1;transform:none}}
"""

    private val JS = """
(function(){
var d=document,root=d.documentElement;root.classList.add('js');
var nav=d.querySelector('.nav'),burger=d.querySelector('.burger'),links=d.querySelector('.links');
if(burger&&links){burger.addEventListener('click',function(){links.classList.toggle('open');burger.classList.toggle('on');});
 [].forEach.call(links.querySelectorAll('a'),function(a){a.addEventListener('click',function(){links.classList.remove('open');burger.classList.remove('on');});});}
var bar=d.querySelector('.progress'),up=d.querySelector('.totop');
function onScroll(){var y=window.pageYOffset||root.scrollTop;if(nav)nav.classList.toggle('scrolled',y>30);
 if(bar){var h=root.scrollHeight-window.innerHeight;bar.style.width=(h>0?y/h*100:0)+'%';}
 if(up)up.classList.toggle('show',y>500);}
window.addEventListener('scroll',onScroll,{passive:true});onScroll();
var mode=d.querySelector('.mode');
if(mode){mode.addEventListener('click',function(){root.setAttribute('data-mode',root.getAttribute('data-mode')==='alt'?'main':'alt');});}
function counter(el){var t=el.textContent.trim(),m=t.match(/^([^0-9]*)([0-9][0-9,]*[.]?[0-9]*)(.*)/);if(!m)return;
 var to=parseFloat(m[2].replace(/,/g,'')),dec=(m[2].split('.')[1]||'').length,start=null,dur=1400;
 function step(ts){if(start===null)start=ts;var p=Math.min(1,(ts-start)/dur),v=to*(1-Math.pow(1-p,3));
  var s=v.toFixed(dec);if(m[2].indexOf(',')>-1)s=Number(s).toLocaleString('en-US');el.textContent=m[1]+s+m[3];if(p<1)requestAnimationFrame(step);else el.textContent=t;}
 requestAnimationFrame(step);}
var rv=d.querySelectorAll('.rv');
if('IntersectionObserver' in window){var fired=false;var io=new IntersectionObserver(function(es){es.forEach(function(e){if(e.isIntersecting){fired=true;e.target.classList.add('in');
  [].forEach.call(e.target.querySelectorAll('.num'),counter);if(e.target.classList.contains('num'))counter(e.target);io.unobserve(e.target);}});},{threshold:.12});
 [].forEach.call(rv,function(el){io.observe(el);});
 setTimeout(function(){if(!fired)[].forEach.call(rv,function(el){el.classList.add('in');});},1500);}else{[].forEach.call(rv,function(el){el.classList.add('in');});}
var form=d.querySelector('.form');
if(form){form.addEventListener('submit',function(e){e.preventDefault();var ok=true;
 [].forEach.call(form.querySelectorAll('input,textarea'),function(f){var bad=!f.value.trim()||(f.type==='email'&&!/^[^@\s]+@[^@\s]+[.][^@\s]+/.test(f.value));f.classList.toggle('bad',bad);if(bad)ok=false;});
 var msg=form.querySelector('.ok');
 if(!ok){msg.textContent='Please fill in every field with a valid email.';return;}
 msg.textContent='Thank you, '+form.name.value.trim().split(' ')[0]+'! Your message has been sent \u2014 we will reply soon.';form.reset();});}
[].forEach.call(d.querySelectorAll('a[href^="#"]'),function(a){a.addEventListener('click',function(e){var id=a.getAttribute('href').slice(1),t=id?d.getElementById(id):d.body;if(t){e.preventDefault();t.scrollIntoView({behavior:'smooth',block:'start'});}});});
var yr=d.querySelector('.yr');if(yr)yr.textContent=new Date().getFullYear();
})();
"""
}
