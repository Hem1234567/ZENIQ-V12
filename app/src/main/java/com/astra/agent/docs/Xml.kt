package com.astra.agent.docs

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Small helpers shared by the Office writers (pure JVM). */
internal object Xml {
    /** Escapes text for XML and drops characters XML 1.0 does not allow (Word/Excel refuse the file otherwise). */
    fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c.code < 0x20 -> {}
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> {}
                c.code == 0xFFFE || c.code == 0xFFFF -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    const val HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
}

internal class ZipBuilder {
    private val bos = ByteArrayOutputStream()
    private val zip = ZipOutputStream(bos)
    fun add(path: String, content: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    fun addBytes(path: String, content: ByteArray) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(content)
        zip.closeEntry()
    }
    fun finish(): ByteArray { zip.close(); return bos.toByteArray() }
}
