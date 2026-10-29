package com.astra.agent.render

enum class Tk { KEYWORD, STRING, NUMBER, COMMENT, FUNCTION, TYPE, TAG, ATTR, PROPERTY, ANNOT, OPERATOR }

data class Tok(val start: Int, val end: Int, val type: Tk)

/** Small dependency-free syntax tokenizer. Good enough for all the common languages a chat assistant prints. */
object Highlight {

    private val plainLangs = setOf("text", "txt", "plaintext", "output", "console", "log", "markdown", "md", "diff", "csv")
    private val hashComment = setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb", "toml", "dockerfile",
        "makefile", "r", "perl", "pl", "powershell", "ps1", "ini", "properties", "conf", "env")
    private val dashComment = setOf("sql", "lua", "haskell", "hs", "mysql", "postgresql", "plsql")
    private val markupLangs = setOf("html", "htm", "xml", "svg", "xhtml", "vue", "jsp", "plist")
    private val cssLangs = setOf("css", "scss", "sass", "less")
    private val jsonLangs = setOf("json", "jsonc", "json5")
    private val tripleQuoteLangs = setOf("python", "py", "kotlin", "kt", "scala", "swift", "java", "groovy", "")
    private val backtickLangs = setOf("javascript", "js", "jsx", "typescript", "ts", "tsx", "go", "golang", "sql", "mjs", "vue", "")

    private val keywords: Set<String> = (
        // js / ts
        "var let const function return if else for while do switch case break continue new this class extends super import export from default " +
        "async await try catch finally throw typeof instanceof in of delete void yield static get set interface type enum implements " +
        "public private protected abstract readonly namespace declare as is keyof " +
        // python
        "def lambda pass with elif except raise global nonlocal assert del and or not None True False self print match " +
        // java / kotlin / c# / swift / go / rust / dart
        "fun val object companion override open sealed data when is by init constructor package internal lateinit suspend inline out " +
        "int long short byte float double boolean char string bool void final synchronized volatile transient native throws " +
        "struct func guard defer where let mut impl trait use mod pub crate fn loop unsafe move ref dyn match " +
        "chan fallthrough goto " +
        "using namespace virtual template typename operator friend auto register extern sizeof typedef union signed unsigned " +
        // literals
        "true false null nil undefined NaN Infinity " +
        // shell / sql
        "echo then fi done esac elif exit export source alias " +
        "select insert update delete create table drop alter from where join left right inner outer group order by having limit offset " +
        "values into set primary key foreign references index unique distinct union all like between exists not null default " +
        "SELECT INSERT UPDATE DELETE CREATE TABLE DROP ALTER FROM WHERE JOIN LEFT RIGHT INNER OUTER GROUP ORDER BY HAVING LIMIT OFFSET " +
        "VALUES INTO SET PRIMARY KEY FOREIGN REFERENCES INDEX UNIQUE DISTINCT UNION ALL LIKE BETWEEN EXISTS NOT NULL DEFAULT AND OR AS ON IN IS"
        ).split(" ").filter { it.isNotEmpty() }.toSet()

    /** Words that are commonly identifiers in everyday text; skipped for unlabeled blocks to avoid false colours. */
    private val builtinTypes = setOf("String", "Int", "Long", "Double", "Float", "Boolean", "List", "Map", "Set", "Array", "Object", "Number",
        "Promise", "Date", "Error", "Exception", "Console", "Math", "JSON", "Optional", "Unit", "Any", "Nothing")

    fun tokenize(code: String, langRaw: String): List<Tok> {
        val lang = langRaw.trim().lowercase()
        if (lang in plainLangs || code.length > 30_000) return emptyList()
        val out = ArrayList<Tok>()
        when {
            lang in markupLangs -> markup(code, 0, code.length, out)
            lang in cssLangs -> css(code, 0, code.length, out)
            lang in jsonLangs -> json(code, out)
            else -> general(code, 0, code.length, lang, out)
        }
        return out
    }

    // ── helpers ──────────────────────────────────────────────────────────────
    private fun isIdStart(c: Char) = c.isLetter() || c == '_' || c == '$'
    private fun isId(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

    /** Returns end index of a quoted string starting at i (handles \ escapes). Unterminated strings end at the line break. */
    private fun stringEnd(s: String, i: Int, end: Int, q: Char, multiline: Boolean): Int {
        var j = i + 1
        while (j < end) {
            val c = s[j]
            if (c == '\\') { j += 2; continue }
            if (c == q) return j + 1
            if (c == '\n' && !multiline) return j
            j++
        }
        return end
    }

    private fun numberEnd(s: String, i: Int, end: Int): Int {
        var j = i
        if (s[j] == '0' && j + 1 < end && (s[j + 1] == 'x' || s[j + 1] == 'X' || s[j + 1] == 'b' || s[j + 1] == 'B')) {
            j += 2
            while (j < end && (s[j].isLetterOrDigit() || s[j] == '_')) j++
            return j
        }
        while (j < end && (s[j].isDigit() || s[j] == '_')) j++
        if (j + 1 < end && s[j] == '.' && s[j + 1].isDigit()) { j++; while (j < end && (s[j].isDigit() || s[j] == '_')) j++ }
        if (j < end && (s[j] == 'e' || s[j] == 'E') && j + 1 < end && (s[j + 1].isDigit() || ((s[j + 1] == '-' || s[j + 1] == '+') && j + 2 < end && s[j + 2].isDigit()))) {
            j += 2; while (j < end && s[j].isDigit()) j++
        }
        while (j < end && (s[j] == 'f' || s[j] == 'F' || s[j] == 'L' || s[j] == 'u' || s[j] == 'U' || s[j] == 'd')) j++
        return j
    }

    // ── general (C-like, python, shell, sql, ...) ────────────────────────────
    private fun general(s: String, from: Int, end: Int, lang: String, out: MutableList<Tok>) {
        val hash = lang in hashComment
        val dash = lang in dashComment
        val slash = !hash && !dash || lang == "powershell"
        var i = from
        while (i < end) {
            val c = s[i]
            when {
                // comments
                slash && c == '/' && i + 1 < end && s[i + 1] == '/' -> {
                    var j = s.indexOf('\n', i); if (j < 0 || j > end) j = end
                    out.add(Tok(i, j, Tk.COMMENT)); i = j
                }
                slash && c == '/' && i + 1 < end && s[i + 1] == '*' -> {
                    var j = s.indexOf("*/", i + 2); j = if (j < 0 || j + 2 > end) end else j + 2
                    out.add(Tok(i, j, Tk.COMMENT)); i = j
                }
                hash && c == '#' -> {
                    var j = s.indexOf('\n', i); if (j < 0 || j > end) j = end
                    out.add(Tok(i, j, Tk.COMMENT)); i = j
                }
                dash && c == '-' && i + 1 < end && s[i + 1] == '-' -> {
                    var j = s.indexOf('\n', i); if (j < 0 || j > end) j = end
                    out.add(Tok(i, j, Tk.COMMENT)); i = j
                }
                !hash && !dash && lang in setOf("c", "cpp", "c++", "h", "hpp", "objc", "csharp", "cs") && c == '#' &&
                    (i == 0 || s[i - 1] == '\n') -> {
                    var j = s.indexOf('\n', i); if (j < 0 || j > end) j = end
                    out.add(Tok(i, j, Tk.ANNOT)); i = j
                }
                // strings
                (c == '"' || c == '\'') && i + 2 < end && s[i + 1] == c && s[i + 2] == c && lang in tripleQuoteLangs -> {
                    var j = s.indexOf("" + c + c + c, i + 3); j = if (j < 0 || j + 3 > end) end else j + 3
                    out.add(Tok(i, j, Tk.STRING)); i = j
                }
                c == '"' -> { val j = stringEnd(s, i, end, c, false); out.add(Tok(i, j, Tk.STRING)); i = j }
                c == '\'' -> {
                    // rust lifetimes / apostrophes in prose: only treat as string if a closing quote is close on the same line
                    val j = stringEnd(s, i, end, c, false)
                    if (j - i >= 2 && j <= end && s[j - 1] == c) { out.add(Tok(i, j, Tk.STRING)); i = j } else i++
                }
                c == '`' && lang in backtickLangs -> { val j = stringEnd(s, i, end, c, true); out.add(Tok(i, j, Tk.STRING)); i = j }
                // annotations / decorators
                c == '@' && i + 1 < end && isIdStart(s[i + 1]) -> {
                    var j = i + 1; while (j < end && (isId(s[j]) || s[j] == '.')) j++
                    out.add(Tok(i, j, Tk.ANNOT)); i = j
                }
                // shell variables
                lang in hashComment && c == '$' && i + 1 < end && (isIdStart(s[i + 1]) || s[i + 1] == '{') -> {
                    var j = i + 1
                    if (s[j] == '{') { while (j < end && s[j] != '}') j++; j = minOf(j + 1, end) } else while (j < end && isId(s[j])) j++
                    out.add(Tok(i, j, Tk.PROPERTY)); i = j
                }
                // numbers
                c.isDigit() && (i == from || !isId(s[i - 1])) -> { val j = numberEnd(s, i, end); out.add(Tok(i, j, Tk.NUMBER)); i = j }
                // identifiers
                isIdStart(c) -> {
                    var j = i + 1; while (j < end && isId(s[j])) j++
                    val w = s.substring(i, j)
                    var k = j; while (k < end && (s[k] == ' ' || s[k] == '\t')) k++
                    val call = k < end && s[k] == '('
                    val afterDot = i > from && s[i - 1] == '.'
                    when {
                        w in keywords && !afterDot -> out.add(Tok(i, j, Tk.KEYWORD))
                        call -> out.add(Tok(i, j, Tk.FUNCTION))
                        w[0].isUpperCase() && (w.length > 1 && (w.any { it.isLowerCase() } || w in builtinTypes)) -> out.add(Tok(i, j, Tk.TYPE))
                        w.length > 1 && w.all { it.isUpperCase() || it.isDigit() || it == '_' } && w.any { it.isUpperCase() } -> out.add(Tok(i, j, Tk.NUMBER))
                    }
                    i = j
                }
                c == '=' || c == '+' || c == '-' || c == '*' || c == '/' || c == '<' || c == '>' || c == '!' || c == '&' || c == '|' || c == '%' || c == '^' || c == '~' || c == '?' -> {
                    var j = i + 1
                    while (j < end && (s[j] == '=' || s[j] == '>' || s[j] == '<' || s[j] == '&' || s[j] == '|' || s[j] == '+' || s[j] == '-')) j++
                    out.add(Tok(i, j, Tk.OPERATOR)); i = j
                }
                else -> i++
            }
        }
    }

    // ── JSON ─────────────────────────────────────────────────────────────────
    private fun json(s: String, out: MutableList<Tok>) {
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            when {
                c == '"' -> {
                    val j = stringEnd(s, i, n, '"', false)
                    var k = j; while (k < n && s[k].isWhitespace()) k++
                    out.add(Tok(i, j, if (k < n && s[k] == ':') Tk.PROPERTY else Tk.STRING)); i = j
                }
                c == '/' && i + 1 < n && s[i + 1] == '/' -> { var j = s.indexOf('\n', i); if (j < 0) j = n; out.add(Tok(i, j, Tk.COMMENT)); i = j }
                c.isDigit() || (c == '-' && i + 1 < n && s[i + 1].isDigit()) -> { val j = numberEnd(s, if (c == '-') i + 1 else i, n); out.add(Tok(i, j, Tk.NUMBER)); i = j }
                c.isLetter() -> {
                    var j = i; while (j < n && s[j].isLetter()) j++
                    if (s.substring(i, j) in setOf("true", "false", "null")) out.add(Tok(i, j, Tk.KEYWORD))
                    i = j
                }
                else -> i++
            }
        }
    }

    // ── CSS ──────────────────────────────────────────────────────────────────
    private fun css(s: String, from: Int, end: Int, out: MutableList<Tok>) {
        var i = from
        var depth = 0
        while (i < end) {
            val c = s[i]
            when {
                c == '/' && i + 1 < end && s[i + 1] == '*' -> {
                    var j = s.indexOf("*/", i + 2); j = if (j < 0 || j + 2 > end) end else j + 2
                    out.add(Tok(i, j, Tk.COMMENT)); i = j
                }
                c == '"' || c == '\'' -> { val j = stringEnd(s, i, end, c, false); out.add(Tok(i, j, Tk.STRING)); i = j }
                c == '{' -> { depth++; i++ }
                c == '}' -> { if (depth > 0) depth--; i++ }
                c == '@' -> { var j = i + 1; while (j < end && (isId(s[j]) || s[j] == '-')) j++; out.add(Tok(i, j, Tk.KEYWORD)); i = j }
                c == '#' && depth > 0 -> { var j = i + 1; while (j < end && s[j].isLetterOrDigit()) j++; out.add(Tok(i, j, Tk.NUMBER)); i = j }
                c.isDigit() && depth > 0 -> {
                    var j = i; while (j < end && (s[j].isDigit() || s[j] == '.')) j++
                    while (j < end && (s[j].isLetter() || s[j] == '%')) j++
                    out.add(Tok(i, j, Tk.NUMBER)); i = j
                }
                (isIdStart(c) || c == '-') && depth > 0 -> {
                    var j = i; while (j < end && (isId(s[j]) || s[j] == '-')) j++
                    var k = j; while (k < end && s[k] == ' ') k++
                    if (j > i && k < end && s[k] == ':') out.add(Tok(i, j, Tk.PROPERTY))
                    else if (k < end && s[k] == '(') out.add(Tok(i, j, Tk.FUNCTION))
                    i = maxOf(j, i + 1)
                }
                (c == '.' || c == '#') && depth == 0 -> {
                    var j = i + 1; while (j < end && (isId(s[j]) || s[j] == '-')) j++
                    out.add(Tok(i, j, Tk.TYPE)); i = j
                }
                isIdStart(c) && depth == 0 -> {
                    var j = i; while (j < end && (isId(s[j]) || s[j] == '-')) j++
                    out.add(Tok(i, j, Tk.TAG)); i = j
                }
                c == ':' && depth == 0 -> {
                    var j = i + 1; while (j < end && (s[j] == ':' || isId(s[j]) || s[j] == '-')) j++
                    out.add(Tok(i, j, Tk.KEYWORD)); i = j
                }
                else -> i++
            }
        }
    }

    // ── HTML / XML (with embedded <script> and <style>) ──────────────────────
    private fun markup(s: String, from: Int, end: Int, out: MutableList<Tok>) {
        var i = from
        while (i < end) {
            if (s.startsWith("<!--", i)) {
                var j = s.indexOf("-->", i + 4); j = if (j < 0 || j + 3 > end) end else j + 3
                out.add(Tok(i, j, Tk.COMMENT)); i = j; continue
            }
            if (s[i] == '<' && i + 1 < end && (s[i + 1].isLetter() || s[i + 1] == '/' || s[i + 1] == '!' || s[i + 1] == '?')) {
                var j = i + 1
                if (s[j] == '/' || s[j] == '!' || s[j] == '?') j++
                val nameStart = j
                while (j < end && (isId(s[j]) || s[j] == '-' || s[j] == ':' || s[j] == '.')) j++
                val name = s.substring(nameStart, j).lowercase()
                out.add(Tok(i, j, Tk.TAG))
                // attributes up to '>'
                while (j < end && s[j] != '>') {
                    val c = s[j]
                    when {
                        c == '"' || c == '\'' -> { val e = stringEnd(s, j, end, c, true); out.add(Tok(j, e, Tk.STRING)); j = e }
                        isIdStart(c) || c == '@' || c == ':' -> {
                            var e = j + 1; while (e < end && (isId(s[e]) || s[e] == '-' || s[e] == ':' || s[e] == '.')) e++
                            out.add(Tok(j, e, Tk.ATTR)); j = e
                        }
                        else -> j++
                    }
                }
                if (j < end) { out.add(Tok(j - (if (j > 0 && s[j - 1] == '/') 1 else 0), j + 1, Tk.TAG)) }
                j = minOf(j + 1, end)
                i = j
                val opening = s[nameStart - 1] != '/'
                if (opening && (name == "script" || name == "style")) {
                    var close = s.indexOf("</$name", i, ignoreCase = true)
                    if (close < 0 || close > end) close = end
                    if (name == "script") general(s, i, close, "js", out) else css(s, i, close, out)
                    i = close
                }
                continue
            }
            if (s[i] == '&') {
                var j = i + 1; while (j < end && j - i < 10 && (s[j].isLetterOrDigit() || s[j] == '#')) j++
                if (j < end && s[j] == ';') { out.add(Tok(i, j + 1, Tk.NUMBER)); i = j + 1; continue }
            }
            i++
        }
    }
}
