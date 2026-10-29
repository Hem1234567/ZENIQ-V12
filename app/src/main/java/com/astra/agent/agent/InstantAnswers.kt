package com.astra.agent.agent

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Answers that need no model at all: greetings, thanks, the time, the date and simple arithmetic.
 * They appear instantly (even offline, even before a model is loaded).
 */
object InstantAnswers {
    private val IC = RegexOption.IGNORE_CASE
    private val greeting = Regex("^(hi+|hello+|hey+|hola|namaste|vanakkam|good (morning|afternoon|evening|night))( there| zeniq| everyone)?$", IC)
    private val thanks = Regex("^(thanks|thank you|thank u|thx|ty|thanks a lot|thank you so much|thanks so much)( zeniq)?$", IC)
    private val bye = Regex("^(bye|goodbye|see you|see ya|good night)( zeniq)?$", IC)
    private val timeQ = Regex("^(what('s| is)? (the )?(current )?time( now| right now)?|what time is it( now| right now)?|current time|time now|tell me the time)$", IC)
    private val dateQ = Regex("^(what('s| is)? (the )?(today'?s )?date( today)?|what day is (it|today)( today)?|today'?s date|what is today|which day is today)$", IC)
    private val whoQ = Regex("^(who are you|what('s| is) your name|what are you|who made you)$", IC)
    private val mathQ = Regex("^(?:what(?:'s| is)|calculate|calc|compute|solve|evaluate)?\\s*([-+*/%^().,\\dxX\u00D7\u00F7 ]{3,60})\\s*(?:=|equals)?$", IC)

    fun answer(raw: String, now: LocalDateTime = LocalDateTime.now()): String? {
        val t = raw.trim().trimEnd('?', '!', '.', ' ').replace(Regex("\\s+"), " ")
        if (t.isEmpty() || t.length > 70) return null
        if (greeting.matches(t)) {
            val low = t.lowercase()
            if (low.startsWith("good night")) return "Good night! Sleep well."
            return if (low.startsWith("good ")) t.split(" ").take(2).joinToString(" ").replaceFirstChar { it.uppercase() } + "! How can I help?"
            else "Hi! I'm ZENIQ. What can I do for you?"
        }
        if (thanks.matches(t)) return "You're welcome!"
        if (bye.matches(t)) return "Bye! I'm here whenever you need me."
        if (timeQ.matches(t)) return "It's " + now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)) + "."
        if (dateQ.matches(t)) return "Today is " + now.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)) + "."
        if (whoQ.matches(t)) return "I'm ZENIQ, your personal AI assistant. I can chat, write documents and websites, work with your files, code, and control your phone."
        val m = mathQ.find(t)
        if (m != null) {
            val expr = m.groupValues[1]
            if (expr.any { it.isDigit() } && expr.any { it in "+-*/%^x\u00D7\u00F7" }) {
                val v = try { Calc(expr).parse() } catch (_: Exception) { null } ?: return null
                if (v.isNaN() || v.isInfinite()) return "That is not a number I can show."
                return expr.trim() + " = " + fmt(v)
            }
        }
        return null
    }

    private fun fmt(v: Double): String {
        if (v == Math.floor(v) && Math.abs(v) < 1e15) return String.format(Locale.ENGLISH, "%,.0f", v).replace(",", "")
        return java.math.BigDecimal(v).round(java.math.MathContext(10)).stripTrailingZeros().toPlainString()
    }

    /** + - * / % ^ and brackets, with the usual order of operations. */
    private class Calc(src: String) {
        private val s = src.replace("x", "*").replace("X", "*").replace('\u00D7', '*').replace('\u00F7', '/').replace(",", "").replace(" ", "")
        private var i = 0
        fun parse(): Double? { val v = expr(); return if (i == s.length) v else null }
        private fun expr(): Double {
            var v = term()
            while (i < s.length && (s[i] == '+' || s[i] == '-')) { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }
            return v
        }
        private fun term(): Double {
            var v = power()
            while (i < s.length && (s[i] == '*' || s[i] == '/' || s[i] == '%')) {
                val op = s[i++]; val r = power()
                v = when (op) { '*' -> v * r; '/' -> v / r; else -> v % r }
            }
            return v
        }
        private fun power(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; Math.pow(b, power()) } else b }
        private fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            if (i < s.length && s[i] == '+') { i++; return unary() }
            return atom()
        }
        private fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); if (i >= s.length || s[i] != ')') throw IllegalStateException(); i++; return v }
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (st == i) throw IllegalStateException("number expected")
            return s.substring(st, i).toDouble()
        }
    }
}
