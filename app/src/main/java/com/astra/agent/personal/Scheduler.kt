package com.astra.agent.personal

import java.time.LocalDateTime
import java.time.LocalTime

class ScheduledTask(val id: String, val hour: Int, val minute: Int, val prompt: String) {
    fun timeLabel(): String {
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "%d:%02d %s".format(h12, minute, if (hour < 12) "AM" else "PM")
    }
}

/** Daily tasks ("every day at 8 am, give me the weather"). Stored with the other personal settings. */
class TaskStore(private val kv: Kv) {
    private val RS = "\u0001"; private val FS = "\u0002"
    fun list(): List<ScheduledTask> = (kv.get("tasks") ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { r ->
        val p = r.split(FS)
        if (p.size >= 4) ScheduledTask(p[0], p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: return@mapNotNull null, p[3]) else null
    }
    private fun save(l: List<ScheduledTask>) = kv.put("tasks", l.joinToString(RS) { listOf(it.id, it.hour, it.minute, it.prompt).joinToString(FS) })
    fun add(hour: Int, minute: Int, prompt: String): ScheduledTask {
        val t = ScheduledTask(System.currentTimeMillis().toString(), hour.coerceIn(0, 23), minute.coerceIn(0, 59), prompt.replace(RS, " ").replace(FS, " ").trim().take(300))
        save((list() + t).takeLast(20)); return t
    }
    fun remove(id: String) = save(list().filter { it.id != id })
}

object ScheduleIntent {
    class Parsed(val hour: Int, val minute: Int, val prompt: String)
    private val IC = RegexOption.IGNORE_CASE
    private val re = Regex("^\\s*(?:please\\s+)?(?:remind me\\s+)?(?:every\\s*day|everyday|daily|every\\s+(?:morning|evening|night|afternoon))(?:\\s+at)?\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?[\\s,:-]*(?:to\\s+)?(.{3,300})$", IC)
    private val re2 = Regex("^\\s*(?:please\\s+)?(?:at)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)\\s+(?:every\\s*day|daily)[\\s,:-]*(?:to\\s+)?(.{3,300})$", IC)

    fun parse(text: String): Parsed? {
        val m = re.find(text) ?: re2.find(text) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ap = m.groupValues[3].lowercase().replace(".", "")
        if (ap == "pm" && h < 12) h += 12
        if (ap == "am" && h == 12) h = 0
        val low = text.lowercase()
        if (ap.isEmpty() && h in 1..11 && (low.contains("evening") || low.contains("night") || low.contains("afternoon"))) h += 12
        if (h !in 0..23 || min !in 0..59) return null
        val prompt = m.groupValues[4].trim().trimEnd('.')
        return if (prompt.length < 3) null else Parsed(h, min, prompt)
    }

    /** The next time (millis since epoch, local zone) the task should fire. */
    fun nextTime(hour: Int, minute: Int, now: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val today = now.toLocalDate().atTime(LocalTime.of(hour, minute))
        return if (today.isAfter(now.plusSeconds(5))) today else today.plusDays(1)
    }
}
