package io.github.yunusemreakdeniz.privacydisplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.hardware.input.InputManager
import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * The privacy layer above every app. Touches always pass through. Two strengths:
 * - ghost: a dark tint plus fine horizontal lines, like a privacy filter. Readable up close and
 *   head on; dimmer and lower contrast, so it is hard to read from the side or from further away;
 * - curtain: pure black (#000000, OLED friendly, no blur) when someone else is looking.
 * Darkening is instant; lightening fades over [FADE_IN_MS].
 */
class ShieldOverlay(private val context: Context) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: GhostView? = null
    var visible = false
        private set

    fun attach() {
        if (view != null) return
        val v = GhostView(context).apply { visibility = View.GONE }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { alpha = maxAlpha() }
        runCatching { wm.addView(v, params) }.onSuccess { view = v }
    }

    /** Curtain: as dark as Android allows for a touch-through layer. Instant. */
    fun curtain(intensity: Float) = set(level = intensity / maxAlpha(), lines = false, animate = false)

    /** Ghost filter: [strength] 0..1 of the tint, with the line pattern. */
    fun ghost(strength: Float) = set(level = strength, lines = true, animate = true)

    fun clear(animate: Boolean = true) = set(level = 0f, lines = false, animate = animate)

    private fun set(level: Float, lines: Boolean, animate: Boolean) {
        val v = view ?: return
        val target = level.coerceIn(0f, 1f)
        visible = target > 0f
        v.animate().cancel()
        if (visible) {
            v.lines = lines
            v.visibility = View.VISIBLE
        }
        // Darker is always instant (safety first); lighter fades.
        if (!animate || target >= v.alpha) {
            v.alpha = target
            if (!visible) v.visibility = View.GONE
            return
        }
        v.animate().alpha(target).setDuration(FADE_IN_MS)
            .withEndAction { if (!visible) v.visibility = View.GONE }
            .start()
    }

    fun detach() {
        view?.let { v -> v.animate().cancel(); runCatching { wm.removeView(v) } }
        view = null
        visible = false
    }

    /** Android 12+ only lets touches pass through overlays up to a system maximum (usually 0.8). */
    private fun maxAlpha(): Float = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
    } else {
        0.8f
    }

    /** Black tint, plus every other pixel row darker when [lines] is on. */
    private class GhostView(context: Context) : View(context) {
        var lines = false
            set(value) {
                if (field != value) {
                    field = value
                    invalidate()
                }
            }

        private val linePaint = Paint().apply {
            // A 1x2 bitmap: one dark row, one clear row, repeated over the whole screen.
            val stripe = Bitmap.createBitmap(1, 2, Bitmap.Config.ARGB_8888).apply {
                setPixel(0, 0, Color.argb(255, 0, 0, 0))
                setPixel(0, 1, Color.argb(0, 0, 0, 0))
            }
            shader = BitmapShader(stripe, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        }

        override fun onDraw(canvas: Canvas) {
            if (lines) {
                // Tint for overall dimness, lines for the filter look and lower contrast.
                canvas.drawColor(Color.argb(150, 0, 0, 0))
                canvas.drawPaint(linePaint)
            } else {
                canvas.drawColor(Color.BLACK)
            }
        }
    }

    companion object {
        const val FADE_IN_MS = 180L
    }
}
