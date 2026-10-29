package com.astra.agent.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.astra.agent.device.AstraAccessibilityService
import com.astra.agent.device.BrowserHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class Param(val name: String, val type: String, val desc: String)

class ToolDef(
    val name: String, val desc: String, val params: List<Param>,
    val risky: Boolean, val exec: suspend (JSONObject) -> String
) {
    fun spec(): JSONObject = JSONObject().put("type", "function").put("function",
        JSONObject().put("name", name).put("description", desc).put("parameters",
            JSONObject().put("type", "object")
                .put("properties", JSONObject().also { p -> params.forEach { p.put(it.name, JSONObject().put("type", it.type).put("description", it.desc)) } })
                .put("required", JSONArray(params.map { it.name }))))

    /** One short line per tool: far fewer tokens than the full JSON schema, which matters a lot for a phone-sized model. */
    fun compact(): String {
        val d = if (name == "browser_script") desc else desc.substringBefore(". ").take(150)
        return "$name(${params.joinToString(", ") { it.name + ": " + it.type }}) - $d"
    }
}

class ToolRegistry(ctx: Context) {
    private val app = ctx.applicationContext
    private val browser = BrowserHost(app)
    val workspace = File(app.filesDir, "workspace").apply { mkdirs() }
    private val noAcc = "Accessibility service is OFF. Tell the user to enable ZENIQ in Settings > Accessibility."

    private fun acc() = AstraAccessibilityService.instance
    private fun launchCtx(): Context = acc() ?: app
    private suspend fun withAcc(block: suspend (AstraAccessibilityService) -> String): String {
        val s = acc() ?: return noAcc
        return block(s)
    }

    val tools: List<ToolDef> = listOf(
        // ── Phone control ────────────────────────────────────────────────────
        ToolDef("open_app",
            "Open an installed app by name OR a category like 'browser', 'camera', 'gallery', 'email', 'maps', 'music', 'phone', 'messages', 'settings', 'files', 'calculator', 'clock', 'contacts'. Waits for the app to load, then returns a screen snapshot.",
            listOf(Param("name", "string", "App name or category, e.g. 'browser', 'camera', 'Chrome', 'Notes'")), false) { a ->
            openApp(a.optString("name"))
        },

        ToolDef("open_url",
            "Open a URL in the phone's default browser app.",
            listOf(Param("url", "string", "Full URL")), false) { a ->
            val url = a.optString("url").trim()
            if (url.isBlank()) return@ToolDef "No URL provided."
            try {
                val uri = Uri.parse(if (url.startsWith("http")) url else "https://$url")
                launchCtx().startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(2000)
                "Opened $url"
            } catch (e: Exception) { "Failed to open URL: ${e.message}" }
        },

        ToolDef("read_screen",
            "List visible text and tappable items on the current screen with [x,y] coordinates. Always call this after opening an app or performing an action.",
            emptyList(), false) { _ ->
            withAcc { it.dumpScreen() }
        },

        ToolDef("look_at_screen",
            "Take a screenshot and have a vision model describe the screen: app, visible elements with [x,y] coordinates, dialogs and the best next action. Use it when read_screen is empty or unclear (games, images, custom views). The screenshot is sent to the online model for this request only.",
            listOf(Param("task", "string", "What you are trying to do, so the description focuses on it")), false) { a ->
            withAcc { s -> com.astra.agent.agent.ScreenVision.describe(app, s, a.optString("task")) }
        },

        ToolDef("tap_text",
            "Tap the on-screen element whose text or description matches. Use read_screen first to see what is available.",
            listOf(Param("text", "string", "Label to tap")), true) { a ->
            withAcc { s ->
                val text = a.optString("text")
                if (s.tapText(text)) {
                    delay(600) // wait for UI to respond
                    "Tapped '$text'. Call read_screen to see result."
                } else {
                    // No clickable node: tap the centre of the text's position instead
                    val r = s.boundsOfText(text)
                    if (r != null && s.tapXY(r.centerX().toFloat(), r.centerY().toFloat())) {
                        delay(600)
                        "Tapped '$text'. Call read_screen to see result."
                    } else {
                        "No tappable element with text '$text'. Call read_screen to see current screen."
                    }
                }
            }
        },

        ToolDef("tap_xy",
            "Tap at exact screen coordinates. Use only when read_screen gives you specific coordinates.",
            listOf(Param("x", "number", "X coordinate"), Param("y", "number", "Y coordinate")), true) { a ->
            withAcc { s ->
                if (s.tapXY(a.optDouble("x").toFloat(), a.optDouble("y").toFloat())) {
                    delay(600)
                    "Tapped (${a.optInt("x")}, ${a.optInt("y")}). Call read_screen to verify."
                } else "Tap failed"
            }
        },

        ToolDef("type_text",
            "Type text into the currently focused input field. You MUST tap the input field first with tap_text or tap_xy, then call type_text.",
            listOf(Param("text", "string", "Text to type")), true) { a ->
            withAcc { s ->
                val text = a.optString("text")
                if (s.typeText(text)) {
                    delay(400)
                    "Typed: $text"
                } else {
                    "No focused input field found. Use tap_text to tap on a text field first, then type_text."
                }
            }
        },

        ToolDef("swipe",
            "Swipe the screen. direction is where the finger moves: up (scrolls content down), down, left, right.",
            listOf(Param("direction", "string", "up|down|left|right")), true) { a ->
            withAcc { s ->
                if (s.swipe(a.optString("direction"))) {
                    delay(500)
                    "Swiped. Call read_screen to see result."
                } else "Swipe failed"
            }
        },

        ToolDef("press",
            "Press a system key: back, home, recents, notifications, screenshot, enter (submit the focused field).",
            listOf(Param("key", "string", "back|home|recents|notifications|screenshot|enter")), true) { a ->
            withAcc { s ->
                val key = a.optString("key")
                if (s.press(key)) {
                    delay(500)
                    "Pressed $key."
                } else "Key failed or unknown"
            }
        },

        ToolDef("web_search",
            "Search Google for a query in the phone's browser. Use for any 'search / google / look up' request.",
            listOf(Param("query", "string", "What to search for")), false) { a ->
            val q = a.optString("query").trim()
            if (q.isBlank()) return@ToolDef "No query provided."
            viewIntent("https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"), "Searched Google for '$q'")
        },

