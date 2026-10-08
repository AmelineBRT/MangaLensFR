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

    private val known = listOf(
        "weconics", "wecomics", "webtoon", "scanlation", "scan", "raw",
        "translated by", "translation by", "credits", "credit", "chapter by"
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
