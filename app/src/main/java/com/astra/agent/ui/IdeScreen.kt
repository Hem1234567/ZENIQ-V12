package com.astra.agent.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.Settings
import com.astra.agent.code.*
import com.astra.agent.llm.Completion
import com.astra.agent.llm.DocumentManager
import com.astra.agent.llm.LlmEngine
import com.astra.agent.llm.SttState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

// IDE mode (Lovable style): prompt -> thinking page -> plan (Edit / Approve) -> live build page -> editor + preview. Projects are saved
// automatically in the IDE folder and listed as cards. The screens only READ a BuildSession; IdeBuilder writes to it.

internal enum class IdePage { HOME, WORKING, PLAN, BUILD, EDITOR }

/** Lives for the whole app run (not for one screen), so a build keeps going while the user visits another tab. */
internal object IdeRuntime {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var agentJob: Job? = null

    var page by mutableStateOf(IdePage.HOME)
    var session by mutableStateOf<BuildSession?>(null)
    var openName by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    /** The Claude Code agent run (separate from the IDE flow). */
    var agent by mutableStateOf<BuildSession?>(null)

    fun start(ctx: Context, s: Settings, prompt: String) {
        job?.cancel()
        val app = ctx.applicationContext
        val session = BuildSession(CodeMode.IDE, prompt, autoApprove = false)
        this.session = session; notice = null
        page = IdePage.WORKING
        job = scope.launch {
            try {
                IdeBuilder.think(s, session)
                session.phase = Phase.PLAN
                page = IdePage.PLAN
                val finalPlan = session.approval.await()
                session.plan = finalPlan
                IdeBuilder.applyPlanMeta(session)
                page = IdePage.BUILD
                IdeBuilder.build(app, s, session, ProjectStore.root(app, CodeMode.IDE))
                notice = session.error
            } catch (e: CancellationException) {
                session.phase = Phase.FAILED; session.error = "Stopped."
                throw e
            } catch (e: Throwable) {
                session.phase = Phase.FAILED
                session.error = e.message ?: "Something went wrong."
                if (page == IdePage.WORKING) page = IdePage.BUILD   // the failure card lives on the build page
            }
        }
    }

    /** Claude Code "Agent": no plan approval, builds straight into the open project of the Claude Code folder. */
    fun startAgent(ctx: Context, s: Settings, prompt: String, project: String) {
        agentJob?.cancel()
        val app = ctx.applicationContext
        val session = BuildSession(CodeMode.CLAUDE, prompt, autoApprove = true, existingProject = project)
        agent = session
        agentJob = scope.launch {
            try {
                IdeBuilder.think(s, session)
                IdeBuilder.applyPlanMeta(session)
                IdeBuilder.build(app, s, session, ProjectStore.root(app, CodeMode.CLAUDE))
            } catch (e: CancellationException) {
                session.phase = Phase.FAILED; session.error = "Stopped."
                throw e
            } catch (e: Throwable) {
                session.phase = Phase.FAILED; session.error = e.message ?: "Something went wrong."
            }
        }
    }

    fun stop() { job?.cancel(); job = null; session?.let { if (!it.approval.isCompleted) it.approval.cancel() } }
    fun stopAgent() { agentJob?.cancel(); agentJob = null }
    fun backHome() { stop(); page = IdePage.HOME; session = null }
}

// ── Mode toggle (IDE | Claude Code) ───────────────────────────────────────────

