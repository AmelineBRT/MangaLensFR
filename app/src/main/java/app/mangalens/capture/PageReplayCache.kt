package app.mangalens.capture

import android.graphics.Rect
import app.mangalens.ocr.Balloon
import app.mangalens.overlay.RenderBubble
import app.mangalens.pipeline.TranslatePipeline

/**
 * Short-lived replay memory for pages already translated in this reading session.
 *
 * A scroll-back must not start OCR/translation again just because the viewport
 * moved. We remember the clean capture thumb together with the finished
 * RenderBubbles, then align the old page vertically against the new viewport.
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
        val copy = thumb.copyOf()
        entries.removeAll { sameBubbleFingerprint(it.bubbles, bubbles) }
        entries.add(0, Entry(copy, bubbles))
        while (entries.size > maxEntries) entries.removeAt(entries.lastIndex)
    }

    /**
     * Returns a translated copy when the current clean frame matches a
     * remembered page. The returned geometry is shifted into the current
     * viewport, so the caller can paint it immediately.
     */
    @Synchronized
    fun get(current: IntArray, width: Int, height: Int): List<RenderBubble>? {
        if (current.isEmpty()) return null
        val found = entries.firstOrNull { entry ->
            val shift = TranslatePipeline.replayShift(entry.thumb, current)
            shift != null
        } ?: return null

        val shift = TranslatePipeline.replayShift(found.thumb, current) ?: return null
        entries.remove(found)
        entries.add(0, found)

        val dy = shift
        return found.bubbles.mapNotNull { b ->
            val box = Rect(b.box).apply { offset(0, dy) }
            if (box.right <= 0 || box.left >= width || box.bottom <= 0 || box.top >= height) {
                null
            } else {
                val balloon = b.balloon?.let { old ->
                    old.copy(box = Rect(old.box).apply { offset(0, dy) })
                }
                b.copy(box = box, balloon = balloon)
            }
        }
    }

    private fun sameBubbleFingerprint(a: List<RenderBubble>, b: List<RenderBubble>): Boolean {
        if (a.size != b.size) return false
        return a.zip(b).all { (x, y) ->
            x.original.trim().equals(y.original.trim(), ignoreCase = true) &&
                x.translated.trim().equals(y.translated.trim(), ignoreCase = true)
        }
    }
}
