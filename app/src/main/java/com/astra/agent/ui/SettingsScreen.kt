package com.astra.agent.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.llm.Providers

@Composable
fun SettingsScreen(vm: AstraViewModel) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val sliderColors = SliderDefaults.colors(
        thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.fill
    )

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 40.dp)) {
        Text(
            "Settings", Modifier.padding(start = 20.dp, top = 8.dp),
            fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp
        )

        // ── Storage & memory ──────────────────────────────────────────────────
        SectionHeader("Storage & memory")
        Group { StorageCard(vm) }

        // ── Voice ─────────────────────────────────────────────────────────────
        val ttsOn by vm.ttsOn.collectAsState()
        SectionHeader("Voice")
        Group { ToggleRow("Speak replies aloud", ttsOn) { vm.setTts(it) } }
        PersonalSection(vm)
        Text("Voice is off until you turn it on here (or with the speaker button in chat).",
            Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp, color = c.sub)

        // ── Fast Mode ─────────────────────────────────────────────────────────
        SectionHeader("Fast Mode \u26a1")
        Group {
            // Toggle row
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Fast Mode", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (s.fastMode) "Using ${Providers.byId(s.provider).name} cloud AI \u26a1"
                        else "Using on-device AI (slower)",
                        fontSize = 13.sp,
                        color = if (s.fastMode) c.green else c.sub
                    )
                }
                Switch(
                    checked = s.fastMode,
                    onCheckedChange = { vm.updateSettings(s.copy(fastMode = it)) },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = c.green, checkedThumbColor = Color.White,
                        checkedBorderColor = Color.Transparent,
                        uncheckedTrackColor = c.fill, uncheckedThumbColor = Color.White,
                        uncheckedBorderColor = Color.Transparent
                    )
                )
            }

            Hairline()

            // Cloud provider: OpenRouter, Groq, Gemini, Cerebras, Mistral ... or your own server
            var showProvider by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().pressScale { showProvider = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Cloud provider", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Text(
                        Providers.byId(s.provider).name + (if (s.openRouterKey.isNotBlank()) " \u2022 " + s.openRouterModel.substringAfterLast('/').take(32) else " \u2022 not connected"),
                        fontSize = 13.sp, color = if (s.openRouterKey.isNotBlank()) c.green else c.sub, maxLines = 1
                    )
                }
                Text("Connect \u203a", color = c.accent, fontSize = 16.sp)
            }
            if (showProvider) ProviderDialog(vm) { showProvider = false }
            Hairline()

            if (s.provider == Providers.OPENROUTER) {
            // OpenRouter API Key
            var showKey by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.VpnKey, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("OpenRouter API Key", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    // Show/hide toggle
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(c.fill)
                            .pressScale { showKey = !showKey }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(if (showKey) "Hide" else "Show", fontSize = 12.sp, color = c.accent)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(c.fill)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    BasicTextField(
                        value = s.openRouterKey,
                        onValueChange = { vm.updateSettings(s.copy(openRouterKey = it.trim())) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 14.sp),
                        cursorBrush = SolidColor(c.accent),
                        visualTransformation = if (showKey) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        decorationBox = { inner ->
                            if (s.openRouterKey.isEmpty()) {
                                Text("sk-or-v1-...", color = c.sub, fontSize = 14.sp)
                            }
                            inner()
                        }
                    )
                }
                Spacer(Modifier.height(6.dp))

                // Key status indicator
                when {
                    s.openRouterKey.isBlank() ->
                        Text("No key set \u2014 get one free at openrouter.ai", fontSize = 12.sp, color = c.sub)
                    s.openRouterKey.startsWith("sk-") ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = c.green, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("API key saved \u2022 Key is stored locally on device", fontSize = 12.sp, color = c.green)
                        }
                    else ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = c.orange, modifier = Modifier.size(13.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Key format looks wrong (should start with sk-)", fontSize = 12.sp, color = c.orange)
                        }
                }
            }

            Hairline()

            // Model picker (live list of FREE models from OpenRouter)
            val freeModels by vm.freeModels.collectAsState()
            val status by vm.modelsStatus.collectAsState()
            var showAll by remember { mutableStateOf(false) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Free cloud model", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    PillButton("Refresh") { vm.refreshFreeModels() }
                }
                Text(status, fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                val shown = if (showAll) freeModels else freeModels.take(8)
                shown.forEach { m ->
                    val sel = s.openRouterModel == m.id
                    Row(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (sel) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                            .pressScale { vm.updateSettings(s.copy(openRouterModel = m.id)) }
                            .padding(horizontal = 8.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = sel,
                            onClick = { vm.updateSettings(s.copy(openRouterModel = m.id)) },
                            colors = RadioButtonDefaults.colors(selectedColor = c.accent)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                m.name, fontSize = 14.sp, maxLines = 1,
                                color = if (sel) c.accent else c.text,
                                fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(m.id, fontSize = 11.sp, color = c.sub, maxLines = 1)
                        }
                    }
                }
                if (freeModels.size > 8) {
                    Box(Modifier.padding(top = 6.dp)) {
                        PillButton(if (showAll) "Show fewer" else "Show all ${freeModels.size}", c.sub) { showAll = !showAll }
                    }
                }
                Text(
                    "If a model is busy or unavailable, ZENIQ automatically retries with another free model.",
                    fontSize = 12.sp, color = c.sub, modifier = Modifier.padding(top = 8.dp)
                )
            }
            }
        }
        Text(
            "Fast Mode uses cloud AI (OpenRouter, Groq, Gemini and more) \u2014 instant responses, no GPU needed. Your key is stored only on your device.",
            Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp, color = c.sub
        )

        // ── Phone control ─────────────────────────────────────────────────────
        SectionHeader("Phone control")
        Group {
            Row(
                Modifier.fillMaxWidth().pressScale { ctx.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Accessibility service", Modifier.weight(1f), fontSize = 16.sp)
                Text("Open \u203a", color = c.accent, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        Group {
            Row(
                Modifier.fillMaxWidth().pressScale { ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Display over other apps", Modifier.weight(1f), fontSize = 16.sp)
                Text("Open \u203a", color = c.accent, fontSize = 16.sp)
            }
        }
        Text(
            "Needed for read screen, tap, type and swipe. On Android 13+: App info \u2192 \u22ee \u2192 Allow restricted settings.",
            Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp, color = c.sub
        )

        // ── Agent ─────────────────────────────────────────────────────────────
        SectionHeader("Agent")
        Group {
            Hairline()
            ToggleRow("Thinking mode (slower)", s.thinking) { vm.updateSettings(s.copy(thinking = it)) }
            Hairline()
            ToggleRow("Ask before calls, SMS, shell and file writes", s.askApproval) { vm.updateSettings(s.copy(askApproval = it)) }
        }
        Text("Thinking makes responses smarter but slower. Disable for faster answers.", Modifier.padding(horizontal = 32.dp, vertical = 6.dp), fontSize = 12.sp, color = c.sub)

        // ── Generation ────────────────────────────────────────────────────────
        SectionHeader("Generation")
        Group {
            SliderRow("Temperature", "%.2f".format(s.temperature)) {
                Slider(s.temperature, { v -> vm.updateSettings(s.copy(temperature = v)) }, valueRange = 0f..1.5f, colors = sliderColors)
            }
            Hairline()
            SliderRow("Max steps per task", "${s.maxSteps}") {
                Slider(s.maxSteps.toFloat(), { v -> vm.updateSettings(s.copy(maxSteps = v.toInt())) }, valueRange = 1f..20f, colors = sliderColors)
            }
            Hairline()
            SliderRow("Max tokens per reply", "${s.maxTokens}") {
                Slider(s.maxTokens.toFloat(), { v -> vm.updateSettings(s.copy(maxTokens = (v / 128).toInt() * 128 + 128)) }, valueRange = 128f..2048f, colors = sliderColors)
            }
        }

        // ── Performance ───────────────────────────────────────────────────────
        SectionHeader("Performance (reload model to apply)")
        Group {
            Column(Modifier.padding(16.dp)) {
                Text("Context size", fontSize = 16.sp)
                Spacer(Modifier.height(10.dp))
                Segmented(listOf(2048, 4096, 8192).map { "$it" }, listOf(2048, 4096, 8192).indexOf(s.nCtx)) {
                    vm.updateSettings(s.copy(nCtx = listOf(2048, 4096, 8192)[it]))
                }
            }
            Hairline()
            SliderRow("CPU threads", "${s.threads}") {
                Slider(s.threads.toFloat(), { v -> vm.updateSettings(s.copy(threads = v.toInt().coerceAtLeast(1))) }, valueRange = 1f..8f, steps = 6, colors = sliderColors)
            }
        }

        // ── System prompt ─────────────────────────────────────────────────────
        SectionHeader("System prompt")
        Group {
            Box(Modifier.padding(16.dp)) {
                BasicTextField(
                    s.systemPrompt,
                    { vm.updateSettings(s.copy(systemPrompt = it)) },
                    Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = c.text, fontSize = 15.sp, lineHeight = 21.sp),
                    cursorBrush = SolidColor(c.accent),
                    minLines = 3
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val c = LocalIos.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        Switch(value, onChange, colors = SwitchDefaults.colors(
            checkedTrackColor = c.green, checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = c.fill, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }
}

@Composable
private fun SliderRow(label: String, value: String, slider: @Composable () -> Unit) {
    val c = LocalIos.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row { Text(label, Modifier.weight(1f), fontSize = 16.sp); Text(value, color = c.sub, fontSize = 16.sp) }
        slider()
    }
}

@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val c = LocalIos.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(2.dp)) {
        options.forEachIndexed { i, o ->
            val bg by animateColorAsState(if (i == selected) (if (c.dark) Color(0xFF636366) else Color.White) else Color.Transparent, tween(220), label = "seg")
            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(bg).pressScale { onSelect(i) }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                Text(o, fontSize = 14.sp, fontWeight = if (i == selected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
}
