package com.hizir.sadehome.ui

import android.content.Context
import android.graphics.Rect
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Sağdan açılan panel (uzun basma menüsü, ayarlar, hız testi…). Açıkken arkadaki ekran odak
 * alamaz; Geri tuşu paneli kapatır ve odak önceki yerine döner.
 */
class SidePanel(private val host: FrameLayout, private val behind: ViewGroup) {
    private val ctx: Context = host.context
    private val scrim = View(ctx).apply {
        setBackgroundColor(0x99000000.toInt())
        alpha = 0f
    }
    private val sheet = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = roundRect(Palette.PANEL, ctx.dp(0f))
        setPadding(ctx.dp(28), ctx.dp(34), ctx.dp(28), ctx.dp(24))
        isClickable = true
    }
    private val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        addView(sheet, FrameLayout.LayoutParams(MATCH, WRAP))
    }
    private var restoreFocus: View? = null
    private var onClose: (() -> Unit)? = null
    var isOpen = false
        private set
    var tag: String? = null
        private set

    private val width = ctx.dp(370)

    init {
        host.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))
        host.addView(scroll, FrameLayout.LayoutParams(width, MATCH, Gravity.END))
        scroll.translationX = width.toFloat()
        scrim.visibility = View.GONE
        scroll.visibility = View.GONE
    }

    /**
     * Paneli açar ya da (aynı [tag] açıksa) içeriğini yeniler.
     * [focusIndex]: içerik yenilenince odaklanacak satırın sırası.
     */
    fun show(tag: String, onClose: (() -> Unit)? = null, focusIndex: Int = 0, build: Builder.() -> Unit) {
        val refreshing = isOpen && this.tag == tag
        if (!isOpen) restoreFocus = host.rootView.findFocus()
        this.tag = tag
        this.onClose = onClose
        sheet.removeAllViews()
        val b = Builder(ctx, sheet)
        b.build()
        if (!refreshing && !isOpen) {
            isOpen = true
            behind.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            scrim.visibility = View.VISIBLE
            scroll.visibility = View.VISIBLE
            scrim.animate().alpha(1f).setDuration(200).start()
            scroll.animate().translationX(0f).setDuration(240)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.8f)).start()
        }
        isOpen = true
        val rows = b.focusables
        (rows.getOrNull(focusIndex) ?: rows.firstOrNull())?.requestFocus()
    }

    fun close() {
        if (!isOpen) return
        isOpen = false
        tag = null
        scrim.animate().alpha(0f).setDuration(180).withEndAction { scrim.visibility = View.GONE }.start()
        scroll.animate().translationX(width.toFloat()).setDuration(200)
            .withEndAction { scroll.visibility = View.GONE; sheet.removeAllViews() }.start()
        behind.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        restoreFocus?.takeIf { it.isAttachedToWindow }?.requestFocus()
        restoreFocus = null
        onClose?.invoke()
        onClose = null
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (!isOpen) return false
        if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) close()
            return true
        }
        return false
    }

    class Builder(private val ctx: Context, private val root: LinearLayout) {
        val focusables = mutableListOf<View>()

        fun title(text: String, subtitle: String? = null) {
            root.addView(ctx.text(text, 24f, Palette.TEXT, Fonts.bold), lp(MATCH, WRAP))
            if (subtitle != null) {
                root.addView(
                    ctx.text(subtitle, 13f, Palette.TEXT_DIM).apply { maxLines = 2 },
                    lp(MATCH, WRAP).margins(t = ctx.dp(6)),
                )
            }
            root.addView(View(ctx), lp(MATCH, ctx.dp(18)))
        }

        fun header(text: String) {
            root.addView(
                ctx.text(text.uppercase(java.util.Locale.forLanguageTag("tr")), 11f, Palette.TEXT_FAINT, Fonts.bold).apply {
                    letterSpacing = 0.08f
                },
                lp(MATCH, WRAP).margins(l = ctx.dp(14), t = ctx.dp(14), b = ctx.dp(6)),
            )
        }

        fun paragraph(text: CharSequence): TextView {
            val tv = ctx.text(text, 13f, Palette.TEXT_DIM).apply {
                maxLines = 30
                ellipsize = null
                setLineSpacing(ctx.dp(3f), 1f)
            }
            root.addView(tv, lp(MATCH, WRAP).margins(l = ctx.dp(14), r = ctx.dp(8), t = ctx.dp(4), b = ctx.dp(10)))
            return tv
        }

        /** Büyük değer gösteren satır (hız testi gibi). */
        fun stat(label: String, value: String, unit: String): TextView {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = roundRect(0x14FFFFFF, ctx.dp(14f))
                setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            }
            row.addView(ctx.text(label, 12f, Palette.TEXT_DIM, Fonts.medium))
            val line = LinearLayout(ctx).apply { gravity = Gravity.BOTTOM }
            val v = ctx.text(value, 34f, Palette.TEXT, Fonts.bold)
            line.addView(v)
            line.addView(ctx.text(unit, 14f, Palette.TEXT_DIM, Fonts.medium), lp(WRAP, WRAP).margins(l = ctx.dp(6), b = ctx.dp(5)))
            row.addView(line, lp(MATCH, WRAP).margins(t = ctx.dp(4)))
            root.addView(row, lp(MATCH, WRAP).margins(b = ctx.dp(10)))
            return v
        }

        fun row(
            title: String,
            subtitle: String? = null,
            glyph: Glyph? = null,
            value: String? = null,
            checked: Boolean = false,
            danger: Boolean = false,
            onClick: () -> Unit,
        ): PanelRow {
            val r = PanelRow(ctx, title, subtitle, glyph, value, checked, danger)
            r.setOnClickListener { onClick() }
            root.addView(r, lp(MATCH, WRAP).margins(b = ctx.dp(4)))
            focusables += r
            return r
        }
    }
}

