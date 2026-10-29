package com.astra.agent.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.astra.agent.llm.Providers

/** One piece of the manual. */
private sealed class HelpBlock {
    class Title(val text: String) : HelpBlock()
    class Para(val text: String) : HelpBlock()
    class Step(val n: Int, val text: String) : HelpBlock()
    class Code(val text: String) : HelpBlock()
    class Note(val text: String) : HelpBlock()
}

private class HelpTab(val label: String, val blocks: List<HelpBlock>)

private val OR_ENV = "export ANTHROPIC_BASE_URL=\"https://openrouter.ai/api\"\nexport ANTHROPIC_AUTH_TOKEN=\"sk-or-v1-YOUR_KEY\"\nexport ANTHROPIC_API_KEY=\"\"\nclaude"

private fun tabs(): List<HelpTab> = listOf(
    HelpTab("Start here", listOf(
        HelpBlock.Title("What is in the Code tab"),
        HelpBlock.Para("IDE: describe an app, ZENIQ plans it, writes every file, checks it and lets you run it. Claude Code: the official Claude Code assistant in a real terminal on your phone. Open Claw: the OpenClaw personal agent with a dashboard, in a real terminal."),
        HelpBlock.Title("Two ways to power them"),
        HelpBlock.Step(1, "Fast Mode (cloud): answers in seconds. Needs internet and a key from a provider such as OpenRouter, Groq or Gemini. See the tab \"API providers\"."),
        HelpBlock.Step(2, "Local model (offline): free and private, but speed depends on your phone. Models tab: download a model, tap Load. See the tab \"Speed\"."),
        HelpBlock.Title("Do this once"),
        HelpBlock.Step(1, "Settings > Fast Mode > Cloud provider > Connect: choose a provider, paste its key, Connect."),
        HelpBlock.Step(2, "Settings > Phone control: turn on the Accessibility service and Display over other apps, so ZENIQ can open apps and tap for you."),
        HelpBlock.Step(3, "Keep the phone charged and connected to Wi-Fi for the first Claude Code / Open Claw install (about 250 MB base system plus packages)."),
        HelpBlock.Note("Tip: tap a tab above to read the manual for that part. Every command box has a Copy button.")
    )),
    HelpTab("IDE", listOf(
        HelpBlock.Title("Build an app with the IDE"),
        HelpBlock.Step(1, "Open Code > IDE. Make sure Fast Mode is ON with a provider connected (fastest), or load a local model on the Models tab."),
        HelpBlock.Step(2, "Describe what you want in the box and tap Run. Be specific: what the app does, its screens, the style. Example below."),
        HelpBlock.Code("Build a to-do app with add, edit, delete, mark done, filters (all/active/done), a dark theme and saving in the browser."),
        HelpBlock.Step(3, "ZENIQ thinks, researches and writes an Implementation plan. Read it. Tap Approve to build (or edit the plan first)."),
        HelpBlock.Step(4, "Files appear one by one under FILES and the TERMINAL shows the checks. If a check finds a mistake (a missing file, an id the script cannot find, a cut-off file) ZENIQ repairs it by itself."),
        HelpBlock.Step(5, "Open the project: tap \u25B6 Run to try the app. Save & Share, Export ZIP and Commit checkpoint are in the project screen; History restores older versions."),
        HelpBlock.Step(6, "To change the app later, open it and use Run agent with an instruction such as \"add a search box\". A checkpoint is saved first, so you can undo."),
        HelpBlock.Title("Get apps that really work"),
        HelpBlock.Para("Ask for one app at a time. Say what data it keeps (\"save in the browser\"). Ask for a backend or database only if you need one: plain web apps run immediately, a backend needs the Linux environment from the Claude Code tab. Name React only if you want React."),
        HelpBlock.Para("With a small local model ZENIQ builds one compact index.html (page, style and script together) because that is faster and more reliable. Cloud mode writes separate files at the same time."),
        HelpBlock.Title("If something goes wrong"),
        HelpBlock.Para("\"Could not reach / busy\": check the internet and your key, or connect another provider. Nothing happens with a local model: load one on the Models tab. Blank preview: open the Build log, then Run agent with \"fix the errors\"."),
    )),
    HelpTab("Claude Code", listOf(
        HelpBlock.Title("Install and start"),
        HelpBlock.Step(1, "Open Code > Claude Code. Tap the install / start setup button. It downloads a small Linux system, Node.js and Claude Code. Needs internet, about 1.5 GB free space and 5 to 15 minutes. Keep the app open."),
        HelpBlock.Step(2, "Create a project with + and open it. The terminal starts in the folder /workspace."),
        HelpBlock.Step(3, "Tap Run Claude (or type claude and press enter)."),
        HelpBlock.Step(4, "First run: Claude asks you to sign in. Choose a login method, open the link it shows in your browser, sign in, and paste the code back into the terminal. You need a Claude subscription or an Anthropic API account."),
        HelpBlock.Title("Use OpenRouter instead of an Anthropic login"),
        HelpBlock.Para("Claude Code can talk to OpenRouter. Type these lines in the terminal (replace the key), or tap Use OpenRouter key in the Claude Code tab to send them with the key you saved in Settings:"),
        HelpBlock.Code(OR_ENV),
        HelpBlock.Note("If you were signed in before, type /logout inside Claude first. OpenRouter documents this setup as guaranteed for Anthropic models. The lines last until you close the shell; to keep them, add them to the file ~/.profile."),
        HelpBlock.Title("Keys that a phone keyboard does not have"),
        HelpBlock.Para("Use the key bar under the terminal: Esc, Tab, Ctrl, arrows, ^C (stop), ^D, |, /, ~, -. Restart opens a fresh shell. Sign out clears your Claude login."),
        HelpBlock.Title("Problems"),
        HelpBlock.Para("Setup stops: tap Retry. Check connection tests the network. Repair environment reinstalls Claude Code without touching your projects. View logs / Copy log shows what happened. Reset all deletes the Linux system and starts again."),
    )),
    HelpTab("Open Claw", listOf(
        HelpBlock.Title("Install"),
        HelpBlock.Step(1, "Open Code > Open Claw. Tap Install OpenClaw. OpenClaw only runs on Node.js 24.22.1 or newer, which the small system used for Claude Code cannot provide. So ZENIQ downloads a separate Ubuntu system (about 28 MB), the official Node.js 24 (about 58 MB, checked with its published checksum), git, and the openclaw package. You need about 2 GB free space, internet, and 10 to 25 minutes. Keep the app open."),
        HelpBlock.Step(2, "When it says Ready you see the buttons 1 Onboard, 2 Gateway, 3 Dashboard link and a terminal."),
        HelpBlock.Title("Connect an AI provider"),
        HelpBlock.Step(1, "Tap 1 Onboard and answer the questions. When it asks for a model provider, choose OpenRouter (or Gemini, Mistral, OpenAI, Hugging Face ...) and paste the key. If it offers to install a background service or daemon, say No: the Gateway button runs it for you."),
        HelpBlock.Step(2, "Or use a command. For OpenRouter (tap Use my OpenRouter key first to fill in the key from Settings):"),
        HelpBlock.Code("openclaw onboard --auth-choice openrouter-api-key"),
        HelpBlock.Step(3, "Choose the model. Models are written provider/model:"),
        HelpBlock.Code("openclaw models list\nopenclaw models set openrouter/auto"),
        HelpBlock.Para("Other built-in choices: --auth-choice gemini-api-key, mistral-api-key, openai-api-key, huggingface-api-key. A service that is not built in can be added as a custom OpenAI-compatible provider in the OpenClaw settings."),
        HelpBlock.Title("Run it"),
        HelpBlock.Step(1, "Tap 2 Gateway. It runs openclaw gateway --port 18789 and keeps running while the notification is shown."),
        HelpBlock.Step(2, "Tap 3 Dashboard link to print the dashboard address, then Open in browser (http://127.0.0.1:18789)."),
        HelpBlock.Step(3, "Chat channels (Telegram, WhatsApp ...) are added during onboarding or in the dashboard."),
        HelpBlock.Step(4, "Stop gateway sends Ctrl+C. Doctor checks the setup. Status shows the gateway state. Update installs the newest version."),
        HelpBlock.Note("OpenClaw can run commands and change files in its workspace. Only give it keys and channels you trust it with."),
        HelpBlock.Title("Problems"),
        HelpBlock.Para("\"No API key found for provider\": run Onboard again. \"Node ... use 24.22.1+\": the old Alpine-based install was used; tap Install OpenClaw to set up the new environment. Install failed: Retry, then Repair environment; View logs shows the reason. Reset OpenClaw settings deletes its saved keys and sessions on this phone. Reset environment removes Open Claw's Ubuntu system and installs it again; Claude Code is never touched."),
    )),
    HelpTab("API providers", listOf(
        HelpBlock.Title("Connect a provider"),
        HelpBlock.Step(1, "Settings > Fast Mode > Cloud provider > Connect (or the button below)."),
        HelpBlock.Step(2, "Pick a provider chip, tap Get a key, create a key on the provider's site and paste it."),
        HelpBlock.Step(3, "Tap Load models and tap the one you want (or type its name). Tap Test connection."),
        HelpBlock.Step(4, "Tap Connect. Fast Mode, the IDE and the phone-control agent now use this provider. Each provider keeps its own key, so you can switch back at any time."),
        HelpBlock.Title("Providers built in"),
    ) + Providers.all.map { p ->
        HelpBlock.Para(p.name + ": " + p.note + (if (p.keyUrl.isNotBlank()) "\nKey: " + p.keyUrl else "") + "\nBase URL: " + p.base)
    } + listOf(
        HelpBlock.Title("Your own computer (Ollama / LM Studio)"),
        HelpBlock.Para("Run the server on your PC in the same Wi-Fi, allow network access (for Ollama set OLLAMA_HOST=0.0.0.0), choose \"Ollama / LM Studio\" and type http://YOUR-PC-IP:11434/v1 as the base URL. Any text works as the key."),
        HelpBlock.Title("Any other service"),
        HelpBlock.Para("Choose Custom and type the base URL of a service that offers /v1/chat/completions, its key and a model name."),
        HelpBlock.Note("Free tiers and model names change often. Load models shows what your key can use today. Tools (open apps, search, tap) use the model's function calling when it has it, and ZENIQ falls back to a text format when it does not."),
    )),
    HelpTab("Speed", listOf(
        HelpBlock.Title("Seconds: use Fast Mode"),
        HelpBlock.Para("A cloud model answers in seconds. A model on the phone has to compute every word with the phone's processor, so it is slower: the bigger the model, the slower. This is normal for every phone."),
        HelpBlock.Title("Make the local model faster"),
        HelpBlock.Step(1, "Choose a smaller model. Models tab > Best for what you do > Fast & light. A 0.5B to 1.7B model answers in seconds; 4B and bigger take much longer."),
        HelpBlock.Step(2, "Prefer models that offer a Q4_0 file: ZENIQ downloads it automatically when the repository has one, because it runs faster on phones."),
        HelpBlock.Step(3, "Keep Thinking mode OFF (Settings > Agent) and keep Max tokens per reply small."),
        HelpBlock.Step(4, "Close other apps, keep the phone cool and charged. A hot phone slows down."),
        HelpBlock.Step(5, "Models tab > Speed test shows how many words per second your phone gets with the loaded model and tells you what to do."),
        HelpBlock.Step(6, "In the IDE a local model builds one compact file after a short plan, so a small app can finish in about a minute on a fast phone."),
        HelpBlock.Note("Your phone's speed decides the rest: a flagship phone with a 1.5B model feels quick, a budget phone with a 4B model does not. For long answers and big apps use Fast Mode."),
    ))
)

