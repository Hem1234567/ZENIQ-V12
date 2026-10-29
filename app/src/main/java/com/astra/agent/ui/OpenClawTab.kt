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
import com.astra.agent.device.AgentService
import com.astra.agent.term.*
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
import java.io.File

private const val OC_TAG = "openclaw"
private const val OC_PORT = 18789

/**
 * Open Claw: the OpenClaw agent runs inside ZENIQ in the same embedded Linux environment as Claude Code (PTY + PRoot + Alpine + Node).
 * Install once, then: Onboard (choose a model provider and paste its key), start the Gateway, open the Dashboard.
 */
@Composable
fun OpenClawTab(workspace: File?) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ui by TermSetup.ocUi.collectAsState()
    var showLog by remember { mutableStateOf(false) }
    var net by remember { mutableStateOf<NetReport?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }   // "reset" | "config"
    var restartTick by remember { mutableStateOf(0) }
    var tips by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { TermSetup.refreshOpenClaw(ctx, TermSetup.appScope) }

    Column(Modifier.fillMaxSize().imePadding()) {
        // status line
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text("Open Claw  \u2022  " + (if (ui.stage == Stage.READY) (ui.claudeVersion?.let { "v$it" } ?: "Ready") else ui.stage.label),
                color = if (ui.stage == Stage.READY) c.green else if (ui.stage == Stage.ERROR) c.red else c.sub, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("Dashboard: http://127.0.0.1:$OC_PORT" + (net?.let { "  \u2022  " + it.detail } ?: ""), color = c.sub, fontSize = 11.sp, maxLines = 3)
        }

        if (ui.stage != Stage.READY) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ui.stage.label, color = c.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(ui.error ?: ui.message, color = if (ui.error != null) c.red else c.sub, fontSize = 14.sp)
                if (ui.busy) { if (ui.progress in 0f..1f) LinearProgressIndicator(progress = { ui.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth()) }
                Text("OpenClaw is a personal AI agent with a gateway and a web dashboard. It requires Node.js 24.22.1 or newer, so ZENIQ installs a small Ubuntu system with the official Node.js 24 for it " +
                    "(about 350 MB, needs internet once, Claude Code is not affected). After the install you run Onboard to choose an AI model provider " +
                    "(for example OpenRouter) and paste its API key. OpenClaw can run commands and change files in its workspace, so only give it keys you trust it with.", color = c.sub, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!ui.busy) PillButton(if (ui.stage == Stage.ERROR) "Retry" else if (ui.message.contains("Resume")) "Resume" else "Install OpenClaw", c.green) { TermSetup.startOpenClaw(ctx, TermSetup.appScope) }
                    if (ui.busy) PillButton("Cancel", c.red) { TermSetup.cancel() }
                    PillButton("Repair environment", c.orange, enabled = !ui.busy) { TermSetup.repairOpenClaw(ctx, false, TermSetup.appScope) }
                    PillButton("Reset environment", c.red, enabled = !ui.busy) { confirm = "reset" }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (showLog) "Hide logs" else "View logs", c.accent) { showLog = !showLog }
                    PillButton("Copy log", c.accent) {
                        val logText = TermSetup.readLog(ctx)
                        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ZENIQ setup log", logText))
                        Toast.makeText(ctx, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    PillButton("Check connection", c.accent) { scope.launch { net = TermNet.report(ctx, "registry.npmjs.org") } }
                }
                if (showLog) Text(TermSetup.readLog(ctx), color = c.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(8.dp))
            }
        } else {
            if (tips) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)) {
                    Text("Quick start: 1) Onboard: answer the questions, pick a model provider and paste your key.  2) Gateway: starts OpenClaw (leave it running).  " +
                        "3) Dashboard link: shows the address, then Open in browser.", color = c.sub, fontSize = 11.sp, lineHeight = 15.sp)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("1 Onboard", c.green) { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("openclaw onboard\n", OC_TAG) }
                PillButton("2 Gateway", c.green) {
                    TermSessions.ensure(ctx, workspace, OC_TAG)
                    TermSessions.send("openclaw gateway --port $OC_PORT\n", OC_TAG)
                    AgentService.start(ctx, "OpenClaw gateway is running")
                }
                PillButton("3 Dashboard link", c.green) { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("openclaw dashboard --no-open\n", OC_TAG) }
                PillButton("Open in browser", c.accent) { openOcUrl(ctx, "http://127.0.0.1:$OC_PORT/") }
                PillButton("Stop gateway", c.red) { TermSessions.send("\u0003", OC_TAG); AgentService.stop(ctx) }
                PillButton("Doctor", c.orange) { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("openclaw doctor\n", OC_TAG) }
                PillButton("Status", c.sub) { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("openclaw gateway status\n", OC_TAG) }
                PillButton("Update", c.sub) { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("npm install -g openclaw@latest\n", OC_TAG) }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Use my OpenRouter key", c.accent) {
                    val k = com.astra.agent.SettingsStore(ctx).keyFor("openrouter")
                    if (k.isBlank()) Toast.makeText(ctx, "Save your OpenRouter key in Settings > Cloud provider first.", Toast.LENGTH_LONG).show()
                    else { TermSessions.ensure(ctx, workspace, OC_TAG); TermSessions.send("export OPENROUTER_API_KEY='" + k + "'\n", OC_TAG) }
                }
                PillButton("Restart shell", c.orange) { TermSessions.restart(ctx, workspace, OC_TAG); restartTick++ }
                PillButton("Clear") { TermSessions.send("\u000c", OC_TAG) }
                PillButton("Check connection", c.accent) { scope.launch { net = TermNet.report(ctx, "registry.npmjs.org") } }
                PillButton("Reset OpenClaw settings", c.red) { confirm = "config" }
                PillButton(if (tips) "Hide tips" else "Show tips", c.sub) { tips = !tips }
                PillButton(if (showLog) "Hide logs" else "Logs", c.sub) { showLog = !showLog }
            }
            if (showLog) Text(TermSetup.readLog(ctx).takeLast(3000), color = c.text, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).verticalScroll(rememberScrollState()).background(c.fill).padding(6.dp))
            val client = remember { TermSessions.client(ctx, OC_TAG) }
            key(restartTick, workspace?.absolutePath) {
                val session = remember { TermSessions.ensure(ctx, workspace, OC_TAG) }
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
            if (client.finished) Text("The shell ended. Tap Restart shell to open a new one.", color = c.orange, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
            // keys a phone keyboard does not have
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PillButton("Esc") { client.view?.handleKeyCode(KeyEvent.KEYCODE_ESCAPE, 0) }
                PillButton("Tab") { client.view?.handleKeyCode(KeyEvent.KEYCODE_TAB, 0) }
                PillButton("Ctrl", if (client.ctrl) c.green else c.sub) { client.ctrl = !client.ctrl }
                PillButton("\u2191") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_UP, 0) }
                PillButton("\u2193") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_DOWN, 0) }
                PillButton("\u2190") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0) }
                PillButton("\u2192") { client.view?.handleKeyCode(KeyEvent.KEYCODE_DPAD_RIGHT, 0) }
                PillButton("^C", c.red) { TermSessions.send("\u0003", OC_TAG) }
                PillButton("^D") { TermSessions.send("\u0004", OC_TAG) }
                PillButton("|") { TermSessions.send("|", OC_TAG) }
                PillButton("/") { TermSessions.send("/", OC_TAG) }
                PillButton("~") { TermSessions.send("~", OC_TAG) }
                PillButton("-") { TermSessions.send("-", OC_TAG) }
            }
        }
    }

    confirm?.let { what ->
        AlertDialog(onDismissRequest = { confirm = null },
            title = { Text(if (what == "reset") "Reset the Open Claw environment?" else "Reset OpenClaw settings?") },
            text = { Text(if (what == "reset") "This deletes Open Claw's Ubuntu system, Node.js 24, OpenClaw and its settings, and downloads them again (about 350 MB). Claude Code and your ZENIQ projects are not touched."
                          else "This deletes OpenClaw's saved settings, keys and sessions on this phone. The install stays. Run Onboard again afterwards.") },
            confirmButton = { TextButton(onClick = {
                if (what == "reset") { TermSessions.stop(OC_TAG); TermSetup.repairOpenClaw(ctx, true, TermSetup.appScope) }
                else { TermSessions.stop(OC_TAG); TermSetup.resetOpenClawConfig(ctx); restartTick++ }
                confirm = null
            }) { Text(if (what == "reset") "Reset" else "Delete settings") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
}

private fun openOcUrl(ctx: Context, url: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Throwable) { Toast.makeText(ctx, "Open " + url + " in your browser while the gateway runs.", Toast.LENGTH_LONG).show() }
}
