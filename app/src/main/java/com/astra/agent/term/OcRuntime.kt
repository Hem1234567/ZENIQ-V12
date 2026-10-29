package com.astra.agent.term

// Pure Kotlin (no Android imports).
// OpenClaw refuses to start on a Node.js whose built-in SQLite is broken or whose TLS root list is outdated. Its supported runtimes are
// Node 24.22.1 or newer on the 24 line, and Node 26.12.1 or newer. The small Alpine (musl) system used for Claude Code only offers Node 22,
// and Node publishes no musl ARM64 build, so Open Claw runs in its own Ubuntu (glibc) system with the official Node build.

object OcRuntime {
    const val NODE_MIN_24 = "24.22.1"
    const val NODE_MIN_26 = "26.12.1"

    private val ver = Regex("""v?(\d+)\.(\d+)\.(\d+)""")

    fun parse(v: String?): List<Int>? {
        val m = ver.find(v ?: return null) ?: return null
        return listOf(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
    }

    private fun atLeast(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until 3) { if (a[i] != b[i]) return a[i] > b[i] }
        return true
    }

    /** The exact rule of OpenClaw's engines field: ">=24.22.1 <25 || >=26.12.1". */
    fun nodeOk(v: String?): Boolean {
        val p = parse(v) ?: return false
        return (p[0] == 24 && atLeast(p, parse(NODE_MIN_24)!!)) || (p[0] >= 26 && atLeast(p, parse(NODE_MIN_26)!!))
    }

    private val sumLine = Regex("""^([0-9a-fA-F]{64})\s+\*?(\S+)\s*$""")

    /** All (file name, sha256) pairs of a SHASUMS256.txt / SHA256SUMS file. */
    fun parseSums(text: String): List<Pair<String, String>> =
        text.lines().mapNotNull { sumLine.find(it.trim())?.let { m -> m.groupValues[2] to m.groupValues[1].lowercase() } }

    /** The official Node.js tarball for ARM64 Linux (glibc) with a version OpenClaw supports, from a SHASUMS256.txt. */
    fun pickNode(shasums: String): Pair<String, String>? {
        val re = Regex("""^node-v(\d+\.\d+\.\d+)-linux-arm64\.tar\.gz$""")
        return parseSums(shasums).filter { re.matches(it.first) && nodeOk(it.first) }
            .maxByOrNull { (parse(it.first)!!).let { p -> p[0] * 1_000_000 + p[1] * 1_000 + p[2] } }
    }

    /** The newest Ubuntu 24.04 base system for ARM64 (a .tar.gz) from the release SHA256SUMS file. */
    fun pickUbuntu(sums: String): Pair<String, String>? {
        val re = Regex("""^ubuntu-base-24\.04\.(\d+)-base-arm64\.tar\.gz$""")
        return parseSums(sums).filter { re.matches(it.first) }.maxByOrNull { re.find(it.first)!!.groupValues[1].toInt() }
    }

    /** Parses the version out of `openclaw --version` ("OpenClaw 2026.10.8 (abc)"), ignoring other numbers in the output. */
    fun openclawVersion(out: String): String? =
        Regex("""(?i)openclaw\s+v?(\d+\.\d+\.\d+)""").find(out)?.groupValues?.get(1) ?: Regex("""\b(\d{4}\.\d+\.\d+)\b""").find(out)?.groupValues?.get(1)
}