@Composable
internal fun ModeToggle(selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalIos.current
    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.fill).padding(3.dp)) {
        CodeMode.values().forEachIndexed { i, m ->
            val sel = selected == i
            val bg by animateColorAsState(if (sel) c.accent else Color.Transparent, tween(240), label = "tg_bg$i")
            val fg by animateColorAsState(if (sel) Color.White else c.sub, tween(240), label = "tg_fg$i")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(bg).pressScale { onSelect(i) }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                Text(m.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

// ── Home: prompt box + project cards ──────────────────────────────────────────

@Composable
internal fun IdeHome(vm: AstraViewModel, onOpen: (String) -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val attachments = remember { mutableStateListOf<Pair<String, String>>() }
    var reading by remember { mutableStateOf(false) }
    var projects by remember { mutableStateOf(ProjectStore.list(ctx, CodeMode.IDE)) }
    var deleting by remember { mutableStateOf<ProjectInfo?>(null) }
    var sharing by remember { mutableStateOf<ProjectInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val stt by vm.voice.sttState.collectAsState()
    val partial by vm.voice.partialText.collectAsState()
    val listening = stt == SttState.Listening || stt == SttState.Processing
    LaunchedEffect(partial) { if (partial.isNotBlank()) prompt = partial }
    LaunchedEffect(Unit) { projects = withContext(Dispatchers.IO) { ProjectStore.list(ctx, CodeMode.IDE) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && cur.moveToFirst()) cur.getString(i) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "file"
        scope.launch {
            reading = true
            val r = DocumentManager(ctx).load(uri, name)
            reading = false
            r.onSuccess { d ->
                if (d.readable) { attachments.add(name to d.text.take(8000)) } else { error = "\"$name\" has no readable text." }
            }.onFailure { e -> error = "Could not read $name: ${e.message}" }
        }
    }

    fun submit() {
        val text = prompt.trim()
        if (text.isBlank()) { error = "Describe the app you want to build."; return }
        if (!Completion.usesCloud(s) && LlmEngine.loadedName == null) {
            error = "No AI is ready. Add an OpenRouter key in Settings (Fast Mode), or load a model on the Models tab."; return
        }
        error = null
        val full = text + attachments.joinToString("") { "\n\nAttached file ${it.first}:\n${it.second}" }
        prompt = ""; attachments.clear()
        IdeRuntime.start(ctx, s, full)
    }

    LazyColumn(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.card).border(0.5.dp, c.line, RoundedCornerShape(20.dp)).padding(14.dp)) {
                    Box(Modifier.fillMaxWidth().defaultMinSize(minHeight = 84.dp)) {
                        if (prompt.isEmpty()) Text("Build a full stack music application", color = c.sub, fontSize = 17.sp)
                        BasicTextField(prompt, { prompt = it; error = null }, Modifier.fillMaxWidth(),
                            textStyle = TextStyle(color = c.text, fontSize = 17.sp), cursorBrush = SolidColor(c.accent), maxLines = 8,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }))
                    }
                    AnimatedVisibility(attachments.isNotEmpty() || reading) {
                        Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            attachments.toList().forEach { a ->
                                Row(Modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(start = 10.dp, top = 5.dp, bottom = 5.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Description, null, tint = c.accent, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(5.dp))
                                    Text(a.first.take(22), fontSize = 12.sp, maxLines = 1)
                                    Icon(Icons.Filled.Close, "Remove", tint = c.sub, modifier = Modifier.size(22.dp).padding(4.dp).pressScale { attachments.remove(a) })
                                }
                            }
                            if (reading) Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp)); Text("Reading file…", fontSize = 12.sp, color = c.sub)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).pressScale { picker.launch(arrayOf("*/*")) }.background(c.fill, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.AttachFile, "Attach a file", tint = c.accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        val micColor by animateColorAsState(if (listening) c.red else c.fill, tween(200), label = "ide_mic")
                        Box(Modifier.size(38.dp).pressScale {
                            if (listening) vm.stopVoiceInput()
                            else vm.voice.startListening(onResult = { t -> prompt = t }, onError = { error = "Voice not recognised. Tap the mic and try again." })
                        }.background(micColor, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (listening) Icons.Filled.MicOff else Icons.Filled.Mic, "Voice", tint = if (listening) Color.White else c.accent, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.pressScale { submit() }.clip(CircleShape).background(c.accent).padding(horizontal = 20.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Run", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                AnimatedVisibility(error != null) { Text(error ?: "", Modifier.padding(start = 6.dp, top = 8.dp), color = c.red, fontSize = 13.sp) }
                AnimatedVisibility(message != null) { Text(message ?: "", Modifier.padding(start = 6.dp, top = 8.dp), color = c.sub, fontSize = 13.sp) }
            }
        }
        item { SectionHeader("Your projects") }
        if (projects.isEmpty()) item { Text("No projects yet. Describe an app above and press Run: it is saved here automatically.", Modifier.padding(horizontal = 32.dp, vertical = 8.dp), color = c.sub, fontSize = 14.sp) }
        items(projects, key = { it.name }) { p ->
            ProjectCard(p, Modifier.animateItem(), onOpen = { onOpen(p.name) }, onShare = { sharing = p }, onDelete = { deleting = p })
        }
    }

    deleting?.let { p ->
        AlertDialog(containerColor = c.card, onDismissRequest = { deleting = null },
            title = { Text("Delete \"${p.title}\"?", fontWeight = FontWeight.SemiBold, color = c.text) },
            text = { Text("This removes the project and all its files from this phone. It cannot be undone.", color = c.sub) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        val ok = withContext(Dispatchers.IO) { ProjectStore.delete(ctx, CodeMode.IDE, p.name) }
                        projects = withContext(Dispatchers.IO) { ProjectStore.list(ctx, CodeMode.IDE) }
                        message = if (ok) "Deleted ${p.title}." else "Could not delete ${p.title}."
                    }
                }) { Text("Delete", color = c.red, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = c.sub) } })
    }
    sharing?.let { p ->
        ShareDialog(p.name, p.title, CodeMode.IDE, onDismiss = { sharing = null }, onMessage = { message = it })
    }
}

