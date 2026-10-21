package com.hizir.sadehome.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import com.hizir.sadehome.R
import com.hizir.sadehome.ui.Glyph

/**
 * Uygulamanın kartta ve büyük logoda nasıl görüneceği. Öncelik sırası:
 * 1. [Kind.BRAND]: Simple Icons'tan sade marka simgesi (vektör, her boyutta keskin) + resmi marka rengi.
 * 2. [Kind.GLYPH]: uygulamanın kendi çizgi ikonu (Ayarlar, Tarayıcı).
 * 3. [Kind.SILHOUETTE]: uyarlanabilir ikonun logo katmanı beyaz silüet olarak.
 * 4. [Kind.ICON]: hiçbiri olmazsa uygulamanın renkli ikonu.
 */
class Mark(val kind: Kind, val brandRes: Int = 0, val glyph: Glyph? = null, val color: Int? = null) {
    enum class Kind { BRAND, GLYPH, SILHOUETTE, ICON }
}

object Marks {
    private class Brand(val res: Int, val color: Int)

    // Renkler Simple Icons'un resmi marka renkleri; çok koyu/gri olanlar kartta okunsun diye hafif açıldı.
    private val BRANDS = mapOf(
        "com.netflix.ninja" to Brand(R.drawable.brand_netflix, 0xFFE50914.toInt()),
        "com.apple.atve.androidtv.appletv" to Brand(R.drawable.brand_appletv, 0xFF2C2C30.toInt()), // resmi: #000000
        "com.google.android.youtube.tv" to Brand(R.drawable.brand_youtube, 0xFFFF0000.toInt()),
        "com.google.android.youtube.tvmusic" to Brand(R.drawable.brand_youtubemusic, 0xFFFF0000.toInt()),
        "com.android.vending" to Brand(R.drawable.brand_googleplay, 0xFF01875F.toInt()), // resmi: #414141
        "com.mubi" to Brand(R.drawable.brand_mubi, 0xFF1F1F23.toInt()), // resmi: #000000
        "com.spotify.tv.android" to Brand(R.drawable.brand_spotify, 0xFF1DB954.toInt()),
        "tv.twitch.android.app" to Brand(R.drawable.brand_twitch, 0xFF9146FF.toInt()),
        "tv.twitch.android.viewer" to Brand(R.drawable.brand_twitch, 0xFF9146FF.toInt()),
    )

    private val GLYPHS = mapOf(
        "com.android.tv.settings" to Glyph.GEAR,
        "com.android.settings" to Glyph.GEAR,
    )

    /** Silüet, logo katmanının en fazla bu kadarını kaplıyorsa "logo" sayılır; daha doluysa fotoğraf gibidir. */
    private const val MAX_COVERAGE = 0.55f

    fun of(ctx: Context, pkg: String, icon: Drawable): Mark {
        BRANDS[pkg]?.let { return Mark(Mark.Kind.BRAND, brandRes = it.res, color = it.color) }
        GLYPHS[pkg]?.let { return Mark(Mark.Kind.GLYPH, glyph = it) }
        if (pkg == ctx.packageName) return Mark(Mark.Kind.GLYPH, glyph = Glyph.GLOBE)
        val fg = (icon as? AdaptiveIconDrawable)?.foreground
        if (fg != null) {
            val probe = silhouette(fg, 48)
            val ok = coverage(probe) in 0.04f..MAX_COVERAGE
            probe.recycle()
            if (ok) return Mark(Mark.Kind.SILHOUETTE)
        }
        return Mark(Mark.Kind.ICON)
    }

    /** Büyük logo ve kart simgesi için [sizePx] boyutunda beyaz (ya da renkli) bitmap. Arka planda çağrılmalı. */
    fun render(ctx: Context, mark: Mark, component: ComponentName, sizePx: Int): Bitmap? {
        val pm = ctx.packageManager
        return when (mark.kind) {
            Mark.Kind.BRAND -> ctx.getDrawable(mark.brandRes)?.let { drawFit(it, sizePx, 0.78f) }
            Mark.Kind.GLYPH -> mark.glyph?.let { drawFit(com.hizir.sadehome.ui.GlyphDrawable(it, Color.WHITE), sizePx, 0.82f) }
            Mark.Kind.SILHOUETTE -> {
                val fg = (runCatching { pm.getActivityIcon(component) }.getOrNull() as? AdaptiveIconDrawable)?.foreground
                fg?.let { silhouette(it, sizePx) }
            }
            Mark.Kind.ICON -> hiResIcon(ctx, component)?.let { roundedIcon(it, sizePx) }
        }
    }

    private fun drawFit(d: Drawable, size: Int, fill: Float): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val s = (size * fill).toInt()
        val off = (size - s) / 2
        d.setBounds(off, off, off + s, off + s)
        d.draw(Canvas(out))
        return out
    }

    /** Uyarlanabilir ön katman 108 birim; görünen kısım ortadaki 72 birim. Kırpıp beyaza boyar. */
    private fun silhouette(fg: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = (size * 108f / 72f).toInt()
        val off = -(full - size) / 2
        fg.setBounds(off, off, off + full, off + full)
        fg.draw(c)
        toneToWhite(out)
        return out
    }

    /**
     * Logoyu beyaza çevirir ama içindeki açık-koyu farkını saydamlık olarak korur: tek renkli logolar
     * düz beyaz kalır, Play Games gibi çok tonlu logolarda iç detay (kumanda) kaybolmaz.
     */
    private fun toneToWhite(b: Bitmap) {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        var lo = 255; var hi = 0
        for (p in px) {
            if ((p ushr 24) < 40) continue
            val l = luma(p)
            if (l < lo) lo = l
            if (l > hi) hi = l
        }
        val range = (hi - lo).coerceAtLeast(1)
        val flat = hi - lo < 48 // neredeyse tek ton: düz beyaz
        for (i in px.indices) {
            val a = px[i] ushr 24
            if (a == 0) continue
            val k = if (flat) 1f else 0.45f + 0.55f * (luma(px[i]) - lo) / range
            px[i] = ((a * k).toInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
        }
        b.setPixels(px, 0, b.width, 0, 0, b.width, b.height)
    }

    private fun luma(p: Int) = (((p shr 16) and 0xFF) * 54 + ((p shr 8) and 0xFF) * 183 + (p and 0xFF) * 19) shr 8

    private fun coverage(b: Bitmap): Float {
        val px = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        return px.count { (it ushr 24) > 40 }.toFloat() / px.size
    }

    /** Klasik ikonlar düşük yoğunlukta yüklenince büyütüldüğünde bulanık olur; en yüksek yoğunluktan al. */
    private fun hiResIcon(ctx: Context, component: ComponentName): Drawable? {
        val pm = ctx.packageManager
        return runCatching {
            val ai = pm.getActivityInfo(component, 0)
            val res = pm.getResourcesForApplication(ai.applicationInfo)
            val id = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
            res.getDrawableForDensity(id, DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull() ?: runCatching { pm.getActivityIcon(component) }.getOrNull()
    }

    private fun roundedIcon(d: Drawable, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val r = size * 0.22f
        c.clipPath(Path().apply { addRoundRect(0f, 0f, size.toFloat(), size.toFloat(), r, r, Path.Direction.CW) })
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return out
    }
}
