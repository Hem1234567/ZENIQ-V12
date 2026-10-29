package com.astra.agent.code

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Real git (JGit, pure Java, no root and no git binary needed) for a project folder.
 * Supports: init, status, add, commit [-a] -m "msg", log, branch. Used by the Code tab terminal ("git ..." commands and buttons).
 */
object GitOps {
    private val token = Regex("\"([^\"]*)\"|'([^']*)'|(\\S+)")
    private val who = PersonIdent("ZENIQ", "zeniq@localhost")

    private fun args(cmd: String): List<String> =
        token.findAll(cmd.trim()).map { m -> m.groups[1]?.value ?: m.groups[2]?.value ?: m.groups[3]?.value ?: "" }.toList()

    private fun open(dir: File): Git? = try { if (File(dir, ".git").exists()) Git.open(dir) else null } catch (_: Exception) { null }

    /** [command] is the full text, e.g. `git commit -m "first"`. Never throws: errors come back as text. */
    fun run(command: String, dir: File): String {
        return try {
            if (System.getProperty("user.home").isNullOrEmpty()) System.setProperty("user.home", dir.absolutePath)
            val a = args(command).drop(1)
            val sub = a.firstOrNull() ?: return "usage: git init | status | add <path|.> | commit [-a] -m \"message\" | log | branch"
            if (sub == "init") {
                if (File(dir, ".git").exists()) return "Git repository already exists."
                Git.init().setDirectory(dir).call().close()
                if (!File(dir, ".gitignore").exists()) File(dir, ".gitignore").writeText(".zeniq/\n")
                return "Initialized empty git repository."
            }
            val git = open(dir) ?: return "Not a git repository. Run: git init"
            git.use { g ->
                when (sub) {
                    "status" -> {
                        val st = g.status().call()
                        val sb = StringBuilder()
                        fun sec(title: String, items: Set<String>) { if (items.isNotEmpty()) { sb.append(title).append(":\n"); items.sorted().forEach { sb.append("  ").append(it).append("\n") } } }
                        sec("Staged (new)", st.added); sec("Staged (changed)", st.changed); sec("Staged (removed)", st.removed)
                        sec("Modified", st.modified); sec("Deleted", st.missing); sec("Untracked", st.untracked); sec("Conflicts", st.conflicting)
                        if (sb.isEmpty()) "Nothing to commit, working tree clean." else sb.toString().trimEnd()
                    }
                    "add" -> {
                        val paths = a.drop(1).ifEmpty { return "usage: git add <path|.>" }
                        for (p in paths) {
                            g.add().addFilepattern(p).call()
                            if (p == ".") g.add().setUpdate(true).addFilepattern(".").call()
                        }
                        "Added: " + paths.joinToString(" ")
                    }
                    "commit" -> {
                        val all = a.contains("-a") || a.contains("-am")
                        var msg: String? = null
                        for (i in a.indices) if (a[i] == "-m" || a[i] == "-am") msg = a.getOrNull(i + 1)
                        if (msg.isNullOrBlank()) return "usage: git commit [-a] -m \"message\""
                        if (all) { g.add().addFilepattern(".").call(); g.add().setUpdate(true).addFilepattern(".").call() }
                        val st = g.status().call()
                        if (!st.hasUncommittedChanges()) return "Nothing to commit."
                        val c = g.commit().setMessage(msg).setAuthor(who).setCommitter(who).call()
                        "[" + c.name.take(7) + "] " + msg
                    }
                    "log" -> {
                        if (g.repository.resolve("HEAD") == null) return "No commits yet."
                        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        g.log().setMaxCount(20).call().joinToString("\n") { c -> c.name.take(7) + "  " + fmt.format(Date(c.commitTime * 1000L)) + "  " + c.shortMessage }
                    }
                    "branch" -> {
                        val cur = g.repository.branch
                        val names = g.branchList().call().map { it.name.removePrefix("refs/heads/") }
                        if (names.isEmpty()) "On branch $cur (no commits yet)" else names.sorted().joinToString("\n") { (if (it == cur) "* " else "  ") + it }
                    }
                    else -> "Unsupported git command: $sub (supported: init, status, add, commit, log, branch)"
                }
            }
        } catch (e: Throwable) { "git error: " + (e.message ?: e.javaClass.simpleName) }
    }
}
