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
import android.util.LruCache
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * Tam ekran arka plan: odaktaki uygulamanın renginden sinematik degrade, sağda ince çapraz
 * çizgiler ve uygulamanın büyük logosu.
 *
 * Performans: degrade, parlama, çizgiler ve karartmalar her renk için arka planda bir kez küçük bir
 * bitmap'e işlenir ([bake]) ve önbelleğe alınır. Ekranda sadece bu bitmap büyütülerek çizilir; geçişte
 * eski ve yeni bitmap çapraz solar. Önceki sürüm her karede ~7 tam ekran katman ve yeni shader
 * oluşturuyordu; zayıf Mali GPU'da gezinmede kare düşmesi %60'ın üstündeydi.
 */
class HeroBackdrop(context: Context) : View(context) {
    private var oldBase: Bitmap? = null
    private var base: Bitmap? = null
    private var oldLogo: Bitmap? = null
    private var logo: Bitmap? = null
    private var t = 1f

    private val basePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val logoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val full = RectF()
    private val dst = RectF()
    private var animator: ValueAnimator? = null

    /** [baked]: [bake] ile hazırlanmış arka plan; [newLogo]: hedef boyutta çizilmiş logo. */
    fun set(baked: Bitmap, newLogo: Bitmap?, animate: Boolean = true) {
        animator?.cancel()
        oldBase = base
        oldLogo = logo
        base = baked
        logo = newLogo
        if (!animate || oldBase == null) {
            t = 1f; oldBase = null; oldLogo = null
            invalidate()
            return
        }
        t = 0f
        animator = ValueAnimator.ofFloat(0f, 1f).setDuration(420).apply {
            interpolator = DecelerateInterpolator(1.4f)
            addUpdateListener {
                t = it.animatedValue as Float
                if (t >= 1f) { oldBase = null; oldLogo = null }
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        full.set(0f, 0f, w, h)
        val b = base
        if (b == null) {
            canvas.drawColor(Palette.BG)
        } else {
            val ob = oldBase
            if (ob != null && t < 1f) {
                drawBase(canvas, ob, 255)
                drawBase(canvas, b, (t * 255).toInt())
            } else {
                drawBase(canvas, b, 255)
            }
        }

        val size = h * 0.36f
        val cx = w * 0.785f
        val cy = h * 0.37f
        dst.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        oldLogo?.let { if (t < 1f) drawLogo(canvas, it, 1f - t, 1f + 0.04f * t) }
        logo?.let { drawLogo(canvas, it, t, 0.96f + 0.04f * t) }
    }

    private fun drawBase(canvas: Canvas, bmp: Bitmap, alpha: Int) {
        if (bmp.isRecycled) return
        basePaint.alpha = alpha
        src.set(0, 0, bmp.width, bmp.height)
        canvas.drawBitmap(bmp, src, full, basePaint)
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

    companion object {
        /** Ekranın 1/3'ü çözünürlük: degradeler büyütmede fark edilmez, bitmap ~0.9 MB. */
        private const val BW = 640
        private const val BH = 360
        private val cache = LruCache<Int, Bitmap>(8)

        /** Ana ekran görünmezken önbelleği boşalt; ekrandaki bitmap'ler görünüm tarafından tutulmaya devam eder. */
        fun trim() = cache.evictAll()

        /** Arka planda çağrılmalı. Aynı renk için önbellekteki bitmap döner. */
        fun bake(color: Int, density: Float, screenWidth: Int): Bitmap {
            cache.get(color)?.let { return it }
            val bmp = Bitmap.createBitmap(BW, BH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val w = BW.toFloat()
            val h = BH.toFloat()
            val px = density * BW / screenWidth.coerceAtLeast(1) // 1 dp'nin bu küçük tuvaldeki karşılığı

            val deep = blend(color, 0xFF000000.toInt(), 0.82f)
            val mid = blend(color, 0xFF000000.toInt(), 0.42f)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(
                0f, h, w, 0f,
                intArrayOf(0xFF050508.toInt(), deep, mid, blend(color, 0xFF000000.toInt(), 0.15f)),
                floatArrayOf(0f, 0.35f, 0.7f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = RadialGradient(
                w * 0.8f, h * 0.32f, w * 0.55f,
                intArrayOf(withAlpha(blend(color, 0xFFFFFFFF.toInt(), 0.15f), 0.75f), withAlpha(color, 0.25f), withAlpha(color, 0f)),
                floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, paint)

            // İnce çapraz çizgiler (sağa doğru belirginleşir).
            paint.shader = LinearGradient(w * 0.38f, 0f, w, 0f, 0x00FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP)
            paint.strokeWidth = 1.2f * px
            val step = 16f * px
            var x = w * 0.3f
            while (x < w + h) {
                c.drawLine(x, 0f, x - h, h, paint)
                x += step
            }

            // Okunabilirlik karartmaları: sol (başlık), alt (kartlar), üst (saat).
            paint.shader = LinearGradient(
                0f, 0f, w * 0.62f, 0f,
                intArrayOf(0xE6050508.toInt(), 0x99050508.toInt(), 0x00050508), floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = LinearGradient(
                0f, h * 0.45f, 0f, h,
                intArrayOf(0x00050508, 0xB3050508.toInt(), 0xF2050508.toInt()), floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
            c.drawRect(0f, h * 0.45f, w, h, paint)
            paint.shader = LinearGradient(0f, 0f, 0f, h * 0.18f, 0x80050508.toInt(), 0x00050508, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h * 0.18f, paint)

            cache.put(color, bmp)
            return bmp
        }
    }
}