        ToolDef("search_web",
            "Look things up on the internet and get the top results back (title, link, snippet). Use for facts, news, prices and research. Needs internet.",
            listOf(Param("query", "string", "What to look up")), false) { a ->
            val q = a.optString("query").trim()
            if (q.isBlank()) return@ToolDef "No query provided."
            withContext(Dispatchers.IO) {
                try {
                    val c = java.net.URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as java.net.HttpURLConnection
                    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
                    c.connectTimeout = 15_000; c.readTimeout = 20_000
                    val html = c.inputStream.bufferedReader().use { it.readText() }.take(500_000)
                    val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
                    val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
                    fun clean(s: String) = s.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&lt;", "<").replace("&gt;", ">").replace(Regex("\\s+"), " ").trim()
                    val out = StringBuilder()
                    var n = 0
                    for ((i, m) in links.withIndex()) {
                        var url = m.groupValues[1]
                        Regex("uddg=([^&]+)").find(url)?.let { url = java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }
                        if (url.startsWith("//")) url = "https:$url"
                        if (!url.startsWith("http") || url.contains("duckduckgo.com/y.js")) continue
                        n++
                        out.append(n).append(". ").append(clean(m.groupValues[2])).append("\n   ").append(url).append("\n   ").append(clean(snippets.getOrNull(i).orEmpty()).take(240)).append("\n")
                        if (n >= 6) break
                    }
                    if (n == 0) "No results found for '$q'." else out.toString().trim()
                } catch (e: java.net.UnknownHostException) { "No internet connection, so I can't look that up right now." }
                catch (e: Exception) { "Search failed: ${e.message}" }
            }
        },

