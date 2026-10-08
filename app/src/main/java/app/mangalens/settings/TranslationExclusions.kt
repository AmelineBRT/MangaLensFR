package app.mangalens.settings

import android.content.Context
import android.graphics.Rect

class TranslationExclusions(context: Context) {
    private val prefs = context.getSharedPreferences("mangalens_exclusions", Context.MODE_PRIVATE)

    fun get(width: Int, height: Int): List<Rect> {
        if (width <= 0 || height <= 0) return emptyList()
        return prefs.getStringSet("rects", emptySet()).orEmpty().mapNotNull { s ->
            val p = s.split(',').mapNotNull { it.toIntOrNull() }
            if (p.size != 4) null else Rect(p[0] * width / 1000, p[1] * height / 1000, p[2] * width / 1000, p[3] * height / 1000)
        }.filter { it.width() > 2 && it.height() > 2 }
    }

    fun add(rect: Rect, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val p = listOf(rect.left * 1000 / width, rect.top * 1000 / height, rect.right * 1000 / width, rect.bottom * 1000 / height)
        val raw = prefs.getStringSet("rects", emptySet()).toMutableSet()
        raw.add(p.joinToString(","))
        prefs.edit().putStringSet("rects", raw).apply()
    }

    fun clear() = prefs.edit().remove("rects").apply()
}
