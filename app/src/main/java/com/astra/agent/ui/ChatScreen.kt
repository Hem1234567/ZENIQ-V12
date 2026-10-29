package com.astra.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import java.io.File
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material.icons.rounded.Add
import com.astra.agent.code.AppStorage
import com.astra.agent.code.FileStorage
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.device.AstraAccessibilityService
import com.astra.agent.llm.DlState
import com.astra.agent.llm.SttState
import kotlinx.coroutines.delay
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt

@Composable
fun ChatScreen(vm: AstraViewModel, onOpenModels: () -> Unit, onMenu: () -> Unit = {}) {
    val c       = LocalIos.current
    val ctx     = LocalContext.current
    val haptic  = LocalHapticFeedback.current
    val list    by vm.items.collectAsState()
    val running by vm.running.collectAsState()
    val model   by vm.loadedModel.collectAsState()
    val loading by vm.loadingModel.collectAsState()
    val approval by vm.approval.collectAsState()
    val liveStatus by vm.liveStatus.collectAsState()
    val sttState   by vm.voice.sttState.collectAsState()
    val partialTxt by vm.voice.partialText.collectAsState()
    val ttsOn      by vm.ttsOn.collectAsState()
    val isSpeaking by vm.voice.isSpeaking.collectAsState()
    val attached   by vm.attached.collectAsState()
    val attachedImgs by vm.attachedImages.collectAsState()
    val readingDoc by vm.readingDoc.collectAsState()
    val settings   by vm.settings.collectAsState()
    var input   by remember { mutableStateOf("") }
    var accOn   by remember { mutableStateOf(true) }
    val state   = rememberLazyListState()
    val isListening = sttState == SttState.Listening || sttState == SttState.Processing

    // File picker (PDF / Word / Excel / PowerPoint / text)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) { null } ?: uri.lastPathSegment ?: "document"
        vm.loadDocument(uri, name)
    }

    // Photos picker
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) vm.loadImage(uri, "Photo")
    }
    // Camera (takes a picture and attaches it)
    var camUri by remember { mutableStateOf<Uri?>(null) }
    val camLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok: Boolean ->
        val u = camUri
        if (ok && u != null) vm.loadImage(u, "Camera photo")
    }
    var attachMenu by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    // Suggestion chip -> prompt box animation: where the box is, where the chip was, and which chip is travelling.
    var rootPos by remember { mutableStateOf(Offset.Zero) }
    var inputRect by remember { mutableStateOf<Rect?>(null) }
    var flying by remember { mutableStateOf<FlyingChip?>(null) }

    LaunchedEffect(Unit)           { while (true) { accOn = AstraAccessibilityService.instance != null; delay(1500) } }
    val draft by vm.draft.collectAsState()
    LaunchedEffect(draft) { if (draft.isNotBlank()) { input = draft; vm.draft.value = "" } }
    var preview by remember { mutableStateOf<FileItem?>(null) }
    preview?.let { pf ->
        SitePreview(pf.path, pf.name, onClose = { preview = null }, onOpenExternal = {
            if (!FileStorage.open(ctx, File(pf.path), pf.mime)) vm.addError("No app on this phone can open ${pf.name}. Use Download or Share instead.")
        })
    }
    // Auto-scroll: follow the answer while it is being written, unless the user scrolled up to read something.
    var stick by remember { mutableStateOf(true) }
    val last = list.lastOrNull()
    val growth = when (last) {
        is AssistantItem -> last.text.length + last.thinking.length
        is ToolItem -> (last.result?.length ?: 0) + 1
        else -> 0
    }
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { i ->
            if (i is androidx.compose.foundation.interaction.DragInteraction.Start) stick = false
            else if (i is androidx.compose.foundation.interaction.DragInteraction.Stop ||
                     i is androidx.compose.foundation.interaction.DragInteraction.Cancel) stick = !state.canScrollForward
        }
    }
    LaunchedEffect(list.size) { if (list.lastOrNull() is UserItem) stick = true }      // a new question: follow the new answer again
    LaunchedEffect(list.size, growth) {
        if (stick && list.isNotEmpty()) {
            val visibleLast = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index
            if (visibleLast != list.lastIndex) state.scrollToItem(list.lastIndex)
            state.scrollBy(100_000f)                                                    // down to the very end of the newest text
        }
    }
    // Show partial STT text in input field
    LaunchedEffect(partialTxt)     { if (partialTxt.isNotBlank()) input = partialTxt }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootPos = it.positionInRoot() }) {
    Column(Modifier.fillMaxSize()) {

        // ── Header ───────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).pressScale { onMenu() }.background(c.fill, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Menu, contentDescription = "Menu", tint = c.text, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("ZENIQ", fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp)
                val dot by animateColorAsState(
                    if (settings.fastMode) c.green else if (loading) c.orange else if (model != null) c.green else c.sub, tween(400), label = "dot")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(dot, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    val cloud = settings.fastMode
                    Text(
                        when {
                            cloud -> "Fast \u26a1 " + settings.openRouterModel.substringAfterLast('/').removeSuffix(":free")
                            loading -> "Loading model…"
                            else -> model ?: "No model loaded"
                        },
                        fontSize = 13.sp, color = c.sub, maxLines = 1)
                }
            }
            // Fast mode toggle
            val fastColor by animateColorAsState(if (settings.fastMode) c.green else c.sub, tween(200), label = "fast")
            Row(Modifier.clip(CircleShape).background(fastColor.copy(alpha = 0.14f))
                .pressScale { vm.setFastMode(!settings.fastMode) }
                .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FlashOn, contentDescription = "Fast mode", tint = fastColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
                Text(if (settings.fastMode) "Fast" else "Local", fontSize = 12.sp, color = fastColor, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(6.dp))
            // TTS toggle
            val ttsColor by animateColorAsState(if (ttsOn) c.accent else c.sub, tween(200), label = "tts")
            Box(Modifier.size(34.dp).pressScale { vm.setTts(!ttsOn) }
                .background(ttsColor.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(if (isSpeaking) Icons.Filled.VolumeUp else if (ttsOn) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                    contentDescription = "TTS", tint = ttsColor, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(6.dp))
            // New chat
            Box(Modifier.size(34.dp).pressScale { vm.newChat() }.background(c.fill, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.EditNote, contentDescription = "New chat", tint = c.accent, modifier = Modifier.size(20.dp))
            }
        }

        // ── Accessibility warning ────────────────────────────────────────────
        AnimatedVisibility(!accOn, enter = expandVertically(spring(0.7f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
                .clip(RoundedCornerShape(14.dp)).background(c.orange.copy(alpha = 0.14f))
                .padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = c.orange, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Phone control is off", Modifier.weight(1f), fontSize = 14.sp, color = c.orange, fontWeight = FontWeight.Medium)
                PillButton("Enable", c.orange) {
                    ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        // ── Setup card ───────────────────────────────────────────────────────
        val installed by vm.installed.collectAsState()
        AnimatedVisibility(installed.isEmpty() && model == null && !loading && !settings.fastMode,
            enter = expandVertically(spring(0.75f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SetupCard(vm, onOpenModels)
        }

        // ── Live activity banner ─────────────────────────────────────────────
        AnimatedVisibility(running && liveStatus.isNotBlank(),
            enter = expandVertically(spring(0.75f)) + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            LiveActivityBanner(liveStatus)
        }

        // ── Message list ─────────────────────────────────────────────────────
        Box(Modifier.weight(1f)) {
            if (list.isEmpty()) EmptyState(hidden = flying?.text) { text, rect ->
                if (flying == null && !running) { focus.clearFocus(); flying = FlyingChip(text, rect) }
            }
            LazyColumn(Modifier.fillMaxSize(), state = state,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(list, key = { it.id }) { item ->
                    val shouldAnimate = item is UserItem || item is InfoItem || item is DocItem || item is AppItem || item is FileItem ||
                        (item is ToolItem && item.result != null) ||
                        (item is AssistantItem && !item.streaming)
                    Box(if (shouldAnimate) Modifier.animateItem() else Modifier) {
                        Appear {
                            when (item) {
                                is UserItem      -> UserBubble(item)
                                is AssistantItem -> AssistantBlock(item)
                                is ToolItem      -> ToolCard(item)
                                is DocItem       -> DocChip(item)
                                is AppItem       -> AppCard(item,
                                    onDownload = { vm.downloadApp(item) },
                                    onShare = {
                                        if (!AppStorage.share(ctx, File(item.zipPath)))
                                            vm.addError("Couldn't share the ZIP. It may have been deleted.")
                                    })
                                is FileItem      -> FileCard(item,
                                    onOpen = {
                                        if (!FileStorage.open(ctx, File(item.path), item.mime))
                                            vm.addError("No app on this phone can open ${item.name}. Use Download or Share instead.")
                                    },
                                    onDownload = { vm.downloadFile(item) },
                                    onShare = {
                                        if (!FileStorage.share(ctx, File(item.path), item.mime))
                                            vm.addError("Couldn't share the file. It may have been deleted.")
                                    },
                                    onPreview = { preview = item })
                                is InfoItem      -> Text(item.text,
                                    Modifier.padding(8.dp).fillMaxWidth(), fontSize = 13.sp,
                                    color = if (item.error) c.red else c.sub, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
                // under the last answer: run it again, or take the question back into the box and edit it
                val tail = list.lastOrNull()
                if (!running && tail is AssistantItem && !tail.streaming && tail.text.isNotBlank()) {
                    item(key = "message-controls") {
                        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PillButton("\u21BB Regenerate") { vm.regenerate() }
                            PillButton("\u270E Edit my message") { val t = vm.lastUserText(); vm.dropLastTurn(); if (t != null) input = t }
                        }
                    }
                }
            }
        }

        // ── STT partial overlay ──────────────────────────────────────────────
        AnimatedVisibility(isListening, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val inf = rememberInfiniteTransition(label = "mic_pulse")
                val pa by inf.animateFloat(0.4f, 1f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "mp")
                Box(Modifier.size(8.dp).background(c.red.copy(alpha = pa), CircleShape))
                Spacer(Modifier.width(10.dp))
                Text(partialTxt.ifBlank { if (sttState == SttState.Processing) "Processing…" else "Listening…" },
                    fontSize = 14.sp, color = c.accent, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.stopVoiceInput() }) { Text("Cancel", color = c.sub) }
            }
        }

        // ── Attached files (shown above the prompt, NOT in chat until sent) ──
        AnimatedVisibility(attached.isNotEmpty() || attachedImgs.isNotEmpty() || readingDoc != null,
            enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                attached.forEach { doc ->
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.card)
                        .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(
                            (if (doc.readable) c.accent else c.orange).copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
                            contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.PictureAsPdf, null, tint = if (doc.readable) c.accent else c.orange,
                                modifier = Modifier.size(19.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.widthIn(max = 170.dp)) {
                            Text(doc.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(if (doc.readable) "${doc.pageCount} pages \u00b7 ${doc.sizeKb} KB" else "No readable text",
                                fontSize = 11.sp, color = c.sub)
                        }
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Filled.Close, contentDescription = "Remove",
                            tint = c.sub, modifier = Modifier.size(22.dp).padding(3.dp).pressScale { vm.removeAttached(doc.name) })
                    }
                }
                attachedImgs.forEach { img ->
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(c.card)) {
                        Image(bitmap = img.thumb.asImageBitmap(), contentDescription = img.name,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .pressScale { vm.removeImage(img.id) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(12.dp))
                        }
                    }
                }
                readingDoc?.let { n ->
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.card).padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Reading ${n.take(24)}\u2026", fontSize = 13.sp, color = c.sub)
                    }
                }
            }
        }

        // ── Input bar ────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
            // Attach button -> iOS action sheet: Camera / Photos / Files
            Box(Modifier.size(38.dp).pressScale { focus.clearFocus(); attachMenu = true }
                .background(c.fill, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Add, contentDescription = "Attach", tint = c.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(6.dp))
            // Text field
            Box(Modifier.weight(1f).onGloballyPositioned { inputRect = it.boundsInRoot() }
                .clip(RoundedCornerShape(22.dp)).background(c.fill).animateContentSize()) {
                BasicTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = c.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(c.accent), maxLines = 5,
                    decorationBox = { inner ->
                        Box(Modifier.padding(horizontal = 16.dp, vertical = 9.dp)) {
                            if (input.isEmpty()) Text(if (attached.isNotEmpty() || attachedImgs.isNotEmpty()) "Ask about the attachment\u2026" else "Ask ZENIQ to do something", color = c.sub, fontSize = 17.sp)
                            inner()
                        }
                    })
            }
            Spacer(Modifier.width(6.dp))
            // Mic / Stop speaking / Send button
            val canSend = input.isNotBlank() || attached.isNotEmpty() || attachedImgs.isNotEmpty()
            AnimatedContent(
                targetState = when { running -> "stop"; canSend -> "send"; else -> "mic" },
                transitionSpec = { (scaleIn(spring(0.6f)) + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                label = "action_btn"
            ) { state ->
                when (state) {
                    "stop" -> Box(Modifier.size(38.dp).pressScale {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress); vm.stop()
                    }.background(c.red, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.Stop, contentDescription = "Stop", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    "send" -> Box(Modifier.size(38.dp).pressScale {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        vm.send(input.trim()); input = ""
                    }.background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.ArrowUpward, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    else -> {
                        // Mic button
                        val micColor by animateColorAsState(
                            if (isListening) c.red else c.accent.copy(alpha = 0.15f), tween(200), label = "mic_c")
                        Box(Modifier.size(38.dp).pressScale {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (isListening) vm.stopVoiceInput() else vm.startVoiceInput()
                        }.background(micColor, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                                contentDescription = "Voice", tint = if (isListening) Color.White else c.accent,
                                modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }

    // The tapped suggestion glides into the prompt box, stays there for a moment, then is sent.
    flying?.let { f ->
        FlyingChipOverlay(f, inputRect, rootPos,
            onArrive = { input = f.text },
            onDone = { flying = null; input = ""; vm.send(f.text) })
    }

    // ── iOS-style attach sheet (Camera / Photos / Files) ─────────────────────
    AttachSheet(
        visible = attachMenu,
        onDismiss = { attachMenu = false },
        onCamera = {
            attachMenu = false
            try {
                val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                val f = File.createTempFile("IMG_", ".jpg", dir)
                val u = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", f)
                camUri = u
                camLauncher.launch(u)
            } catch (e: Exception) {
                vm.addError("Couldn't open the camera: ${e.message}")
            }
        },
        onPhotos = {
            attachMenu = false
            photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        },
        onFiles = {
            attachMenu = false
            filePicker.launch(arrayOf("*/*"))   // PDF, Word, Excel, PowerPoint, text: unsupported types get a clear error
        }
    )
    }

    // ── Approval dialog ──────────────────────────────────────────────────────
    approval?.let { a ->
        AlertDialog(containerColor = c.card, shape = RoundedCornerShape(20.dp),
            onDismissRequest = { a.result.complete(false); vm.approval.value = null },
            icon = { Icon(Icons.Filled.Shield, contentDescription = null, tint = c.orange, modifier = Modifier.size(28.dp)) },
            title = { Text("Allow this action?", fontWeight = FontWeight.SemiBold, color = c.text) },
            text = { Text("${a.name}\n${a.args}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.sub) },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        vm.updateSettings(vm.settings.value.copy(askApproval = false))
                        a.result.complete(true); vm.approval.value = null
                    }) { Text("Always allow", color = c.sub) }
                    TextButton(onClick = { a.result.complete(true); vm.approval.value = null }) { Text("Allow", color = c.accent, fontWeight = FontWeight.SemiBold) }
                }
            },
            dismissButton = { TextButton(onClick = { a.result.complete(false); vm.approval.value = null }) { Text("Deny", color = c.red) } }
        )
    }
}

// ── Live Activity Banner ──────────────────────────────────────────────────────
@Composable
private fun LiveActivityBanner(status: String) {
    val c = LocalIos.current
    val inf = rememberInfiniteTransition(label = "pulse")
    val alpha by inf.animateFloat(0.4f, 1f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pa")
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()
        .clip(RoundedCornerShape(14.dp)).background(c.accent.copy(alpha = 0.10f))
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(c.accent.copy(alpha = alpha), CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(status, fontSize = 13.sp, color = c.accent, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f), maxLines = 1)
        CircularProgressIndicator(modifier = Modifier.size(14.dp), color = c.accent, strokeWidth = 1.5.dp)
    }
}

// ── Empty state ───────────────────────────────────────────────────────────────
private class FlyingChip(val text: String, val from: Rect)

private val SUGGESTIONS = listOf(
    "Open browser and search for today's news",
    "Open camera and take a photo",
    "What time is it?",
    "Fetch example.com and summarise it",
    "Write a Python function to check prime numbers",
    "Build a small calculator app"
)

@Composable
private fun EmptyState(hidden: String?, onPick: (String, Rect) -> Unit) {
    val c = LocalIos.current
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Appear {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = c.accent, modifier = Modifier.size(44.dp))
                Spacer(Modifier.height(8.dp))
                Text("What can I do?", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Text("Runs fully on your phone", fontSize = 14.sp, color = c.sub)
                Spacer(Modifier.height(20.dp))
                SUGGESTIONS.forEach { s ->
                    SuggestionButton(s, hidden = hidden == s, onPick = onPick)
                }
            }
        }
    }
}

/** One tappable suggestion. While it is travelling to the prompt box the button itself fades out (the overlay shows the text). */
@Composable
private fun SuggestionButton(text: String, hidden: Boolean, onPick: (String, Rect) -> Unit) {
    val c = LocalIos.current
    var rect by remember { mutableStateOf<Rect?>(null) }
    val a by animateFloatAsState(if (hidden) 0f else 1f, tween(160), label = "chip_alpha")
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.padding(vertical = 4.dp).alpha(a)
        .onGloballyPositioned { rect = it.boundsInRoot() }
        .semantics { role = Role.Button }
        .pressScale { rect?.let { r -> onPick(text, r) } }
        .clip(shape).background(c.fill).border(0.5.dp, c.line, shape)
        .padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Filled.ArrowForward, contentDescription = null, tint = c.accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun FlyingChipOverlay(f: FlyingChip, target: Rect?, origin: Offset, onArrive: () -> Unit, onDone: () -> Unit) {
    val c = LocalIos.current
    val density = LocalDensity.current
    val p = remember(f) { Animatable(0f) }
    var arrived by remember(f) { mutableStateOf(false) }
    LaunchedEffect(f) {
        p.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        arrived = true
        onArrive()
        delay(420)          // let the text rest in the prompt box so the user sees it before it is sent
        onDone()
    }
    val to = target ?: f.from
    val t = p.value
    fun lerp(a: Float, b: Float) = a + (b - a) * t
    val x = lerp(f.from.left, to.left) - origin.x
    val y = lerp(f.from.top, to.top) - origin.y
    val w = lerp(f.from.width, to.width)
    val h = lerp(f.from.height, to.height)
    Box(Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
        .alpha(if (arrived) 0f else 1f)
        .clip(RoundedCornerShape(lerp(16f, 22f).dp))
        .background(c.fill),
        contentAlignment = Alignment.CenterStart) {
        Text(f.text, Modifier.padding(horizontal = 16.dp), fontSize = lerp(15f, 17f).sp, color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Appear(content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(220)) +
        slideInVertically(spring(0.75f, Spring.StiffnessMediumLow)) { it / 5 } +
        scaleIn(spring(0.75f, Spring.StiffnessMediumLow), 0.94f)) { content() }
}

@Composable
private fun UserBubble(item: UserItem) {
    val c = LocalIos.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item.attachments.forEach { a ->
            if (a.isImage) {
                if (a.thumb != null) {
                    Image(bitmap = a.thumb.asImageBitmap(), contentDescription = a.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(120.dp).clip(RoundedCornerShape(14.dp)))
                } else {
                    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(c.card).padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Image, null, tint = c.accent, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Image", fontSize = 13.sp)
                    }
                }
                return@forEach
            }
            Row(Modifier.widthIn(max = 280.dp).clip(RoundedCornerShape(14.dp)).background(c.card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(32.dp).background(c.accent.copy(alpha = 0.15f), RoundedCornerShape(9.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PictureAsPdf, null, tint = c.accent, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(a.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("${a.pages} pages \u00b7 ${a.sizeKb} KB", fontSize = 11.sp, color = c.sub)
                }
            }
        }
        if (item.text.isNotBlank()) {
            RichMessage(item.text, true)
            CopyButton(item.text, c.sub, showLabel = false)
        }
    }
}

@Composable
private fun AssistantBlock(a: AssistantItem) {
    val c = LocalIos.current
    var open by remember { mutableStateOf(false) }
    val displayText = a.text.ifBlank { if (!a.streaming && a.thinking.isNotBlank()) a.thinking else "" }
    Column {
        if (a.thinking.isNotBlank()) {
            val rot by animateFloatAsState(if (open) 90f else 0f, spring(0.7f), label = "rot")
            Row(Modifier.padding(vertical = 3.dp).pressScale { open = !open }
                .clip(CircleShape).background(c.card).padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Psychology, contentDescription = null, tint = c.sub, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Thinking", fontSize = 13.sp, color = c.sub)
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = c.sub,
                    modifier = Modifier.size(16.dp).rotate(rot))
            }
            if (open) Text(a.thinking, Modifier.padding(start = 10.dp, bottom = 6.dp),
                fontSize = 13.sp, color = c.sub, lineHeight = 18.sp)
        }
        if (displayText.isNotBlank()) {
            RichMessage(displayText, false)
            if (!a.streaming) CopyButton(displayText, c.sub)
        } else if (a.streaming && a.thinking.isBlank()) TypingDots()
    }
}

@Composable
private fun TypingDots() {
    val c = LocalIos.current
    val t = rememberInfiniteTransition(label = "dots")
    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(c.fill).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val alpha by t.animateFloat(0.25f, 1f,
                infiniteRepeatable(tween(550, delayMillis = i * 160, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).background(c.sub.copy(alpha = alpha), CircleShape))
        }
    }
}

// ── Doc chip shown in chat feed ───────────────────────────────────────────────
@Composable
private fun DocChip(d: DocItem) {
    val c = LocalIos.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(c.card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(c.accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.PictureAsPdf, contentDescription = null, tint = c.accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(d.docName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${d.pageCount} pages · ${d.sizeKb} KB — ready to answer questions", fontSize = 12.sp, color = c.sub)
        }
        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = c.green, modifier = Modifier.size(18.dp))
    }
}

private enum class ToolStatus { Run, Wait, Ok, Fail }

@Composable
private fun ToolCard(t: ToolItem) {
    val c = LocalIos.current
    var open by remember { mutableStateOf(false) }
    val status = when { t.waiting -> ToolStatus.Wait; t.result == null -> ToolStatus.Run; t.ok -> ToolStatus.Ok; else -> ToolStatus.Fail }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.card)
        .pressScale { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(status,
                transitionSpec = { (scaleIn(spring(0.5f)) + fadeIn()) togetherWith fadeOut(tween(100)) }, label = "st") { s ->
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    when (s) {
                        ToolStatus.Run  -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                        ToolStatus.Wait -> Box(Modifier.size(22.dp).background(c.orange, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.NotificationImportant, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        ToolStatus.Ok   -> Box(Modifier.size(22.dp).background(c.green, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                        ToolStatus.Fail -> Box(Modifier.size(22.dp).background(c.red, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Close, null, tint = Color.White, modifier = Modifier.size(13.dp)) }
                    }
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                val (toolIcon, toolLabel) = toolMeta(t.name)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(toolIcon, null, tint = c.sub, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(toolLabel, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(t.args, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = c.sub, maxLines = if (open) 10 else 1)
            }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = c.sub, modifier = Modifier.size(18.dp))
        }
        if (t.result != null) {
            Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(10.dp)) {
                Text(t.result, fontFamily = FontFamily.Monospace, fontSize = 12.sp, maxLines = if (open) 60 else 3)
            }
        }
    }
}

@Composable
private fun toolMeta(name: String): Pair<androidx.compose.ui.graphics.vector.ImageVector, String> = when (name) {
    "open_app"       -> Icons.Filled.OpenInNew to "Open App"
    "open_url"       -> Icons.Filled.Language to "Open URL"
    "read_screen"    -> Icons.Filled.Visibility to "Read Screen"
    "tap_text"       -> Icons.Filled.TouchApp to "Tap"
    "tap_xy"         -> Icons.Filled.CenterFocusStrong to "Tap XY"
    "type_text"      -> Icons.Filled.Keyboard to "Type Text"
    "swipe"          -> Icons.Filled.SwipeUp to "Swipe"
    "press"          -> Icons.Filled.PhoneAndroid to "Press Key"
    "browser_open"   -> Icons.Filled.Language to "Browser Open"
    "browser_text"   -> Icons.Filled.Article to "Browser Text"
    "browser_click"  -> Icons.Filled.TouchApp to "Browser Click"
    "browser_type"   -> Icons.Filled.Edit to "Browser Type"
    "browser_submit" -> Icons.Filled.Send to "Browser Submit"
    "browser_eval"   -> Icons.Filled.Code to "Browser Eval"
    "browser_links"  -> Icons.Filled.Link to "Browser Links"
    "run_shell"      -> Icons.Filled.Terminal to "Shell"
    "http_get"       -> Icons.Filled.CloudDownload to "HTTP Get"
    "read_file"      -> Icons.Filled.FolderOpen to "Read File"
    "write_file"     -> Icons.Filled.Save to "Write File"
    "list_files"     -> Icons.Filled.Folder to "List Files"
    "get_time"       -> Icons.Filled.Schedule to "Get Time"
    "take_photo"     -> Icons.Filled.PhotoCamera to "Take Photo"
    "web_search"     -> Icons.Filled.Search to "Web Search"
    "youtube_search" -> Icons.Filled.PlayCircle to "YouTube"
    "maps_search"    -> Icons.Filled.Map to "Maps"
    "call_number"    -> Icons.Filled.Call to "Call"
    "send_sms"       -> Icons.Filled.Sms to "Send SMS"
    "set_alarm"      -> Icons.Filled.Alarm to "Set Alarm"
    "set_timer"      -> Icons.Filled.Timer to "Set Timer"
    "wait"           -> Icons.Filled.Timelapse to "Wait"
    else             -> Icons.Filled.Circle to name
}

@Composable
private fun SetupCard(vm: AstraViewModel, onOpenModels: () -> Unit) {
    val c   = LocalIos.current
    val rec = remember { vm.models.recommended() }
    val dl  by vm.models.downloads.collectAsState()
    val st  = dl[rec.url]
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
        .clip(RoundedCornerShape(18.dp)).background(c.card).animateContentSize(spring(0.8f)).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Download, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Set up your AI", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(4.dp))
        Text("Your phone has %.1f GB of RAM. Best match: ${rec.name} (${rec.sizeLabel}).".format(vm.models.ramGb),
            fontSize = 14.sp, color = c.sub, lineHeight = 20.sp)
        Spacer(Modifier.height(12.dp))
        if (st is DlState.Progress) {
            val target = if (st.total > 0) st.done.toFloat() / st.total else 0f
            val p by animateFloatAsState(target, tween(400, easing = LinearOutSlowInEasing), label = "setup")
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                color = c.accent, trackColor = c.fill)
            Text("Downloading… ${st.done / 1_000_000} of ${st.total / 1_000_000} MB",
                Modifier.padding(top = 6.dp), fontSize = 12.sp, color = c.sub)
            Spacer(Modifier.height(8.dp))
            PillButton("Pause", c.orange) { vm.cancelDownload(rec.url) }
        } else {
            if (st is DlState.Failed) Text("Failed: ${st.msg}", fontSize = 12.sp, color = c.red, modifier = Modifier.padding(bottom = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val part = vm.models.partialSize(rec.url)
                PillButton(if (part > 0) "Resume" else "Download") { vm.download(rec.url) }
                Spacer(Modifier.width(8.dp))
                PillButton("More models", c.sub) { onOpenModels() }
            }
        }
    }
}