        ToolDef("add_calendar_event",
            "Open the calendar with a new event filled in. start is the local time as yyyy-MM-dd HH:mm. Optional: minutes (duration, default 60), location.",
            listOf(Param("title", "string", "Event title"), Param("start", "string", "yyyy-MM-dd HH:mm")), false) { a ->
            val title = a.optString("title").trim()
            val startText = a.optString("start").trim()
            if (title.isBlank()) return@ToolDef "No title provided."
            val start = try { java.time.LocalDateTime.parse(startText.replace(' ', 'T')) } catch (_: Exception) { null }
                ?: return@ToolDef "Could not read the start time '$startText'. Use yyyy-MM-dd HH:mm."
            val ms = start.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val minutes = a.optString("minutes").toLongOrNull() ?: 60L
            val i = Intent(Intent.ACTION_INSERT).setData(android.provider.CalendarContract.Events.CONTENT_URI)
                .putExtra(android.provider.CalendarContract.Events.TITLE, title)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
                .putExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, ms + minutes * 60_000L)
                .putExtra(android.provider.CalendarContract.Events.EVENT_LOCATION, a.optString("location"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { launchCtx().startActivity(i); "Opened the calendar with '$title' on $startText. Tap Save there to add it." }
            catch (e: Exception) { "Could not open a calendar app: ${e.message}" }
        },

        ToolDef("youtube_search",
            "Search YouTube for videos (opens the YouTube app or site with the results).",
            listOf(Param("query", "string", "What to search for")), false) { a ->
            val q = a.optString("query").trim()
            if (q.isBlank()) return@ToolDef "No query provided."
            viewIntent("https://www.youtube.com/results?search_query=" + URLEncoder.encode(q, "UTF-8"), "Searched YouTube for '$q'")
        },

        ToolDef("maps_search",
            "Search a place or get directions in the maps app.",
            listOf(Param("query", "string", "Place or destination")), false) { a ->
            val q = a.optString("query").trim()
            if (q.isBlank()) return@ToolDef "No query provided."
            viewIntent("geo:0,0?q=" + URLEncoder.encode(q, "UTF-8"), "Opened maps for '$q'")
        },

        ToolDef("call_number",
            "Open the dialer with a phone number ready. Then call tap_text with 'Call' to place the call.",
            listOf(Param("number", "string", "Phone number")), true) { a ->
            val n = a.optString("number").filter { it.isDigit() || it == '+' }
            if (n.isBlank()) return@ToolDef "No phone number provided."
            try {
                launchCtx().startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(2000)
                "Dialer opened with $n. Call tap_text with 'Call' to place the call."
            } catch (e: Exception) { "Failed to open dialer: ${e.message}" }
        },

        ToolDef("send_sms",
            "Write a text message to a number and press Send.",
            listOf(Param("number", "string", "Phone number"), Param("text", "string", "Message text")), true) { a ->
            val n = a.optString("number").filter { it.isDigit() || it == '+' }
            if (n.isBlank()) return@ToolDef "No phone number provided."
            try {
                val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$n"))
                    .putExtra("sms_body", a.optString("text")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchCtx().startActivity(i)
                delay(2500)
                val s = acc()
                var sent = false
                if (s != null) {
                    for (label in listOf("Send", "Send SMS", "Send message")) {
                        if (s.tapText(label)) { sent = true; break }
                    }
                    delay(800)
                }
                if (sent) "Message to $n sent." else "Message to $n is written; press Send (tap_text 'Send') to send it."
            } catch (e: Exception) { "Failed to open messages: ${e.message}" }
        },

        ToolDef("set_alarm",
            "Set an alarm at a given time (24-hour clock).",
            listOf(Param("hour", "number", "Hour 0-23"), Param("minute", "number", "Minute 0-59"),
                Param("label", "string", "Alarm name")), false) { a ->
            try {
                val h = a.optInt("hour", 7).coerceIn(0, 23)
                val m = a.optInt("minute", 0).coerceIn(0, 59)
                val i = Intent(android.provider.AlarmClock.ACTION_SET_ALARM)
                    .putExtra(android.provider.AlarmClock.EXTRA_HOUR, h)
                    .putExtra(android.provider.AlarmClock.EXTRA_MINUTES, m)
                    .putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, a.optString("label", "ZENIQ alarm"))
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchCtx().startActivity(i)
                delay(1000)
                "Alarm set for %02d:%02d.".format(h, m)
            } catch (e: Exception) { "Failed to set alarm: ${e.message}" }
        },

        ToolDef("set_timer",
            "Start a countdown timer.",
            listOf(Param("seconds", "number", "Timer length in seconds")), false) { a ->
            try {
                val sec = a.optInt("seconds", 60).coerceIn(1, 86_400)
                val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER)
                    .putExtra(android.provider.AlarmClock.EXTRA_LENGTH, sec)
                    .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                launchCtx().startActivity(i)
                delay(1000)
                "Timer started for $sec seconds."
            } catch (e: Exception) { "Failed to start timer: ${e.message}" }
        },

