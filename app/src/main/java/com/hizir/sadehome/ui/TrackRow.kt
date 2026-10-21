package com.hizir.sadehome.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout

/**
 * Yatay kaydırılan satır. HorizontalScrollView yerine içeriği translationX ile kaydırır:
 * hareket RenderThread'de çalışır, yeniden düzen (layout) yoktur, zayıf GPU'da akıcıdır.
 * Odaklanan öğe sol kenara yakın sabit bir noktada tutulur (Apple TV / Google TV gibi).
 *
 * Satıra dışarıdan (yukarıdan/aşağıdan) gelindiğinde odak en son seçili öğeye döner.
 */
class TrackRow(context: Context) : ViewGroup(context) {
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private var lastFocused: View? = null
    private val interp = DecelerateInterpolator(1.6f)
    /** Odaklanan öğenin sol kenarının duracağı nokta (px, satırın içerik başlangıcına göre). */
    var anchor = 0

    init {
        clipChildren = false
        clipToPadding = false
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // İçerik genişliği sınırsız ölçülür; satır ise ebeveyninin verdiği genişliği alır.
        content.measure(
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
            getChildMeasureSpec(heightMeasureSpec, paddingTop + paddingBottom, LayoutParams.WRAP_CONTENT),
        )
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize(content.measuredHeight + paddingTop + paddingBottom, heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(paddingLeft, paddingTop, paddingLeft + content.measuredWidth, paddingTop + content.measuredHeight)
        if (changed) lastFocused?.let { scrollTo(it, false) }
    }

    override fun requestChildFocus(child: View?, focused: View?) {
        super.requestChildFocus(child, focused)
        var v = focused
        while (v != null && v.parent !== content) v = v.parent as? View
        if (v != null) {
            lastFocused = v
            scrollTo(v, true)
        }
    }

    private fun scrollTo(item: View, animate: Boolean) {
        val visible = width - paddingLeft - paddingRight
        if (visible <= 0) return
        val maxShift = (content.measuredWidth - visible).coerceAtLeast(0)
        val target = -(item.left - anchor).coerceIn(0, maxShift).toFloat()
        if (animate) {
            content.animate().translationX(target).setDuration(260).setInterpolator(interp).start()
        } else {
            content.animate().cancel()
            content.translationX = target
        }
    }

    fun reset() {
        lastFocused = null
        content.animate().cancel()
        content.translationX = 0f
    }

    /** Bu öğe satıra dönüldüğünde odaklanacak öğe olsun. */
    fun remember(child: View) {
        lastFocused = child
        if (width > 0) scrollTo(child, false)
    }

    fun lastFocusedChild(): View? = lastFocused?.takeIf { it.parent === content }

    override fun addFocusables(views: ArrayList<View>, direction: Int, focusableMode: Int) {
        val last = lastFocusedChild()
        if (!hasFocus() && last != null && last.isFocusable && last.visibility == View.VISIBLE &&
            (direction == View.FOCUS_UP || direction == View.FOCUS_DOWN)
        ) {
            views.add(last)
            return
        }
        super.addFocusables(views, direction, focusableMode)
    }
}
