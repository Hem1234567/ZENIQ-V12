package com.astra.agent.code

import com.astra.agent.Settings
import com.astra.agent.llm.Completion

/** The AI side of ZENIQ Code: reads the project, and answers or proposes file changes that you review as diffs. */
object CodeAgent {
    class Result(val summary: String, val changes: List<FileChange>)

    const val SYSTEM = "You are ZENIQ Code, an expert programmer working inside the user's project. You are shown the project's files.\n" +
        "- To create or change a file, output a line \"### FILE: relative/path\" followed by a fenced code block with the COMPLETE new content of that file " +
        "(never partial snippets and never \"...\"). Only output files that change or are new.\n" +
        "- Start with one or two sentences saying what you did or found. If the user only asks a question (explain, why, how), answer in plain text and output no files.\n" +
        "- Keep the code simple and working. Follow the project's existing style and file names. Never delete user data."

    val modes = listOf("Edit", "Generate", "Explain", "Debug", "Refactor")

    private fun task(mode: String, instruction: String, current: String?, selection: String?): String {
        val what = when (mode) {
            "Generate" -> "Create new code for this request"
            "Explain" -> "Explain the code clearly and simply (no file changes)"
            "Debug" -> "Find the cause of the problem and fix it"
            "Refactor" -> "Refactor for readability and structure without changing behaviour"
            else -> "Make this change"
        }
        return "$what: $instruction" + (if (current != null) "\nThe user has \"$current\" open." else "") +
            (if (!selection.isNullOrBlank()) "\nSelected code:\n```\n${selection.take(1500)}\n```" else "")
    }

    suspend fun run(s: Settings, ws: Workspace, mode: String, instruction: String, current: String?, selection: String?): Result {
        val cloud = Completion.usesCloud(s)
        val outTokens = if (cloud) 4000 else 1500
        val budget = if (cloud) 24_000 else (((s.nCtx - outTokens - 500) * 3).coerceIn(2500, 9000))
        val user = task(mode, instruction, current, selection) + "\n\n" + ws.contextFor(instruction, current, budget)
        val answer = Completion.ask(s, SYSTEM, user, outTokens, 0.2f)
        val changes = if (mode == "Explain") emptyList() else CodeEdits.parse(answer)
        return Result(if (changes.isEmpty()) answer else CodeEdits.summary(answer).ifBlank { "Proposed ${changes.size} file change(s)." }, changes)
    }
}
