package com.astra.agent.code

/**
 * Decides what kind of request the user made, WITHOUT calling any model.
 *  - CODE : "write a python function ..." -> model writes the code itself, no tools / no web search
 *  - APP  : "build a small todo app"      -> model writes files, ZENIQ zips them for download
 *  - DOC  : "make a PDF / Word / Excel ..." or edit an uploaded file (decided by DocIntent)
 *  - CHAT : everything else (normal chat / phone actions)
 */
enum class ReqIntent { CHAT, CODE, APP, DOC, SITE }

object CodeIntent {

    private val appNouns = "(app|application|website|web ?site|web ?app|webpage|web page|landing page|game|calculator|" +
        "todo|to-do|to do list|tool|dashboard|portfolio|tracker|timer|clock|quiz|converter|generator|planner|" +
        "notes app|chat ?bot|project|program|script|extension|api|server|bot|cli|ui|page)"
    private val buildVerbs = "(build|create|make|develop|generate|design|code up|write|craft|implement|prepare)"

    /** "build me a small calculator app", "create a todo web app", "make a snake game" */
    private val appRe = Regex(
        "\\b$buildVerbs\\b[^.\\n?!]{0,60}\\b$appNouns\\b", RegexOption.IGNORE_CASE)

    /** Explicit "give me the zip / full project / downloadable" */
    private val appHintRe = Regex(
        "\\b(zip|downloadable|full project|complete project|whole project|project files|multiple files|with files)\\b",
        RegexOption.IGNORE_CASE)

    private val codeRe = Regex(
        "\\b(code|coding|program|snippet|function|method|class|algorithm|script|regex|query|sql|" +
        "html|css|javascript|typescript|python|java|kotlin|swift|c\\+\\+|c#|golang|rust|php|ruby|bash|shell script|" +
        "react|node\\.?js|flutter|dart|json|yaml|xml|api endpoint|leetcode|debug|fix (this|my) (code|bug|error)|" +
        "refactor|unit test|compile|syntax|loop|array|recursion|variable)\\b", RegexOption.IGNORE_CASE)

    private val codeVerbRe = Regex(
        "\\b(write|give|show|generate|create|make|implement|code|fix|debug|convert|translate|explain|refactor|optimi[sz]e|complete|build)\\b",
        RegexOption.IGNORE_CASE)

    private val explicitWebRe = Regex(
        "\\b(search the web|google|look ?up online|on the web|online|latest|news|youtube|price of|weather|score)\\b", RegexOption.IGNORE_CASE)

    private val fenceRe = Regex("```")

    /** Words that mean the user wants the PHONE to act, never code. */
    private val phoneActionRe = Regex(
        "\\b(open (the )?(camera|browser|gallery|settings|whatsapp|youtube|maps)|take a (photo|picture|selfie)|" +
        "call |send (a )?(sms|message)|set (an? )?(alarm|timer)|screenshot|turn (on|off) )", RegexOption.IGNORE_CASE)

    fun classify(text: String): ReqIntent {
        val t = text.trim()
        if (t.isEmpty()) return ReqIntent.CHAT
        if (phoneActionRe.containsMatchIn(t) && !fenceRe.containsMatchIn(t)) return ReqIntent.CHAT
        if (isAppBuild(t)) return ReqIntent.APP
        if (isCode(t)) return ReqIntent.CODE
        return ReqIntent.CHAT
    }

    fun isAppBuild(t: String): Boolean {
        if (!appRe.containsMatchIn(t)) return false
        // "write a function" / "write a script to rename files" are plain code, not an app project
        val m = appRe.find(t)!!.value.lowercase()
        val plain = Regex("\\b(function|method|class|snippet|regex|query|script|program)\\b").containsMatchIn(m) &&
            !Regex("\\b(app|application|website|web ?site|web ?app|game|page|project)\\b").containsMatchIn(m)
        if (plain && !appHintRe.containsMatchIn(t)) return false
        return true
    }

    fun isCode(t: String): Boolean {
        if (fenceRe.containsMatchIn(t)) return true
        if (!codeRe.containsMatchIn(t)) return false
        // "search the web for the latest java news" is a web request, not a coding request
        if (explicitWebRe.containsMatchIn(t) && !codeVerbRe.containsMatchIn(t)) return false
        return true
    }

    /** Short slug for naming a project from the user's request. */
    fun projectName(request: String): String {
        val stop = setOf("a", "an", "the", "me", "my", "please", "small", "simple", "basic", "new", "for", "to", "of", "and",
            "build", "create", "make", "develop", "generate", "design", "write", "code", "up", "can", "you", "i", "want", "need",
            "using", "with", "in", "on", "that", "which", "app", "application", "project", "program", "full", "complete", "zip")
        val words = request.lowercase().replace(Regex("[^a-z0-9 ]"), " ").split(Regex("\\s+"))
            .filter { it.length > 1 && it !in stop }.take(3)
        val base = words.joinToString("-").ifBlank { "astra-app" }
        return base.take(32).trim('-').ifBlank { "astra-app" }
    }

    // ── System prompts ────────────────────────────────────────────────────────

    const val CODE_RULES = """
# Code answers
- You write the code YOURSELF from your own knowledge. Do NOT search the web, do NOT call any tool.
- Put every piece of code in a fenced block with its language, e.g. ```python ... ```.
- Give COMPLETE, working code (no "..." placeholders, no omitted parts). Add short comments.
- Before the code write one or two sentences; after the code add a short note on how to run it. Keep prose brief."""

    const val APP_RULES = """
# Building an app (output is packaged into a downloadable ZIP automatically)
- You write ALL the code YOURSELF. Do NOT search the web, do NOT call any tool.
- First line of your reply must be exactly:  Project: <short-kebab-case-name>
- Prefer a simple app that runs by just opening index.html (HTML + CSS + JavaScript) unless the user asked for another stack.
- If it is a small website or page, put the HTML, CSS and JavaScript together in ONE index.html file. Only use separate files when the project really needs more than one.
- Keep it small: 1 to 5 files. Every file must be COMPLETE and working. No placeholders.
- Give EVERY file in its own fenced block, and put the file path on the line directly above the block, exactly like this:

File: index.html
```html
<!DOCTYPE html>...
```

File: style.css
```css
body { ... }
```

- Use relative paths only (no leading slash). Link the files to each other correctly.
- After the files, add 2 or 3 lines on how to run the app. Do not repeat the code."""
}
