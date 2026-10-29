package com.astra.agent.site

class Feature(val title: String, val text: String)
class Stat(val value: String, val label: String)
class Quote(val name: String, val role: String, val text: String)
class Faq(val q: String, val a: String)
class Plan(val name: String, val price: String, val items: List<String>)

/** Everything a website template needs. The AI writes the short parts; [SiteContent] fills in the rest. */
class SiteSpec(
    val name: String, val tagline: String, val about: String, val category: String,
    val features: List<Feature>, val stats: List<Stat>, val quotes: List<Quote>, val faqs: List<Faq>,
    val steps: List<Feature>, val plans: List<Plan>, val gallery: List<Feature>,
    val cta: String, val email: String, val phone: String, val address: String
)

/** Built-in wording for one kind of website. */
internal class Cat(
    val id: String, val keys: Regex, val noun: String, val tagline: String, val about: String,
    val features: List<Feature>, val emoji: List<String>, val stats: List<Stat>, val gallery: List<Feature>,
    val planNames: List<String>, val planPrices: List<String>, val cta: String
)

/**
 * Turns the user's request and the model's short answer into a complete [SiteSpec].
 * Anything the model left out (or got wrong) is replaced by sensible built-in content, so a page is never empty.
 */
object SiteContent {
    private fun f(t: String, x: String) = Feature(t, x)
    private fun s(v: String, l: String) = Stat(v, l)
    private val IC = RegexOption.IGNORE_CASE

