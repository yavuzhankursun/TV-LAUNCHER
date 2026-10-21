package com.hizir.sadehome.ui

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Üst çubuktaki ve satırlardaki yuvarlak "çip" düğmesi: [ikon] [yazı] [durum noktası].
 * Odakta beyaz dolgu, koyu yazı ve hafif büyüme.
 */
class Pill(
    context: Context,
    glyph: Glyph?,
    label: String?,
    private val normalBg: Int = Palette.GLASS,
    private val normalFg: Int = Palette.TEXT,
    heightDp: Int = 34,
    focusable: Boolean = true,
) : LinearLayout(context) {

    private val icon: GlyphDrawable? = glyph?.let { GlyphDrawable(it, normalFg) }
    private val iconView: ImageView? = icon?.let { d -> ImageView(context).apply { setImageDrawable(d) } }
    val label: TextView? = label?.let { context.text(it, 13f, normalFg, Fonts.medium) }
    private var dot: View? = null
    private var dotColor = 0

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val h = dp(heightDp)
        minimumHeight = h
        val r = h / 2f
        background = focusStates(
            roundRect(normalBg, r, Palette.GLASS_STROKE, if (normalBg == Palette.GLASS) dp(1) else 0),
            roundRect(Palette.FOCUS_BG, r),
        )
        val hasText = label != null
        setPadding(if (hasText) dp(14) else dp(9), 0, if (hasText) dp(14) else dp(9), 0)
        iconView?.let { addView(it, lp(dp(16), dp(16)).margins(r = if (hasText) dp(7) else 0)) }
        this.label?.let { addView(it, lp(WRAP, WRAP)) }
        isFocusable = focusable
        isClickable = focusable
        if (!hasText) minimumWidth = h
    }

    fun setText(t: CharSequence) {
        label?.text = t
    }

    fun setGlyph(g: Glyph) {
        val fg = if (isFocused) Palette.FOCUS_TEXT else normalFg
        iconView?.setImageDrawable(GlyphDrawable(g, fg))
    }

    /** Sağda küçük renkli durum noktası; 0 verilirse gizlenir. */
    fun setDot(color: Int) {
        dotColor = color
        if (color == 0) {
            dot?.visibility = View.GONE
            return
        }
        val d = dot ?: View(context).also {
            addView(it, lp(dp(7), dp(7)).margins(l = dp(8)))
            dot = it
        }
        d.visibility = View.VISIBLE
        d.background = oval(color)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val fg = if (gainFocus) Palette.FOCUS_TEXT else normalFg
        label?.setTextColor(fg)
        (iconView?.drawable as? GlyphDrawable)?.color = fg
        animateFocus(gainFocus, 1.07f)
    }
}