@Composable
private fun Thumb(info: ProjectInfo) {
    val hue = (abs(info.name.hashCode()) % 360).toFloat()
    Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
        .background(Brush.linearGradient(listOf(Color.hsv(hue, 0.55f, 0.95f), Color.hsv((hue + 40f) % 360f, 0.65f, 0.72f)))),
        contentAlignment = Alignment.Center) {
        Text(info.title.take(1).uppercase(), color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun ProjectCard(p: ProjectInfo, modifier: Modifier = Modifier, onOpen: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = LocalIos.current
    Column(modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.card).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Thumb(p)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(p.title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Edited " + DateUtils.getRelativeTimeSpanString(p.lastEdited, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS) +
                    " \u00b7 ${p.fileCount} file" + (if (p.fileCount == 1) "" else "s") + (if (p.stack.isNotBlank()) " \u00b7 ${p.stack}" else ""),
                    fontSize = 12.sp, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (p.prompt.isNotBlank()) Text(p.prompt.replace('\n', ' '), fontSize = 12.sp, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Open", c.accent, onClick = onOpen)
            PillButton("Share", c.green, onClick = onShare)
            PillButton("Delete", c.red, onClick = onDelete)
        }
    }
}

/** Share a project for real: a ZIP of all files, or ONE self-contained web page. Both go through the Android share sheet and are also kept in Documents. */
@Composable
internal fun ShareDialog(name: String, title: String, mode: CodeMode, onDismiss: () -> Unit, onMessage: (String) -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    AlertDialog(containerColor = c.card, onDismissRequest = onDismiss,
        title = { Text("Share \"$title\"", fontWeight = FontWeight.SemiBold, color = c.text) },
        text = { Text("ZIP contains every file. The web page option makes one single .html file that opens in any browser.", color = c.sub, fontSize = 14.sp) },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    onDismiss()
                    scope.launch {
                        try {
                            val ws = Workspace(File(ProjectStore.root(ctx, mode), name))
                            val html = withContext(Dispatchers.IO) { ProjectStore.singleHtml(ws) }
                            if (html == null) { onMessage("This project has no web page to share as one file. Use ZIP."); return@launch }
                            val saved = withContext(Dispatchers.IO) { FileStorage.save(ctx, name, "html", "text/html", html.toByteArray()) }
                            onMessage(if (FileStorage.share(ctx, File(saved.path), "text/html")) "Page saved to ${saved.publicPath}" else "Page saved to ${saved.publicPath}, but no app could receive it.")
                        } catch (e: Exception) { onMessage("Could not share: ${e.message ?: "unknown error"}") }
                    }
                }) { Text("Web page", color = c.accent) }
                TextButton(onClick = {
                    onDismiss()
                    scope.launch {
                        try {
                            val ws = Workspace(File(ProjectStore.root(ctx, mode), name))
                            val bytes = withContext(Dispatchers.IO) { ws.zip() }
                            val saved = withContext(Dispatchers.IO) { FileStorage.save(ctx, name, "zip", "application/zip", bytes) }
                            onMessage(if (FileStorage.share(ctx, File(saved.path), "application/zip")) "ZIP saved to ${saved.publicPath}" else "ZIP saved to ${saved.publicPath}, but no app could receive it.")
                        } catch (e: Exception) { onMessage("Could not create the ZIP: ${e.message ?: "unknown error"}") }
                    }
                }) { Text("ZIP", color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.sub) } })
}

// ── Working page: Claude-style thinking ───────────────────────────────────────

