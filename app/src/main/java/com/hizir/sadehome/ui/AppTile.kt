package com.hizir.sadehome.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import com.hizir.sadehome.data.AppEntry
import com.hizir.sadehome.data.Mark

/** Uygulama kartı + odaktayken altında beliren "Netflix · OK ile aç" yazısı. */
@SuppressLint("ViewConstructor")
class AppTile(context: Context, val entry: AppEntry, bannerMode: Boolean) : LinearLayout(context) {
    val card = AppCardView(context, entry, bannerMode)
    // Düz (span'siz) metin: saydamlık animasyonu ekran dışı katman gerektirmez, doğrudan çizilir.
    private val caption = context.text(entry.label, 11f, Palette.TEXT, Fonts.bold).apply { alpha = 0f }
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
    private val shineMatrix = android.graphics.Matrix()
    private var shineT = -1f
    private val markRect = RectF()
    private val src = Rect()

    init {
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
        clipToOutline = true
    }

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
        val a = entry.accent
        bg.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(blend(a, 0xFFFFFFFF.toInt(), 0.12f), a, blend(a, 0xFF000000.toInt(), 0.55f)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
        )
        gloss.shader = LinearGradient(
            0f, 0f, 0f, h * 0.6f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP,
        )
        // Parlama bandı bir kez oluşturulur; her karede sadece kaydırılır (çizimde nesne oluşturma yok).
        shinePaint.shader = LinearGradient(
            0f, 0f, w * 0.45f, h.toFloat(),
            intArrayOf(0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        // Köşe kırpma görünümün dış hattıyla (clipToOutline) yapılıyor: clipPath'in aksine GPU'da
        // şablon (stencil) maskesi ya da ara katman gerektirmez.
        val banner = if (bannerMode) entry.banner else null
        if (banner != null) {
            canvas.drawColor(0xFF15151B.toInt())
            banner.setBounds(0, 0, width, height)
            banner.draw(canvas)
        } else {
            canvas.drawRect(rect, bg)
            canvas.drawRect(0f, 0f, w, h * 0.6f, gloss)
            val mark = entry.markBitmap
            if (mark != null) {
                // Ortada sade simge (Apple TV gibi); ad, kart seçilince altta beliriyor.
                val s = h * if (entry.mark.kind == Mark.Kind.ICON) 0.56f else 0.62f
                markRect.set((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2)
                src.set(0, 0, mark.width, mark.height)
                canvas.drawBitmap(mark, src, markRect, iconPaint)
            } else {
                val pad = h * 0.13f
                val text = TextUtils.ellipsize(entry.label, label, w - pad * 2, TextUtils.TruncateAt.END)
                canvas.drawText(text, 0, text.length, pad, h - pad, label)
            }
        }
        if (shineT >= 0f) {
            shineMatrix.setTranslate(-w * 0.6f + shineT * w * 2.2f, 0f)
            shinePaint.shader?.setLocalMatrix(shineMatrix)
            canvas.drawRect(rect, shinePaint)
        }
        val inset = border.strokeWidth / 2
        if (highlighted) {
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, border)
        } else {
            canvas.drawRoundRect(inset, inset, w - inset, h - inset, radius, radius, hairline)
        }
    }
}
