package com.astra.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.astra.agent.term.*
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import java.io.File

/**
 * Embedded Linux terminal with the real Claude Code CLI. Everything runs inside ZENIQ (PTY + PRoot + Alpine + Node + Claude Code).
 * The open ZENIQ project is mounted at /workspace and is the working directory.
 */
@Composable
fun ClaudeTerminalTab(workspace: File?, projectName: String?) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by TermSetup.ui.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf<NetReport?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }   // "reset" | "signout"
    var restartTick by remember { mutableStateOf(0) }

    LaunchedEffect(Unit) { TermSetup.refresh(ctx, TermSetup.appScope) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // status line
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Claude Code  \u2022  " + (ui.claudeVersion?.let { "v$it" } ?: ui.stage.label), color = if (ui.stage == Stage.READY) c.green else if (ui.stage == Stage.ERROR) c.red else c.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Folder: /workspace  (" + (projectName ?: "no project open") + ")" + (net?.let { "  \u2022  " + it.detail } ?: ""), color = c.sub, fontSize = 11.sp, maxLines = 3)
        }

        if (ui.stage != Stage.READY) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui.stage.label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(ui.error ?: ui.message, color = if (ui.error != null) c.red else c.sub, fontSize = 14.sp)
                if (ui.busy) { if (ui.progress in 0f..1f) LinearProgressIndicator(progress = { ui.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
                Text("Needs internet for the one-time setup and whenever Claude Code talks to Anthropic. Sign-in happens inside Claude Code the first time you start it (it shows a link to open in a browser; an API key or a Claude subscription is required). Claude Code can run commands and change files in the open project.", color = c.sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ui.busy) PillButton(if (ui.stage == Stage.ERROR) "Retry" else if (ui.message.contains("Resume")) "Resume" else "Start setup", c.green) { TermSetup.start(ctx, TermSetup.appScope) }
                    if (ui.busy) PillButton("Cancel", c.red) { TermSetup.cancel() }
                    PillButton("Repair environment", c.orange, enabled = !ui.busy) { TermSetup.repair(ctx, false, TermSetup.appScope) }
                    PillButton("Reset all", c.red, enabled = !ui.busy) { confirm = "reset" }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (showLog) "Hide logs" else "View logs", c.accent) { showLog = !showLog }
                    PillButton("Copy log", c.accent) {
                        val logText = TermSetup.readLog(ctx)
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ZENIQ setup log", logText))
                        Toast.makeText(ctx, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    PillButton("Check connection", c.accent) { scope.launch { net = TermNet.report(ctx, "api.anthropic.com") } }
                }
                if (showLog) Text(TermSetup.readLog(ctx), color = c.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(8.dp))
            }
        } else {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Run Claude", c.green) { TermSessions.ensure(ctx, workspace); TermSessions.send("claude\n") }
                PillButton("Use OpenRouter key", c.accent) {
                    // sends the OpenRouter key saved in Settings to Claude Code as environment variables (see the Help manual)
                    val k = com.astra.agent.SettingsStore(ctx).keyFor("openrouter")
                    if (k.isBlank()) Toast.makeText(ctx, "Save your OpenRouter key in Settings > Cloud provider first.", Toast.LENGTH_LONG).show()
                    else {
                        TermSessions.ensure(ctx, workspace)
                        TermSessions.send("export ANTHROPIC_BASE_URL=\"https://openrouter.ai/api\" ANTHROPIC_AUTH_TOKEN=\"" + k + "\" ANTHROPIC_API_KEY=\"\"\n")
                        Toast.makeText(ctx, "Done. Now tap Run Claude. If you were signed in before, type /logout first.", Toast.LENGTH_LONG).show()
                    }
                }
                PillButton("Restart", c.orange) { TermSessions.restart(ctx, workspace); restartTick++ }
                PillButton("Clear") { TermSessions.send("\u000c") }
                PillButton("Check connection", c.accent) { scope.launch { net = TermNet.report(ctx, "api.anthropic.com") } }
                PillButton("Sign out", c.red) { confirm = "signout" }
                PillButton(if (showLog) "Hide logs" else "Logs", c.sub) { showLog = !showLog }
                PillButton("Copy log", c.sub) {
                    val logText = TermSetup.readLog(ctx)
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ZENIQ setup log", logText))
                    Toast.makeText(ctx, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            }
            if (showLog) Text(TermSetup.readLog(ctx).takeLast(3000), color = c.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).background(c.fill).padding(6.dp))
            val client = remember { TermSessions.client(ctx) }
            key(restartTick, workspace?.absolutePath) {
                val session = remember { TermSessions.ensure(ctx, workspace) }
                Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { v ->
                        TerminalView(v, null).apply {
                            setTerminalViewClient(client)
                            setTextSize((13 * v.resources.displayMetrics.scaledDensity).toInt())
                            setTypeface(Typeface.MONOSPACE)
                            isFocusable = true; isFocusableInTouchMode = true
                            client.view = this
                            attachSession(session)
                            requestFocus()
                        }
                    }, update = { it.onScreenUpdated() })
                }
            }
            if (client.finished) Text("The shell ended. Tap Restart to open a new one.", color = c.orange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            // keys a phone keyboard does not have
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Esc") { client.view?.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0) }
                PillButton("Tab") { client.view?.handleKeyCode(KeyEvent.KEYCODE_TAB, 0) }
                PillButton("Ctrl", if (client.ctrl) c.green else c.sub) { client.ctrl = !client.ctrl }
                PillButton("\u2191") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0) }
                PillButton("\u2193") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
                PillButton("\u2190") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
                PillButton("\u2192") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
                PillButton("^C", c.red) { TermSessions.send("\u0003") }
                PillButton("^D") { TermSessions.send("\u0004") }
                PillButton("|") { TermSessions.send("|") }
                PillButton("/") { TermSessions.send("/") }
                PillButton("~") { TermSessions.send("~") }
                PillButton("-") { TermSessions.send("-") }
            }
        }
    }

    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (what == "reset") "Reset the Linux environment?" else "Sign out of Claude Code?") },
            text = { Text(if (what == "reset") "This deletes the installed Linux system, Node.js, Claude Code and its sign-in, and downloads them again. Your ZENIQ projects are not touched." else "This removes Claude Code's saved sign-in on this phone. Projects and installed tools stay.") },
            confirmButton = { TextButton(onClick = {
                if (what == "reset") { TermSessions.stop(); TermSetup.repair(ctx, true, TermSetup.appScope) } else { TermSessions.stop(); TermSetup.resetSignIn(ctx); restartTick++ }
                confirm = null
            }) { Text(if (what == "reset") "Reset" else "Sign out") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}
