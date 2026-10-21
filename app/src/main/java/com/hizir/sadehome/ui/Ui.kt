package com.hizir.sadehome.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** Renk dili: koyu sinematik zemin, beyaz metin, odakta beyaz dolgu + koyu yazı. */
object Palette {
    const val BG = 0xFF07070B.toInt()
    const val GLASS = 0x26FFFFFF
    const val GLASS_STROKE = 0x33FFFFFF
    const val PANEL = 0xF2141419.toInt()
    const val TEXT = Color.WHITE
    const val TEXT_DIM = 0xB3FFFFFF.toInt()
    const val TEXT_FAINT = 0x73FFFFFF
    const val FOCUS_BG = Color.WHITE
    const val FOCUS_TEXT = 0xFF0B0B10.toInt()
    const val GOOD = 0xFF34D399.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val BAD = 0xFFF87171.toInt()
    const val ROSE = 0xFFE11D48.toInt()
}

object Fonts {
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)
    val black: Typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
}

fun Context.dp(v: Float): Float = v * resources.displayMetrics.density
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dp(v: Int): Int = context.dp(v)
fun View.dp(v: Float): Float = context.dp(v)

fun roundRect(color: Int, radius: Float, strokeColor: Int = 0, strokeWidth: Int = 0): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (strokeWidth > 0) setStroke(strokeWidth, strokeColor)
    }

fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

/** Odakta [focused], diğer durumlarda [normal] gösteren arka plan. */
fun focusStates(normal: Drawable, focused: Drawable): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), focused)
    addState(intArrayOf(), normal)
}

fun focusColors(normal: Int, focused: Int): ColorStateList = ColorStateList(
    arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
    intArrayOf(focused, normal),
)

fun Context.text(
    value: CharSequence = "",
    sizeSp: Float = 14f,
    color: Int = Palette.TEXT,
    font: Typeface = Fonts.regular,
): TextView = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color)
    typeface = font
    includeFontPadding = false
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
}

fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

fun LinearLayout.LayoutParams.margins(l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(l, t, r, b)
}

/** Odaklanınca hafifçe büyüyen öğeler için ortak animasyon. */
fun View.animateFocus(focused: Boolean, scale: Float = 1.06f) {
    animate().scaleX(if (focused) scale else 1f).scaleY(if (focused) scale else 1f)
        .setDuration(140).start()
}

/** Kumandadaki tuşları anlatan küçük "rozet" (OK, ◀ ▶ gibi). */
fun Context.keyBadge(label: String): TextView = text(label, 10f, Palette.TEXT, Fonts.bold).apply {
    background = roundRect(0x33FFFFFF, dp(4f))
    setPadding(dp(5), dp(2), dp(5), dp(2))
    gravity = Gravity.CENTER
}

fun blend(a: Int, b: Int, t: Float): Int {
    val u = 1f - t
    return Color.argb(
        (Color.alpha(a) * u + Color.alpha(b) * t).toInt(),
        (Color.red(a) * u + Color.red(b) * t).toInt(),
        (Color.green(a) * u + Color.green(b) * t).toInt(),
        (Color.blue(a) * u + Color.blue(b) * t).toInt(),
    )
}

fun withAlpha(color: Int, alpha: Float): Int =
    Color.argb((alpha * 255).toInt().coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
