package com.hizir.sadehome.browser

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.hizir.sadehome.ui.dp

/** Web sayfası üzerinde çizilen fare imleci. Dokunma almaz; sadece çizer. */
class CursorView(context: Context) : View(context) {
    var cx = 0f
        private set
    var cy = 0f
        private set
    var clicking = false
        set(v) {
            field = v; invalidate()
        }
    var shown = true
        set(v) {
            field = v; invalidate()
        }

    private val arrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(1.6f)
        strokeJoin = Paint.Join.ROUND
        color = 0xFF111111.toInt()
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x553B82F6 }

    init {
        isFocusable = false
        isClickable = false
        val s = context.dp(1f)
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 22 * s)
        arrow.lineTo(6 * s, 16.5f * s)
        arrow.lineTo(10 * s, 25 * s)
        arrow.lineTo(13.5f * s, 23.5f * s)
        arrow.lineTo(9.5f * s, 15f * s)
        arrow.lineTo(17 * s, 15f * s)
        arrow.close()
    }

    fun moveTo(x: Float, y: Float) {
        if (x == cx && y == cy) return
        cx = x; cy = y
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        if (clicking) canvas.drawCircle(cx, cy, dp(14f), ring)
        canvas.save()
        canvas.translate(cx, cy)
        canvas.drawPath(arrow, fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }
}
