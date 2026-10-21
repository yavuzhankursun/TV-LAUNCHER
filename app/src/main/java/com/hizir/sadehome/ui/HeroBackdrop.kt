package com.hizir.sadehome.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Tam ekran arka plan: odaktaki uygulamanın renginden sinematik degrade, sağda ince çapraz
 * çizgiler ve uygulamanın büyük logosu. Geçişlerde katmanlar üst üste bindirilmez; sadece renk
 * ara değerlenir ve logo çapraz solar. Böylece zayıf GPU'da tek geçişte çizilir.
 */
class HeroBackdrop(context: Context) : View(context) {
    private var fromColor = 0xFF3B2A7A.toInt()
    private var toColor = fromColor
    private var colorT = 1f
    private var oldLogo: Bitmap? = null
    private var logo: Bitmap? = null
    private var logoT = 1f

    private val base = Paint()
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = context.dp(1.2f) }
    private val shadeLeft = Paint()
    private val shadeBottom = Paint()
    private val shadeTop = Paint()
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()
    private var shaderColor = 0
    private var animator: ValueAnimator? = null

    fun set(color: Int, newLogo: Bitmap?, animate: Boolean = true) {
        val current = currentColor()
        animator?.cancel()
        fromColor = current
        toColor = color
        if (newLogo !== logo) {
            oldLogo = logo
            logo = newLogo
            logoT = 0f
        }
        if (!animate) {
            colorT = 1f; logoT = 1f; oldLogo = null
            invalidate()
            return
        }
        colorT = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(480).apply {
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                val t = it.animatedValue as Float
                colorT = t
                logoT = t
                invalidate()
            }
            start()
        }
    }

    private fun currentColor() = blend(fromColor, toColor, colorT)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        shadeLeft.shader = LinearGradient(
            0f, 0f, w * 0.62f, 0f,
            intArrayOf(0xE6050508.toInt(), 0x99050508.toInt(), 0x00050508), floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        shadeBottom.shader = LinearGradient(
            0f, h * 0.45f, 0f, h.toFloat(),
            intArrayOf(0x00050508, 0xB3050508.toInt(), 0xF2050508.toInt()), floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        shadeTop.shader = LinearGradient(
            0f, 0f, 0f, h * 0.18f, 0x80050508.toInt(), 0x00050508, Shader.TileMode.CLAMP,
        )
        lines.shader = LinearGradient(
            w * 0.38f, 0f, w.toFloat(), 0f, 0x00FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP,
        )
        shaderColor = 0
    }

    private fun updateColorShaders(c: Int) {
        if (c == shaderColor) return
        shaderColor = c
        val w = width.toFloat()
        val h = height.toFloat()
        val deep = blend(c, 0xFF000000.toInt(), 0.82f)
        val mid = blend(c, 0xFF000000.toInt(), 0.42f)
        base.shader = LinearGradient(
            0f, h, w, 0f,
            intArrayOf(0xFF050508.toInt(), deep, mid, blend(c, 0xFF000000.toInt(), 0.15f)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        glow.shader = RadialGradient(
            w * 0.8f, h * 0.32f, w * 0.55f,
            intArrayOf(withAlpha(blend(c, 0xFFFFFFFF.toInt(), 0.15f), 0.75f), withAlpha(c, 0.25f), withAlpha(c, 0f)),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        updateColorShaders(currentColor())
        canvas.drawRect(0f, 0f, w, h, base)
        canvas.drawRect(0f, 0f, w, h, glow)

        // İnce çapraz çizgiler (sağa doğru belirginleşir).
        val step = dp(16f)
        var x = w * 0.3f
        while (x < w + h) {
            canvas.drawLine(x, 0f, x - h, h, lines)
            x += step
        }

        val size = h * 0.36f
        val cx = w * 0.785f
        val cy = h * 0.37f
        dst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        oldLogo?.let { if (logoT < 1f) drawLogo(canvas, it, 1f - logoT, 1f + 0.04f * logoT) }
        logo?.let { drawLogo(canvas, it, logoT, 0.96f + 0.04f * logoT) }

        canvas.drawRect(0f, 0f, w, h, shadeLeft)
        canvas.drawRect(0f, h * 0.45f, w, h, shadeBottom)
        canvas.drawRect(0f, 0f, w, h * 0.18f, shadeTop)
    }

    private fun drawLogo(canvas: Canvas, bmp: Bitmap, alpha: Float, scale: Float) {
        if (bmp.isRecycled) return
        logoPaint.alpha = (alpha * 235).toInt()
        src.set(0, 0, bmp.width, bmp.height)
        canvas.save()
        canvas.scale(scale, scale, dst.centerX(), dst.centerY())
        canvas.drawBitmap(bmp, src, dst, logoPaint)
        canvas.restore()
    }
}
