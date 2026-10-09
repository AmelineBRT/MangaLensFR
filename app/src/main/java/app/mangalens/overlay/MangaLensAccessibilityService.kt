package app.mangalens.overlay

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Hosts MangaLens's existing page-rendering view in Android's trusted
 * accessibility-overlay window layer. The view itself still paints the
 * translations and opaque white balloon masks; no screen-sized bitmap copy
 * or extra OCR pass is introduced.
 */
class MangaLensAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        private var connectedService: MangaLensAccessibilityService? = null

        fun attachOverlay(view: View): Boolean =
            connectedService?.attachPageView(view) ?: false

        fun detachOverlay(view: View) {
            connectedService?.detachPageView(view)
        }
    }

    private var windowManager: WindowManager? = null
    private var pageView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        connectedService = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private fun attachPageView(view: View): Boolean {
        val wm = windowManager ?: return false
        if (pageView === view) return true
        pageView?.let { old -> runCatching { wm.removeView(old) } }
        return try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                alpha = 1f
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            wm.addView(view, params)
            pageView = view
            true
        } catch (_: Exception) {
            pageView = null
            false
        }
    }

    private fun detachPageView(view: View) {
        if (pageView !== view) return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        } finally {
            pageView = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        pageView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Exception) {
            }
        }
        pageView = null
        windowManager = null
        if (connectedService === this) connectedService = null
        super.onDestroy()
    }
}