@Composable
fun CodeHelpScreen(vm: AstraViewModel, onClose: () -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val all = remember { tabs() }
    var tab by remember { mutableStateOf(0) }
    var showProvider by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Help & manual", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
                PillButton("Close", c.accent) { onClose() }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                all.forEachIndexed { i, t ->
                    Box(Modifier.clip(RoundedCornerShape(18.dp)).background(if (i == tab) c.accent else c.fill).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(t.label, fontSize = 14.sp, color = if (i == tab) c.bg else c.text, fontWeight = if (i == tab) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                all[tab].blocks.forEach { b ->
                    when (b) {
                        is HelpBlock.Title -> Text(b.text, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.padding(top = 6.dp))
                        is HelpBlock.Para -> Text(b.text, fontSize = 14.sp, lineHeight = 20.sp, color = c.text)
                        is HelpBlock.Step -> Row(verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(c.accent), contentAlignment = Alignment.Center) {
                                Text("${b.n}", fontSize = 13.sp, color = c.bg, fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(b.text, fontSize = 14.sp, lineHeight = 20.sp, color = c.text, modifier = Modifier.weight(1f))
                        }
                        is HelpBlock.Code -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(10.dp)) {
                            Text(b.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.text, lineHeight = 17.sp)
                            Spacer(Modifier.height(6.dp))
                            PillButton("Copy", c.accent) { copyHelp(ctx, b.text) }
                        }
                        is HelpBlock.Note -> Text(b.text, fontSize = 13.sp, lineHeight = 18.sp, color = c.orange,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.orange.copy(alpha = 0.10f)).padding(10.dp))
                    }
                }
                if (all[tab].label == "API providers") PillButton("Connect a provider now", c.green) { showProvider = true }
                Spacer(Modifier.height(24.dp))
            }
        }
        if (showProvider) ProviderDialog(vm) { showProvider = false }
    }
}

private fun copyHelp(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ZENIQ manual", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