        ToolDef("take_photo",
            "Open the camera app AND press the shutter button to take a photo. Use this whenever the user asks to take/click/capture a photo, picture or selfie. Set front=true for a selfie.",
            listOf(Param("front", "boolean", "true for front/selfie camera, false for back camera")), false) { a ->
            takePhoto(a.optBoolean("front", false))
        },

        // ── Browser ──────────────────────────────────────────────────────────
        ToolDef("browser_open", "Open a URL in ZENIQ's built-in browser and return the page title and text.",
            listOf(Param("url", "string", "URL")), false) { a -> browser.open(a.optString("url")) },
        ToolDef("browser_text", "Return the visible text of the current browser page.",
            emptyList(), false) { _ -> browser.text() },
        ToolDef("browser_links", "List links on the current browser page.",
            emptyList(), false) { _ -> browser.links() },
        ToolDef("browser_click", "Click a link or button on the browser page by its text.",
            listOf(Param("text", "string", "Text of the link/button")), true) { a ->
            val r = browser.click(a.optString("text")); delay(1500); r
        },
        ToolDef("browser_type", "Type into an input on the browser page.",
            listOf(Param("label", "string", "Field hint"), Param("text", "string", "Text to type")), true) { a ->
            browser.type(a.optString("label"), a.optString("text"))
        },
        ToolDef("browser_submit", "Submit the form / press Enter in the focused browser field.",
            emptyList(), true) { _ -> browser.submit() },
        ToolDef("browser_script",
            "Run a whole web task in ONE call in ZENIQ's built-in browser. steps is a JSON array of step objects: " +
            "{\"do\":\"open\",\"url\":\"youtube.com\"}, {\"do\":\"wait\",\"seconds\":2}, {\"do\":\"type\",\"into\":\"search\",\"text\":\"free fire\"}, " +
            "{\"do\":\"press\",\"key\":\"enter\"}, {\"do\":\"click\",\"text\":\"Search\"}, {\"do\":\"scroll\",\"dir\":\"down\"}, " +
            "{\"do\":\"extract\",\"what\":\"text | links | a css selector\"}, {\"do\":\"eval\",\"js\":\"document.title\"}, {\"do\":\"show\"}. " +
            "Unless a step extracts text, the final page is opened for the user in their own browser.",
            listOf(Param("steps", "string", "JSON array of steps")), false) { a ->
            browser.runScript(a.optString("steps")) { url ->
                launchCtx().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                delay(1500)
            }
        },
        ToolDef("browser_eval", "Run JavaScript on the current browser page and return the result.",
            listOf(Param("js", "string", "JavaScript expression")), true) { a ->
            browser.eval(a.optString("js")).take(3500)
        },

