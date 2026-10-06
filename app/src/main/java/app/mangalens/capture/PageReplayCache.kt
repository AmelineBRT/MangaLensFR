package app.mangalens.capture

import android.graphics.Rect
import app.mangalens.overlay.RenderBubble

/**
 * Short-lived replay memory for pages already translated in this reading session.
 * A scroll-back reuses the finished translation instead of starting OCR and
 * network translation again.
 */
class PageReplayCache(private val maxEntries: Int = 8) {

    private data class Entry(
        val thumb: IntArray,
        val bubbles: List<RenderBubble>,
    )

    private val entries = ArrayList<Entry>()

    @Synchronized
    fun put(thumb: IntArray, bubbles: List<RenderBubble>) {
        if (bubbles.isEmpty()) return
        entries.removeAll { sameBubbleFingerprint(it.bubbles, bubbles) }
        entries.add(0, Entry(thumb.copyOf(), bubbles))
        while (entries.size > maxEntries) entries.removeAt(entries.lastIndex)
    }

    @Synchronized
    fun get(current: IntArray, width: Int, height: Int): List<RenderBubble>? {
        val found = entries.firstOrNull { replayShift(it.thumb, current) != null } ?: return null
        val shift = replayShift(found.thumb, current) ?: return null
        entries.remove(found)
        entries.add(0, found)

        return found.bubbles.mapNotNull { b ->
            val box = Rect(b.box).apply { offset(0, shift) }
            if (box.right <= 0 || box.left >= width || box.bottom <= 0 || box.top >= height) {
                null
            } else {
                val balloon = b.balloon?.let { old ->
                    old.copy(box = Rect(old.box).apply { offset(0, shift) })
                }
                b.copy(box = box, balloon = balloon)
            }
        }
    }

    private fun sameBubbleFingerprint(a: List<RenderBubble>, b: List<RenderBubble>): Boolean =
        a.size == b.size && a.zip(b).all { (x, y) ->
            x.original.trim().equals(y.original.trim(), ignoreCase = true) &&
                x.translated.trim().equals(y.translated.trim(), ignoreCase = true)
        }

    /**
     * Align row brightness profiles. A decisive alignment means the current
     * viewport is the same content at a different vertical scroll offset.
     */
    private fun replayShift(base: IntArray, cur: IntArray): Int? {
        val n = kotlin.math.sqrt(base.size.toDouble()).toInt()
        if (n < 8 || cur.size != base.size || n * n != base.size) return null

        fun profile(a: IntArray): DoubleArray {
            val p = DoubleArray(n)
            for (y in 0 until n) {
                var sum = 0L
                val row = y * n
                for (x in 0 until n) sum += a[row + x]
                p[y] = sum.toDouble() / n
            }
            val mean = p.average()
            for (y in 0 until n) p[y] -= mean
            return p
        }

        val a = profile(base)
        val b = profile(cur)

        fun score(s: Int): Double {
            var total = 0.0
            var count = 0
            for (y in 0 until n) {
                val yy = y + s
                if (yy in 0 until n) {
                    total += kotlin.math.abs(a[y] - b[yy])
                    count++
                }
            }
            return if (count >= n * 0.65) total / count else Double.MAX_VALUE
        }

        val zero = score(0)
        if (zero < 2.8) return 0

        var bestShift = 0
        var best = zero
        for (s in -(n / 4)..(n / 4)) {
            if (s == 0) continue
            val value = score(s)
            if (value < best) {
                best = value
                bestShift = s
            }
        }
        if (bestShift == 0 || best >= zero * 0.62) {
            if (bestShift != 0) return null
        }

        // Row profiles alone are too easy to fool on mostly-white manga pages.
        // Confirm the aligned pixels as well, after removing a global brightness
        // offset caused by display dimming or capture exposure.
        val baseMean = base.average()
        val curMean = cur.average()
        var pixelTotal = 0.0
        var pixelCount = 0
        for (y in 0 until n) {
            val yy = y + bestShift
            if (yy !in 0 until n) continue
            for (x in 0 until n) {
                val a0 = base[y * n + x] - baseMean
                val b0 = cur[yy * n + x] - curMean
                pixelTotal += kotlin.math.abs(a0 - b0)
                pixelCount++
            }
        }
        if (pixelCount < n * n * 0.65) return null
        if (pixelTotal / pixelCount > 11.0) return null
        return bestShift
    }
}
