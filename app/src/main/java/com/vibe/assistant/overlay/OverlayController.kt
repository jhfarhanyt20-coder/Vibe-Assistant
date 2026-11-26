package com.vibe.assistant.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import com.vibe.assistant.R
import com.vibe.assistant.core.Role
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import com.vibe.assistant.ui.OrbView
import com.vibe.assistant.ui.VC
import com.vibe.assistant.ui.dp
import com.vibe.assistant.ui.dpf
import com.vibe.assistant.ui.label
import com.vibe.assistant.ui.rounded
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The floating Vibe bubble that sits above every app, like Gemini's.
 *
 *   tap           start / stop talking
 *   drag          move it; it snaps to the nearest screen edge
 *   drag onto ✕   remove the bubble — Vibe keeps running in the background
 *                 ("Hey Vibe" and the notification still work); turn it back on in the app
 *
 * While talking, a caption pill at the bottom shows what is being said.
 */
class OverlayController(private val ctx: Context) {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val size = ctx.dp(64)

    private var bubble: FrameLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var trash: FrameLayout? = null
    private var caption: TextView? = null
    private var scope: CoroutineScope? = null
    private var dragging = false

    val isShown get() = bubble != null

    fun show() {
        if (bubble != null || !Settings.canDrawOverlays(ctx)) return
        val screen = screenBounds()

        val orb = OrbView(ctx, compact = true)
        val root = FrameLayout(ctx).apply {
            addView(orb, FrameLayout.LayoutParams(size, size))
            elevation = ctx.dpf(8f)
        }
        val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = Prefs.overlayX.takeIf { it >= 0 } ?: (screen.width() - size - ctx.dp(10))
            y = Prefs.overlayY.takeIf { it >= 0 } ?: (screen.height() / 3)
        }
        root.setOnTouchListener(DragListener())
        try {
            wm.addView(root, p)
        } catch (e: Exception) {
            return
        }
        bubble = root
        bubbleParams = p

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { s ->
            s.launch {
                combine(VibeBus.state, VibeBus.lines, VibeBus.status) { st, lines, status ->
                    Triple(st, lines, status)
                }.collect { (st, lines, status) -> updateCaption(st, lines.lastOrNull(), status) }
            }
        }
    }

    fun hide() {
        scope?.cancel()
        scope = null
        removeTrash()
        caption?.let { safeRemove(it) }
        caption = null
        bubble?.let { safeRemove(it) }
        bubble = null
        bubbleParams = null
    }

    // ── Caption pill ─────────────────────────────────────────────────────────

    private fun updateCaption(st: VState, last: com.vibe.assistant.core.Line?, status: String) {
        val show = (VibeBus.active(st) || st == VState.ERROR) && !dragging
        if (!show) {
            caption?.let { safeRemove(it) }
            caption = null
            return
        }
        val text = when {
            st == VState.ERROR && status.isNotBlank() -> status
            status.isNotBlank() && st == VState.CONNECTING -> status
            !Prefs.showTranscript && st != VState.ERROR -> {
                caption?.let { safeRemove(it) }
                caption = null
                return
            }
            last != null && st != VState.CONNECTING ->
                (if (last.role == Role.USER) "তুমি: " else "") + last.text.takeLast(140)
            else -> VibeBus.label(st)
        }
        val tv = caption ?: ctx.label("", 14f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.START
            background = rounded(0xEE14132A.toInt(), ctx.dpf(22f), 0x33FFFFFF, ctx.dp(1))
            setPadding(ctx.dp(18), ctx.dp(12), ctx.dp(18), ctx.dp(12))
            elevation = ctx.dpf(6f)
        }.also { v ->
            val p = overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = ctx.dp(96)
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            v.maxWidth = (screenBounds().width() * 0.86f).toInt()
            try { wm.addView(v, p); caption = v } catch (_: Exception) {}
        }
        tv.text = text
    }

    // ── Dragging and the ✕ target ────────────────────────────────────────────

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var holding = false
        private val startHold = Runnable {
            if (!dragging) {
                holding = true
                bubble?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                VibeService.send(ctx, VibeService.A_PTT_DOWN)
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = bubbleParams ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    dragging = false
                    holding = false
                    v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(90).start()
                    if (Prefs.pushToTalk) v.postDelayed(startHold, 220)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && !holding && hypot(dx, dy) > slop) {
                        dragging = true
                        v.removeCallbacks(startHold)
                        caption?.let { safeRemove(it) }
                        caption = null
                        showTrash()
                    }
                    if (dragging) {
                        p.x = startX + dx.toInt()
                        p.y = startY + dy.toInt()
                        try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
                        setTrashHot(nearTrash(e.rawX, e.rawY))
                    }
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    v.removeCallbacks(startHold)
                    if (holding) {
                        holding = false
                        VibeService.send(ctx, VibeService.A_PTT_UP)
                    } else if (dragging) {
                        dragging = false
                        val drop = nearTrash(e.rawX, e.rawY)
                        removeTrash()
                        if (drop) dismissByUser() else snapToEdge(v, p)
                    } else {
                        v.performClick()
                        VibeService.send(ctx, VibeService.A_TOGGLE)
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(startHold)
                    if (holding) VibeService.send(ctx, VibeService.A_PTT_UP)
                    holding = false
                    dragging = false
                    v.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    removeTrash()
                }
            }
            return true
        }
    }

    private fun showTrash() {
        if (trash != null) return
        val t = FrameLayout(ctx).apply {
            background = rounded(0xCC1A1A2E.toInt(), ctx.dpf(36f), 0x55FFFFFF, ctx.dp(1))
            val icon = ImageView(ctx).apply { setImageResource(R.drawable.ic_close) }
            addView(icon, FrameLayout.LayoutParams(ctx.dp(28), ctx.dp(28), Gravity.CENTER))
            alpha = 0f
            scaleX = 0.6f; scaleY = 0.6f
        }
        val p = overlayParams(ctx.dp(72), ctx.dp(72)).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = ctx.dp(56)
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        try {
            wm.addView(t, p)
            trash = t
            t.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(OvershootInterpolator()).start()
        } catch (_: Exception) {}
    }

    private var hot = false
    private fun setTrashHot(isHot: Boolean) {
        val t = trash ?: return
        if (isHot == hot) return
        hot = isHot
        t.background = if (isHot) rounded(0xEEFF4D6A.toInt(), ctx.dpf(36f))
        else rounded(0xCC1A1A2E.toInt(), ctx.dpf(36f), 0x55FFFFFF, ctx.dp(1))
        t.animate().scaleX(if (isHot) 1.25f else 1f).scaleY(if (isHot) 1.25f else 1f).setDuration(120).start()
    }

    private fun removeTrash() {
        hot = false
        trash?.let { safeRemove(it) }
        trash = null
    }

    private fun nearTrash(rawX: Float, rawY: Float): Boolean {
        val s = screenBounds()
        val tx = s.width() / 2f
        val ty = s.height() - ctx.dp(56) - ctx.dp(36).toFloat()
        return hypot(rawX - tx, rawY - ty) < ctx.dp(90)
    }

    private fun dismissByUser() {
        Prefs.overlayEnabled = false
        hide()
        Toast.makeText(
            ctx, "ভাসমান বাটন সরানো হলো। Vibe ব্যাকগ্রাউন্ডে চলছেই — অ্যাপ থেকে আবার আনতে পারবে।",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun snapToEdge(v: View, p: WindowManager.LayoutParams) {
        val s = screenBounds()
        val margin = ctx.dp(10)
        val targetX = if (p.x + size / 2 < s.width() / 2) margin else s.width() - size - margin
        p.y = p.y.coerceIn(ctx.dp(40), s.height() - size - ctx.dp(80))
        ValueAnimator.ofInt(p.x, targetX).apply {
            duration = 260
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                p.x = it.animatedValue as Int
                try { wm.updateViewLayout(v, p) } catch (_: Exception) {}
            }
            start()
        }
        Prefs.overlayX = targetX
        Prefs.overlayY = p.y
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    )

    private fun screenBounds(): Rect =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else ctx.resources.displayMetrics.let { Rect(0, 0, it.widthPixels, it.heightPixels) }

    private fun safeRemove(v: View) {
        try { wm.removeView(v) } catch (_: Exception) {}
    }

    @Suppress("unused")
    private fun dist(a: Int, b: Int) = abs(a - b)
}
