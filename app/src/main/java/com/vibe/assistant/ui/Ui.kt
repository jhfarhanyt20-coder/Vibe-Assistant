package com.vibe.assistant.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** Colours and small builders shared by every screen. */
object VC {
    const val BG = 0xFF0A0A12.toInt()
    const val BG_MID = 0xFF141030.toInt()
    const val CARD = 0xFF16162A.toInt()
    const val CARD_HI = 0xFF1F1F3A.toInt()
    const val STROKE = 0x1FFFFFFF
    const val TEXT = 0xFFF2F2F7.toInt()
    const val SUB = 0xFF9C9CB8.toInt()
    const val FAINT = 0xFF6B6B88.toInt()
    const val A1 = 0xFF4F8BFF.toInt()
    const val A2 = 0xFF9B6BFF.toInt()
    const val A3 = 0xFFFF6BB5.toInt()
    const val RED = 0xFFFF5C7A.toInt()
    const val GREEN = 0xFF3DDC97.toInt()
    const val PINK_SOFT = 0x33FF6BB5
    const val BLUE_SOFT = 0x334F8BFF

    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (strokeWidth > 0) setStroke(strokeWidth, stroke)
    }

fun accentGradient(radius: Float): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(VC.A1, VC.A2, VC.A3)).apply {
        cornerRadius = radius
    }

fun screenBackground(): GradientDrawable =
    GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(VC.BG, VC.BG_MID, VC.BG))

fun ripple(content: Drawable?, mask: Drawable?): RippleDrawable =
    RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, mask)

fun Context.label(
    text: CharSequence, sizeSp: Float, color: Int = VC.TEXT,
    face: Typeface = VC.regular, gravity: Int = Gravity.START,
): TextView = TextView(this).apply {
    this.text = text
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = face
    this.gravity = gravity
    setLineSpacing(0f, 1.15f)
}

/** Paints a TextView's glyphs with the blue-purple-pink accent. */
fun TextView.gradientText() {
    post {
        val w = paint.measureText(text.toString())
        paint.shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(VC.A1, VC.A2, VC.A3), null, Shader.TileMode.CLAMP)
        invalidate()
    }
}

fun Context.primaryButton(text: String, onClick: () -> Unit): TextView =
    label(text, 16f, Color.WHITE, VC.medium, Gravity.CENTER).apply {
        val r = dpf(28f)
        background = ripple(accentGradient(r), rounded(Color.WHITE, r))
        minHeight = dp(56)
        setPadding(dp(24), 0, dp(24), 0)
        isClickable = true
        isFocusable = true
        elevation = dpf(6f)
        setOnClickListener { onClick() }
    }

fun Context.secondaryButton(text: String, onClick: () -> Unit): TextView =
    label(text, 15f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
        val r = dpf(24f)
        background = ripple(rounded(0x14FFFFFF, r, 0x33FFFFFF, dp(1)), rounded(Color.WHITE, r))
        minHeight = dp(48)
        setPadding(dp(20), 0, dp(20), 0)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

fun Context.smallButton(text: String, onClick: () -> Unit): TextView =
    label(text, 13f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
        val r = dpf(16f)
        background = ripple(rounded(0x22FFFFFF, r), rounded(Color.WHITE, r))
        minHeight = dp(34)
        setPadding(dp(14), 0, dp(14), 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

fun Context.card(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(VC.CARD, dpf(22f), VC.STROKE, dp(1))
    setPadding(dp(18), dp(16), dp(18), dp(16))
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(w, h)

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) =
    apply { setMargins(l, t, r, b) }

/** Draw behind the bars, then pad the content so nothing sits under them or the keyboard. */
fun Activity.edgeToEdge(root: View) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = false
        isAppearanceLightNavigationBars = false
    }
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}
