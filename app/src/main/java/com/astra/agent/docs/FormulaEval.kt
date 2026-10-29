package com.astra.agent.docs

/**
 * Tiny spreadsheet formula evaluator. Excel recalculates formulas itself, but phone file viewers and
 * previews only show the value stored in the file, so the writer stores the computed result too.
 * Supports + - * / ^ & % comparisons, cell refs, ranges and the common functions below.
 * Anything it does not understand makes [eval] return null (the cell is then left for Excel to calculate).
 */
internal class FormulaEval(
    private val cellRaw: (row: Int, col: Int) -> String?,   // 1-based
) {
    private class Unsupported : RuntimeException()
    private class Range(val values: List<Any?>)

    private val memo = HashMap<Long, Any?>()
    private val busy = HashSet<Long>()

    /** Returns Double, String, Boolean or null when the value could not be computed. */
    fun cell(row: Int, col: Int): Any? {
        val key = row.toLong() * 100000L + col
        if (memo.containsKey(key)) return memo[key]
        if (!busy.add(key)) return null              // circular reference
        val raw = cellRaw(row, col)
        val v: Any? = try {
            when {
                raw == null || raw.isBlank() -> null
                raw.startsWith("=") && raw.length > 1 -> evalFormula(raw.substring(1))
                else -> XlsxNumbers.parse(raw)?.value ?: raw
            }
        } catch (_: Unsupported) { null } catch (_: ArithmeticException) { null } catch (_: RuntimeException) { null }
        busy.remove(key)
        memo[key] = v
        return v
    }

    fun eval(formula: String): Any? = try { evalFormula(formula.removePrefix("=")) } catch (_: RuntimeException) { null }

    // ── Parser ───────────────────────────────────────────────────────────────

    private class Tok(val type: Char, val text: String)   // n number, s string, i identifier/ref, o operator

    private fun tokenize(src: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < src.length) {
            val c = src[i]
            when {
                c.isWhitespace() -> i++
                c == '"' -> {
                    val sb = StringBuilder(); i++
                    while (i < src.length) {
                        if (src[i] == '"') { if (i + 1 < src.length && src[i + 1] == '"') { sb.append('"'); i += 2; continue } else break }
                        sb.append(src[i]); i++
                    }
                    if (i >= src.length) throw Unsupported()
                    i++; out.add(Tok('s', sb.toString()))
                }
                c.isDigit() || (c == '.' && i + 1 < src.length && src[i + 1].isDigit()) -> {
                    val m = Regex("^\\d*\\.?\\d+(?:[eE][-+]?\\d+)?|^\\d+\\.").find(src.substring(i)) ?: throw Unsupported()
                    out.add(Tok('n', m.value)); i += m.value.length
                }
                c.isLetter() || c == '$' || c == '_' || c == '\'' -> {
                    val m = Regex("^(?:\\$?[A-Za-z]{1,3}\\$?\\d+(?::\\$?[A-Za-z]{1,3}\\$?\\d+)?|[A-Za-z_][A-Za-z0-9_.]*)").find(src.substring(i)) ?: throw Unsupported()
                    out.add(Tok('i', m.value)); i += m.value.length
                    if (i < src.length && src[i] == '!') throw Unsupported()     // other sheets
                }
                c in "+-*/^&%(),;" -> { out.add(Tok('o', c.toString())); i++ }
                c == '<' || c == '>' || c == '=' -> {
                    val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
                    if (two == "<=" || two == ">=" || two == "<>") { out.add(Tok('o', two)); i += 2 } else { out.add(Tok('o', c.toString())); i++ }
                }
                else -> throw Unsupported()
            }
        }
        return out
    }

    private var toks: List<Tok> = emptyList()
    private var pos = 0

    private fun evalFormula(f: String): Any? {
        val savedToks = toks; val savedPos = pos
        try {
            toks = tokenize(f); pos = 0
            val v = comparison()
            if (pos != toks.size) throw Unsupported()
            return scalar(v)
        } finally { toks = savedToks; pos = savedPos }
    }

    private fun peek(): Tok? = toks.getOrNull(pos)
    private fun isOp(s: String) = peek()?.let { it.type == 'o' && it.text == s } == true
    private fun take(): Tok = toks[pos++]

    private fun comparison(): Any? {
        var l = concat()
        while (true) {
            val t = peek() ?: break
            if (t.type != 'o' || t.text !in listOf("=", "<>", "<", ">", "<=", ">=")) break
            take()
            val r = concat()
            l = compare(scalar(l), scalar(r), t.text)
        }
        return l
    }

    private fun concat(): Any? {
        var l = additive()
        while (isOp("&")) { take(); val r = additive(); l = text(scalar(l)) + text(scalar(r)) }
        return l
    }

    private fun additive(): Any? {
        var l = term()
        while (isOp("+") || isOp("-")) {
            val op = take().text; val r = term()
            l = if (op == "+") num(l) + num(r) else num(l) - num(r)
        }
        return l
    }

    private fun term(): Any? {
        var l = power()
        while (isOp("*") || isOp("/")) {
            val op = take().text; val r = power()
            l = if (op == "*") num(l) * num(r) else { val d = num(r); if (d == 0.0) throw ArithmeticException(); num(l) / d }
        }
        return l
    }

    private fun power(): Any? {
        var l = unary()
        while (isOp("^")) { take(); val r = unary(); l = Math.pow(num(l), num(r)) }
        return l
    }

    private fun unary(): Any? {
        if (isOp("-")) { take(); return -num(unary()) }
        if (isOp("+")) { take(); return unary() }
        var v = primary()
        while (isOp("%")) { take(); v = num(v) / 100.0 }
        return v
    }

    private fun primary(): Any? {
        val t = peek() ?: throw Unsupported()
        when (t.type) {
            'n' -> { take(); return t.text.toDouble() }
            's' -> { take(); return t.text }
            'o' -> {
                if (t.text == "(") { take(); val v = comparison(); if (!isOp(")")) throw Unsupported(); take(); return v }
                throw Unsupported()
            }
            else -> {
                take()
                val id = t.text
                if (isOp("(")) {
                    take()
                    val args = ArrayList<Any?>()
                    if (isOp(")")) take()
                    else {
                        while (true) {
                            args.add(comparison())
                            if (isOp(",") || isOp(";")) { take(); continue }
                            if (isOp(")")) { take(); break }
                            throw Unsupported()
                        }
                    }
                    return call(id.uppercase(), args)
                }
                if (id.equals("TRUE", true)) return true
                if (id.equals("FALSE", true)) return false
                return ref(id)
            }
        }
    }

    // ── Values ───────────────────────────────────────────────────────────────

    private fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters.uppercase()) n = n * 26 + (ch - 'A' + 1)
        return n
    }

    private val refRe = Regex("^\\$?([A-Za-z]{1,3})\\$?(\\d+)$")

    private fun ref(id: String): Any? {
        val parts = id.split(":")
        if (parts.size == 1) {
            val m = refRe.find(parts[0]) ?: throw Unsupported()
            return cell(m.groupValues[2].toInt(), colIndex(m.groupValues[1]))
        }
        val a = refRe.find(parts[0]) ?: throw Unsupported()
        val b = refRe.find(parts[1]) ?: throw Unsupported()
        val r1 = minOf(a.groupValues[2].toInt(), b.groupValues[2].toInt()); val r2 = maxOf(a.groupValues[2].toInt(), b.groupValues[2].toInt())
        val c1 = minOf(colIndex(a.groupValues[1]), colIndex(b.groupValues[1])); val c2 = maxOf(colIndex(a.groupValues[1]), colIndex(b.groupValues[1]))
        if ((r2 - r1 + 1).toLong() * (c2 - c1 + 1) > 200_000L) throw Unsupported()
        val vals = ArrayList<Any?>()
        for (r in r1..r2) for (c in c1..c2) vals.add(cell(r, c))
        return Range(vals)
    }

    private fun scalar(v: Any?): Any? = if (v is Range) throw Unsupported() else v

    private fun num(v: Any?): Double = when (v) {
        null -> 0.0
        is Double -> v
        is Boolean -> if (v) 1.0 else 0.0
        is String -> XlsxNumbers.parse(v)?.value ?: v.trim().toDoubleOrNull() ?: throw Unsupported()
        else -> throw Unsupported()
    }

    private fun text(v: Any?): String = when (v) {
        null -> ""
        is Double -> XlsxNumbers.format(v)
        is Boolean -> if (v) "TRUE" else "FALSE"
        else -> v.toString()
    }

    private fun compare(l: Any?, r: Any?, op: String): Boolean {
        val c: Int = if (l is String && r is String) l.compareTo(r, ignoreCase = true)
        else if ((l is String && l.toDoubleOrNull() == null) || (r is String && r.toDoubleOrNull() == null)) text(l).compareTo(text(r), ignoreCase = true)
        else num(l).compareTo(num(r))
        return when (op) { "=" -> c == 0; "<>" -> c != 0; "<" -> c < 0; ">" -> c > 0; "<=" -> c <= 0; else -> c >= 0 }
    }

    private fun flat(args: List<Any?>): List<Any?> = args.flatMap { if (it is Range) it.values else listOf(it) }
    private fun numbersOf(args: List<Any?>): List<Double> =
        args.flatMap { a ->
            if (a is Range) a.values.filterIsInstance<Double>()
            else listOf(num(a))
        }

    private fun call(name: String, args: List<Any?>): Any? = when (name) {
        "SUM" -> numbersOf(args).sum()
        "AVERAGE" -> { val n = numbersOf(args); if (n.isEmpty()) throw ArithmeticException() else n.sum() / n.size }
        "MIN" -> numbersOf(args).minOrNull() ?: 0.0
        "MAX" -> numbersOf(args).maxOrNull() ?: 0.0
        "COUNT" -> flat(args).count { it is Double }.toDouble()
        "COUNTA" -> flat(args).count { it != null && it != "" }.toDouble()
        "ABS" -> Math.abs(num(args.getOrNull(0)))
        "SQRT" -> { val v = num(args.getOrNull(0)); if (v < 0) throw ArithmeticException() else Math.sqrt(v) }
        "INT" -> Math.floor(num(args.getOrNull(0)))
        "POWER" -> Math.pow(num(args.getOrNull(0)), num(args.getOrNull(1)))
        "MOD" -> { val d = num(args.getOrNull(1)); if (d == 0.0) throw ArithmeticException() else { val n = num(args.getOrNull(0)); n - d * Math.floor(n / d) } }
        "ROUND" -> {
            val d = num(args.getOrNull(1)).toInt()
            java.math.BigDecimal(num(args.getOrNull(0)).toString()).setScale(d, java.math.RoundingMode.HALF_UP).toDouble()
        }
        "IF" -> {
            if (args.size < 2) throw Unsupported()
            val cond = args[0]
            val truthy = when (cond) { is Boolean -> cond; null -> false; else -> num(cond) != 0.0 }
            if (truthy) args[1] else if (args.size > 2) args[2] else false
        }
        "AND" -> flat(args).all { it == true || (it is Double && it != 0.0) }
        "OR" -> flat(args).any { it == true || (it is Double && it != 0.0) }
        "CONCATENATE", "CONCAT" -> flat(args).joinToString("") { text(it) }
        "LEN" -> text(args.getOrNull(0)).length.toDouble()
        else -> throw Unsupported()
    }
}

