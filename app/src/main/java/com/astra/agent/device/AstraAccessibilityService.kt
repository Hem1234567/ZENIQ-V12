package com.astra.agent.device

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** The phone-side replacement for pywinauto: reads the screen and performs taps/typing/swipes. */
class AstraAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: AstraAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }

    // ── Screen reading ────────────────────────────────────────────────────────

    /** Takes a screenshot through the accessibility service (Android 11+). Returns null when unavailable; nothing is saved to disk. */
    suspend fun screenshotBitmap(): Bitmap? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT < 30) { cont.resume(null); return@suspendCancellableCoroutine }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bmp = try { Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) }
                              catch (_: Throwable) { null }
                              finally { try { result.hardwareBuffer.close() } catch (_: Throwable) {} }
                    if (cont.isActive) cont.resume(bmp)
                }
                override fun onFailure(errorCode: Int) { if (cont.isActive) cont.resume(null) }
            })
        } catch (_: Throwable) { if (cont.isActive) cont.resume(null) }
    }

    fun dumpScreen(): String {
        val root = try { rootInActiveWindow } catch (_: Exception) { null }
            ?: return "No active window (screen may be locked or loading)."
        val sb = StringBuilder("App: ${root.packageName}\nFormat: [x,y] (type) label\n")
        var count = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (count > 150) return
            val t = try { n.text?.toString().orEmpty() } catch (_: Exception) { "" }
            val d = try { n.contentDescription?.toString().orEmpty() } catch (_: Exception) { "" }
            if (t.isNotBlank() || d.isNotBlank() || n.isClickable || n.isEditable) {
                val r = Rect()
                try { n.getBoundsInScreen(r) } catch (_: Exception) {}
                val type = when {
                    n.isEditable  -> "input"
                    n.isClickable -> "button"
                    else          -> "text"
                }
                sb.append("[${r.centerX()},${r.centerY()}] ($type) ${t.ifBlank { d }.take(80)}\n")
                count++
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { walk(it) } } catch (_: Exception) {}
            }
        }
        try { walk(root) } catch (_: Exception) {}
        return sb.toString()
    }

    // ── Interaction ───────────────────────────────────────────────────────────

    fun tapText(text: String): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val nodes = try { root.findAccessibilityNodeInfosByText(text) } catch (_: Exception) { null }
        var n: AccessibilityNodeInfo? = nodes?.firstOrNull() ?: return false
        while (n != null && !n.isClickable) n = try { n.parent } catch (_: Exception) { null }
        return try { n?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false } catch (_: Exception) { false }
    }

    /** Screen rectangle of the first node matching [text] (used when the node itself is not clickable). */
    fun boundsOfText(text: String): Rect? {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return null
        val node = try { root.findAccessibilityNodeInfosByText(text)?.firstOrNull() } catch (_: Exception) { null } ?: return null
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return null }
        return if (r.isEmpty) null else r
    }

    /**
     * Types text into an input field.
     * 1. Try focused input.
     * 2. Walk tree to find any editable, click to focus, then set text.
     */
    fun typeText(text: String): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        // Try focused input first
        val focused = try { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) } catch (_: Exception) { null }
        if (focused != null && focused.isEditable) return setNodeText(focused, text)
        // Find any editable node and click to focus
        val editable = findEditable(root)
        if (editable != null) {
            try { editable.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) {}
            try { editable.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) } catch (_: Exception) {}
            return setNodeText(editable, text)
        }
        return false
    }

    private fun setNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        return try {
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (_: Exception) { false }
    }

    private fun findEditable(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            try {
                n.getChild(i)?.let { child -> findEditable(child)?.let { return it } }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Finds the camera shutter button (by view id containing "shutter"/"capture", or by label)
     * and clicks it. Returns false if nothing suitable was found.
     */
    suspend fun tapShutter(): Boolean {
        val root = try { rootInActiveWindow } catch (_: Exception) { null } ?: return false
        val labelRe = Regex("shutter|take (a )?(photo|picture)|capture|take photo", RegexOption.IGNORE_CASE)
        var target: AccessibilityNodeInfo? = null
        fun walk(n: AccessibilityNodeInfo) {
            if (target != null) return
            val id = try { n.viewIdResourceName.orEmpty() } catch (_: Exception) { "" }
            val d = try { n.contentDescription?.toString().orEmpty() } catch (_: Exception) { "" }
            val t = try { n.text?.toString().orEmpty() } catch (_: Exception) { "" }
            val idHit = id.contains("shutter", true) || id.contains("capture_button", true) ||
                id.contains("camera_capture", true)
            if (idHit || labelRe.containsMatchIn(d) || labelRe.containsMatchIn(t)) {
                if (n.isClickable || n.isEnabled) { target = n; return }
            }
            for (i in 0 until n.childCount) {
                try { n.getChild(i)?.let { walk(it) } } catch (_: Exception) {}
                if (target != null) return
            }
        }
        try { walk(root) } catch (_: Exception) {}
        val node = target ?: return false
        // 1) normal click on the node or the nearest clickable parent
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = try { n.parent } catch (_: Exception) { null }
        if (n != null) {
            val ok = try { n.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
            if (ok) return true
        }
        // 2) tap the centre of its bounds as a gesture
        val r = Rect()
        try { node.getBoundsInScreen(r) } catch (_: Exception) { return false }
        if (r.isEmpty) return false
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    // ── KEY press — BLOCK body to avoid return-in-expression-body error ───────

    fun press(key: String): Boolean {
        return when (key.lowercase().trim()) {
            "back"          -> tryGlobal(GLOBAL_ACTION_BACK)
            "home"          -> tryGlobal(GLOBAL_ACTION_HOME)
            "recents"       -> tryGlobal(GLOBAL_ACTION_RECENTS)
            "notifications" -> tryGlobal(GLOBAL_ACTION_NOTIFICATIONS)
            "screenshot"    -> tryGlobal(GLOBAL_ACTION_TAKE_SCREENSHOT)
            "enter" -> {
                val f = try { rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }
                        catch (_: Exception) { null }
                if (f != null) {
                    // Try IME enter action first (API 30+)
                    if (android.os.Build.VERSION.SDK_INT >= 30) {
                        try {
                            val ok = f.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                            if (ok) return true
                        } catch (_: Exception) {}
                    }
                    // Fallback: click the focused node
                    try { f.performAction(AccessibilityNodeInfo.ACTION_CLICK) } catch (_: Exception) { false }
                } else {
                    false
                }
            }
            else -> false
        }
    }

    private fun tryGlobal(action: Int): Boolean =
        try { performGlobalAction(action) } catch (_: Exception) { false }

    // ── Gestures ──────────────────────────────────────────────────────────────

    private suspend fun gesture(path: Path, dur: Long): Boolean = suspendCancellableCoroutine { cont ->
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0, dur)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!ok && cont.isActive) cont.resume(false)
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(false)
        }
    }

    suspend fun tapXY(x: Float, y: Float): Boolean =
        gesture(Path().apply { moveTo(x, y) }, 80L)

    /** direction = where the finger moves: up, down, left, right */
    suspend fun swipe(direction: String): Boolean {
        val m = try { resources.displayMetrics } catch (_: Exception) { return false }
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val p = Path()
        when (direction.lowercase().trim()) {
            "up"    -> { p.moveTo(w / 2, h * 0.7f); p.lineTo(w / 2, h * 0.3f) }
            "down"  -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.7f) }
            "left"  -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "right" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else    -> return false
        }
        return gesture(p, 350)
    }
}