@Composable
private fun PulseMark(size: Int = 44) {
    val c = LocalIos.current
    val t = rememberInfiniteTransition(label = "pulse")
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "rot")
    val sc by t.animateFloat(0.88f, 1.12f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sc")
    Icon(Icons.Filled.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(size.dp).rotate(rot).graphicsLayer { scaleX = sc; scaleY = sc })
}

@Composable
internal fun ThinkingSection(text: String, running: Boolean) {
    val c = LocalIos.current
    var open by remember { mutableStateOf(true) }
    val rot by animateFloatAsState(if (open) 90f else 0f, spring(0.7f), label = "think_rot")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.card)) {
        Row(Modifier.fillMaxWidth().pressScale { open = !open }.padding(horizontal = 14.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ChevronRight, null, tint = c.sub, modifier = Modifier.size(18.dp).rotate(rot))
            Spacer(Modifier.width(6.dp))
            Text(if (running) "Thinking\u2026" else "Thought process", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.sub, modifier = Modifier.weight(1f))
            if (running) CircularProgressIndicator(Modifier.size(14.dp), color = c.accent, strokeWidth = 1.5.dp)
        }
        AnimatedVisibility(open, enter = expandVertically(spring(0.8f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            val sc = rememberScrollState()
            LaunchedEffect(text.length) { if (running) sc.scrollTo(sc.maxValue) }
            Text(text.ifBlank { "\u2026" }, Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(sc).padding(start = 14.dp, end = 14.dp, bottom = 12.dp),
                fontSize = 13.sp, color = c.sub, lineHeight = 18.sp)
        }
    }
}

@Composable
internal fun WorkingPage(session: BuildSession, onStop: () -> Unit) {
    val c = LocalIos.current
    val stages = listOf("Analyzing request", "Researching", "Drafting plan")
    val cur = stages.indexOf(session.status).coerceAtLeast(0)
    var secs by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (isActive) { delay(1000); secs++ } }
    Column(Modifier.fillMaxSize().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Working on it", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
            Text("${secs}s", fontSize = 13.sp, color = c.sub)
            Spacer(Modifier.width(10.dp))
            PillButton("Stop", c.red, onClick = onStop)
        }
        Spacer(Modifier.height(22.dp))
        PulseMark(52)
        Spacer(Modifier.height(10.dp))
        AnimatedContent(session.status, transitionSpec = { (slideInVertically { it / 2 } + fadeIn(tween(240))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(160))) }, label = "status") { st ->
            Text(st + "\u2026", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = c.text)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            stages.forEachIndexed { i, label ->
                val done = i < cur; val now = i == cur
                val col by animateColorAsState(if (done) c.green else if (now) c.accent else c.sub.copy(alpha = 0.5f), tween(300), label = "stg$i")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(20.dp).background(col.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
                        if (done) Icon(Icons.Filled.Check, null, tint = col, modifier = Modifier.size(13.dp))
                        else if (now) CircularProgressIndicator(Modifier.size(12.dp), color = col, strokeWidth = 1.5.dp)
                        else Box(Modifier.size(6.dp).background(col, CircleShape))
                    }
                    Spacer(Modifier.width(5.dp))
                    Text(label, fontSize = 12.sp, color = col, fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal)
                }
                if (i < stages.lastIndex) Box(Modifier.padding(horizontal = 6.dp).width(14.dp).height(1.dp).background(c.line))
            }
        }
        Spacer(Modifier.height(18.dp))
        ThinkingSection(session.thinking, running = true)
    }
}

// ── Plan page: read, Edit, Approve ────────────────────────────────────────────

@Composable
internal fun PlanPage(session: BuildSession, onStop: () -> Unit) {
    val c = LocalIos.current
    var editing by remember { mutableStateOf(false) }
    var text by remember(session) { mutableStateOf(session.plan) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Implementation plan", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text, modifier = Modifier.weight(1f))
            PillButton("Cancel", c.sub, onClick = onStop)
        }
        Text(if (editing) "Change anything below, then tap Approve." else "Read the plan. Edit it if you want something different, then Approve to start building.", fontSize = 13.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            ThinkingSection(session.thinking, running = false)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card).padding(14.dp)) {
                AnimatedContent(editing, transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) }, label = "plan_edit") { ed ->
                    if (ed) BasicTextField(text, { text = it }, Modifier.fillMaxWidth().defaultMinSize(minHeight = 260.dp),
                        textStyle = TextStyle(color = c.text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp), cursorBrush = SolidColor(c.accent))
                    else RichMessage(text, false)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f).pressScale { editing = !editing }.clip(RoundedCornerShape(14.dp)).background(c.fill).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editing) Icons.Filled.Check else Icons.Filled.Edit, null, tint = c.text, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(if (editing) "Done" else "Edit", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                }
            }
            Box(Modifier.weight(1f).pressScale { if (!session.approval.isCompleted) session.approval.complete(text) }.clip(RoundedCornerShape(14.dp)).background(c.accent).padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text("Approve", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                }
            }
        }
    }
}