        // ── Shell / Files / Web ──────────────────────────────────────────────
        ToolDef("run_shell", "Run a shell command on the phone (app sandbox). Returns output.",
            listOf(Param("command", "string", "Shell command")), true) { a -> shell(a.optString("command")) },
        ToolDef("http_get", "Fetch a web page over HTTP and return readable text (no JavaScript).",
            listOf(Param("url", "string", "URL")), false) { a -> httpGet(a.optString("url")) },
        ToolDef("read_file", "Read a text file from the ZENIQ workspace.",
            listOf(Param("path", "string", "Relative path")), false) { a ->
            try {
                val f = safe(a.optString("path"))
                when { f == null -> "Invalid or unsafe path."
                    !f.exists()  -> "File not found: ${a.optString("path")}"
                    !f.isFile    -> "Not a file: ${a.optString("path")}"
                    f.length() > 2_000_000L -> "File too large to read (max 2 MB)."
                    else -> f.readText().take(4000) }
            } catch (e: Exception) { "Error reading file: ${e.message}" }
        },
        ToolDef("write_file", "Write a text file in the ZENIQ workspace.",
            listOf(Param("path", "string", "Relative path"), Param("content", "string", "File content")), true) { a ->
            try {
                val f = safe(a.optString("path"))
                if (f == null) "Invalid or unsafe path."
                else { f.parentFile?.mkdirs(); f.writeText(a.optString("content").take(500_000)); "Wrote ${f.name}" }
            } catch (e: Exception) { "Error writing file: ${e.message}" }
        },
        ToolDef("list_files", "List files in the ZENIQ workspace.", emptyList(), false) { _ ->
            try {
                workspace.walkTopDown().filter { it.isFile }
                    .take(100)
                    .joinToString("\n") { "${it.relativeTo(workspace).path} (${it.length()/1024} KB)" }
                    .ifBlank { "(empty)" }
            } catch (e: Exception) { "Error listing files: ${e.message}" }
        },
        ToolDef("get_time", "Get the current date and time.", emptyList(), false) { _ ->
            SimpleDateFormat("EEEE, yyyy-MM-dd HH:mm").format(Date())
        },
        ToolDef("wait",
            "Wait N seconds (max 15) for an app or screen to finish loading. Always use this after open_app if the app needs more time.",
            listOf(Param("seconds", "number", "Seconds to wait")), false) { a ->
            val secs = a.optInt("seconds", 2).coerceIn(1, 15)
            delay(secs * 1000L)
            "Waited ${secs}s. Call read_screen to see current screen."
        }
    )

    fun byName(n: String) = tools.firstOrNull { it.name == n }
    private val coreTools = setOf("open_app", "open_url", "read_screen", "look_at_screen", "tap_text", "type_text", "press", "swipe",
        "web_search", "search_web", "youtube_search", "take_photo", "wait", "browser_script")

    /** Every tool as an OpenAI function schema, for models that support native tool calling (Fast Mode). */
    fun nativeSpecs(): JSONArray = JSONArray().also { arr -> tools.forEach { arr.put(it.spec()) } }

    /** The tools the model is told about: a core set plus the groups that match the request (keeps the prompt short and fast). */
    fun specText(query: String = ""): String {
        val q = query.lowercase()
        val extra = HashSet<String>()
        if (Regex("call|dial|phone|message|sms|text|whatsapp").containsMatchIn(q)) extra += listOf("call_number", "send_sms")
        if (Regex("alarm|timer|remind|wake").containsMatchIn(q)) extra += listOf("set_alarm", "set_timer")
        if (Regex("map|direction|navigate|route|nearby|where").containsMatchIn(q)) extra += "maps_search"
        if (Regex("file|folder|read|write|save|shell|command|download|fetch|http|time|date").containsMatchIn(q))
            extra += listOf("read_file", "write_file", "list_files", "run_shell", "http_get", "get_time")
        if (Regex("browser|website|web page|webpage|site|login|sign in|click|form").containsMatchIn(q))
            extra += listOf("browser_open", "browser_text", "browser_links", "browser_click", "browser_type", "browser_submit", "browser_eval")
        if (Regex("coordinate|tap_xy|x,y").containsMatchIn(q)) extra += "tap_xy"
        if (Regex("calendar|event|meeting|appointment|schedule").containsMatchIn(q)) extra += "add_calendar_event"
        return tools.filter { it.name in coreTools || it.name in extra }.joinToString("\n") { it.compact() }
    }

    private fun safe(rel: String): File? {
        val f = File(workspace, rel).canonicalFile
        return if (f.path.startsWith(workspace.canonicalPath)) f else null
    }

    /**
     * Opens an app by name or common alias using the phone's default apps.
     * Aliases map to system intents so the user's default browser/camera/etc. is used.
     */
    private suspend fun openApp(name: String): String {
        val q = name.trim().lowercase()

        // ── Intent-based aliases (use phone defaults) ──────────────────────
        val intentResult = resolveAlias(q)
        if (intentResult != null) {
            delay(2500)
            val screen = acc()?.dumpScreen() ?: "App opened."
            return "$intentResult\n\nCurrent screen:\n$screen"
        }

        // ── Name-based search ──────────────────────────────────────────────
        val pm = app.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, 0)
        val m = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return "No app named '$name'. Try a category: browser, camera, gallery, email, maps, settings. Installed: " +
                apps.take(20).joinToString { it.loadLabel(pm).toString() }
        val i = pm.getLaunchIntentForPackage(m.activityInfo.packageName) ?: return "Cannot launch"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        launchCtx().startActivity(i)
        delay(2500)
        val screen = acc()?.dumpScreen() ?: "App opened. Enable Accessibility service to read screen."
        return "Opened ${m.loadLabel(pm)}.\n\nCurrent screen:\n$screen"
    }

    private fun resolveAlias(q: String): String? {
        val ctx = launchCtx()
        fun launch(i: Intent): String? {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try { ctx.startActivity(i); "Opened default app for '$q'." } catch (e: Exception) { null }
        }
        return when {
            q.matches(Regex("browser|web|chrome|firefox|internet|safari")) ->
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://")).also { it.addCategory(Intent.CATEGORY_BROWSABLE) })
            q.matches(Regex("camera|photo|take photo|take picture|camera app")) ->
                launch(Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
            q.matches(Regex("gallery|photos|pictures|images|media")) ->
                launch(Intent(Intent.ACTION_PICK, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
            q.matches(Regex("email|mail|gmail|outlook")) ->
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_EMAIL))
            q.matches(Regex("maps|map|navigation|google maps|directions")) ->
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0")))
            q.matches(Regex("music|spotify|youtube music|player|audio")) ->
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC))
            q.matches(Regex("phone|dialer|call|dialpad")) ->
                launch(Intent(Intent.ACTION_DIAL))
            q.matches(Regex("messages|sms|messaging|whatsapp|telegram")) ->
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            q.matches(Regex("settings|system settings")) ->
                launch(Intent(android.provider.Settings.ACTION_SETTINGS))
            q.matches(Regex("files|file manager|documents|storage")) ->
                launch(Intent(Intent.ACTION_VIEW).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE))
            q.matches(Regex("calculator|calc")) ->
                launch(Intent().apply { action = "android.intent.action.MAIN"; addCategory("android.intent.category.APP_CALCULATOR") })
            q.matches(Regex("clock|alarm|timer|stopwatch")) ->
                launch(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            q.matches(Regex("contacts|people|address book")) ->
                launch(Intent(Intent.ACTION_VIEW, android.provider.ContactsContract.Contacts.CONTENT_URI))
            q.matches(Regex("youtube|video|videos")) ->
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://youtube.com")).addCategory(Intent.CATEGORY_BROWSABLE))
            q.matches(Regex("play store|playstore|google play")) ->
                launch(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=app")))
            else -> null
        }
    }

    /**
     * Opens the camera app, then presses the shutter through the accessibility service.
     * Tries: shutter view-id / label match -> fallback tap at the usual shutter position.
     */
    private suspend fun takePhoto(front: Boolean): String {
        val a = acc() ?: return "Could not take photo: Accessibility is OFF. Enable ZENIQ in Settings > Accessibility so I can press the shutter."
        try {
            launchCtx().startActivity(
                Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            return "Could not open the camera app: ${e.message}"
        }
        delay(3000)   // let the camera app start and show its preview

        if (front) {
            for (label in listOf("Switch camera", "Front camera", "Selfie", "Flip camera", "Switch to front camera")) {
                if (a.tapText(label)) { delay(1200); break }
            }
        }

        var pressed = a.tapShutter()
        if (!pressed) {
            val m = app.resources.displayMetrics
            pressed = a.tapXY(m.widthPixels / 2f, m.heightPixels * 0.88f)
        }
        if (!pressed) return "Could not take photo: failed to press the shutter button."
        delay(1800)
        val screen = a.dumpScreen()
        return "Shutter pressed \u2014 photo captured.\n\nCurrent screen:\n$screen"
    }

    private suspend fun viewIntent(url: String, okMsg: String): String {
        return try {
            launchCtx().startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            delay(2500)
            val screen = acc()?.dumpScreen()
            if (screen != null) "$okMsg.\n\nCurrent screen:\n$screen" else "$okMsg."
        } catch (e: Exception) { "Failed: ${e.message}" }
    }

    private suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("sh", "-c", cmd.take(2000))
                .directory(workspace).redirectErrorStream(true).start()
            val sb = StringBuffer()
            val t = thread { try { p.inputStream.bufferedReader().forEachLine {
                if (sb.length < 8000) sb.append(it).append('\n') } } catch (_: Exception) {} }
            val done = p.waitFor(30, TimeUnit.SECONDS)
            if (!done) p.destroyForcibly()
            t.join(2000)
            "exit=${if (done) p.exitValue() else "timeout"}\n$sb".take(8000)
        } catch (e: Exception) { "Shell error: ${e.message}" }
    }

    private suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL(url.trim()).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) ZENIQ")
            val html = conn.inputStream.bufferedReader().use { it.readText().take(300_000) }
            html.replace(Regex("(?is)<(script|style).*?</\\1>"), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace(Regex("\\s+"), " ")
                .trim().take(4000)
        } catch (e: Exception) { "HTTP error: ${e.message}" }
        finally { conn?.disconnect() }
    }
}