class PanelRow(
    context: Context,
    title: String,
    subtitle: String?,
    glyph: Glyph?,
    value: String?,
    checked: Boolean,
    danger: Boolean,
) : LinearLayout(context) {
    private val baseColor = if (danger) Palette.BAD else Palette.TEXT
    private val icon = glyph?.let { GlyphDrawable(it, baseColor) }
    private val check = if (checked) GlyphDrawable(Glyph.CHECK, Palette.GOOD) else null
    private val titleView = context.text(title, 15f, baseColor, Fonts.medium)
    private val subView = subtitle?.let { context.text(it, 12f, Palette.TEXT_DIM).apply { maxLines = 2 } }
    private val valueView = value?.let { context.text(it, 13f, Palette.TEXT_DIM, Fonts.medium) }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        minimumHeight = dp(50)
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = focusStates(roundRect(0, dp(12f)), roundRect(Palette.FOCUS_BG, dp(12f)))
        icon?.let { d -> addView(ImageView(context).apply { setImageDrawable(d) }, lp(dp(20), dp(20)).margins(r = dp(14))) }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(titleView)
        subView?.let { texts.addView(it, lp(WRAP, WRAP).margins(t = dp(3))) }
        addView(texts, LayoutParams(0, WRAP, 1f))
        valueView?.let { addView(it, lp(WRAP, WRAP).margins(l = dp(10))) }
        check?.let { d -> addView(ImageView(context).apply { setImageDrawable(d) }, lp(dp(18), dp(18)).margins(l = dp(10))) }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        val fg = if (gainFocus) Palette.FOCUS_TEXT else baseColor
        titleView.setTextColor(fg)
        icon?.color = fg
        subView?.setTextColor(if (gainFocus) 0xB30B0B10.toInt() else Palette.TEXT_DIM)
        valueView?.setTextColor(if (gainFocus) 0xB30B0B10.toInt() else Palette.TEXT_DIM)
        check?.color = if (gainFocus) 0xFF059669.toInt() else Palette.GOOD
    }
}
