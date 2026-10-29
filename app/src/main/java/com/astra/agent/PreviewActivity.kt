package com.astra.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.astra.agent.code.FileStorage
import java.io.File

/**
 * Full-screen preview of a generated page. It is a normal Android screen around a normal WebView, which renders reliably
 * (a WebView inside a Compose dialog can stay blank on some phones). Close, view the code, reload, or open in the browser.
 */
class PreviewActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var codeScroll: ScrollView
    private var html = ""
    private var path = ""
    private var rootDir: File? = null
    private var showCode = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Preview"
        rootDir = intent.getStringExtra(EXTRA_ROOT)?.let { File(it) }?.takeIf { it.isDirectory }
        html = try { File(path).readText() } catch (e: Exception) { "<html><body><h3>Could not read the file</h3><p>${e.message}</p></body></html>" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#14141A"))
            fitsSystemWindows = true
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false; textSize = 13f
            minWidth = 0; minimumWidth = 0; setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { onClick() }
        }
        val titleView = TextView(this).apply {
            text = title; setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            setPadding(dp(10), 0, dp(10), 0)
        }
        val codeBtn = btn("Code") {}
        codeBtn.setOnClickListener {
            showCode = !showCode
            codeBtn.text = if (showCode) "Preview" else "Code"
            web.visibility = if (showCode) View.GONE else View.VISIBLE
            codeScroll.visibility = if (showCode) View.VISIBLE else View.GONE
        }
        bar.addView(btn("\u2715 Close") { finish() })
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(codeBtn)
        bar.addView(btn("Reload") { loadPage() })
        bar.addView(btn("Open") {
            if (!FileStorage.open(this, File(path), "text/html")) {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(File(path)), "text/html"), "Open with"))
            }
        })
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val frame = FrameLayout(this)
        web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                // Project files (images, fonts, other pages, CSS/JS that were not inlined) are served from the project folder,
                // so relative paths such as "img/logo.png" or "about.html" work. Nothing outside the project folder is served.
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): android.webkit.WebResourceResponse? {
                    val root = rootDir ?: return null
                    val url = request.url.toString()
                    if (!url.startsWith(BASE)) return null
                    return try {
                        val rel = Uri.decode(url.removePrefix(BASE).substringBefore('#').substringBefore('?')).trimStart('/')
                        if (rel.isEmpty()) return null
                        val f = File(root, rel).canonicalFile
                        if (!f.path.startsWith(root.canonicalPath + File.separator) || !f.isFile) return null
                        val ext = f.extension.lowercase()
                        val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                            ?: when (ext) { "js", "mjs" -> "text/javascript"; "json" -> "application/json"; "svg" -> "image/svg+xml"; "woff2" -> "font/woff2"; "woff" -> "font/woff"; else -> "application/octet-stream" }
                        android.webkit.WebResourceResponse(mime, if (mime.startsWith("text/") || mime.endsWith("json") || mime.endsWith("svg+xml")) "utf-8" else null, f.inputStream())
                    } catch (_: Throwable) { null }
                }
                // links to other sites open in the phone's browser; anchors and the page itself stay here
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    val s = u.scheme ?: return false
                    if (u.toString().startsWith(BASE)) return false
                    if (s == "http" || s == "https" || s == "mailto" || s == "tel") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        return true
                    }
                    return false
                }
            }
        }
        frame.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        codeScroll = ScrollView(this).apply {
            visibility = View.GONE
            setBackgroundColor(Color.parseColor("#0E0E12"))
            addView(TextView(this@PreviewActivity).apply {
                text = html; setTextColor(Color.parseColor("#E6E6EA")); textSize = 11f; typeface = Typeface.MONOSPACE
                setPadding(dp(12), dp(12), dp(12), dp(12)); setTextIsSelectable(true)
            })
        }
        frame.addView(codeScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        loadPage()
    }

    private fun loadPage() { web.loadDataWithBaseURL(BASE, html, "text/html", "utf-8", null) }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!showCode && web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        try { web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() } catch (_: Throwable) {}
        super.onDestroy()
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ROOT = "root"
        private const val BASE = "https://zeniq.preview/"
    }
}
