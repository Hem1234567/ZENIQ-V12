package com.astra.agent.device

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

/** In-app real browser (Android WebView) the agent can drive: the phone's Playwright equivalent. */
@SuppressLint("SetJavaScriptEnabled")
class BrowserHost(private val app: Context) {
    private var web: WebView? = null
    @Volatile private var pageDone: CompletableDeferred<Unit>? = null

    private suspend fun view(): WebView = withContext(Dispatchers.Main) {
        web ?: WebView(app).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString.replace("; wv", "")
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) { pageDone?.complete(Unit) }
            }
            layout(0, 0, 1080, 1920)
        }.also { web = it }
    }

    suspend fun eval(js: String): String {
        val v = view()
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { c ->
                v.evaluateJavascript(js) { r ->
                    val out = if (r == null || r == "null") "" else try { JSONTokener(r).nextValue().toString() } catch (e: Exception) { r }
                    if (c.isActive) c.resume(out)
                }
            }
        }
    }

    suspend fun open(url: String): String {
        val v = view()
        val d = CompletableDeferred<Unit>()
        pageDone = d
        withContext(Dispatchers.Main) { v.loadUrl(if (url.startsWith("http")) url else "https://$url") }
        val ok = withTimeoutOrNull(25_000) { d.await() }
        delay(800)
        return (if (ok == null) "(page still loading) " else "") + "Title: ${eval("document.title")}\n" + text(1500)
    }

    suspend fun text(max: Int = 3500): String =
        eval("document.body ? document.body.innerText : ''").replace(Regex("\\s+"), " ").trim().take(max)

    suspend fun links(): String = eval(
        "[].slice.call(document.querySelectorAll('a[href]')).slice(0,40).map(function(a){return (a.innerText||'').trim().slice(0,60)+' -> '+a.href}).join('\\n')"
    ).ifBlank { "(no links)" }

    suspend fun click(text: String): String = eval(
        "(function(t){t=t.toLowerCase();var els=[].slice.call(document.querySelectorAll('a,button,input[type=submit],input[type=button],[role=button],[onclick]'));" +
        "var e=els.find(function(x){return ((x.innerText||x.value||x.getAttribute('aria-label')||'')+'').toLowerCase().indexOf(t)>=0});" +
        "if(!e)return 'NOT_FOUND: no clickable element with that text. Try browser_links or browser_text.';e.click();return 'Clicked '+((e.innerText||e.value||'')+'').slice(0,60)})(${JSONObject.quote(text)})"
    )

    suspend fun type(label: String, value: String): String = eval(
        "(function(l,v){l=l.toLowerCase();var els=[].slice.call(document.querySelectorAll('input,textarea,[contenteditable=true]'));" +
        "els=els.filter(function(x){return x.type!=='hidden'&&x.type!=='submit'&&x.type!=='button'});" +
        "var e=els.find(function(x){return l&&((x.placeholder||'')+(x.name||'')+(x.id||'')+(x.getAttribute('aria-label')||'')).toLowerCase().indexOf(l)>=0})||els[0];" +
        "if(!e)return 'NOT_FOUND: no input field';e.focus();" +
        "var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;var s=Object.getOwnPropertyDescriptor(p,'value');" +
        "if(s&&s.set&&e.tagName!=='DIV'){s.set.call(e,v)}else{e.textContent=v}" +
        "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'Typed into '+(e.name||e.id||e.placeholder||e.tagName)})(${JSONObject.quote(label)},${JSONObject.quote(value)})"
    )

    suspend fun currentUrl(): String = eval("location.href")

    // ── One-shot scripts: the model writes a JSON step list, the phone runs it all ──────────────────────────────

    private val searchSites = listOf(
        Triple("youtube.com", "https://m.youtube.com/results?search_query=", "results"),
        Triple("google.", "https://www.google.com/search?q=", "search"),
        Triple("bing.com", "https://www.bing.com/search?q=", "search"),
        Triple("duckduckgo.com", "https://duckduckgo.com/?q=", "q="),
        Triple("amazon.", "https://www.amazon.in/s?k=", "/s?"),
        Triple("flipkart.com", "https://www.flipkart.com/search?q=", "search"),
        Triple("wikipedia.org", "https://en.wikipedia.org/w/index.php?search=", "search=")
    )

    private fun parseSteps(raw: String): JSONArray? {
        var t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        try { if (t.startsWith("[")) return JSONArray(t) } catch (_: Exception) {}
        try { if (t.startsWith("{")) { val o = JSONObject(t); o.optJSONArray("steps")?.let { return it }; return JSONArray().put(o) } } catch (_: Exception) {}
        val a = t.indexOf('['); val b = t.lastIndexOf(']')
        if (a >= 0 && b > a) try { return JSONArray(t.substring(a, b + 1)) } catch (_: Exception) {}
        return null
    }

    private fun str(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank() && v != "null") return v.trim() }
        return ""
    }

    /** If pressing Enter did not start the search (some sites only react to their own button), go to the results URL directly. */
    private suspend fun ensureSearched(query: String): String {
        if (query.isBlank()) return ""
        val href = currentUrl()
        val site = searchSites.firstOrNull { href.contains(it.first, true) } ?: return ""
        if (href.contains(site.third, true) && href.contains(java.net.URLEncoder.encode(query, "UTF-8").take(6), true)) return ""
        open(site.second + java.net.URLEncoder.encode(query, "UTF-8"))
        return " (opened the results page directly)"
    }

    /**
     * Runs a whole web task in one go. Steps (JSON array of objects):
     * open{url} wait{seconds} type{into,text} click{text} press{key} scroll{dir} extract{what} eval{js} show.
     * Unless a step reads something out of the page, the final page is shown to the user in their own browser.
     */
    suspend fun runScript(raw: String, showToUser: suspend (String) -> Unit): String {
        val steps = parseSteps(raw) ?: return "Invalid script. Give a JSON array, for example " +
            "[{\"do\":\"open\",\"url\":\"youtube.com\"},{\"do\":\"type\",\"into\":\"search\",\"text\":\"free fire\"},{\"do\":\"press\",\"key\":\"enter\"}]"
        val log = StringBuilder()
        var reads = false
        var shown = false
        var typed = ""
        val known = setOf("open", "goto", "go", "navigate", "visit", "wait", "sleep", "pause", "type", "fill", "input", "write", "click", "tap",
            "press", "key", "submit", "enter", "scroll", "extract", "read", "get", "text", "eval", "js", "javascript", "show", "view", "display")
        for (i in 0 until minOf(steps.length(), 40)) {
            val o = steps.optJSONObject(i) ?: continue
            var kind = str(o, "do", "action", "step", "op", "cmd", "command").lowercase()
            if (kind.isEmpty()) { val t = o.optString("type", "").lowercase(); if (t in known) kind = t }
            val n = i + 1
            try {
                when (kind) {
                    "open", "goto", "go", "navigate", "visit" -> {
                        val url = str(o, "url", "link", "to", "site", "page")
                        if (url.isEmpty()) log.append("$n. open: no url\n")
                        else log.append("$n. open $url: ").append(open(url).lineSequence().firstOrNull().orEmpty()).append('\n')
                    }
                    "wait", "sleep", "pause" -> {
                        val sec = if (o.has("ms")) o.optDouble("ms", 1500.0) / 1000.0 else o.optDouble("seconds", o.optDouble("s", 1.5))
                        delay((sec.coerceIn(0.2, 20.0) * 1000).toLong()); log.append("$n. waited\n")
                    }
                    "type", "fill", "input", "write" -> {
                        val label = str(o, "into", "label", "field", "selector", "target", "name")
                        val text = str(o, "text", "value", "query")
                        typed = text
                        var r = type(label, text)
                        if (r.startsWith("NOT_FOUND") && (label.isBlank() || label.contains("search", true))) {
                            click("search"); delay(900); r = type("", text)
                        }
                        log.append("$n. type \"$text\": $r\n")
                    }
                    "click", "tap" -> {
                        val text = str(o, "text", "label", "target", "name")
                        log.append("$n. click $text: ").append(click(text)).append('\n'); delay(1200)
                    }
                    "press", "key", "submit", "enter" -> {
                        val key = str(o, "key", "value").lowercase()
                        if (key.isEmpty() || key in setOf("enter", "return", "go", "search", "submit")) {
                            val r = submit()
                            log.append("$n. press enter: $r").append(ensureSearched(typed)).append('\n')
                        } else log.append("$n. key '$key' is not supported here; use enter\n")
                    }
                    "scroll" -> {
                        val up = str(o, "dir", "direction", "value").lowercase().startsWith("up")
                        eval("window.scrollBy(0, ${if (up) "-" else ""}window.innerHeight*0.8)"); delay(400); log.append("$n. scroll\n")
                    }
                    "extract", "read", "get", "text" -> {
                        reads = true
                        val what = str(o, "what", "selector", "target", "of", "value").lowercase()
                        val out = when {
                            what.isEmpty() || what == "text" || what == "page" -> text(2500)
                            what == "links" -> links()
                            what == "title" -> eval("document.title")
                            else -> eval("[].slice.call(document.querySelectorAll(${JSONObject.quote(what)})).slice(0,20).map(function(e){return ((e.innerText||e.value||e.getAttribute('href')||'')+'').trim()}).filter(Boolean).join('\\n')").ifBlank { "(nothing matched)" }
                        }
                        log.append("$n. extract:\n").append(out.take(2000)).append('\n')
                    }
                    "eval", "js", "javascript" -> {
                        reads = true
                        log.append("$n. js: ").append(eval(str(o, "js", "code", "script", "value")).take(1500)).append('\n')
                    }
                    "show", "view", "display" -> { showToUser(currentUrl()); shown = true; log.append("$n. shown to the user\n") }
                    else -> log.append("$n. unknown step '${kind.ifEmpty { o.toString().take(40) }}' skipped\n")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { log.append("$n. $kind failed: ${e.message}\n") }
        }
        val url = try { currentUrl() } catch (_: Exception) { "" }
        if (!reads && !shown && url.startsWith("http")) { try { showToUser(url); shown = true } catch (_: Exception) {} }
        log.append("Final page: ").append(try { eval("document.title") } catch (_: Exception) { "" }).append(" - ").append(url)
        if (shown) log.append("\n(Opened in the user's browser.)")
        return log.toString()
    }

    suspend fun submit(): String {
        val r = eval("(function(){var e=document.activeElement;var f=e&&e.form;if(f){if(f.requestSubmit)f.requestSubmit();else f.submit();return 'Submitted form'}" +
            "if(e){['keydown','keypress','keyup'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t,{key:'Enter',keyCode:13,bubbles:true}))});return 'Sent Enter'}return 'NOT_FOUND: nothing focused'})()")
        delay(1500)
        return r
    }
}
