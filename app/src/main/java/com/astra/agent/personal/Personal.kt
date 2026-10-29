package com.astra.agent.personal

/** Tiny key-value store (SharedPreferences on the phone, a map in tests). */
interface Kv { fun get(key: String): String?; fun put(key: String, value: String) }

class Project(val id: String, val name: String, val instructions: String)

object WritingStyles {
    val all: List<Pair<String, String>> = listOf("default" to "Default", "concise" to "Concise", "formal" to "Formal", "friendly" to "Friendly", "detailed" to "Detailed", "simple" to "Simple")
    fun prompt(id: String): String = when (id) {
        "concise" -> "Be as brief as possible: short sentences, no filler."
        "formal" -> "Write in a formal, professional tone."
        "friendly" -> "Write in a warm, friendly, conversational tone."
        "detailed" -> "Give thorough, well-organised answers with explanations and examples."
        "simple" -> "Explain in very simple words, as if to a 12 year old."
        else -> ""
    }
}

/**
 * What ZENIQ remembers about you, your projects (a name plus instructions that are always applied) and your writing style.
 * All of it is added to the system prompt, and it works the same offline and online.
 */
class Personal(private val kv: Kv) {
    private val RS = "\u0001"; private val FS = "\u0002"

    fun memories(): List<String> = (kv.get("memories") ?: "").split(RS).map { it.trim() }.filter { it.isNotEmpty() }
    private fun saveMemories(l: List<String>) = kv.put("memories", l.joinToString(RS))
    fun addMemory(fact: String): Boolean {
        val f = fact.trim().replace(RS, " ").replace(FS, " ").trim('.', ' ').take(240)
        if (f.length < 3) return false
        val cur = memories()
        if (cur.any { it.equals(f, true) }) return true
        saveMemories((cur + f).takeLast(60)); return true
    }
    fun removeMemory(index: Int) { val l = memories().toMutableList(); if (index in l.indices) { l.removeAt(index); saveMemories(l) } }
    /** Removes memories containing [what]; returns how many were removed. */
    fun forget(what: String): Int {
        val w = what.trim().lowercase(); if (w.isEmpty()) return 0
        val l = memories(); val keep = l.filter { !it.lowercase().contains(w) }
        saveMemories(keep); return l.size - keep.size
    }
    fun clearMemory() = saveMemories(emptyList())

    fun projects(): List<Project> = (kv.get("projects") ?: "").split(RS).filter { it.isNotBlank() }.mapNotNull { r ->
        val p = r.split(FS); if (p.size >= 3) Project(p[0], p[1], p[2]) else null
    }
    private fun saveProjects(l: List<Project>) = kv.put("projects", l.joinToString(RS) { listOf(it.id, it.name, it.instructions).joinToString(FS) })
    fun saveProject(name: String, instructions: String, id: String? = null): Project {
        val clean = { s: String -> s.replace(RS, " ").replace(FS, " ").trim() }
        val p = Project(id ?: System.currentTimeMillis().toString(), clean(name).ifBlank { "Project" }.take(40), clean(instructions).take(2000))
        saveProjects(projects().filter { it.id != p.id } + p); return p
    }
    fun deleteProject(id: String) { saveProjects(projects().filter { it.id != id }); if (activeProject == id) activeProject = null }
    var activeProject: String?
        get() = kv.get("activeProject")?.takeIf { it.isNotBlank() && projects().any { p -> p.id == it } }
        set(v) = kv.put("activeProject", v ?: "")
    var style: String
        get() = kv.get("style") ?: "default"
        set(v) = kv.put("style", v)

    /** Added to the end of the system prompt. Empty when there is nothing to add. */
    fun promptExtra(): String {
        val sb = StringBuilder()
        val mem = memories()
        if (mem.isNotEmpty()) sb.append("\n\nThings you know about the user (use them when relevant; never recite the list):\n").append(mem.joinToString("\n") { "- $it" })
        val proj = projects().firstOrNull { it.id == activeProject }
        if (proj != null && proj.instructions.isNotBlank()) sb.append("\n\nActive project \"${proj.name}\". Always follow these project instructions and facts:\n").append(proj.instructions)
        val st = WritingStyles.prompt(style)
        if (st.isNotEmpty()) sb.append("\n\nWriting style: ").append(st)
        return sb.toString()
    }
}

/** "remember that my shop is Sunrise Bakery", "forget the shop", "my name is Asha" */
object MemoryIntent {
    sealed class Cmd { class Remember(val fact: String) : Cmd(); class Forget(val what: String) : Cmd(); object ForgetAll : Cmd() }
    private val IC = RegexOption.IGNORE_CASE
    private val remember = Regex("^\\s*(?:please\\s+|hey\\s+|zeniq[, ]+)*remember(?:\\s+that)?[:,]?\\s+(.{3,})$", IC)
    private val forget = Regex("^\\s*(?:please\\s+)*forget(?:\\s+(?:that|about|the))?\\s+(.{2,})$", IC)
    private val forgetAll = Regex("^\\s*(?:please\\s+)*(?:forget everything|clear (?:my |your |all )?memory|erase (?:my |your )?memory)\\b", IC)

    fun detect(text: String): Cmd? {
        val t = text.trim()
        if (t.length > 400) return null
        if (forgetAll.containsMatchIn(t)) return Cmd.ForgetAll
        remember.find(t)?.let { return Cmd.Remember(it.groupValues[1].trim()) }
        forget.find(t)?.let { return Cmd.Forget(it.groupValues[1].trim().trimEnd('.', '!')) }
        return null
    }

    private val autoName = Regex("\\bmy name is\\s+([A-Za-z][A-Za-z'-]{1,20}(?:\\s+[A-Za-z][A-Za-z'-]{1,20})?)", IC)
    private val autoCall = Regex("\\bcall me\\s+([A-Za-z][A-Za-z'-]{1,20})", IC)
    private val autoLive = Regex("\\bi (?:live|stay|am living) in\\s+([A-Za-z][A-Za-z ,'-]{2,40}?)(?:[.!?,]|\\s+and\\b|$)", IC)

    /** Facts that are worth keeping without being asked. */
    fun auto(text: String): List<String> {
        val out = ArrayList<String>()
        autoName.find(text)?.let { out.add("The user's name is " + it.groupValues[1].trim()) }
        autoCall.find(text)?.let { out.add("The user likes to be called " + it.groupValues[1].trim()) }
        autoLive.find(text)?.let { out.add("The user lives in " + it.groupValues[1].trim()) }
        return out.distinct()
    }
}
