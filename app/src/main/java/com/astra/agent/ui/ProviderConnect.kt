package com.astra.agent.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.llm.OpenRouterClient
import com.astra.agent.llm.Provider
import com.astra.agent.llm.Providers
import kotlinx.coroutines.launch

/**
 * "Connect a cloud AI provider": pick OpenRouter, Groq, Gemini, Cerebras, Mistral ... or your own OpenAI-compatible server, paste the key,
 * load the model list, test, and connect. Fast Mode, the IDE and the agent then use that provider. Every provider keeps its own key.
 */
@Composable
fun ProviderDialog(vm: AstraViewModel, onDismiss: () -> Unit) {
    val c = LocalIos.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val s by vm.settings.collectAsState()
    var sel by remember { mutableStateOf(Providers.byId(s.provider)) }
    var key by remember { mutableStateOf(vm.savedKey(sel.id)) }
    var model by remember { mutableStateOf(vm.savedModel(sel.id).ifBlank { sel.defaultModel }) }
    var base by remember { mutableStateOf(vm.savedBase(sel.id).ifBlank { sel.base }) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var statusOk by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }

    fun pick(p: Provider) {
        sel = p
        key = vm.savedKey(p.id)
        model = vm.savedModel(p.id).ifBlank { p.defaultModel }
        base = vm.savedBase(p.id).ifBlank { p.base }
        models = emptyList()
        status = ""
    }
    val effectiveKey = if (key.isBlank() && sel.needsBase) "none" else key

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect a cloud AI provider", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Providers.all.forEach { p -> ProvChip(p.name, p.id == sel.id) { pick(p) } }
                }
                Text(sel.note, color = c.sub, fontSize = 13.sp, lineHeight = 18.sp)
                if (sel.keyUrl.isNotBlank()) PillButton("Get a key (opens browser)", c.accent) { openProviderUrl(ctx, sel.keyUrl) }
                if (sel.needsBase) {
                    Text("Base URL (ends before /chat/completions)", color = c.sub, fontSize = 12.sp)
                    ProvField(base, { base = it }, "http://192.168.1.10:11434/v1", false)
                }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text("API key", color = c.sub, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    PillButton(if (showKey) "Hide" else "Show", c.sub) { showKey = !showKey }
                }
                ProvField(key, { key = it.trim() }, if (sel.needsBase) "optional" else "paste your key", !showKey)
                Text("Model", color = c.sub, fontSize = 12.sp)
                ProvField(model, { model = it.trim() }, sel.defaultModel.ifBlank { "model name" }, false)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(if (busy) "Please wait" else "Load models", c.accent, enabled = !busy) {
                        scope.launch {
                            busy = true; status = "Loading the model list\u2026"; statusOk = true
                            try {
                                models = OpenRouterClient.listModels(sel.id, base, effectiveKey)
                                status = if (models.isEmpty()) "The server returned no models. Type the model name yourself." else "${models.size} models. Tap one to use it."
                            } catch (e: Exception) { models = emptyList(); status = e.message ?: "Could not load the list."; statusOk = false }
                            busy = false
                        }
                    }
                    PillButton("Test connection", c.green, enabled = !busy) {
                        scope.launch {
                            busy = true; status = "Testing\u2026"; statusOk = true
                            val r = OpenRouterClient.testConnection(sel.id, base, effectiveKey, model.ifBlank { sel.defaultModel })
                            status = r ?: "Connected. The key and the model work."
                            statusOk = r == null
                            busy = false
                        }
                    }
                }
                if (status.isNotBlank()) Text(status, color = if (statusOk) c.green else c.red, fontSize = 12.sp, lineHeight = 16.sp)
                if (models.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(max = 170.dp).clip(RoundedCornerShape(10.dp)).background(c.fill).verticalScroll(rememberScrollState())) {
                        models.take(60).forEach { m ->
                            Text(m, Modifier.fillMaxWidth().clickable { model = m }.padding(horizontal = 10.dp, vertical = 8.dp),
                                fontSize = 13.sp, color = if (m == model) c.accent else c.text, fontWeight = if (m == model) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                }
                Text("Your key stays on this phone and is only sent to the provider you choose. Free tiers and model names change: use Load models to see what is available now.",
                    color = c.sub, fontSize = 11.sp, lineHeight = 15.sp)
            }
        },
        confirmButton = {
            TextButton(onClick = { vm.connectProvider(sel.id, base, effectiveKey, model); onDismiss() }, enabled = effectiveKey.isNotBlank()) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ProvChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = LocalIos.current
    Box(Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) c.accent else c.fill).clickable { onClick() }.padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, fontSize = 13.sp, color = if (selected) c.bg else c.text, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
    }
}

@Composable
private fun ProvField(value: String, onChange: (String) -> Unit, hint: String, hidden: Boolean) {
    val c = LocalIos.current
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 10.dp)) {
        BasicTextField(
            value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), singleLine = true,
            textStyle = TextStyle(color = c.text, fontSize = 14.sp), cursorBrush = SolidColor(c.accent),
            visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (hidden) KeyboardType.Password else KeyboardType.Uri),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, color = c.sub, fontSize = 14.sp); inner() } }
        )
    }
}

internal fun openProviderUrl(ctx: Context, url: String) {
    try {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    } catch (_: Throwable) { }
}