    internal val cats: List<Cat> = listOf(
        Cat("restaurant", Regex("restaurant|cafe|caf\u00e9|bakery|pizza|burger|food|kitchen|diner|bistro|coffee|bar\\b|sweets|catering|juice|ice ?cream", IC), "dining",
            "Fresh flavours, made with love", "{name} serves handmade dishes cooked from fresh, local ingredients. Come for a quick bite, a family dinner or a celebration \u2014 every plate is made to order and every guest is treated like family.",
            listOf(f("Fresh ingredients", "Sourced every morning from trusted local farms and suppliers."), f("Signature recipes", "Time-tested recipes with a modern twist you will not find elsewhere."), f("Dine in or take away", "Cosy seating, quick pickup and reliable delivery to your door."), f("Catering & events", "Birthdays, office lunches and weddings \u2014 we cook for any crowd."), f("Vegetarian friendly", "A generous menu of vegetarian, vegan and gluten-free choices."), f("Open late", "Open every day, with relaxed evening hours for a proper dinner.")),
            listOf("\uD83C\uDF7D\uFE0F", "\uD83C\uDF45", "\uD83D\uDE9A", "\uD83C\uDF89", "\uD83E\uDD57", "\uD83C\uDF19"),
            listOf(s("15+", "Years of taste"), s("120+", "Dishes on the menu"), s("50k+", "Happy guests"), s("4.9", "Average rating")),
            listOf(f("Chef's specials", "\uD83C\uDF5D"), f("Fresh from the oven", "\uD83E\uDD50"), f("Sweet endings", "\uD83C\uDF70"), f("Healthy bowls", "\uD83E\uDD57"), f("Coffee & more", "\u2615"), f("Weekend brunch", "\uD83C\uDF73")),
            listOf("Lunch Set", "Family Feast", "Party Catering"), listOf("\u20B9249", "\u20B9899", "\u20B94,999"), "Reserve a table"),
        Cat("portfolio", Regex("portfolio|photographer|photography|designer|developer|freelanc|artist|resume|cv\\b|personal|author|writer|illustrat|video editor", IC), "work",
            "Design and code that people remember", "{name} is a creative professional who turns ideas into clear, polished work. With a focus on detail, honest communication and on-time delivery, every project is built to help you stand out.",
            listOf(f("Brand identity", "Logos, colours and guidelines that make your brand instantly recognisable."), f("Web design", "Fast, responsive websites that look great on every screen."), f("Development", "Clean, maintainable code and smooth interactions that just work."), f("UI / UX", "Interfaces designed around real people and real goals."), f("Photography", "Natural, story-driven images for products, people and places."), f("Consulting", "Friendly, practical advice to move your project forward.")),
            listOf("\uD83C\uDFA8", "\uD83D\uDCBB", "\u2699\uFE0F", "\uD83D\uDCF1", "\uD83D\uDCF7", "\uD83D\uDCA1"),
            listOf(s("8+", "Years of experience"), s("150+", "Projects delivered"), s("60+", "Happy clients"), s("12", "Awards")),
            listOf(f("Brand refresh", "\uD83C\uDFA8"), f("Mobile app UI", "\uD83D\uDCF1"), f("E-commerce site", "\uD83D\uDED2"), f("Product shoot", "\uD83D\uDCF7"), f("Dashboard design", "\uD83D\uDCCA"), f("Landing page", "\uD83D\uDE80")),
            listOf("Starter", "Professional", "Studio"), listOf("\u20B99,999", "\u20B924,999", "\u20B959,999"), "Hire me"),
        Cat("shop", Regex("shop|store|boutique|fashion|cloth|apparel|jewel|shoe|ecommerce|e-commerce|gift|furniture|electronics|mobile store|grocery|supermarket|market", IC), "products",
            "Everything you love, delivered", "{name} brings you carefully chosen products at fair prices. We check quality, pack with care and ship quickly, so shopping with us is simple, safe and enjoyable.",
            listOf(f("Curated collection", "Hand-picked products that meet our quality standards."), f("Fast delivery", "Free shipping on most orders, delivered to your door in days."), f("Easy returns", "Changed your mind? Return within 14 days, no questions asked."), f("Secure payments", "Pay with cards, UPI or cash on delivery \u2014 always protected."), f("Member offers", "Early access to sales and special prices for our regulars."), f("Friendly support", "Real people ready to help you every day of the week.")),
            listOf("\uD83D\uDECD\uFE0F", "\uD83D\uDE9A", "\u21A9\uFE0F", "\uD83D\uDD12", "\uD83C\uDF81", "\uD83D\uDCAC"),
            listOf(s("5k+", "Products"), s("40k+", "Orders shipped"), s("98%", "Happy customers"), s("24/7", "Support")),
            listOf(f("New arrivals", "\uD83C\uDD95"), f("Best sellers", "\u2B50"), f("Seasonal offers", "\uD83C\uDFF7\uFE0F"), f("Gift ideas", "\uD83C\uDF81"), f("Trending now", "\uD83D\uDD25"), f("Clearance", "\uD83D\uDCB8")),
            listOf("Basic", "Plus", "VIP"), listOf("Free", "\u20B9499/yr", "\u20B91,499/yr"), "Shop now"),
        Cat("education", Regex("school|college|course|class|tutor|coaching|academy|learn|education|training|institute|university|study|teacher|kids", IC), "learning",
            "Learn with confidence, grow with purpose", "{name} helps students learn faster with clear lessons, friendly teachers and regular practice. Whether you are starting out or aiming higher, we guide you at every step.",
            listOf(f("Expert teachers", "Experienced mentors who explain things simply and patiently."), f("Flexible batches", "Morning, evening and weekend classes that fit your routine."), f("Practice & tests", "Regular quizzes and mock tests to track real progress."), f("Small groups", "Personal attention in classes of limited size."), f("Study material", "Clear notes and recorded lessons you can revisit anytime."), f("Career guidance", "Advice on choosing the right path and preparing for it.")),
            listOf("\uD83D\uDC69\u200D\uD83C\uDFEB", "\uD83D\uDCC5", "\uD83D\uDCDD", "\uD83D\uDC65", "\uD83D\uDCDA", "\uD83C\uDFAF"),
            listOf(s("12+", "Years teaching"), s("5,000+", "Students"), s("95%", "Success rate"), s("40+", "Courses")),
            listOf(f("Live classes", "\uD83C\uDFA5"), f("Science lab", "\uD83D\uDD2C"), f("Library", "\uD83D\uDCDA"), f("Sports day", "\uD83C\uDFC6"), f("Workshops", "\uD83D\uDEE0\uFE0F"), f("Graduation", "\uD83C\uDF93")),
            listOf("Basic Course", "Full Programme", "Mentorship"), listOf("\u20B92,999", "\u20B99,999", "\u20B919,999"), "Enrol today"),
        Cat("fitness", Regex("gym|fitness|yoga|workout|trainer|sport|wellness|zumba|crossfit|martial|dance|swim", IC), "training",
            "Stronger every single day", "{name} is a friendly, motivating place to get fit. Our certified trainers build programmes around your goals, so you train smarter, stay consistent and enjoy the results.",
            listOf(f("Certified trainers", "Coaches who guide your form, plan and progress."), f("Modern equipment", "Clean, well-kept machines and free weights for every level."), f("Group classes", "Yoga, HIIT, zumba and more \u2014 energy that keeps you going."), f("Personal plans", "Workout and nutrition plans made for your body and goals."), f("Flexible hours", "Open early and late so training always fits your day."), f("Community", "A supportive crowd that celebrates every milestone.")),
            listOf("\uD83C\uDFCB\uFE0F", "\u2699\uFE0F", "\uD83E\uDDD8", "\uD83D\uDCCB", "\u23F0", "\uD83E\uDD1D"),
            listOf(s("10+", "Years open"), s("2,500+", "Members"), s("30+", "Weekly classes"), s("15", "Expert trainers")),
            listOf(f("Strength zone", "\uD83C\uDFCB\uFE0F"), f("Yoga studio", "\uD83E\uDDD8"), f("Cardio area", "\uD83C\uDFC3"), f("Group HIIT", "\uD83D\uDD25"), f("Smoothie bar", "\uD83E\uDD64"), f("Personal coaching", "\uD83C\uDFAF")),
            listOf("Monthly", "Quarterly", "Annual"), listOf("\u20B9999", "\u20B92,499", "\u20B96,999"), "Join now"),
        Cat("travel", Regex("travel|tour|hotel|resort|trip|holiday|vacation|booking|stay|homestay|trek|adventure|safari", IC), "journeys",
            "Your next adventure starts here", "{name} plans trips that feel effortless. From hand-picked stays to local guides and smooth transport, we take care of the details so you can simply enjoy the journey.",
            listOf(f("Handpicked stays", "Comfortable, well-reviewed hotels and homestays everywhere."), f("Local guides", "Friendly experts who show you the places only locals know."), f("Flexible plans", "Change dates or destinations without the stress."), f("Best prices", "Honest, all-inclusive packages with no hidden costs."), f("Safe travel", "24/7 on-trip support and trusted transport partners."), f("Custom trips", "Honeymoons, families, groups \u2014 planned around you.")),
            listOf("\uD83C\uDFE8", "\uD83E\uDDED", "\uD83D\uDCC5", "\uD83D\uDCB0", "\uD83D\uDEE1\uFE0F", "\uD83C\uDF34"),
            listOf(s("80+", "Destinations"), s("12k+", "Happy travellers"), s("4.8", "Average rating"), s("24/7", "Trip support")),
            listOf(f("Beach escape", "\uD83C\uDFD6\uFE0F"), f("Mountain trek", "\u26F0\uFE0F"), f("City break", "\uD83C\uDFD9\uFE0F"), f("Wildlife safari", "\uD83E\uDD81"), f("Heritage tour", "\uD83C\uDFF0"), f("Island hopping", "\u26F5")),
            listOf("Weekend", "Week Away", "Grand Tour"), listOf("\u20B97,999", "\u20B924,999", "\u20B974,999"), "Plan my trip"),
        Cat("tech", Regex("tech|saas|software|startup|app\\b|platform|ai\\b|cloud|digital|it company|website builder|crypto|data|cyber|robot", IC), "software",
            "Build faster. Scale smarter.", "{name} gives teams a simple, powerful platform to get more done. Set up in minutes, connect the tools you already use and watch your work move faster with less effort.",
            listOf(f("Quick setup", "Get started in minutes with no complicated configuration."), f("Smart automation", "Let routine work run itself so your team can focus."), f("Live analytics", "Clear dashboards that show what is working right now."), f("Secure by design", "Encryption, backups and access controls built in."), f("Integrations", "Connect your favourite tools with a few clicks."), f("Friendly support", "Real help from a team that actually answers.")),
            listOf("\u26A1", "\uD83E\uDD16", "\uD83D\uDCCA", "\uD83D\uDD12", "\uD83D\uDD17", "\uD83D\uDCAC"),
            listOf(s("10k+", "Active users"), s("99.9%", "Uptime"), s("50+", "Integrations"), s("4.9", "User rating")),
            listOf(f("Dashboard", "\uD83D\uDCCA"), f("Automation", "\uD83E\uDD16"), f("Team chat", "\uD83D\uDCAC"), f("Reports", "\uD83D\uDCC8"), f("Mobile app", "\uD83D\uDCF1"), f("API access", "\uD83D\uDD0C")),
            listOf("Starter", "Growth", "Enterprise"), listOf("Free", "\u20B9999/mo", "Custom"), "Start free"),
        Cat("health", Regex("clinic|doctor|dental|dentist|hospital|medical|health|pharmacy|care|therapy|physio|skin|salon|spa|beauty|hair", IC), "care",
            "Caring for you, every step of the way", "{name} offers gentle, professional care in a calm and friendly setting. Our experienced team listens first, explains clearly and tailors every treatment to you.",
            listOf(f("Experienced team", "Qualified specialists with years of hands-on experience."), f("Modern facility", "Clean, comfortable rooms with up-to-date equipment."), f("Easy appointments", "Book online or by phone \u2014 short waits, always on time."), f("Personal care", "Treatment plans designed around your needs and comfort."), f("Transparent pricing", "Clear costs explained before any treatment begins."), f("Emergency support", "Quick help when you need it most.")),
            listOf("\uD83D\uDC69\u200D\u2695\uFE0F", "\uD83C\uDFE5", "\uD83D\uDCC5", "\uD83D\uDC9A", "\uD83E\uDDFE", "\uD83D\uDE91"),
            listOf(s("15+", "Years of care"), s("20k+", "Patients"), s("12", "Specialists"), s("4.9", "Patient rating")),
            listOf(f("Reception", "\uD83C\uDFE5"), f("Consultation", "\uD83D\uDC69\u200D\u2695\uFE0F"), f("Treatment room", "\uD83D\uDECF\uFE0F"), f("Wellness", "\uD83C\uDF3F"), f("Diagnostics", "\uD83D\uDD2C"), f("Recovery", "\uD83D\uDC9A")),
            listOf("Check-up", "Care Plan", "Family Pack"), listOf("\u20B9499", "\u20B91,999", "\u20B94,999"), "Book appointment"),
        Cat("realestate", Regex("real ?estate|property|properties|apartment|builder|construction|interior|architect|villa|flat|housing|rental", IC), "homes",
            "Find a place you will love to call home", "{name} helps families and investors find the right property with honest advice. We handle viewings, paperwork and negotiation, so buying, selling or renting feels easy.",
            listOf(f("Verified listings", "Every home is checked in person before it appears here."), f("Local expertise", "Deep knowledge of the best areas, prices and trends."), f("Smooth paperwork", "We guide you through documents, loans and registration."), f("Virtual tours", "See homes from your sofa before you visit."), f("Fair negotiation", "We work to get you the best possible deal."), f("After-sales help", "Support with move-in, interiors and utilities.")),
            listOf("\u2705", "\uD83D\uDDFA\uFE0F", "\uD83D\uDCC4", "\uD83C\uDFE0", "\uD83E\uDD1D", "\uD83D\uDD11"),
            listOf(s("500+", "Homes sold"), s("12+", "Years in business"), s("98%", "Client satisfaction"), s("25", "Prime locations")),
            listOf(f("Modern villa", "\uD83C\uDFE1"), f("City apartment", "\uD83C\uDFE2"), f("Garden home", "\uD83C\uDF33"), f("Penthouse", "\uD83C\uDF06"), f("Studio flat", "\uD83D\uDECB\uFE0F"), f("Commercial space", "\uD83C\uDFEC")),
            listOf("Buyer", "Seller", "Investor"), listOf("Free", "1% fee", "Custom"), "Schedule a visit"),
        Cat("event", Regex("event|wedding|party|festival|conference|concert|birthday|photography studio|planner|decor|expo|summit|meetup", IC), "events",
            "Moments worth remembering", "{name} plans and runs events that people talk about for years. From the first idea to the last guest leaving, we handle every detail with creativity and care.",
            listOf(f("Full planning", "Venue, decor, food and schedule \u2014 all managed for you."), f("Creative themes", "Fresh ideas tailored to your style and story."), f("Trusted vendors", "Caterers, DJs and photographers we know and trust."), f("On-day team", "A calm, experienced crew making everything run smoothly."), f("Any budget", "Clear options at every price point, no surprises."), f("Memories kept", "Photo and video coverage to relive the day.")),
            listOf("\uD83D\uDCCB", "\uD83C\uDFA8", "\uD83E\uDD1D", "\uD83D\uDC65", "\uD83D\uDCB0", "\uD83D\uDCF8"),
            listOf(s("10+", "Years of events"), s("800+", "Events hosted"), s("100k+", "Guests"), s("4.9", "Client rating")),
            listOf(f("Wedding stage", "\uD83D\uDC92"), f("Birthday party", "\uD83C\uDF82"), f("Corporate summit", "\uD83C\uDFA4"), f("Live concert", "\uD83C\uDFB6"), f("Garden reception", "\uD83C\uDF3A"), f("Festival night", "\uD83C\uDF86")),
            listOf("Essential", "Signature", "Grand"), listOf("\u20B919,999", "\u20B974,999", "\u20B92,49,999"), "Plan my event"),
        Cat("charity", Regex("charity|ngo|donat|non-?profit|foundation|volunteer|trust\\b|NGO|relief|community|welfare", IC), "impact",
            "Small acts, big change", "{name} works with local communities to bring education, food and care to people who need it most. Every donation and every hour of volunteering goes straight into real, visible impact.",
            listOf(f("Education for all", "Scholarships, books and classrooms for children in need."), f("Food & shelter", "Meals and safe places for families facing hardship."), f("Health camps", "Free check-ups and medicines in underserved areas."), f("Women's skills", "Training and micro-loans that build independence."), f("Clean water", "Wells and filters that protect whole villages."), f("Transparent giving", "Every rupee reported \u2014 you always see where it goes.")),
            listOf("\uD83D\uDCDA", "\uD83C\uDF72", "\uD83E\uDE7A", "\uD83E\uDDF5", "\uD83D\uDCA7", "\uD83D\uDCCA"),
            listOf(s("15k+", "Lives touched"), s("300+", "Volunteers"), s("120", "Villages reached"), s("92%", "Spent on programmes")),
            listOf(f("School drive", "\uD83C\uDFEB"), f("Food camp", "\uD83C\uDF5B"), f("Health camp", "\uD83E\uDE7A"), f("Tree planting", "\uD83C\uDF33"), f("Clean water", "\uD83D\uDCA7"), f("Community day", "\uD83E\uDD1D")),
            listOf("Supporter", "Champion", "Patron"), listOf("\u20B9500", "\u20B92,000", "\u20B910,000"), "Donate now"),
        Cat("business", Regex(".*", IC), "services",
            "Solutions that help your business grow", "{name} is a trusted partner for growing businesses. Our experienced team listens to your goals, builds a clear plan and delivers results you can measure \u2014 on time and with total transparency.",
            listOf(f("Strategy & planning", "Clear roadmaps that turn your goals into measurable steps."), f("Expert team", "Specialists with years of real-world experience."), f("Quality delivery", "Careful work, on schedule, with regular progress updates."), f("Customer first", "Friendly support and honest advice at every stage."), f("Fair pricing", "Transparent packages with no hidden costs."), f("Proven results", "Hundreds of happy clients and projects delivered.")),
            listOf("\uD83D\uDCC8", "\uD83D\uDC65", "\u2705", "\uD83E\uDD1D", "\uD83D\uDCB0", "\uD83C\uDFC6"),
            listOf(s("12+", "Years of experience"), s("500+", "Projects completed"), s("300+", "Happy clients"), s("24/7", "Support")),
            listOf(f("Team workshop", "\uD83D\uDC65"), f("Client meeting", "\uD83E\uDD1D"), f("Growth report", "\uD83D\uDCCA"), f("Product launch", "\uD83D\uDE80"), f("Training day", "\uD83C\uDF93"), f("Award night", "\uD83C\uDFC6")),
            listOf("Starter", "Business", "Enterprise"), listOf("\u20B94,999", "\u20B914,999", "Custom"), "Get a free quote"),
    )

