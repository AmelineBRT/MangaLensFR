package app.mangalens.ocr

import android.graphics.Rect

/**
 * Rejects likely scan credits/watermarks before they become translation regions.
 *
 * This is deliberately conservative: being near the bottom of a page is not
 * enough. A candidate must also be unusually small, isolated, and outside a
 * detected dialogue balloon. Known scan-credit strings get an extra signal.
 */
object WatermarkFilter {
    // v0.11.0 follow-up: OCR text is retained globally; only watermark candidates are removed.

    private val known = listOf(
        "weconics", "wecomics", "webtoon", "scanlation", "scan", "raw",
        "translated by", "translation by", "credits", "credit", "chapter by",
        "oui comics", "ouicomics", "oui-comics", "japanese to english",
        "japanese > english", "japanese → english", "jp to en",
        "translated from japanese", "translation from japanese",
        "日本語から英語", "日本語→英語", "日英翻訳"
    )

    fun filter(lines: List<OcrLine>, balloons: List<Rect>, pageWidth: Int, pageHeight: Int): List<OcrLine> {
        if (lines.isEmpty()) return lines
        val strokes = lines.map { stroke(it) }.sorted()
        val median = strokes[strokes.size / 2].coerceAtLeast(6)
        return lines.filterNot { line ->
            isLikely(line, lines, balloons, pageWidth, pageHeight, median)
        }
    }

    fun isLikely(
        line: OcrLine,
        all: List<OcrLine>,
        balloons: List<Rect>,
        pageWidth: Int,
        pageHeight: Int,
        medianStroke: Int,
    ): Boolean {
        val text = Script.clean(line.text).lowercase()
        if (text.length < 3) return false
        // Some scan brands are printed directly over artwork (not at the
        // page edge and sometimes inside a large white cloud). Exact known
        // brand phrases must be discarded before balloon association, or a
        // false "balloon" could bleach the artwork beneath the watermark.
        val compact = text.filter { it.isLetterOrDigit() }
        if (text.replace(Regex("\\s+"), " ").contains("oui comics") || compact.contains("ouicomics")) return true
        // Explicit bilingual translator stamps are noise even when printed over
        // a balloon or artwork; they must not become a giant, nonsensical card.
        val explicitStamp = listOf(
            "japanese to english", "japanese > english", "japanese → english",
            "jp to en", "translated from japanese", "translation from japanese",
            "日本語から英語", "日本語→英語", "日英翻訳"
        )
        if (explicitStamp.any { text.contains(it) || compact.contains(it.filter { ch -> ch.isLetterOrDigit() }) }) return true

        // Scan credits are often split into separate OCR boxes, e.g. one box
        // reads "Japanese" and the next reads "to English". Match nearby
        // fragments as a group so neither half is translated as manga text.
        val stampFragment = listOf("japanese", "english", "translated", "translation", "scanlation")
            .any { text.contains(it) }
        if (stampFragment) {
            val nearbyStampFragment = all.any { other ->
                other !== line &&
                    listOf("japanese", "english", "translated", "translation", "scanlation")
                        .any { Script.clean(other.text).lowercase().contains(it) } &&
                    Rect.intersects(expanded(line.box, (medianStroke * 8).coerceAtLeast(36)), other.box)
            }
            if (nearbyStampFragment) return true
            // Standalone, small "Japanese"/"English" labels are also common
            // in bilingual scan watermarks. Keep normal-sized dialogue intact.
            if ((text.contains("japanese") || text.contains("english")) &&
                stroke(line) <= (medianStroke * 0.9f).toInt().coerceAtLeast(5) &&
                all.none { other ->
                    other !== line && Rect.intersects(expanded(line.box, stroke(line) * 3), other.box)
                }
            ) return true
        }
        if (balloons.any { containsMostly(it, line.box) }) return false

        val stroke = stroke(line)
        val tiny = stroke <= (medianStroke * 0.62f).toInt().coerceAtLeast(4)
        val edgeX = line.box.left <= pageWidth * 0.035f || line.box.right >= pageWidth * 0.965f
        val edgeY = line.box.top <= pageHeight * 0.035f || line.box.bottom >= pageHeight * 0.965f
        val nearEdge = edgeX || edgeY
        val isolated = all.none { other ->
            other !== line && Rect.intersects(expanded(line.box, stroke * 3), other.box)
        }
        val creditWord = known.any { text.replace(Regex("\\s+"), " ").contains(it) }
        val shortLatinCredit = text.count { it.isLetter() } in 3..28 &&
            text.count { it.isLetter() && it.code < 128 } >= text.count { it.isLetter() } * 0.8

        // Known credits are allowed to be slightly larger; unknown candidates
        // need the stronger small + edge + isolated combination.
        if (creditWord && nearEdge && isolated) return true
        return tiny && nearEdge && isolated && shortLatinCredit
    }

    private fun stroke(l: OcrLine): Int =
        if (l.vertical) l.box.width() else l.box.height()

    private fun expanded(r: Rect, pad: Int): Rect =
        Rect(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad)

    private fun containsMostly(container: Rect, box: Rect): Boolean {
        val ix = maxOf(0, minOf(container.right, box.right) - maxOf(container.left, box.left))
        val iy = maxOf(0, minOf(container.bottom, box.bottom) - maxOf(container.top, box.top))
        val area = box.width().toLong() * box.height().toLong()
        return area > 0 && ix.toLong() * iy.toLong() >= area * 0.75
    }
}
