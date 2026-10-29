package com.astra.agent.code

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.astra.agent.Settings
import com.astra.agent.llm.Completion
import com.astra.agent.term.Stage
import com.astra.agent.term.TermSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

// ── What the screens watch ───────────────────────────────────────────────────

/** One file that is being written in front of the user. [text] is everything received so far, [shown] how much of it the typing animation has revealed. */
class LiveFile(val path: String) {
    var text by mutableStateOf("")
    var shown by mutableStateOf(0)
    var done by mutableStateOf(false)
}

enum class TaskState { PENDING, RUNNING, DONE, FAILED }
class TaskRow(val title: String) { var state by mutableStateOf(TaskState.PENDING) }
enum class Phase { THINKING, PLAN, BUILDING, DONE, FAILED }

/**
 * Everything the IDE / Agent screens show about one run: thinking text, status, plan, task list, files being typed,
 * terminal lines. The builder writes to it, the UI only reads it.
 */
class BuildSession(
    val mode: CodeMode,
    val prompt: String,
    /** true = no plan approval step (Claude Code agent). */
    val autoApprove: Boolean,
    /** set = build INTO this existing project folder name instead of creating a new project. */
    val existingProject: String? = null
) {
    var phase by mutableStateOf(Phase.THINKING)
    var status by mutableStateOf("Analyzing request")
    /** Short animated label next to the spinner: Thinking, Researching, Creating file, Writing code, Installing dependencies, Running build, Fixing error. */
    var action by mutableStateOf("Thinking")
    var thinking by mutableStateOf("")
    var plan by mutableStateOf("")
    var title by mutableStateOf("")
    var stack by mutableStateOf("html")
    var projectName by mutableStateOf<String?>(null)
    val tasks = mutableStateListOf<TaskRow>()
    val files = mutableStateListOf<LiveFile>()
    var active by mutableStateOf<LiveFile?>(null)
    val terminal = mutableStateListOf<String>()
    var error by mutableStateOf<String?>(null)
    /** The user's decision on the plan (possibly edited). Completed by the Approve button. */
    val approval = CompletableDeferred<String>()

    /** Called once per frame by the UI: reveals a few more characters of every file that is behind. Returns true while something is still typing. */
    fun typeTick(): Boolean {
        var busy = false
        for (f in files) {
            val total = f.text.length
            if (f.shown > total) f.shown = total
            if (f.shown < total) {
                val backlog = total - f.shown
                f.shown = (f.shown + maxOf(3, backlog / 10)).coerceAtMost(total)
                busy = true
            }
        }
        return busy
    }

    val typingDone: Boolean get() = files.all { it.shown >= it.text.length }

    fun term(line: String) {
        terminal.add(line.take(300))
        while (terminal.size > 400) terminal.removeAt(0)
    }
}

// ── The pipeline ─────────────────────────────────────────────────────────────

object IdeBuilder {
    private const val ANALYZE_SYS = "You are a senior software architect thinking before building. Think deeply about the user's request. " +
        "Write short notes (no code) covering: the goal, who uses it, every feature it needs, the data it stores, the screens, " +
        "edge cases and risks. Plain text, at most 25 lines."

    private fun planSystem(stackHint: String, backend: Boolean) = "You are a senior software architect. Write a brief but COMPLETE implementation plan using EXACTLY this markdown layout:\n\n" +
        "# <Project title>\nStack: html\n## Overview\n2-3 sentences.\n## Features\n- every feature the user asked for, plus the essentials the app needs\n" +
        "## Files\n- path/to/file.ext \u2014 what it contains\n## Data & API\n- data model and how the frontend uses it\n## Tasks\n- short build step\n\n" +
        "Rules:\n- The Stack line is either \"Stack: html\" (plain HTML/CSS/JavaScript) or \"Stack: react\". $stackHint\n" +
        (if (backend) "- The request needs a backend: also plan a server/ folder with Express routes under /api and say how the frontend uses them.\n"
         else "- Do NOT plan a server/ folder: the app runs completely in the browser and saves its data with localStorage, so it works as soon as it is opened.\n") +
        "- For a plain HTML app use exactly these files unless it truly needs more: index.html, style.css, script.js.\n" +
        "- List every file the app needs with its relative path, 2 to 8 files. Do not list node_modules, lock files or images.\n" +
        "- Tasks are 4 to 8 short steps in build order.\n- Output only the plan. No code."

    private const val LOCAL_FILE_SYS = "You write ONE complete file of a small web app. Output ONLY the raw file content: no markdown fences, no explanation. " +
        "Be compact: no comments, short code, about 120 lines. The file must be complete and working: every button and form works and data is kept in localStorage. " +
        "Clean, modern, responsive design. Never stop in the middle of the file."

    private fun fileSystem(backend: Boolean, local: Boolean = false): String = if (local) LOCAL_FILE_SYS else fileSystemFull(backend)

    private fun fileSystemFull(backend: Boolean) = "You are an expert full-stack engineer writing ONE file of a project, following the approved plan.\n" +
        "Rules:\n- Output ONLY the raw content of the requested file. No markdown fences, no explanation before or after it.\n" +
        "- The file must be COMPLETE and working. No placeholders, no TODO, no \"...\". Never stop in the middle of the file.\n" +
        "- Use the relative file names exactly as the plan lists them, and link files to each other correctly (the page loads style.css and script.js with relative paths).\n" +
        "- In the HTML give every element that the script uses a unique id, and write the HTML first so the other files can use those exact ids and classes.\n" +
        "- In CSS and JavaScript use ONLY the ids, classes and elements that exist in the HTML you are shown. Run JavaScript after the page has loaded (script tag with defer, or DOMContentLoaded).\n" +
        (if (backend) "- The frontend talks to the backend only through fetch('/api/...') and ALSO falls back to localStorage when the server is not reachable, so the app works when opened without the server.\n"
         else "- Keep all data in the browser with localStorage. Do not call any server.\n") +
        "- Make the interface responsive, polished and accessible. Implement EVERY feature of the plan that belongs in this file, and make every button and form really work."