    internal fun category(id: String?, request: String): Cat {
        val byId = cats.firstOrNull { it.id.equals(id?.trim(), true) }
        if (byId != null) return byId
        return cats.dropLast(1).firstOrNull { it.keys.containsMatchIn(request) } ?: cats.last()
    }

    private fun title(s: String): String = s.trim().split(Regex("\\s+")).joinToString(" ") { w ->
        if (w.length <= 3 && w.lowercase() in setOf("and", "the", "for", "of", "in", "on", "a", "an")) w.lowercase()
        else w.replaceFirstChar { it.uppercase() }
    }.replaceFirstChar { it.uppercase() }

    /** "website for my pizza shop" -> "Pizza Shop"; "bakery called Sweet Crumbs" -> "Sweet Crumbs" */
    fun nameFromRequest(request: String): String {
        Regex("(?:called|named|name is|name:)\\s+[\"']?([A-Za-z0-9&' .-]{2,40}?)[\"']?(?:\\s+(?:website|web site|site|page|with|using|that|in|on|and)\\b|[,.]|$)", IC).find(request)?.let { return title(it.groupValues[1]) }
        val m = Regex("\\b(?:for|of|about)\\s+(?:my|a|an|the|our|me)?\\s*(.{2,40}?)(?:\\s+(?:website|web site|site|web ?page|page|with|using|that|in|on|and)\\b|[,.]|$)", IC).find(request)
        val subject = m?.groupValues?.get(1)?.trim().orEmpty()
        if (subject.isNotBlank() && subject.split(" ").size <= 5) return title(subject)
        return "Your Brand"
    }

