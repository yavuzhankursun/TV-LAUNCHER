package com.hizir.sadehome.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.hizir.sadehome.data.AppEntry

/** Uygulama kartı + odaktayken altında beliren "Netflix · OK ile aç" yazısı. */
@SuppressLint("ViewConstructor")
class AppTile(context: Context, val entry: AppEntry, bannerMode: Boolean) : LinearLayout(context) {
    val card = AppCardView(context, entry, bannerMode)
    private val caption = context.text("", 11f, Palette.TEXT_DIM, Fonts.medium).apply {
        alpha = 0f
        val name = SpannableBold.make(entry.label, " · OK ile aç")
        text = name
    }
    private val overshoot = OvershootInterpolator(1.6f)

    init {
        orientation = VERTICAL
        clipChildren = false
        clipToPadding = false
        isFocusable = true
        isClickable = true
        isLongClickable = true
        addView(card, lp(dp(CARD_W), dp(CARD_H)))
        addView(caption, lp(dp(CARD_W), WRAP).margins(t = dp(9)))
        card.pivotX = dp(CARD_W) / 2f
        card.pivotY = dp(CARD_H).toFloat()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        card.highlighted = gainFocus
        val s = if (gainFocus) 1.12f else 1f
        card.animate().scaleX(s).scaleY(s).setDuration(if (gainFocus) 260 else 160)
            .setInterpolator(if (gainFocus) overshoot else null).start()
        caption.animate().alpha(if (gainFocus) 1f else 0f).translationY(if (gainFocus) dp(4f) else 0f)
            .setDuration(180).start()
        if (gainFocus) card.shine()
    }

    companion object {
        const val CARD_W = 136
        const val CARD_H = 77
    }
}

private object SpannableBold {
    fun make(bold: String, rest: String): CharSequence {
        val s = android.text.SpannableString(bold + rest)
        s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, bold.length, 0)
        s.setSpan(android.text.style.ForegroundColorSpan(Palette.TEXT), 0, bold.length, 0)
        return s
    }
}

/**
 * Tek View ile çizilen kart: uygulamanın renginden degrade zemin, sağ üstte ikon, sol altta ad.
 * Banner modunda uygulamanın kendi TV banner'ı tam kaplanır. Seçilince üzerinden ışık geçer.
 */
@SuppressLint("ViewConstructor")
class AppCardView(context: Context, private val entry: AppEntry, private val bannerMode: Boolean) : View(context) {
    var highlighted = false
        set(v) {
            field = v; invalidate()
        }
    private val radius = context.dp(12f)
    private val rect = RectF()
    private val clip = Path()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gloss = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2f)
        color = 0xFFFFFFFF.toInt()
    }
    private val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1f)
        color = 0x24FFFFFF
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = context.dp(12.5f)
        typeface = Fonts.bold
        setShadowLayer(context.dp(3f), 0f, context.dp(1f), 0x66000000)
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shineT = -1f
    private val iconRect = RectF()
    private val src = Rect()

    fun shine() {
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f).setDuration(620)
        a.addUpdateListener { shineT = it.animatedValue as Float; invalidate() }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                shineT = -1f; invalidate()
            }
        })
        a.startDelay = 90
        a.start()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        val a = entry.accent
        bg.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(blend(a, 0xFFFFFFFF.toInt(), 0.12f), a, blend(a, 0xFF000000.toInt(), 0.55f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        gloss.shader = LinearGradient(
            0f, 0f, 0f, h * 0.6f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.save()
        canvas.clipPath(clip)
        val banner = if (bannerMode) entry.banner else null
        if (banner != null) {
            canvas.drawColor(0xFF15151B.toInt())
            banner.setBounds(0, 0, width, height)
            banner.draw(canvas)
        } else {
            canvas.drawRect(rect, bg)
            canvas.drawRect(0f, 0f, w, h * 0.6f, gloss)
            val s = h * 0.36f
            val pad = h * 0.13f
            iconRect.set(w - pad - s, pad, w - pad, pad + s)
            src.set(0, 0, entry.icon.width, entry.icon.height)
            canvas.drawBitmap(entry.icon, src, iconRect, iconPaint)
            val text = TextUtils.ellipsize(entry.label, label, w - pad * 2, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, pad, h - pad, label)
        }
        if (shineT >= 0f) {
            val x = -w * 0.6f + shineT * w * 2.2f
            shinePaint.shader = LinearGradient(
                x, 0f, x + w * 0.45f, h,
                intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(rect, shinePaint)
        }
        canvas.restore()
        val inset = border.strokeWidth / 2
        if (highlighted) {
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, border)
        } else {
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, hairline)
        }
    }
}