    private val SCAFFOLD = setOf("package.json", "vite.config.js", "index.html", "src/main.jsx")

    fun wantsReact(prompt: String) = Regex("\\breact\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    /** A backend is only planned when the user asks for one: everything else runs in the browser and works the moment it is opened. */
    fun wantsBackend(prompt: String) = Regex("\\b(backend|back-end|server|database|api|express|node\\.?js|mongodb|mysql|postgres|sqlite|authentication|rest)\\b", RegexOption.IGNORE_CASE).containsMatchIn(prompt)

    /** The real Linux environment (Node + npm) is only used when its setup has finished. */
    fun linuxReady(): Boolean = TermSetup.ui.value.stage == Stage.READY

    // ── Step 1: think and research, then plan ────────────────────────────────

    private suspend fun research(prompt: String): List<Pair<String, String>> = coroutineScope {
        val short = prompt.replace(Regex("\\s+"), " ").take(70)
        val results = listOf("$short features best practices", "$short example app design").map { q ->
            async { try { WebLookup.search(q) } catch (e: CancellationException) { throw e } catch (_: Throwable) { emptyList() } }
        }.awaitAll()
        val found = ArrayList<Pair<String, String>>()
        for (r in results) for (x in r) if (found.none { it.second == x.second } && found.size < 6) found.add(x)
        found
    }

    private const val SINGLE_FILE = "- index.html \u2014 the whole app: page, styles in a <style> tag and script in a <script> tag, all in one file"

    private fun defaultFiles(react: Boolean, single: Boolean = false) =
        if (single) SINGLE_FILE
        else if (react) "- src/App.jsx \u2014 the whole app component (imports ./App.css)\n- src/App.css \u2014 the styles"
        else "- index.html \u2014 the page structure\n- style.css \u2014 the styles\n- script.js \u2014 the app logic"

    /** A complete plan that needs no AI. Used when the model could not write one (busy cloud model, empty answer), so the flow never stops at the plan. */
    fun fallbackPlan(prompt: String, single: Boolean = false): String {
        val react = wantsReact(prompt)
        val title = CodeIntent.projectName(prompt).replace('-', ' ').replaceFirstChar { it.uppercase() }
        return "# $title\nStack: ${if (react) "react" else "html"}\n## Overview\n${prompt.replace(Regex("\\s+"), " ").take(240)}\n" +
            "## Features\n- Everything the request describes\n- Clean, responsive interface\n- Data saved in the browser\n" +
            "## Files\n${defaultFiles(react, single)}\n## Data & API\n- Runs in the browser; data is kept in localStorage\n" +
            (if (single) "## Tasks\n- Write the page, styles and app logic in one file\n- Check that everything works"
             else "## Tasks\n- Create the page structure\n- Write the styles\n- Write the app logic\n- Check that everything is connected")
    }

    /** A small plan for the phone model: the model only names the app and lists its features (a few seconds), the rest is fixed. */
    private fun assembleLocalPlan(prompt: String, modelText: String): String {
        val lines = modelText.lines().map { it.trim() }.filter { it.isNotBlank() }
        val title = lines.firstOrNull { it.startsWith("#") }?.trimStart('#', ' ')?.take(50)
        val feats = lines.filter { it.startsWith("-") || it.startsWith("*") || it.startsWith("\u2022") }.map { it.trimStart('-', '*', '\u2022', ' ').take(100) }.filter { it.isNotBlank() }.take(7)
        if (title.isNullOrBlank() || feats.size < 2) return fallbackPlan(prompt, true)
        val react = wantsReact(prompt)
        return "# $title\nStack: ${if (react) "react" else "html"}\n## Overview\n${prompt.replace(Regex("\\s+"), " ").take(240)}\n" +
            "## Features\n" + feats.joinToString("\n") { "- $it" } + "\n## Files\n$SINGLE_FILE\n## Data & API\n- Runs in the browser; data is kept in localStorage\n" +
            "## Tasks\n- Write the page, styles and app logic in one file\n- Check that everything works"
    }

    /** Keeps the model's plan but adds the parts it forgot (Files, Tasks), so the build always has something to follow. */
    private fun completePlan(plan: String, prompt: String): String {
        if (plan.isBlank() || !plan.contains("#")) return fallbackPlan(prompt)
        var p = plan.trimEnd()
        if (parseFiles(p, 12).isEmpty()) p += "\n\n## Files\n" + defaultFiles(Regex("(?im)^\\s*stack\\s*:\\s*react").containsMatchIn(p) || wantsReact(prompt))
        if (parseTasks(p).isEmpty()) p += "\n\n## Tasks\n- Create the page structure\n- Write the styles\n- Write the app logic\n- Check that everything is connected"
        return p
    }

    suspend fun think(s: Settings, session: BuildSession) {
        val cloud = Completion.usesCloud(s)
        session.phase = Phase.THINKING
        session.status = "Analyzing request"; session.action = "Thinking"
        session.thinking = "Analysis\n"
        // The web research runs at the same time as the analysis, so it costs no extra waiting time.
        val found = coroutineScope {
            val lookup = async { research(session.prompt) }
            if (cloud) {
                try { Completion.stream(s, ANALYZE_SYS, session.prompt, 900, 0.4f, onReset = { session.thinking = "Analysis\n" }) { session.thinking += it } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { session.thinking += "(The detailed analysis was skipped: ${e.message ?: "the AI did not answer"}.)\n" }
            } else {
                // a small phone model goes straight to the plan: a separate analysis round would only add minutes of waiting
                session.thinking += "Reading the request and choosing the simplest structure that works without internet.\n"
            }
            session.status = "Researching"; session.action = "Researching"
            lookup.await()
        }
        session.thinking += "\n\nResearch\n"
        if (found.isEmpty()) session.thinking += "No internet results, so the plan uses built-in knowledge.\n"
        else for (x in found) session.thinking += "\u2022 ${x.first}\n"
        session.thinking = session.thinking.trimEnd()

        session.status = "Drafting plan"; session.action = "Drafting plan"
        session.plan = ""
        val hint = when {
            wantsReact(session.prompt) -> "The user asked for React, so use Stack: react."
            else -> "Use Stack: react only if the app really needs a component framework; otherwise Stack: html."
        }
        val research = if (found.isEmpty()) "" else "\n\nIdeas from web research (use only what helps):\n" + found.joinToString("\n") { "- ${it.first}: ${it.second.take(160)}" }
        val analysis = if (cloud) "\n\nMy analysis:\n${session.thinking.take(2500)}" else ""
        if (!cloud && !wantsBackend(session.prompt)) {
            // Phone model: ask only for a title and a feature list (about 100 words). Everything else in the plan is fixed, so this takes seconds.
            session.action = "Drafting plan"
            val short = try {
                Completion.ask(s, "You plan a small web app. Reply with exactly: a first line '# <app title>' and then 4 to 7 lines that each start with '- ' and name one feature. Nothing else.",
                    "App: ${session.prompt}", 170, 0.3f)
            } catch (e: CancellationException) { throw e } catch (_: Exception) { "" }
            session.plan = assembleLocalPlan(session.prompt, short)
            applyPlanMeta(session)
            return
        }
        val text = try {
            Completion.stream(s, planSystem(hint, wantsBackend(session.prompt)), "Request: ${session.prompt}$analysis$research",
                if (cloud) 1500 else 700, 0.3f, onReset = { session.plan = "" }) { session.plan += it }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            session.thinking += "\n\nThe AI could not write the plan (${e.message ?: "no answer"}), so a standard plan is used."
            ""
        }
        session.plan = completePlan(text.ifBlank { session.plan }, session.prompt)
        applyPlanMeta(session)
    }

    fun applyPlanMeta(session: BuildSession) {
        val p = session.plan
        session.title = Regex("(?m)^#\\s+(.+)$").find(p)?.groupValues?.get(1)?.trim()?.take(60)?.ifBlank { null } ?: CodeIntent.projectName(session.prompt).replace('-', ' ').replaceFirstChar { it.uppercase() }
        session.stack = if (Regex("(?im)^\\s*stack\\s*:\\s*react").containsMatchIn(p)) "react" else "html"
        session.tasks.clear()
        parseTasks(p).forEach { session.tasks.add(TaskRow(it)) }
    }

    private fun section(plan: String, name: String): List<String> {
        val lines = plan.lines()
        val start = lines.indexOfFirst { Regex("^#{2,3}\\s*" + Regex.escape(name), RegexOption.IGNORE_CASE).containsMatchIn(it.trim()) }
        if (start < 0) return emptyList()
        val out = ArrayList<String>()
        for (l in lines.drop(start + 1)) { if (l.trim().startsWith("#")) break; out.add(l) }
        return out
    }

    fun parseTasks(plan: String): List<String> =
        section(plan, "Tasks").mapNotNull { Regex("^\\s*(?:[-*\u2022]|\\d+[.)])\\s+(.+)$").find(it)?.groupValues?.get(1)?.trim()?.take(90) }.take(10)

    private val fileLine = Regex("^\\s*(?:[-*\u2022]|\\d+[.)])?\\s*[`*]*([\\w@.-]+(?:/[\\w@.-]+)*\\.[A-Za-z0-9]{1,6})[`*]*\\s*(?:[-\u2013\u2014:(]\\s*(.*))?$")

    /** (path, purpose) pairs from the plan's Files section. */
    fun parseFiles(plan: String, max: Int): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        for (l in section(plan, "Files")) {
            val m = fileLine.find(l) ?: continue
            val path = m.groupValues[1].trimStart('/').removePrefix("./")
            if (path.contains("..") || path.startsWith("node_modules") || path.endsWith(".lock") || path.startsWith(".")) continue
            if (path !in out) out[path] = m.groupValues[2].trim().trimEnd(')').take(160)
        }
        return out.map { it.key to it.value }.take(max)
    }

    /** Build order: the page first (so every other file can see its real ids), then styles, then scripts, backend last. */
    private fun rank(path: String): Int {
        val p = path.lowercase()
        return when {
            p.endsWith(".html") -> 0
            p.endsWith("app.jsx") || p.endsWith("app.js") -> 1
            p.endsWith(".css") -> 2
            p.startsWith("server/") -> 5
            p.endsWith(".js") || p.endsWith(".jsx") || p.endsWith(".mjs") -> 3
            else -> 4
        }
    }

    // ── Step 2: build ────────────────────────────────────────────────────────

    suspend fun build(ctx: Context, s: Settings, session: BuildSession, modeRoot: File) {
        val cloud = Completion.usesCloud(s)
        session.phase = Phase.BUILDING
        session.status = "Setting up the project"; session.action = "Creating file"
        val name = session.existingProject ?: ProjectStore.uniqueName(modeRoot, session.title.ifBlank { CodeIntent.projectName(session.prompt) })
        session.projectName = name
        val dir = File(modeRoot, name)
        val ws = Workspace(dir)
        ProjectStore.writeMeta(dir, session.title.ifBlank { name }, session.prompt, session.stack, false)
        session.term("$ mkdir -p " + name)
        if (session.existingProject != null) {
            try { if (ws.tree().any { !it.dir }) { ws.commit("Before agent run"); session.term("$ checkpoint saved (use History to restore)") } } catch (_: Throwable) {}
        }

        val single = !cloud && !wantsBackend(session.prompt)          // phone model: one self-contained index.html (one generation instead of several)
        val vite = session.stack == "react" && linuxReady() && !single
        val cdnReact = session.stack == "react" && !vite
        var planned = parseFiles(session.plan, if (cloud) 10 else 6).filter { it.first !in SCAFFOLD || !vite }
        if (planned.isEmpty()) planned = listOf((if (vite) "src/App.jsx" else "index.html") to "the whole application")
        if (vite && planned.none { it.first == "src/App.jsx" }) planned = listOf("src/App.jsx" to "the root component") + planned
        if (!vite && planned.none { it.first.endsWith(".html") } && session.stack == "html") planned = listOf("index.html" to "the page") + planned
        planned = planned.sortedBy { rank(it.first) }
        if (single) planned = listOf("index.html" to "the whole app: page structure, <style> and <script> in this one file")
        val hasServer = planned.any { it.first.startsWith("server/") }
        val stackNote = when {
            vite -> "Stack: React + Vite (JSX in src/, entry src/main.jsx imports ./App.jsx, index.html is generated). Frontend code uses ES modules. Backend files use ES module syntax (import/export)."
            single && session.stack == "react" -> "Stack: ONE self-contained index.html using React 18 and @babel/standalone from https://unpkg.com; all UI code in <script type=\"text/babel\">, styles in a <style> tag. No other files."
            single -> "Stack: ONE self-contained index.html. All CSS in a <style> tag in the head and all JavaScript in a <script> tag at the end of the body. No external files and no libraries."
            cdnReact -> "Stack: React WITHOUT a build step. index.html loads React 18 and @babel/standalone from https://unpkg.com and contains the app inside <script type=\"text/babel\">. Keep all UI code in index.html."
            else -> "Stack: plain HTML, CSS and JavaScript. Backend files (if any) use CommonJS (require)."
        }

        session.status = "Setting up the project"
        if (vite) {
            session.action = "Creating file"
            for ((path, content) in viteScaffold(session.title.ifBlank { name }, hasServer)) putInstant(ws, session, path, content)
        } else if (hasServer && planned.none { it.first == "package.json" }) {
            putInstant(ws, session, "package.json", plainPackageJson(name))
        }

        // the whole file list shows up at once, then each file fills with code
        for ((path, _) in planned) if (session.files.none { it.path == path }) session.files.add(LiveFile(path))
        val written = java.util.Collections.synchronizedList(ArrayList<LiveFile>())
        val total = planned.size
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        advanceTasks(session, 0, total)
        // 1) the page alone, so every other file can see its real ids and classes
        val first = planned.first()
        writeFile(s, session, ws, first.first, first.second, planned, written, stackNote, null)?.let { written.add(it) }
        advanceTasks(session, done.incrementAndGet(), total)
        // 2) the rest: at the same time in Fast Mode (the cloud can answer several requests at once); one by one on the phone model
        val rest = planned.drop(1)
        if (cloud && rest.size > 1) {
            val gate = Semaphore(3)
            coroutineScope {
                rest.map { (path, purpose) ->
                    async {
                        gate.withPermit {
                            writeFile(s, session, ws, path, purpose, planned, written, stackNote, null)?.let { written.add(it) }
                            advanceTasks(session, done.incrementAndGet(), total)
                        }
                    }
                }.awaitAll()
            }
        } else {
            for ((path, purpose) in rest) {
                writeFile(s, session, ws, path, purpose, planned, written, stackNote, null)?.let { written.add(it) }
                advanceTasks(session, done.incrementAndGet(), total)
            }
        }
        advanceTasks(session, total, total)
        try { ws.commit("Initial build") } catch (_: Throwable) {}

        // verify and repair
        session.status = "Checking the project"; session.action = "Running build"
        session.term("$ zeniq check")
        var problems = verifyDeep(ctx, ws)
        if (problems.isEmpty()) session.term("  all files present, links, ids and syntax valid")
        var round = 0
        while (problems.isNotEmpty() && round < (if (cloud) 2 else 1)) {
            round++
            session.action = "Fixing error"; session.status = "Fixing ${problems.size} problem(s)"
            for (pr in problems.take(if (cloud) 4 else 2)) {
                session.term("  ! " + pr.message)
                val fixPath = pr.path ?: continue
                writeFile(s, session, ws, fixPath, pr.fix, planned, written, stackNote, pr.message)
            }
            session.term("$ zeniq check")
            problems = verifyDeep(ctx, ws)
            if (problems.isEmpty()) session.term("  fixed, all checks pass")
        }
        if (problems.isNotEmpty()) problems.forEach { session.term("  ! still open: " + it.message) }

        if (vite) buildWithNpm(ctx, s, session, ws, planned, written, stackNote)

        ProjectStore.writeMeta(dir, session.title.ifBlank { name }, session.prompt, session.stack, true)
        session.tasks.forEach { if (it.state != TaskState.FAILED) it.state = TaskState.DONE }
        session.status = "Done"; session.action = "Done"
        session.phase = Phase.DONE
    }

    private fun advanceTasks(session: BuildSession, done: Int, total: Int) {
        val n = session.tasks.size
        if (n == 0 || total == 0) return
        val cur = if (done >= total) n else (done * n / total).coerceAtMost(n - 1)
        for ((i, t) in session.tasks.withIndex()) t.state = when { i < cur -> TaskState.DONE; i == cur -> TaskState.RUNNING; else -> TaskState.PENDING }
    }

    /** Writes a file whose content is already known (config files). It still "types out" on screen. */
    private fun putInstant(ws: Workspace, session: BuildSession, path: String, content: String) {
        val lf = LiveFile(path)
        session.files.add(lf); session.active = lf
        session.status = "Creating $path"; session.action = "Creating file"
        lf.text = content
        ws.write(path, content)
        lf.done = true
        session.term("$ write $path")
    }

    /** What the model is shown of the files that already exist. Styles and scripts get the page in FULL, so they use its real ids and classes. */
    private fun contextFor(path: String, written: List<LiveFile>, cloud: Boolean): String {
        val snap = synchronized(written) { ArrayList(written) }
        val ext = path.substringAfterLast('.', "").lowercase()
        val needsPage = ext in setOf("css", "js", "jsx", "mjs")
        val sb = StringBuilder()
        var budget = if (cloud) 14_000 else 3_200
        if (needsPage) for (w in snap) {
            if (!w.path.endsWith(".html") || w.path == path) continue
            val cap = if (cloud) 7_000 else 2_600
            sb.append("--- ").append(w.path).append(" (FULL PAGE: use exactly these ids, classes and elements) ---\n").append(w.text.take(cap)).append("\n\n")
            budget -= cap
        }
        for (w in snap) {
            if (w.path == path || (needsPage && w.path.endsWith(".html"))) continue
            if (budget <= 0) break
            val cap = if (cloud) 1500 else 500
            sb.append("--- ").append(w.path).append(" ---\n").append(w.text.take(cap)).append("\n\n")
            budget -= cap
        }
        return sb.toString()
    }

    private suspend fun writeFile(
        s: Settings, session: BuildSession, ws: Workspace, path: String, purpose: String,
        planned: List<Pair<String, String>>, written: List<LiveFile>, stackNote: String, problem: String?
    ): LiveFile? {
        val cloud = Completion.usesCloud(s)
        val lf = session.files.firstOrNull { it.path == path }?.also { it.text = ""; it.shown = 0; it.done = false } ?: LiveFile(path).also { session.files.add(it) }
        session.active = lf
        session.action = if (problem == null) "Creating file" else "Fixing error"
        session.status = (if (problem == null) "Creating " else "Fixing ") + path
        delay(80)
        if (problem == null) session.action = "Writing code"
        // The first part (request, plan, stack, file list) is the same for every file, so the phone model reads it only once (its cache is reused);
        // what changes from file to file comes last.
        val backend = planned.any { it.first.startsWith("server/") }
        val stable = "Original request: ${session.prompt}\n\nApproved plan:\n${session.plan.take(if (cloud) 4000 else 1500)}\n\n$stackNote\n\n" +
            "All files of the project: " + planned.joinToString(", ") { it.first } + "\n\n"
        val ctxText = contextFor(path, written, cloud)
        val current = if (problem != null) try { ws.read(path).take(if (cloud) 7000 else 2800) } catch (_: Throwable) { "" } else ""
        val user = stable +
            (if (ctxText.isNotEmpty()) "Files already written:\n$ctxText" else "") +
            (if (problem != null) "Problem to fix: $problem\n" + (if (current.isNotEmpty()) "Current content of $path:\n$current\n\n" else "") else "") +
            "Now write the COMPLETE " + (if (problem != null) "corrected " else "") + "file: $path" + (if (purpose.isNotBlank()) " ($purpose)" else "") + "\nOutput only the file content."
        val raw = StringBuilder()
        try {
            Completion.stream(s, fileSystem(backend, !cloud), user, if (cloud) 4000 else 1800, 0.2f, onReset = { raw.setLength(0); lf.text = "" }) { chunk ->
                raw.append(chunk)
                lf.text = cleanStream(raw.toString(), false)
            }
        } catch (e: CancellationException) { throw e
        } catch (e: Throwable) {
            session.term("! could not write $path: ${e.message ?: "unknown error"}")
            if (raw.isEmpty()) { lf.done = true; return null }
        }
        var body = cleanStream(raw.toString(), true)
        // A file that was cut off (the answer hit its length limit) is continued from where it stopped, so the app never ships half a file.
        var rounds = 0
        while (rounds < (if (cloud) 2 else 1) && isTruncated(path, body)) {
            rounds++
            session.action = "Writing code"; session.status = "Finishing $path"
            val more = StringBuilder()
            try {
                Completion.stream(s, fileSystem(backend, !cloud), continuationPrompt(path, body), if (cloud) 3000 else 1000, 0.2f, onReset = { more.setLength(0) }) { chunk ->
                    more.append(chunk)
                    lf.text = body + cleanStream(more.toString(), false)
                }
            } catch (e: CancellationException) { throw e } catch (_: Throwable) { break }
            val extra = cleanStream(more.toString(), true)
            if (extra.isBlank() || extra.trim().equals("DONE", true)) break
            body = joinContinuation(body, extra)
            lf.text = body
        }
        val clean = body.trimEnd() + "\n"
        lf.text = clean
        ws.write(path, clean)
        lf.done = true
        session.term("$ write $path (${clean.lines().size} lines)")
        return lf
    }

    private fun continuationPrompt(path: String, body: String) =
        "The file $path was cut off because it was too long. Its last part is:\n<<<\n${body.takeLast(1800)}\n>>>\n" +
        "Continue the file EXACTLY from the last character to the very end of the file. Output ONLY the missing remainder: no repeats, no code fences, no explanation. " +
        "If the file is already complete, output only the word DONE."

    /** Joins a continuation onto a file; if the model repeated the end of the file, the repeated part is dropped. */
    fun joinContinuation(a: String, more: String): String {
        var k = minOf(240, more.length, a.length)
        while (k >= 12) { if (a.endsWith(more.substring(0, k))) return a + more.substring(k); k-- }
        return a + more
    }

    /** True when the text clearly stops in the middle: an open bracket, string or comment (code), or a missing </html>. */
    fun isTruncated(path: String, text: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "html", "htm" -> text.contains("<html", true) && !text.contains("</html>", true) ||
                Regex("<script", RegexOption.IGNORE_CASE).findAll(text).count() > Regex("</script>", RegexOption.IGNORE_CASE).findAll(text).count()
            "js", "jsx", "mjs", "ts", "tsx", "json", "css" -> scanBrackets(text, ext == "css").unfinished
            else -> false
        }
    }

