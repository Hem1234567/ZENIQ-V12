package com.astra.agent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.llm.ResourceMonitor
import com.astra.agent.llm.fmtBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
private fun UsageRow(label: String, value: String, fraction: Float, color: Color) {
    val c = LocalIos.current
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Row {
            Text(label, Modifier.weight(1f), fontSize = 15.sp)
            Text(value, fontSize = 15.sp, color = c.sub, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
            color = color, trackColor = c.fill
        )
    }
}

/** Live storage + RAM usage of the app (updates every second). */
@Composable
fun StorageCard(vm: AstraViewModel) {
    val c = LocalIos.current
    val loaded by vm.loadedModel.collectAsState()
    var snap by remember { mutableStateOf<ResourceMonitor.Snap?>(null) }
    var cleared by remember { mutableStateOf("") }

    LaunchedEffect(loaded) {
        while (true) {
            snap = withContext(Dispatchers.IO) {
                try { vm.monitor.snapshot(loaded, vm.models.dir) } catch (_: Exception) { null }
            } ?: snap
            delay(1000)
        }
    }

    val s = snap
    Column(Modifier.padding(16.dp)) {
        if (s == null) {
            Text("Measuring\u2026", color = c.sub, fontSize = 14.sp)
            return@Column
        }
        // ── Storage ───────────────────────────────────────────────────────
        Text("STORAGE USED BY ASTRA", fontSize = 12.sp, color = c.sub, letterSpacing = 0.3.sp)
        Text(fmtBytes(s.appTotal), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        val tot = s.appTotal.coerceAtLeast(1L).toFloat()
        UsageRow("AI models", fmtBytes(s.models), s.models / tot, c.accent)
        UsageRow("Chats, documents & data", fmtBytes(s.chatsAndData), s.chatsAndData / tot, c.green)
        UsageRow("Cache", fmtBytes(s.cache), s.cache / tot, c.orange)
        UsageRow("App itself (APK)", fmtBytes(s.apk), s.apk / tot, c.sub)
        val used = (s.diskTotal - s.diskFree).coerceAtLeast(0L)
        UsageRow("Phone storage free", "${fmtBytes(s.diskFree)} of ${fmtBytes(s.diskTotal)}",
            if (s.diskTotal > 0) used / s.diskTotal.toFloat() else 0f, c.red)

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            PillButton("Clear cache") {
                val n = vm.clearCache()
                cleared = "Freed ${fmtBytes(n)}"
            }
            if (cleared.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(cleared, fontSize = 13.sp, color = c.green)
            }
        }

        Spacer(Modifier.height(16.dp))
        // ── RAM (live) ────────────────────────────────────────────────────
        Text("MEMORY (RAM) \u2022 LIVE", fontSize = 12.sp, color = c.sub, letterSpacing = 0.3.sp)
        Text(fmtBytes(s.processRam), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        val devTotal = s.deviceRamTotal.coerceAtLeast(1L).toFloat()
        UsageRow("ZENIQ is using", "${fmtBytes(s.processRam)} of ${fmtBytes(s.deviceRamTotal)}", s.processRam / devTotal, c.accent)
        UsageRow(
            if (loaded != null) "Model in memory" else "Model in memory (none loaded)",
            fmtBytes(s.modelInRam), s.modelInRam / devTotal, c.green
        )
        UsageRow("App (Java) memory", fmtBytes(s.javaHeap), s.javaHeap / devTotal, c.orange)
        UsageRow("Native memory", fmtBytes(s.nativeHeap), s.nativeHeap / devTotal, c.sub)
        val usedDev = (s.deviceRamTotal - s.deviceRamFree).coerceAtLeast(0L)
        UsageRow("Whole phone RAM free", "${fmtBytes(s.deviceRamFree)} of ${fmtBytes(s.deviceRamTotal)}",
            usedDev / devTotal, c.red)
    }
}
