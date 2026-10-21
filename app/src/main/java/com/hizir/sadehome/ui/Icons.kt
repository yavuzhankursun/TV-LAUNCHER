package com.hizir.sadehome.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.sin

enum class Glyph {
    GEAR, BOLT, CAST, GAUGE, INFO, WIFI, BLUETOOTH, DISPLAY, SOUND, GRID, STORAGE, CLOCK,
    CODE, PERSON, MOON, INPUT, GLOBE, CHIP, OPEN, ARROW_LEFT, ARROW_RIGHT, ARROW_FIRST,
    HIDE, STOP, TRASH, CHECK, HOME, REFRESH, STAR, SEARCH, LINK,
    SUN, CLOUD, PARTLY, RAIN, SNOW, STORM, FOG,
}

/**
 * Kütüphanesiz, 24 birimlik ızgarada çizgiyle çizilen tek renkli ikonlar.
 * Font ikonları (⚙ vb.) cihazda renkli emojiye düşebildiği için elle çiziliyor.
 */
class GlyphDrawable(private val glyph: Glyph, color: Int) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }
    private val path = Path()
    private val oval = RectF()

    var color: Int
        get() = stroke.color
        set(value) {
            if (stroke.color != value) {
                stroke.color = value
                fill.color = value
                invalidateSelf()
            }
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val s = minOf(b.width(), b.height()) / 24f
        canvas.save()
        canvas.translate(b.left + (b.width() - 24 * s) / 2f, b.top + (b.height() - 24 * s) / 2f)
        canvas.scale(s, s)
        drawGlyph(canvas)
        canvas.restore()
    }

    private fun circle(c: Canvas, x: Float, y: Float, r: Float, filled: Boolean = false) =
        c.drawCircle(x, y, r, if (filled) fill else stroke)

    private fun line(c: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) = c.drawLine(x1, y1, x2, y2, stroke)

    private fun rect(c: Canvas, l: Float, t: Float, r: Float, b: Float, rad: Float = 2f) {
        oval.set(l, t, r, b)
        c.drawRoundRect(oval, rad, rad, stroke)
    }

    private fun arc(c: Canvas, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        oval.set(cx - r, cy - r, cx + r, cy + r)
        c.drawArc(oval, start, sweep, false, stroke)
    }

    private fun poly(c: Canvas, vararg pts: Float, close: Boolean = false, filled: Boolean = false) {
        path.reset()
        path.moveTo(pts[0], pts[1])
        var i = 2
        while (i < pts.size) {
            path.lineTo(pts[i], pts[i + 1]); i += 2
        }
        if (close) path.close()
        c.drawPath(path, if (filled) fill else stroke)
    }

    private fun cloud(c: Canvas, dy: Float = 0f) {
        path.reset()
        path.moveTo(7f, 18f + dy)
        oval.set(2.5f, 9.5f + dy, 11.5f, 18f + dy)
        path.arcTo(oval, 90f, 160f)
        oval.set(6.5f, 4.5f + dy, 17f, 15f + dy)
        path.arcTo(oval, 200f, 150f)
        oval.set(13.5f, 9f + dy, 22f, 18f + dy)
        path.arcTo(oval, 280f, 170f)
        path.close()
        c.drawPath(path, stroke)
    }

    private fun drawGlyph(c: Canvas) {
        when (glyph) {
            Glyph.GEAR -> {
                circle(c, 12f, 12f, 3f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(c, 12f + 6.5f * cos(a).toFloat(), 12f + 6.5f * sin(a).toFloat(),
                        12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
                }
                circle(c, 12f, 12f, 6.5f)
            }
            Glyph.BOLT -> poly(c, 13f, 2f, 4f, 14f, 11f, 14f, 10f, 22f, 20f, 9f, 13f, 9f, close = true)
            Glyph.CAST -> {
                poly(c, 3f, 9f, 3f, 5f, 21f, 5f, 21f, 17f, 15f, 17f)
                poly(c, 6f, 20f, 12f, 12f, 18f, 20f, close = true, filled = true)
            }
            Glyph.GAUGE -> {
                arc(c, 12f, 14f, 9f, 180f, 180f)
                line(c, 12f, 14f, 16.5f, 8.5f)
                circle(c, 12f, 14f, 1.4f, filled = true)
                line(c, 3f, 18f, 21f, 18f)
            }
            Glyph.INFO -> {
                circle(c, 12f, 12f, 9.5f)
                line(c, 12f, 11f, 12f, 16.5f)
                circle(c, 12f, 7.6f, 1.2f, filled = true)
            }
            Glyph.WIFI -> {
                arc(c, 12f, 19f, 15f, 225f, 90f)
                arc(c, 12f, 19f, 10f, 225f, 90f)
                arc(c, 12f, 19f, 5f, 225f, 90f)
                circle(c, 12f, 19f, 1.3f, filled = true)
            }
            Glyph.BLUETOOTH -> poly(c, 6f, 7f, 17f, 16f, 12f, 21f, 12f, 3f, 17f, 8f, 6f, 17f)
            Glyph.DISPLAY -> {
                rect(c, 2.5f, 4f, 21.5f, 16.5f)
                line(c, 8f, 20.5f, 16f, 20.5f)
                line(c, 12f, 16.5f, 12f, 20.5f)
            }
            Glyph.SOUND -> {
                poly(c, 3f, 9f, 7f, 9f, 12f, 4.5f, 12f, 19.5f, 7f, 15f, 3f, 15f, close = true)
                arc(c, 12f, 12f, 4.5f, -50f, 100f)
                arc(c, 12f, 12f, 8.5f, -50f, 100f)
            }
            Glyph.GRID -> {
                rect(c, 3.5f, 3.5f, 10.5f, 10.5f); rect(c, 13.5f, 3.5f, 20.5f, 10.5f)
                rect(c, 3.5f, 13.5f, 10.5f, 20.5f); rect(c, 13.5f, 13.5f, 20.5f, 20.5f)
            }
            Glyph.STORAGE -> {
                rect(c, 3f, 4f, 21f, 10.5f); rect(c, 3f, 13.5f, 21f, 20f)
                circle(c, 7f, 7.25f, 1f, filled = true); circle(c, 7f, 16.75f, 1f, filled = true)
            }
            Glyph.CLOCK -> {
                circle(c, 12f, 12f, 9.5f)
                poly(c, 12f, 6.5f, 12f, 12f, 15.5f, 14f)
            }
            Glyph.CODE -> {
                poly(c, 8f, 6.5f, 2.5f, 12f, 8f, 17.5f)
                poly(c, 16f, 6.5f, 21.5f, 12f, 16f, 17.5f)
                line(c, 14f, 4f, 10f, 20f)
            }
            Glyph.PERSON -> {
                circle(c, 12f, 5f, 2f)
                line(c, 4.5f, 9f, 19.5f, 9f)
                line(c, 12f, 9f, 12f, 14.5f)
                poly(c, 8f, 21f, 12f, 14.5f, 16f, 21f)
            }
            Glyph.MOON -> {
                path.reset()
                oval.set(3f, 3f, 21f, 21f)
                path.arcTo(oval, -60f, 300f)
                oval.set(9f, 0f, 24f, 15f)
                path.arcTo(oval, 160f, -150f)
                c.drawPath(path, stroke)
            }
            Glyph.INPUT -> {
                poly(c, 3f, 8f, 21f, 8f, 21f, 13f, 18f, 16f, 6f, 16f, 3f, 13f, close = true)
                line(c, 7f, 11.5f, 17f, 11.5f)
            }
            Glyph.GLOBE -> {
                circle(c, 12f, 12f, 9.5f)
                line(c, 2.5f, 12f, 21.5f, 12f)
                oval.set(7.5f, 2.5f, 16.5f, 21.5f); c.drawOval(oval, stroke)
            }
            Glyph.CHIP -> {
                rect(c, 6f, 6f, 18f, 18f)
                for (p in floatArrayOf(9f, 12f, 15f)) {
                    line(c, p, 2.5f, p, 6f); line(c, p, 18f, p, 21.5f)
                    line(c, 2.5f, p, 6f, p); line(c, 18f, p, 21.5f, p)
                }
            }
            Glyph.OPEN -> poly(c, 8f, 5f, 19f, 12f, 8f, 19f, close = true, filled = true)
            Glyph.ARROW_LEFT -> { line(c, 20f, 12f, 4f, 12f); poly(c, 10f, 6f, 4f, 12f, 10f, 18f) }
            Glyph.ARROW_RIGHT -> { line(c, 4f, 12f, 20f, 12f); poly(c, 14f, 6f, 20f, 12f, 14f, 18f) }
            Glyph.ARROW_FIRST -> {
                line(c, 4f, 5f, 4f, 19f)
                line(c, 20f, 12f, 8f, 12f); poly(c, 13f, 7f, 8f, 12f, 13f, 17f)
            }
            Glyph.HIDE -> {
                path.reset(); path.moveTo(2f, 12f); path.quadTo(12f, 2f, 22f, 12f); path.quadTo(12f, 22f, 2f, 12f)
                c.drawPath(path, stroke)
                circle(c, 12f, 12f, 3f)
                line(c, 4f, 20f, 20f, 4f)
            }
            Glyph.STOP -> { circle(c, 12f, 12f, 9.5f); rect(c, 8.5f, 8.5f, 15.5f, 15.5f, 1f) }
            Glyph.TRASH -> {
                line(c, 3.5f, 6f, 20.5f, 6f)
                poly(c, 9f, 6f, 9f, 3f, 15f, 3f, 15f, 6f)
                poly(c, 5.5f, 6f, 7f, 21f, 17f, 21f, 18.5f, 6f)
                line(c, 10f, 10f, 10f, 17f); line(c, 14f, 10f, 14f, 17f)
            }
            Glyph.CHECK -> poly(c, 4f, 12.5f, 9.5f, 18f, 20f, 6.5f)
            Glyph.HOME -> poly(c, 3f, 11f, 12f, 3.5f, 21f, 11f, 21f, 4f + 16.5f, 3f, 20.5f, close = true)
            Glyph.REFRESH -> {
                arc(c, 12f, 12f, 8f, -60f, 300f)
                poly(c, 20.5f, 3f, 20.5f, 8.5f, 15f, 8.5f)
            }
            Glyph.STAR -> {
                path.reset()
                for (i in 0 until 10) {
                    val r = if (i % 2 == 0) 10f else 4.2f
                    val a = Math.toRadians(-90.0 + i * 36.0)
                    val x = 12f + r * cos(a).toFloat()
                    val y = 12.5f + r * sin(a).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, stroke)
            }
            Glyph.SEARCH -> { circle(c, 10.5f, 10.5f, 6.5f); line(c, 15.5f, 15.5f, 21f, 21f) }
            Glyph.LINK -> {
                rect(c, 2.5f, 8.5f, 13.5f, 15.5f, 3.5f)
                rect(c, 10.5f, 8.5f, 21.5f, 15.5f, 3.5f)
            }
            Glyph.SUN -> {
                circle(c, 12f, 12f, 4.5f)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    line(c, 12f + 7.5f * cos(a).toFloat(), 12f + 7.5f * sin(a).toFloat(),
                        12f + 10f * cos(a).toFloat(), 12f + 10f * sin(a).toFloat())
                }
            }
            Glyph.CLOUD -> cloud(c, 1f)
            Glyph.PARTLY -> {
                arc(c, 8f, 8f, 4f, 120f, 240f)
                line(c, 8f, 1.5f, 8f, 2.5f); line(c, 1.5f, 8f, 2.5f, 8f); line(c, 3.4f, 3.4f, 4.1f, 4.1f)
                cloud(c, 3f)
            }
            Glyph.RAIN -> {
                cloud(c, -3f)
                line(c, 8f, 18f, 7f, 21f); line(c, 12f, 18f, 11f, 21f); line(c, 16f, 18f, 15f, 21f)
            }
            Glyph.SNOW -> {
                cloud(c, -3f)
                circle(c, 8f, 19.5f, 1f, filled = true); circle(c, 12f, 21f, 1f, filled = true)
                circle(c, 16f, 19.5f, 1f, filled = true)
            }
            Glyph.STORM -> {
                cloud(c, -3f)
                poly(c, 12.5f, 15f, 10f, 19f, 13f, 19f, 11f, 22.5f)
            }
            Glyph.FOG -> {
                line(c, 3f, 8f, 21f, 8f); line(c, 5f, 12f, 19f, 12f)
                line(c, 3f, 16f, 21f, 16f); line(c, 7f, 20f, 17f, 20f)
            }
        }
    }

    override fun setAlpha(alpha: Int) {
        stroke.alpha = alpha; fill.alpha = alpha; invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        stroke.colorFilter = colorFilter; fill.colorFilter = colorFilter; invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