    /** Result of scanning code: [unfinished] = something is still open at the end, [stray] = a closer without an opener. */
    class Scan(val unfinished: Boolean, val stray: Boolean)

    /** Counts brackets while skipping strings, comments and regex literals. A rough check, used as a hint only. */
    fun scanBrackets(t: String, css: Boolean): Scan {
        val stack = ArrayList<Char>()
        var stray = false
        var i = 0
        val n = t.length
        var prev = ' '                    // last significant character, to tell a regex literal from a division
        while (i < n) {
            val c = t[i]
            val d = if (i + 1 < n) t[i + 1] else ' '
            if (c == '/' && d == '*') { val e = t.indexOf("*/", i + 2); if (e < 0) return Scan(true, stray); i = e + 2; continue }
            if (!css && c == '/' && d == '/') { val e = t.indexOf('\n', i); if (e < 0) break; i = e + 1; continue }
            if (c == '"' || c == '\'' || (c == '`' && !css)) {
                var j = i + 1; var closed = false
                while (j < n) {
                    val x = t[j]
                    if (x == '\\') { j += 2; continue }
                    if (x == c) { closed = true; break }
                    if (x == '\n' && c != '`') break
                    j++
                }
                if (!closed && c == '`') return Scan(true, stray)
                i = if (closed) j + 1 else j
                prev = c; continue
            }
            if (!css && c == '/' && prev in "(,=:[!&|?{};" ) {
                var j = i + 1; var inClass = false; var closed = false
                while (j < n && t[j] != '\n') {
                    val x = t[j]
                    if (x == '\\') { j += 2; continue }
                    if (x == '[') inClass = true else if (x == ']') inClass = false
                    else if (x == '/' && !inClass) { closed = true; break }
                    j++
                }
                if (closed) { i = j + 1; prev = '/'; continue }
            }
            when (c) {
                '(', '[', '{' -> stack.add(c)
                ')', ']', '}' -> {
                    val want = when (c) { ')' -> '('; ']' -> '['; else -> '{' }
                    if (stack.isNotEmpty() && stack.last() == want) stack.removeAt(stack.size - 1) else stray = true
                }
            }
            if (!c.isWhitespace()) prev = c
            i++
        }
        return Scan(stack.isNotEmpty(), stray)
    }