    private fun clean(s: String): String = s.replace(Regex("[*_`#>]+"), "").replace(Regex("\\s+"), " ").trim().trim('"', '\u201C', '\u201D')

    private fun parts(rest: String, n: Int): List<String> {
        val p = rest.split('|').map { clean(it) }
        return if (p.size >= n) p else p + List(n - p.size) { "" }
    }

    fun build(request: String, modelText: String): SiteSpec {
        val kv = HashMap<String, MutableList<String>>()
        for (raw in modelText.lines()) {
            val line = raw.trim().trimStart('-', '*', '\u2022', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace("**", "")
            val i = line.indexOf(':')
            if (i < 2 || i > 14) continue
            val key = line.substring(0, i).trim().uppercase()
            val v = line.substring(i + 1).trim()
            if (v.isNotEmpty()) kv.getOrPut(key) { ArrayList() }.add(v)
        }
        fun one(k: String): String = kv[k]?.firstOrNull()?.let { clean(it) }.orEmpty()
        val cat = category(one("CATEGORY"), request)
        val name = one("NAME").takeIf { it.length in 2..40 } ?: nameFromRequest(request)
        val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "").ifBlank { "yourbrand" }
        fun fill(t: String) = t.replace("{name}", name)

        val features = (kv["FEATURE"] ?: emptyList()).map { parts(it, 2) }.filter { it[0].isNotBlank() && it[1].isNotBlank() }.map { Feature(it[0], it[1]) }
            .let { got -> got + cat.features.drop(got.size) }.take(6)
        val stats = (kv["STAT"] ?: emptyList()).map { parts(it, 2) }.filter { it[0].isNotBlank() && it[1].isNotBlank() }.map { Stat(it[0], it[1]) }
            .let { got -> got + cat.stats.drop(got.size) }.take(4)
        val quotes = (kv["QUOTE"] ?: emptyList()).map { parts(it, 3) }.filter { it[0].isNotBlank() && it[2].isNotBlank() }.map { Quote(it[0], it[1].ifBlank { "Customer" }, it[2]) }
            .let { got -> got + defaultQuotes(name, cat).drop(got.size) }.take(3)
        val faqs = (kv["FAQ"] ?: emptyList()).map { parts(it, 2) }.filter { it[0].isNotBlank() && it[1].isNotBlank() }.map { Faq(it[0], it[1]) }
            .let { got -> got + defaultFaqs(name, cat).drop(got.size) }.take(5)
        val steps = (kv["STEP"] ?: emptyList()).map { parts(it, 2) }.filter { it[0].isNotBlank() && it[1].isNotBlank() }.map { Feature(it[0], it[1]) }
            .let { got -> got + defaultSteps(cat).drop(got.size) }.take(4)
        val about = one("ABOUT").takeIf { it.length > 30 } ?: fill(cat.about)
        val plans = cat.planNames.indices.map { i ->
            Plan(cat.planNames[i], cat.planPrices[i], listOf("Everything you need to get started", "Friendly, fast support", if (i == 0) "Simple and affordable" else if (i == 1) "Priority attention and extras" else "Premium service, tailored to you"))
        }
        return SiteSpec(
            name = name, tagline = one("TAGLINE").takeIf { it.length in 4..90 } ?: cat.tagline, about = about, category = cat.id,
            features = features, stats = stats, quotes = quotes, faqs = faqs, steps = steps, plans = plans,
            gallery = cat.gallery, cta = one("CTA").takeIf { it.length in 2..28 } ?: cat.cta,
            email = one("EMAIL").takeIf { it.contains("@") } ?: "hello@$slug.com",
            phone = one("PHONE").takeIf { it.count { c -> c.isDigit() } >= 6 } ?: "+91 98765 43210",
            address = one("ADDRESS").takeIf { it.length > 6 } ?: "12 Main Street, Your City"
        )
    }

