package com.astra.agent.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File

private const val BASE = "https://zeniq.preview/"

/**
 * A live page inside the IDE: the project's own page (entry file relative to [root]) in a real WebView. Files the page asks for (CSS, JS,
 * images, built assets) are served from the project folder only. Changing [reloadKey] reloads it.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ProjectWebView(root: File, entryRel: String, reloadKey: Int, modifier: Modifier = Modifier) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { c ->
            WebView(c).apply {
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                webChromeClient = WebChromeClient()
            }
        },
        update = { web ->
            val key = root.absolutePath + "|" + entryRel + "|" + reloadKey
            if (web.tag != key) {
                web.tag = key
                web.webViewClient = projectClient(root, ctx)
                val html = try { File(root, entryRel).readText() } catch (e: Exception) { "<html><body><h3>Could not read $entryRel</h3><p>${e.message}</p></body></html>" }
                val dir = entryRel.substringBeforeLast('/', "")
                web.loadDataWithBaseURL(BASE + (if (dir.isEmpty()) "" else "$dir/"), html, "text/html", "utf-8", null)
            }
        },
        onRelease = { w -> try { w.stopLoading(); w.destroy() } catch (_: Throwable) {} }
    )
}

private fun projectClient(root: File, ctx: Context) = object : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (!url.startsWith(BASE)) return null
        return try {
            val rel = Uri.decode(url.removePrefix(BASE).substringBefore('#').substringBefore('?')).trimStart('/')
            if (rel.isEmpty()) return null
            val f = File(root, rel).canonicalFile
            if (!f.path.startsWith(root.canonicalPath + File.separator) || !f.isFile) return null
            val ext = f.extension.lowercase()
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: when (ext) { "js", "mjs", "jsx" -> "text/javascript"; "json" -> "application/json"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; else -> "application/octet-stream" }
            val text = mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("svg+xml") || mime.endsWith("javascript")
            WebResourceResponse(mime, if (text) "utf-8" else null, f.inputStream())
        } catch (_: Throwable) { null }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val u = request.url
        val s = u.scheme ?: return false
        if (u.toString().startsWith(BASE)) return false
        if (s == "http" || s == "https" || s == "mailto" || s == "tel") {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return true
        }
        return false
    }
}

/** The file the live preview should open for a project, or null when there is no page. A React + Vite project is shown from its built dist/. */
fun previewEntry(ws: com.astra.agent.code.Workspace): String? {
    val files = ws.tree().filter { !it.dir }.map { it.rel }
    if ("dist/index.html" in files) return "dist/index.html"
    val index = files.firstOrNull { it == "index.html" } ?: files.firstOrNull { it.endsWith("/index.html") } ?: files.firstOrNull { it.endsWith(".html") } ?: return null
    return index
}

/** True when the page needs a build step first (a Vite entry that loads /src/main.jsx), so it cannot run as it is. */
fun needsBuild(ws: com.astra.agent.code.Workspace, entry: String): Boolean =
    try { Regex("src=[\"']/?src/main\\.(?:jsx|tsx)[\"']").containsMatchIn(ws.read(entry)) } catch (_: Throwable) { false }