    /** Removes the markdown fence a model sometimes puts around a file. While streaming, a half-written closing fence is hidden. */
    fun cleanStream(raw: String, final: Boolean): String {
        var t = raw
        val trimmed = t.trimStart()
        if (trimmed.startsWith("```")) { val nl = trimmed.indexOf('\n'); t = if (nl < 0) "" else trimmed.substring(nl + 1) }
        else if (final) {
            val m = Regex("^[\\s\\S]{0,300}?```[A-Za-z0-9_+#.-]*[ \\t]*\\n([\\s\\S]*?)\\n```", RegexOption.MULTILINE).find(trimmed)
            if (m != null && !trimmed.startsWith("<") && !trimmed.startsWith("{") ) t = m.groupValues[1]
        }
        val lastNl = t.lastIndexOf('\n')
        val tailLine = t.substring(lastNl + 1)
        if (if (final) tailLine.trim().startsWith("```") else tailLine.trim().startsWith("`")) t = if (lastNl < 0) "" else t.substring(0, lastNl + 1)
        return t
    }

    // ── Checks ───────────────────────────────────────────────────────────────

    class Problem(val message: String, val path: String?, val fix: String)

    private val idUse = listOf(
        Regex("getElementById\\(\\s*[\"']([\\w-]+)[\"']\\s*\\)"),
        Regex("querySelector(?:All)?\\(\\s*[\"']#([\\w-]+)[\"']")
    )

