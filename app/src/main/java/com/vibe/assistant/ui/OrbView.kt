package com.vibe.assistant.ui

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The living orb: three soft colour blobs drifting inside a glow, blended with
 * "screen" so they mix like light. Colour follows Vibe's state, size follows the
 * voice — your mic level while listening, Vibe's own level while speaking.
 *
 * Idle states redraw at ~15 fps so the always-on bubble costs almost no battery.
 */
class OrbView(context: Context, private val compact: Boolean = false) : View(context) {
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SCREEN }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val start = SystemClock.uptimeMillis()
    private val cur = IntArray(3) { palette(VState.OFF)[it] }
    private var level = 0f
    private var spin = 0f
    private var lastFrame = 0L

    /** Draw a state other than the live one (used on the setup screen). */
    var forcedState: VState? = null

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0.016f else ((now - lastFrame) / 1000f).coerceAtMost(0.1f)
        lastFrame = now
        val t = (now - start) / 1000f
        val st = forcedState ?: VibeBus.state.value

        val target = palette(st)
        for (i in 0..2) cur[i] = blend(cur[i], target[i], 0.07f)

        val raw = when (st) {
            VState.LISTENING -> VibeBus.micLevel
            VState.SPEAKING -> VibeBus.outLevel
            else -> 0f
        }
        level += (raw - level) * 0.25f
        spin += dt * when (st) {
            VState.CONNECTING -> 2.6f
            VState.SPEAKING -> 1.2f
            VState.LISTENING -> 0.9f
            else -> 0.45f
        }

        val cx = width / 2f
        val cy = height / 2f
        val maxR = min(width, height) / 2f
        val base = maxR * if (compact) 0.78f else 0.56f
        val breathe = when (st) {
            VState.WAKE, VState.OFF -> 0.035f * sin(t * 1.5f)
            VState.CONNECTING -> 0.05f * sin(t * 5f)
            else -> 0.02f * sin(t * 2.2f)
        }
        val r = base * (1f + breathe + level * 0.28f)

        // Outer glow.
        val glowR = min(maxR, r * if (compact) 1.28f else 1.75f)
        glow.shader = RadialGradient(
            cx, cy, glowR,
            intArrayOf(withAlpha(cur[1], 120), withAlpha(cur[0], 40), Color.TRANSPARENT),
            floatArrayOf(0.35f, 0.7f, 1f), Shader.TileMode.CLAMP
        )
        c.drawCircle(cx, cy, glowR, glow)

        // Dark body so the blobs read as light inside a sphere.
        core.shader = null
        core.color = 0xFF0C0B1A.toInt()
        c.drawCircle(cx, cy, r, core)

        // Drifting blobs, mixed with "screen" inside their own layer.
        val layer = c.saveLayer(cx - r, cy - r, cx + r, cy + r, null)
        for (i in 0..2) {
            val a = spin * (1f + i * 0.35f) + i * 2.094f
            val off = r * (0.22f + 0.1f * level)
            val bx = cx + cos(a) * off
            val by = cy + sin(a * 1.15f) * off
            val br = r * (0.85f + 0.08f * sin(t * 1.3f + i))
            blob.shader = RadialGradient(
                bx, by, br,
                intArrayOf(withAlpha(cur[i], 235), withAlpha(cur[i], 90), Color.TRANSPARENT),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            c.drawCircle(bx, by, br, blob)
        }
        c.restoreToCount(layer)

        // Soft highlight.
        core.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.35f, r * 0.55f,
            intArrayOf(0x55FFFFFF, Color.TRANSPARENT), null, Shader.TileMode.CLAMP
        )
        c.drawCircle(cx - r * 0.3f, cy - r * 0.35f, r * 0.55f, core)

        // A ring that reacts while listening.
        if (st == VState.LISTENING || st == VState.SPEAKING) {
            ring.strokeWidth = maxR * 0.018f + maxR * 0.03f * level
            ring.color = withAlpha(cur[0], (90 + 140 * level).toInt().coerceAtMost(255))
            c.drawCircle(cx, cy, r * (1.08f + level * 0.08f), ring)
        }

        if (VibeBus.active(st) || st == VState.ERROR || forcedState != null) {
            postInvalidateOnAnimation()
        } else {
            postInvalidateDelayed(66)
        }
    }

    private fun palette(s: VState): IntArray = when (s) {
        VState.OFF -> intArrayOf(0xFF4A5C99.toInt(), 0xFF5E4A99.toInt(), 0xFF3A6A8C.toInt())
        VState.WAKE -> intArrayOf(0xFF3D6BFF.toInt(), 0xFF7B5CFF.toInt(), 0xFF2FB8FF.toInt())
        VState.CONNECTING -> intArrayOf(0xFFFFB547.toInt(), 0xFF9B6BFF.toInt(), 0xFFFF6BB5.toInt())
        VState.LISTENING -> intArrayOf(0xFF2FD0FF.toInt(), 0xFF4F8BFF.toInt(), 0xFF7CF5D6.toInt())
        VState.SPEAKING -> intArrayOf(0xFF9B6BFF.toInt(), 0xFFFF6BB5.toInt(), 0xFF4F8BFF.toInt())
        VState.ERROR -> intArrayOf(0xFFFF4D6A.toInt(), 0xFFB03050.toInt(), 0xFFFF8A5C.toInt())
    }

    private fun withAlpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

    private fun blend(a: Int, b: Int, f: Float): Int {
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return Color.argb(
            255,
            ch(Color.red(a), Color.red(b)),
            ch(Color.green(a), Color.green(b)),
            ch(Color.blue(a), Color.blue(b)),
        )
    }
}
