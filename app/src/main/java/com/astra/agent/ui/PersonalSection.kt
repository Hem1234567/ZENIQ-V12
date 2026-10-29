package com.astra.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astra.agent.personal.WritingStyles

@Composable
private fun PField(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    val c = LocalIos.current
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(c.fill).padding(horizontal = 12.dp, vertical = 9.dp)) {
        if (value.isEmpty()) Text(hint, color = c.sub, fontSize = 14.sp)
        BasicTextField(value, onChange, singleLine = singleLine, textStyle = TextStyle(color = c.text, fontSize = 14.sp),
            cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
    }
}

/** Settings: writing style, memory (view / add / delete) and projects (instructions that are always applied). */
@Composable
fun PersonalSection(vm: AstraViewModel) {
    val c = LocalIos.current
    val tick by vm.personalTick.collectAsState()
    val p = vm.personal
    var newFact by remember { mutableStateOf("") }
    var pName by remember { mutableStateOf("") }
    var pText by remember { mutableStateOf("") }
    val style = remember(tick) { p.style }
    val memories = remember(tick) { p.memories() }
    val projects = remember(tick) { p.projects() }
    val active = remember(tick) { p.activeProject }

    SectionHeader("Writing style")
    Group {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in WritingStyles.all) PillButton(label, if (style == id) c.accent else c.sub) { p.style = id; vm.personalTick.value++ }
        }
    }

    SectionHeader("Memory")
    Group {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("ZENIQ remembers these things about you in every chat, online or offline. Say \"remember that ...\" or \"forget ...\" in the chat, or edit here.",
                color = c.sub, fontSize = 12.sp, lineHeight = 16.sp)
            if (memories.isEmpty()) Text("Nothing remembered yet.", color = c.sub, fontSize = 14.sp)
            memories.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(m, Modifier.weight(1f), color = c.text, fontSize = 14.sp)
                    Text("\u2715", Modifier.clickable { p.removeMemory(i); vm.personalTick.value++ }.padding(6.dp), color = c.red, fontSize = 14.sp)
                }
            }
            PField(newFact, { newFact = it }, "Add something to remember")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Add", enabled = newFact.isNotBlank()) { p.addMemory(newFact); newFact = ""; vm.personalTick.value++ }
                if (memories.isNotEmpty()) PillButton("Clear all", c.red) { p.clearMemory(); vm.personalTick.value++ }
            }
        }
    }

    val tctx = androidx.compose.ui.platform.LocalContext.current
    val tasks = remember(tick) { com.astra.agent.Alarms.store(tctx).list() }
    SectionHeader("Scheduled tasks")
    Group {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Say for example \"every day at 8 am give me the weather\". At that time ZENIQ sends a notification; tap it and the task runs.",
                color = c.sub, fontSize = 12.sp, lineHeight = 16.sp)
            if (tasks.isEmpty()) Text("No scheduled tasks.", color = c.sub, fontSize = 14.sp)
            tasks.forEach { t ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Every day at " + t.timeLabel(), color = c.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(t.prompt, color = c.sub, fontSize = 13.sp, maxLines = 2)
                    }
                    PillButton("Delete", c.red) { com.astra.agent.Alarms.cancel(tctx, t); com.astra.agent.Alarms.store(tctx).remove(t.id); vm.personalTick.value++ }
                }
            }
        }
    }

    SectionHeader("Projects")
    Group {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("A project is a name plus instructions and facts (for example your shop details). The active project is used in every chat.",
                color = c.sub, fontSize = 12.sp, lineHeight = 16.sp)
            projects.forEach { pr ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(pr.name, Modifier.weight(1f), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (active == pr.id) PillButton("Active", c.green) { p.activeProject = null; vm.personalTick.value++ }
                    else PillButton("Use") { p.activeProject = pr.id; vm.personalTick.value++ }
                    PillButton("Edit") { pName = pr.name; pText = pr.instructions; p.deleteProject(pr.id); vm.personalTick.value++ }
                    PillButton("Delete", c.red) { p.deleteProject(pr.id); vm.personalTick.value++ }
                }
            }
            PField(pName, { pName = it }, "Project name, for example My Business")
            PField(pText, { pText = it }, "Instructions and facts, for example: Shop: Sunrise Bakery, Chennai. Open 7am-9pm. Reply in a friendly tone.",
                Modifier.defaultMinSize(minHeight = 80.dp), singleLine = false)
            PillButton("Save project", c.green, enabled = pName.isNotBlank()) {
                val saved = p.saveProject(pName, pText); p.activeProject = saved.id; pName = ""; pText = ""; vm.personalTick.value++
            }
        }
    }
}