    fun verify(ws: Workspace): List<Problem> {
        val out = ArrayList<Problem>()
        val files = ws.tree().filter { !it.dir }
        val htmlIds = HashSet<String>()
        val htmlTexts = ArrayList<Pair<String, String>>()
        for (f in files) {
            val ext = f.rel.substringAfterLast('.', "").lowercase()
            val text = try { ws.read(f.rel) } catch (_: Throwable) { continue }
            if (text.isBlank() && ext in setOf("html", "js", "jsx", "css", "json")) out.add(Problem("${f.rel} is empty", f.rel, "the file was empty"))
            if (ext == "json" && text.isNotBlank()) try { JSONObject(text) } catch (_: Throwable) { out.add(Problem("${f.rel} is not valid JSON", f.rel, "invalid JSON, rewrite it as valid JSON")) }
            if (ext in setOf("js", "jsx", "mjs", "css") && text.isNotBlank()) {
                val sc = scanBrackets(text, ext == "css")
                if (sc.unfinished || sc.stray) out.add(Problem("${f.rel} has unbalanced brackets or quotes (cut off or a typo)", f.rel, "brackets or quotes do not match; rewrite the whole file so it is complete and valid"))
            }
            if (ext == "html") {
                htmlTexts.add(f.rel to text)
                val idsHere = HashSet<String>()
                for (m in Regex("\\bid\\s*=\\s*[\"']([\\w-]+)[\"']").findAll(text)) { htmlIds.add(m.groupValues[1]); idsHere.add(m.groupValues[1]) }
                // scripts written inside the page itself (a single-file app)
                for (m in Regex("<script([^>]*)>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(text)) {
                    val attrs = m.groupValues[1]; val code = m.groupValues[2]
                    if (attrs.contains("src=", true) || code.isBlank()) continue
                    if (!attrs.contains("babel", true) && !attrs.contains("json", true)) {
                        val sc = scanBrackets(code, false)
                        if (sc.unfinished || sc.stray) out.add(Problem("${f.rel} has a script with unbalanced brackets or quotes (cut off or a typo)", f.rel,
                            "the <script> in the page does not close properly; rewrite the whole file so it is complete and valid"))
                    }
                    val missingHere = LinkedHashSet<String>()
                    for (re in idUse) for (mm in re.findAll(code)) {
                        val id = mm.groupValues[1]
                        if (id in idsHere) continue
                        if (Regex("""id\s*=\s*["']?${Regex.escape(id)}\b""").containsMatchIn(code) || code.contains(".id = '$id'") || code.contains(".id = \"$id\"")) continue
                        missingHere.add(id)
                    }
                    if (missingHere.isNotEmpty()) out.add(Problem("${f.rel} script looks up element id(s) ${missingHere.take(4).joinToString { "#$it" }} that the page does not contain",
                        f.rel, "use ONLY these ids from the page: " + idsHere.take(40).joinToString(", ") + ". Rewrite the file so every id the script uses exists."))
                }
                val dir = f.rel.substringBeforeLast('/', "")
                for (m in Regex("(?:src|href)=[\"']([^\"'#?]+)[\"']", RegexOption.IGNORE_CASE).findAll(text)) {
                    val ref = m.groupValues[1].trim()
                    if (ref.isEmpty() || ref.contains(":") || ref.startsWith("//") || ref == "/") continue
                    val rel = (if (ref.startsWith("/") || dir.isEmpty()) ref.trimStart('/') else "$dir/$ref").removePrefix("./")
                    if (rel.contains("..")) continue
                    val e = rel.substringAfterLast('.', "").lowercase()
                    if (e !in setOf("js", "jsx", "css", "mjs", "html", "json")) continue
                    if (files.none { it.rel == rel }) out.add(Problem("${f.rel} uses $rel but that file does not exist", rel, "referenced by ${f.rel} but missing"))
                }
            }
        }
        // a script that looks up an element the page does not have is the most common reason a generated app "does nothing"
        if (htmlTexts.isNotEmpty()) {
            val allHtml = htmlTexts.joinToString("\n") { it.second }
            for (f in files) {
                val ext = f.rel.substringAfterLast('.', "").lowercase()
                if (ext !in setOf("js", "jsx", "mjs") || f.rel.startsWith("server/") || f.rel.startsWith("src/")) continue
                val text = try { ws.read(f.rel) } catch (_: Throwable) { continue }
                val missing = LinkedHashSet<String>()
                for (re in idUse) for (m in re.findAll(text)) {
                    val id = m.groupValues[1]
                    if (id in htmlIds) continue
                    // ids that the script itself creates (innerHTML, el.id = ...) are fine
                    if (Regex("""id\s*=\s*["']?${Regex.escape(id)}\b""").containsMatchIn(text) || text.contains(".id = '$id'") || text.contains(".id = \"$id\"")) continue
                    missing.add(id)
                }
                if (missing.isNotEmpty() && allHtml.isNotBlank())
                    out.add(Problem("${f.rel} looks up element id(s) ${missing.take(4).joinToString { "#$it" }} that the page does not contain",
                        f.rel, "use ONLY these ids from the page: " + htmlIds.take(40).joinToString(", ") + ". Rewrite the script so every id it uses exists."))
            }
        }
        return out.distinctBy { it.path ?: it.message }
    }

    /** [verify] plus a real syntax check with Node.js when the Linux environment is installed. */
    suspend fun verifyDeep(ctx: Context, ws: Workspace): List<Problem> {
        val out = ArrayList(verify(ws))
        if (!linuxReady()) return out
        val js = ws.tree().filter { !it.dir && (it.rel.endsWith(".js") || it.rel.endsWith(".mjs")) && !it.rel.startsWith("node_modules") && !it.rel.startsWith("dist/") }.take(6)
        for (f in js) {
            if (out.any { it.path == f.rel }) continue
            val log = StringBuilder()
            val rc = try { TermSetup.runInRoot(ctx, "cd /workspace && node --check '" + f.rel.replace("'", "") + "' 2>&1", 60_000L, null, ws.root) { log.appendLine(it) } }
                     catch (e: CancellationException) { throw e } catch (_: Throwable) { 0 }
            if (rc != 0 && log.isNotBlank()) out.add(Problem("${f.rel} has a syntax error: " + log.lines().filter { it.isNotBlank() }.take(3).joinToString(" ").take(260),
                f.rel, "fix the syntax error and return the complete valid file"))
        }
        return out
    }

    private suspend fun buildWithNpm(ctx: Context, s: Settings, session: BuildSession, ws: Workspace, planned: List<Pair<String, String>>, written: List<LiveFile>, stackNote: String) {
        session.status = "Installing dependencies"; session.action = "Installing dependencies"
        session.term("$ npm install")
        var rc = try { TermSetup.runInRoot(ctx, "cd /workspace && npm install --no-audit --no-fund 2>&1", 12 * 60_000L, null, ws.root) { session.term(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { session.term("! ${e.message}"); -1 }
        if (rc != 0) { session.term("! npm install failed (code $rc). The project is saved; run it from the Claude Code terminal."); markFailed(session, "Installing dependencies"); return }
        for (attempt in 1..2) {
            session.status = "Running build"; session.action = "Running build"
            session.term("$ npm run build")
            val log = StringBuilder()
            rc = try { TermSetup.runInRoot(ctx, "cd /workspace && npm run build 2>&1", 8 * 60_000L, null, ws.root) { log.appendLine(it); session.term(it) } } catch (e: CancellationException) { throw e } catch (e: Throwable) { session.term("! ${e.message}"); -1 }
            if (rc == 0) { session.term("build finished: dist/ is ready"); return }
            if (attempt == 2) break
            val bad = Regex("((?:src|server)/[\\w./-]+\\.(?:jsx?|tsx?|css))").findAll(log).lastOrNull()?.groupValues?.get(1)
            if (bad == null) break
            session.action = "Fixing error"; session.status = "Fixing $bad"
            writeFile(s, session, ws, bad, "fix the build error", planned, written, stackNote, "npm run build failed:\n" + log.toString().takeLast(1500))
        }
        session.term("! the build still fails. Open the Claude Code terminal and run: npm run build")
        markFailed(session, "Running build")
    }

    private fun markFailed(session: BuildSession, label: String) {
        session.tasks.lastOrNull()?.state = TaskState.FAILED
        session.error = "$label did not finish. Your files are saved; the live preview may be missing until the build passes."
    }

    // ── Templates for React + Vite ───────────────────────────────────────────

    private fun viteScaffold(title: String, hasServer: Boolean): List<Pair<String, String>> {
        val safe = title.replace("\"", "'").replace("<", "").replace(">", "")
        val serverDeps = if (hasServer) ",\n    \"express\": \"^4.21.1\",\n    \"cors\": \"^2.8.5\"" else ""
        val serverScript = if (hasServer) ",\n    \"start\": \"node server/index.js\"" else ""
        return listOf(
            "package.json" to "{\n  \"name\": \"${ProjectStore.slug(title)}\",\n  \"private\": true,\n  \"version\": \"1.0.0\",\n  \"type\": \"module\",\n  \"scripts\": {\n    \"dev\": \"vite\",\n    \"build\": \"vite build\",\n    \"preview\": \"vite preview\"$serverScript\n  },\n  \"dependencies\": {\n    \"react\": \"^18.3.1\",\n    \"react-dom\": \"^18.3.1\"$serverDeps\n  },\n  \"devDependencies\": {\n    \"@vitejs/plugin-react\": \"^4.3.3\",\n    \"vite\": \"^5.4.10\"\n  }\n}\n",
            "vite.config.js" to "import { defineConfig } from 'vite'\nimport react from '@vitejs/plugin-react'\n\n// base './' so the built page also works when it is opened from a folder or inside ZENIQ's preview\nexport default defineConfig({ base: './', plugins: [react()] })\n",
            "index.html" to "<!DOCTYPE html>\n<html lang=\"en\">\n  <head>\n    <meta charset=\"UTF-8\" />\n    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\" />\n    <title>$safe</title>\n  </head>\n  <body>\n    <div id=\"root\"></div>\n    <script type=\"module\" src=\"/src/main.jsx\"></script>\n  </body>\n</html>\n",
            "src/main.jsx" to "import React from 'react'\nimport { createRoot } from 'react-dom/client'\nimport App from './App.jsx'\n\ncreateRoot(document.getElementById('root')).render(<App />)\n"
        )
    }

    private fun plainPackageJson(name: String) =
        "{\n  \"name\": \"${ProjectStore.slug(name)}\",\n  \"version\": \"1.0.0\",\n  \"private\": true,\n  \"scripts\": { \"start\": \"node server/index.js\" },\n  \"dependencies\": { \"express\": \"^4.21.1\", \"cors\": \"^2.8.5\" }\n}\n"
}

/** Small web lookup for the research step (DuckDuckGo HTML results). Returns (title, snippet) pairs; empty when offline. */
object WebLookup {
    suspend fun search(q: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val c = java.net.URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as java.net.HttpURLConnection
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36")
            c.connectTimeout = 8_000; c.readTimeout = 10_000
            val html = c.inputStream.bufferedReader().use { it.readText() }.take(400_000)
            val links = Regex("<a[^>]*class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
            val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).map { it.groupValues[1] }.toList()
            fun clean(x: String) = x.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace(Regex("\\s+"), " ").trim()
            val out = ArrayList<Pair<String, String>>()
            for ((i, m) in links.withIndex()) {
                val title = clean(m.groupValues[2])
                if (title.isNotBlank()) out.add(title.take(90) to clean(snippets.getOrNull(i).orEmpty()))
                if (out.size >= 4) break
            }
            out
        } catch (e: CancellationException) { throw e
        } catch (_: Throwable) { emptyList() }
    }
}
