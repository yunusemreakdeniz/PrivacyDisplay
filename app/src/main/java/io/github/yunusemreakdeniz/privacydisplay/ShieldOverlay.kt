package io.github.yunusemreakdeniz.privacydisplay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.input.InputManager
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * The privacy curtain: a full-screen pure black (#000000, OLED friendly, no blur) window that
 * never takes touches, so the app underneath keeps working. Hiding is instant for safety;
 * showing the screen again fades in over [FADE_IN_MS] so it is easy on the eyes.
 */
class ShieldOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    var visible = false
        private set

    fun attach(intensity: Float) {
        if (view != null) return
        val alpha = maxAlpha(intensity)
        val v = View(context).apply {
            setBackgroundColor(Color.BLACK)
            this.alpha = 0f
            visibility = View.GONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { this.alpha = alpha }
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    fun setIntensity(intensity: Float) {
        val v = view ?: return
        val params = v.layoutParams as? WindowManager.LayoutParams ?: return
        params.alpha = maxAlpha(intensity)
        runCatching { wm.updateViewLayout(v, params) }
    }

    /** Curtain down: no animation, it must cover the screen right away. */
    fun show() {
        val v = view ?: return
        v.animate().cancel()
        v.visibility = View.VISIBLE
        v.alpha = 1f
        visible = true
    }

    /** Curtain up: a short fade. */
    fun hide(animate: Boolean = true) {
        val v = view ?: return
        visible = false
        v.animate().cancel()
        if (!animate) {
            v.alpha = 0f
            v.visibility = View.GONE
            return
        }
        v.animate().alpha(0f).setDuration(FADE_IN_MS).withEndAction { if (!visible) v.visibility = View.GONE }.start()
    }

    fun detach() {
        view?.let { v -> v.animate().cancel(); runCatching { wm.removeView(v) } }
        view = null
        visible = false
    }

    /** Android 12+ only lets touches pass through overlays up to a system maximum (usually 0.8). */
    private fun maxAlpha(intensity: Float): Float {
        val cap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
        } else {
            1f
        }
        return intensity.coerceAtMost(cap)
    }

    companion object {
        const val FADE_IN_MS = 180L
    }
}
