package app.mangalens.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Owns the three overlay windows: the untouchable full-screen result layer, the
 * draggable floating button with its status pill, and the long-press quick menu.
 * All methods must be called from the main thread.
 */
class OverlayController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onTranslateNow()
        /** Translate ordinary OCR dialogue outside detected balloons/rectangles for one pass. */
        fun onTranslateOutsideNow()
        fun onTogglePause()
        fun onToggleMode()
        fun onPeek()
        /** Forget this series' glossary, cast and story so far, and start fresh. */
        fun onNewSeries()
        fun onOpenSettings()
        fun onClearExclusions()
        fun onAddExclusion(rect: Rect)
        fun onStopRequested()
        fun isPaused(): Boolean
        fun isAutoMode(): Boolean
    }

    private val wm = context.getSystemService(WindowManager::class.java)
    val bubbleView = BubbleOverlayView(context)

    private var controls: LinearLayout? = null
    private var button: FloatingButtonView? = null
    private var pill: TextView? = null
    private var menu: LinearLayout? = null
    private var selection: View? = null
    private var controlsLp: WindowManager.LayoutParams? = null
    private var attached = false
    private val hidePill = Runnable { pill?.visibility = View.GONE }

    /**
     * Called on the main thread whenever the screen area the controls
     * occupy changes: the pill comes or goes or is re-measured, the button
     * is dragged, the menu opens or closes. The capture loop masks that
     * area out of its comparisons and must learn of every change before
     * the frame that shows it is drawn — which is why the row's own layout
     * pass reports it, ahead of that frame's draw.
     */
    var onFootprintChanged: (() -> Unit)? = null

    private fun dp(v: Float): Int = (v * context.resources.displayMetrics.density).toInt()

    fun attach() {
        if (attached) return
        val bubbleLp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        bubbleLp.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            bubbleLp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(bubbleView, bubbleLp)
        buildControls()
        attached = true
    }

    fun detach() {
        if (!attached) return
        dismissMenu()
        finishSelection()
        runCatching { wm.removeView(bubbleView) }
        controls?.let { runCatching { wm.removeView(it) } }
        controls = null
        onFootprintChanged = null
        attached = false
    }

    /**
     * Screen regions occupied by MangaLens's own floating controls. Excluded
     * from OCR so the app never translates its own 文A button ("Sentence A").
     */
    fun overlayExclusions(): List<android.graphics.Rect> {
        val out = ArrayList<android.graphics.Rect>(2)
        val lp = controlsLp
        val row = controls
        if (lp != null && row != null) {
            val w = if (row.width > 0) row.width else dp(220f)
            val h = if (row.height > 0) row.height else dp(52f)
            val m = dp(6f)
            out.add(android.graphics.Rect(lp.x - m, lp.y - m, lp.x + w + m, lp.y + h + m))
            menu?.let { mv ->
                val mw = if (mv.width > 0) mv.width else dp(220f)
                val mh = if (mv.height > 0) mv.height else dp(280f)
                out.add(android.graphics.Rect(lp.x - m, lp.y + dp(58f) - m, lp.x + mw + m, lp.y + dp(58f) + mh + m))
            }
        }
        return out
    }

    fun setStatus(text: String?, autoHideMs: Long = 0) {
        val p = pill ?: return
        p.removeCallbacks(hidePill)
        if (text == null) {
            p.visibility = View.GONE
            return
        }
        p.text = text
        p.visibility = View.VISIBLE
        if (autoHideMs > 0) p.postDelayed(hidePill, autoHideMs)
    }

    fun setPaused(paused: Boolean) {
        button?.setPaused(paused)
    }

    /** Sweeps the busy ring on the button while a translation pass runs. */
    fun setBusy(busy: Boolean) {
        button?.setBusy(busy)
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildControls() {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val btn = FloatingButtonView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f))
            elevation = dp(4f).toFloat()
        }
        val status = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 2
            setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
            background = rounded(0xD0202233.toInt(), 14f)
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginStart = dp(6f)
            layoutParams = lp
        }
        row.addView(btn)
        row.addView(status)
        row.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (l != oldL || t != oldT || r != oldR || b != oldB) onFootprintChanged?.invoke()
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Screen-space coords so overlayExclusions() matches the capture.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(8f)
        lp.y = dp(170f)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        var downTime = 0L
        val longPress = Runnable {
            moved = true
            showMenu()
        }
        btn.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = lp.x; startY = lp.y
                    moved = false
                    downTime = System.currentTimeMillis()
                    v.postDelayed(longPress, 480)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || abs(dx) > dp(6f) || abs(dy) > dp(6f)) {
                        if (!moved) {
                            moved = true
                            v.removeCallbacks(longPress)
                        }
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        controls?.let { c -> runCatching { wm.updateViewLayout(c, lp) } }
                        onFootprintChanged?.invoke()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.removeCallbacks(longPress)
                    if (!moved && System.currentTimeMillis() - downTime < 450) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        btn.playTapPulse()
                        listener.onTogglePause()
                    }
                }
                MotionEvent.ACTION_CANCEL -> v.removeCallbacks(longPress)
            }
            true
        }

        wm.addView(row, lp)
        controls = row
        button = btn
        pill = status
        controlsLp = lp
    }


    private fun beginSelection() {
        dismissMenu()
        if (selection != null) return
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0xE06C5CE7.toInt(); style=Paint.Style.STROKE; strokeWidth=dp(3f).toFloat() }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color=0x306C5CE7; style=Paint.Style.FILL }
        val view = object : View(context) {
            var sx=0f; var sy=0f; var ex=0f; var ey=0f
            override fun onDraw(canvas: android.graphics.Canvas) {
                canvas.drawColor(0x24000000)
                if (sx!=ex || sy!=ey) {
                    val l=minOf(sx,ex); val t=minOf(sy,ey); val rr=maxOf(sx,ex); val b=maxOf(sy,ey)
                    canvas.drawRect(l,t,rr,b,fill); canvas.drawRect(l,t,rr,b,stroke)
                }
            }
            override fun onTouchEvent(e: MotionEvent): Boolean {
                when(e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { sx=e.x; sy=e.y; ex=sx; ey=sy; invalidate() }
                    MotionEvent.ACTION_MOVE -> { ex=e.x; ey=e.y; invalidate() }
                    MotionEvent.ACTION_UP -> {
                        ex=e.x; ey=e.y
                        val r=Rect(minOf(sx,ex).toInt(),minOf(sy,ey).toInt(),maxOf(sx,ex).toInt(),maxOf(sy,ey).toInt())
                        finishSelection()
                        if(r.width()>=dp(8) && r.height()>=dp(8)) listener.onAddExclusion(r)
                    }
                    MotionEvent.ACTION_CANCEL -> finishSelection()
                }
                return true
            }
        }
        val lp=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT)
        lp.gravity=Gravity.TOP or Gravity.START
        wm.addView(view,lp); selection=view
    }

    private fun finishSelection() {
        val v=selection ?: return
        runCatching { wm.removeView(v) }
        selection=null
        onFootprintChanged?.invoke()
    }
    private fun showMenu() {
        if (menu != null) return
        val lpControls = controlsLp ?: return
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(0xF01B1C2E.toInt(), 12f)
            elevation = dp(8f).toFloat()
            setPadding(0, dp(4f), 0, dp(4f))
        }

        fun item(label: String, action: () -> Unit) {
            col.addView(TextView(context).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                setOnClickListener {
                    dismissMenu()
                    action()
                }
            })
        }

        item("⚡  Traduire maintenant") { listener.onTranslateNow() }
        item("🔎  Traduire aussi hors bulles/rectangles") { listener.onTranslateOutsideNow() }
        item(if (listener.isPaused()) "▶  Reprendre la traduction en direct" else "⏸  Mettre en pause") { listener.onTogglePause() }
        item(if (listener.isAutoMode()) "✋  Passer au mode toucher pour traduire" else "🔄  Passer au mode automatique") {
            listener.onToggleMode()
        }
        item("👁  Voir l’original (4 s)") { listener.onPeek() }
        item("🚫  Exclure une zone du scan") { beginSelection() }
        item("🧹  Effacer les zones exclues") { listener.onClearExclusions() }
        item("📖  Nouvelle série — oublier les noms mémorisés") { listener.onNewSeries() }
        item("⚙  Réglages") { listener.onOpenSettings() }
        item("✕  Arrêter la traduction") { listener.onStopRequested() }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = lpControls.x
        lp.y = lpControls.y + dp(58f)

        col.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                dismissMenu()
                true
            } else false
        }

        wm.addView(col, lp)
        menu = col
        onFootprintChanged?.invoke()
    }

    fun dismissMenu() {
        val open = menu ?: return
        runCatching { wm.removeView(open) }
        menu = null
        onFootprintChanged?.invoke()
    }
}