/** Recognises numbers, percentages and currency written as text, so they become real numeric cells. */
internal object XlsxNumbers {
    enum class Kind(val numFmtId: Int) { GENERAL(0), PCT0(9), PCT2(10), INT_COMMA(3), DEC_COMMA(4), USD(164), EUR(165), GBP(166), INR(167) }
    class Parsed(val value: Double, val kind: Kind)

    private val plain = Regex("^[-+]?\\d+(\\.\\d+)?$")
    private val pct = Regex("^[-+]?\\d+(\\.\\d+)?\\s?%$")
    private val comma = Regex("^[-+]?\\d{1,3}(,\\d{3})+(\\.\\d+)?$")
    private val cur = Regex("^([-+]?)([$€£₹])\\s?([-+]?)(\\d{1,3}(,\\d{3})+|\\d+)(\\.\\d+)?$")

    fun parse(raw: String): Parsed? {
        val s = raw.trim()
        if (s.isEmpty() || s.length > 24) return null
        if (plain.matches(s)) {
            if (s.startsWith("+")) return null                                   // phone-like
            val digits = s.trimStart('-')
            if (digits.length > 1 && digits.startsWith("0") && !digits.startsWith("0.")) return null   // 007, zip codes
            if (digits.substringBefore('.').length > 15) return null            // IDs / card-like numbers
            return Parsed(s.toDouble(), Kind.GENERAL)
        }
        if (pct.matches(s)) {
            val body = s.replace("%", "").trim()
            return Parsed(body.toDouble() / 100.0, if (body.contains('.')) Kind.PCT2 else Kind.PCT0)
        }
        if (comma.matches(s)) {
            return Parsed(s.replace(",", "").toDouble(), if (s.contains('.')) Kind.DEC_COMMA else Kind.INT_COMMA)
        }
        val m = cur.find(s)
        if (m != null) {
            val neg = m.groupValues[1] == "-" || m.groupValues[3] == "-"
            val v = (m.groupValues[4].replace(",", "") + m.groupValues[6]).toDouble()
            val kind = when (m.groupValues[2]) { "$" -> Kind.USD; "€" -> Kind.EUR; "£" -> Kind.GBP; else -> Kind.INR }
            return Parsed(if (neg) -v else v, kind)
        }
        return null
    }

    fun format(d: Double): String {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return d.toLong().toString()
        return java.math.BigDecimal.valueOf(d).stripTrailingZeros().toPlainString()
    }
}