// ── Build page: files appear and are typed live ───────────────────────────────

@Composable
internal fun BuildPage(session: BuildSession, onStop: () -> Unit, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = LocalIos.current
    LaunchedEffect(session.phase) {
        if (session.phase == Phase.DONE) {
            while (!session.typingDone) delay(100)
            delay(1000)                                   // a calm moment on the finished build before the editor opens
            session.projectName?.let { onOpen(it) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        BuildLive(session, Modifier.weight(1f), onStop)
        AnimatedVisibility(session.phase == Phase.FAILED, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(Modifier.fillMaxWidth().padding(12.dp).clip(RoundedCornerShape(14.dp)).background(c.red.copy(alpha = 0.12f)).padding(14.dp)) {
                Text(session.error ?: "The build stopped.", color = c.red, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton("Back", c.sub, onClick = onBack)
                    session.projectName?.let { n -> PillButton("Open project", c.accent) { onOpen(n) } }
                }
            }
        }
    }
}

private class TreeRow(val key: String, val label: String, val depth: Int, val dir: Boolean, val file: LiveFile?)

private fun treeRows(files: List<LiveFile>): List<TreeRow> {
    val out = ArrayList<TreeRow>(); val seen = HashSet<String>()
    for (f in files.sortedBy { it.path.lowercase() }) {
        val parts = f.path.split('/')
        var acc = ""
        for ((i, part) in parts.withIndex()) {
            acc = if (acc.isEmpty()) part else "$acc/$part"
            if (i < parts.size - 1) { if (seen.add("d:$acc")) out.add(TreeRow("d:$acc", part, i, true, null)) }
            else out.add(TreeRow("f:$acc", part, i, false, f))
        }
    }
    return out
}

/** The live work area shared by the IDE build page and the Claude Code agent: status, task list, file tree, code being typed, terminal. */
@Composable
internal fun BuildLive(session: BuildSession, modifier: Modifier = Modifier, onStop: (() -> Unit)? = null) {
    val c = LocalIos.current
    LaunchedEffect(session) { while (isActive) { withFrameNanos { }; session.typeTick() } }
    Column(modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // status bar with an animated action label
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            val running = session.phase == Phase.BUILDING || session.phase == Phase.THINKING
            if (running) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
            else Icon(if (session.phase == Phase.DONE) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null, tint = if (session.phase == Phase.DONE) c.green else c.red, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                AnimatedContent(session.action, transitionSpec = { (slideInVertically { it / 2 } + fadeIn(tween(200))) togetherWith (slideOutVertically { -it / 2 } + fadeOut(tween(120))) }, label = "action") { a ->
                    Text(a, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                }
                Text(session.status, fontSize = 12.sp, color = c.sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onStop != null && running) PillButton("Stop", c.red, onClick = onStop)
        }
        // task / progress list
        if (session.tasks.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().heightIn(max = 130.dp).clip(RoundedCornerShape(14.dp)).background(c.card).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                session.tasks.forEach { TaskLine(it) }
            }
        }
        // file tree | code editor
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FileTreePanel(session, Modifier.weight(0.38f).fillMaxHeight())
            CodePanel(session, Modifier.weight(0.62f).fillMaxHeight())
        }
        TerminalPanel(session, Modifier.fillMaxWidth().height(130.dp))
    }
}

@Composable
private fun TaskDoneIcon(c: IosColors) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = scaleIn(spring(0.5f, Spring.StiffnessMedium)) + fadeIn()) {
        Box(Modifier.size(18.dp).background(c.green, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun TaskLine(t: TaskRow) {
    val c = LocalIos.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (t.state) {
                TaskState.RUNNING -> CircularProgressIndicator(Modifier.size(15.dp), color = c.accent, strokeWidth = 2.dp)
                TaskState.DONE -> TaskDoneIcon(c)
                TaskState.FAILED -> Icon(Icons.Filled.Close, null, tint = c.red, modifier = Modifier.size(16.dp))
                TaskState.PENDING -> Box(Modifier.size(12.dp).border(1.5.dp, c.sub.copy(alpha = 0.5f), CircleShape))
            }
        }
        Spacer(Modifier.width(8.dp))
        val col by animateColorAsState(when (t.state) { TaskState.RUNNING -> c.text; TaskState.DONE -> c.sub; TaskState.FAILED -> c.red; else -> c.sub.copy(alpha = 0.7f) }, tween(250), label = "task_c")
        Text(t.title, fontSize = 13.sp, color = col, fontWeight = if (t.state == TaskState.RUNNING) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2)
    }
}

@Composable
private fun FileTreePanel(session: BuildSession, modifier: Modifier) {
    val c = LocalIos.current
    val rows = treeRows(session.files.toList())
    val state = rememberLazyListState()
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(c.card)) {
        Text("FILES", Modifier.padding(start = 10.dp, top = 8.dp, bottom = 4.dp), fontSize = 11.sp, color = c.sub, letterSpacing = 0.5.sp)
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(rows, key = { it.key }) { r ->
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) { shown = true }
                AnimatedVisibility(shown, enter = fadeIn(tween(260)) + slideInHorizontally(spring(0.8f)) { -it / 3 }) {
                    val f = r.file
                    val active = f != null && session.active === f
                    val bg by animateColorAsState(if (active) c.accent.copy(alpha = 0.16f) else Color.Transparent, tween(200), label = "tree_bg")
                    Row(Modifier.fillMaxWidth().background(bg).padding(start = (8 + r.depth * 12).dp, end = 6.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (r.dir) Icons.Filled.Folder else Icons.Filled.Description, null, tint = if (r.dir) c.orange else c.sub, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(r.label, Modifier.weight(1f), fontSize = 12.sp, color = if (active) c.accent else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (f != null) {
                            if (f.done) Icon(Icons.Filled.Check, null, tint = c.green, modifier = Modifier.size(12.dp))
                            else CircularProgressIndicator(Modifier.size(10.dp), color = c.accent, strokeWidth = 1.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodePanel(session: BuildSession, modifier: Modifier) {
    val c = LocalIos.current
    val f = session.active
    val bg = if (c.dark) Color(0xFF0D0E12) else Color(0xFFF6F6F8)
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f?.path ?: "editor", Modifier.weight(1f), fontSize = 11.sp, color = c.sub, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (f != null && !f.done) Text("typing", fontSize = 10.sp, color = c.accent)
        }
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().padding(horizontal = 8.dp, vertical = 4.dp), contentAlignment = Alignment.BottomStart) {
            if (f == null) {
                Text("Waiting for the first file\u2026", color = c.sub, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
            } else {
                val shown = f.text.take(f.shown)
                val lines = shown.split('\n')
                val tail = lines.takeLast(40)
                val startNo = lines.size - tail.size + 1
                val code = tail.joinToString("\n")
                val lang = f.path.substringAfterLast('.', "")
                val hl = remember(f.path, f.shown / 6, c.dark) { highlightForEditor(code, lang, c.dark) }
                val blink by rememberInfiniteTransition(label = "cursor").animateFloat(0f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "blink")
                val styled = buildAnnotatedString { append(hl); if (!f.done && blink > 0.5f) append("\u258C") }
                Row {
                    Text((startNo until startNo + tail.size).joinToString("\n"), color = c.sub.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.width(28.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(styled, color = c.text, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 16.sp, softWrap = false, maxLines = tail.size + 1)
                }
            }
        }
    }
}

@Composable
private fun TerminalPanel(session: BuildSession, modifier: Modifier) {
    val c = LocalIos.current
    val state = rememberLazyListState()
    val n = session.terminal.size
    LaunchedEffect(n) { if (n > 0) state.animateScrollToItem(n - 1) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF0B0B0D))) {
        Text("TERMINAL", Modifier.padding(start = 10.dp, top = 6.dp, bottom = 2.dp), fontSize = 10.sp, color = Color(0xFF7A7A85), letterSpacing = 0.5.sp)
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp), state = state) {
            items(session.terminal.toList().withIndex().toList(), key = { it.index }) { (_, line) ->
                Text(line, fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 15.sp,
                    color = when { line.startsWith("$") -> Color(0xFF7DD3FC); line.startsWith("!") -> Color(0xFFFF6B6B); else -> Color(0xFFD4D4D8) })
            }
        }
    }
}
