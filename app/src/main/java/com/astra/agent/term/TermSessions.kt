package com.astra.agent.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File

/** Bridges the terminal widget to ZENIQ: clipboard, keyboard, virtual Ctrl key and session-finished state. */
class TermClient(private val app: Context) : TerminalSessionClient, TerminalViewClient {
    var view: TerminalView? = null
    var ctrl by mutableStateOf(false)
    var finished by mutableStateOf(false)

    // session side
    override fun onTextChanged(changedSession: TerminalSession) { view?.onScreenUpdated() }
    override fun onTitleChanged(changedSession: TerminalSession) {}
    override fun onSessionFinished(finishedSession: TerminalSession) { finished = true }
    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        val cm = app.getSystemService(ClipboardManager::class.java)
        if (text != null) cm?.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }
    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = app.getSystemService(ClipboardManager::class.java)
        val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(app)?.toString()
        if (!t.isNullOrEmpty()) session?.emulator?.paste(t)
    }
    override fun onBell(session: TerminalSession) {}
    override fun onColorsChanged(session: TerminalSession) {}
    override fun onTerminalCursorStateChange(state: Boolean) {}
    override fun getTerminalCursorStyle(): Int? = null

    // view side
    override fun onScale(scale: Float): Float = 1.0f
    override fun onSingleTapUp(e: MotionEvent) {
        val v = view ?: return
        v.requestFocus()
        app.getSystemService(InputMethodManager::class.java)?.showSoftInput(v, 0)
    }
    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}
    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = false
    override fun readControlKey(): Boolean = ctrl
    override fun readAltKey(): Boolean = false
    override fun readShiftKey(): Boolean = false
    override fun readFnKey(): Boolean = false
    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean { if (ctrl) ctrl = false; return false }
    override fun onEmulatorSet() {}

    // logging (shared by both interfaces); terminal contents are never logged
    override fun logError(tag: String, message: String) {}
    override fun logWarn(tag: String, message: String) {}
    override fun logInfo(tag: String, message: String) {}
    override fun logDebug(tag: String, message: String) {}
    override fun logVerbose(tag: String, message: String) {}
    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
    override fun logStackTrace(tag: String, e: Exception) {}
}

/**
 * Holds the interactive terminals so they survive tab switches and screen rotation (they end if Android kills the app process).
 * There is one per tool ("claude" = Claude Code, "openclaw" = Open Claw), so starting the OpenClaw gateway never disturbs Claude Code.
 */
object TermSessions {
    private class Slot { var session: TerminalSession? = null; var key: String? = null; var client: TermClient? = null }
    private val slots = HashMap<String, Slot>()
    private fun slot(tag: String): Slot = slots.getOrPut(tag) { Slot() }

    val session: TerminalSession? get() = slots["claude"]?.session

    fun client(ctx: Context, tag: String = "claude"): TermClient {
        val sl = slot(tag)
        return sl.client ?: TermClient(ctx.applicationContext).also { sl.client = it }
    }

    /** Returns the running session for [workspace], or starts a new one. A different project restarts the shell in that project. */
    fun ensure(ctx: Context, workspace: File?, tag: String = "claude"): TerminalSession {
        val sl = slot(tag)
        val k = workspace?.absolutePath ?: ""
        val cur = sl.session
        if (cur != null && sl.key == k && cur.isRunning) return cur
        cur?.finishIfRunning()
        // Open Claw ("openclaw") runs in its own Ubuntu system; Claude Code ("claude") runs in the Alpine system
        val d = if (tag == "openclaw") Distro.UBUNTU else Distro.ALPINE
        TermEnv.writeResolvConf(ctx, d)
        val args = TermEnv.prootArgs(ctx, workspace, TermEnv.interactiveShell(if (workspace != null) "/workspace" else "/root", d), d)
        val c = client(ctx, tag)
        c.finished = false; c.ctrl = false
        val s = TerminalSession(args[0], ctx.filesDir.absolutePath, args.toTypedArray(), TermEnv.hostEnv(ctx), 4000, c)
        sl.session = s; sl.key = k
        return s
    }

    fun restart(ctx: Context, workspace: File?, tag: String = "claude"): TerminalSession {
        val sl = slot(tag)
        sl.session?.finishIfRunning(); sl.session = null
        return ensure(ctx, workspace, tag)
    }

    fun stop(tag: String = "claude") { val sl = slot(tag); sl.session?.finishIfRunning(); sl.session = null }

    fun send(text: String, tag: String = "claude") {
        val s = slot(tag).session ?: return
        if (!s.isRunning) return
        val b = text.toByteArray()
        s.write(b, 0, b.size)
    }
}
