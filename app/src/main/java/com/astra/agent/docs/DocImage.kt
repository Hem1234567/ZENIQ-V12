package com.astra.agent.docs

/** A picture the user attached (always JPEG: the app re-encodes attachments). */
class DocImage(val name: String, val bytes: ByteArray, val w: Int, val h: Int) {
    val caption: String get() = name.substringBeforeLast('.').replace('_', ' ').replace('-', ' ').trim().ifBlank { "Image" }

    /** Size that fits [maxW] x [maxH] (any unit) and keeps the aspect ratio. */
    fun fit(maxW: Double, maxH: Double): Pair<Double, Double> {
        val ar = w.toDouble() / h.coerceAtLeast(1)
        var cw = maxW; var ch = cw / ar
        if (ch > maxH) { ch = maxH; cw = ch * ar }
        return Pair(cw, ch)
    }

    companion object {
        /** Reads the pixel size of a JPEG without Android: walks the markers up to the first "start of frame". */
        fun jpegSize(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 4 || (b[0].toInt() and 0xFF) != 0xFF || (b[1].toInt() and 0xFF) != 0xD8) return null
            var i = 2
            while (i + 9 < b.size) {
                if ((b[i].toInt() and 0xFF) != 0xFF) { i++; continue }
                val m = b[i + 1].toInt() and 0xFF
                if (m == 0xFF) { i++; continue }
                if (m in 0xD0..0xD9 || m == 0x01) { i += 2; continue }
                val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
                if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    val h = ((b[i + 5].toInt() and 0xFF) shl 8) or (b[i + 6].toInt() and 0xFF)
                    val w = ((b[i + 7].toInt() and 0xFF) shl 8) or (b[i + 8].toInt() and 0xFF)
                    return if (w > 0 && h > 0) Pair(w, h) else null
                }
                i += 2 + len
            }
            return null
        }

        fun of(name: String, jpeg: ByteArray): DocImage? = jpegSize(jpeg)?.let { DocImage(name, jpeg, it.first, it.second) }
    }
}

/** Makes sure every attached picture ends up somewhere in the document, even if the model did not mention it. */
object ImagePlacement {
    /**
     * Pictures the model placed itself (![caption](image:N)) stay where they are. Pictures it left out are added:
     * [slides] = true gives each one its own slide (title + picture); otherwise the first goes right after the title
     * and the rest at the end.
     */
    fun place(blocks: List<Block>, images: List<DocImage>, slides: Boolean): List<Block> {
        if (images.isEmpty()) return blocks.filter { it !is ImageBlock }
        val used = blocks.filterIsInstance<ImageBlock>().map { it.index }.filter { it in images.indices }.toSet()
        val missing = images.indices.filter { it !in used }
        val cleaned = blocks.filter { it !is ImageBlock || it.index in images.indices }.toMutableList()
        if (missing.isEmpty()) return cleaned
        if (slides) {
            for (i in missing) { cleaned.add(Heading(2, listOf(Run(images[i].caption.replaceFirstChar { it.uppercase() })))); cleaned.add(ImageBlock(i, images[i].caption)) }
            return cleaned
        }
        val firstH = cleaned.indexOfFirst { it is Heading }
        var insertAt = if (firstH >= 0) firstH + 1 else 0
        // right after the title and its introduction paragraph (if there is one)
        if (insertAt < cleaned.size && cleaned[insertAt] is Para) insertAt++
        cleaned.add(insertAt, ImageBlock(missing.first(), images[missing.first()].caption))
        for (i in missing.drop(1)) cleaned.add(ImageBlock(i, images[i].caption))
        return cleaned
    }
}