    private fun defaultQuotes(name: String, c: Cat) = listOf(
        Quote("Priya Sharma", "Happy customer", "Absolutely wonderful experience. $name exceeded every expectation \u2014 friendly people and excellent ${c.noun}."),
        Quote("Rahul Mehta", "Regular client", "Professional, reliable and genuinely caring. I recommend $name to everyone I know."),
        Quote("Anita Desai", "Verified customer", "From the first message to the final result, everything was smooth. Truly five stars."))

    private fun defaultFaqs(name: String, c: Cat) = listOf(
        Faq("What makes $name different?", "We focus on quality, honest communication and a personal touch in everything we do."),
        Faq("How do I get started?", "Just use the contact form or call us \u2014 we will reply within one working day."),
        Faq("Do you offer custom options?", "Yes. Tell us what you need and we will shape our ${c.noun} around it."),
        Faq("What are your opening hours?", "We are open Monday to Saturday, 9 am to 7 pm, and reachable by message any time."),
        Faq("Can I get a refund or change my mind?", "Of course. If you are not happy, tell us and we will make it right."))

    private fun defaultSteps(c: Cat) = listOf(
        Feature("Tell us what you need", "Share your goals in a quick message or call."),
        Feature("We make a plan", "You get a clear proposal with timing and pricing."),
        Feature("We get to work", "Our team delivers with regular updates along the way."),
        Feature("You enjoy the result", "Review, refine and love the final ${c.noun}."))
}
