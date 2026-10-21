package com.hizir.sadehome.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Process
import android.util.LruCache
import com.hizir.sadehome.LauncherActivity
import kotlin.math.abs

class AppEntry(
    val pkg: String,
    val component: ComponentName,
    val label: String,
    /** Kartlar için küçük ikon (96 px). */
    val icon: Bitmap,
    /** İkondan çıkarılmış baskın renk. */
    val accent: Int,
    val isSystem: Boolean,
    val isLeanback: Boolean,
    val category: Int,
    /** Kartta ve büyük logoda gösterilecek sade simgenin türü. */
    val mark: Mark,
    /** Kart için önceden çizilmiş simge (beyaz marka/silüet ya da renkli ikon). */
    val markBitmap: Bitmap?,
) {
    @Volatile var banner: Drawable? = null
    @Volatile var lastUsed: Long = 0L
}

/**
 * Kurulu uygulamaları yükler ve önbellekte tutar. Liste sadece uygulama
 * kurulup kaldırılınca/güncellenince yeniden üretilir.
 */
object AppRepository {
    @Volatile private var cache: List<AppEntry>? = null
    @Volatile var dirty = true
        private set
    private val heroCache = LruCache<String, Bitmap>(4)

    fun invalidate() {
        dirty = true
    }

    fun cached(): List<AppEntry>? = cache

    /** Arka planda çağrılmalı. */
    fun load(ctx: Context): List<AppEntry> {
        if (!dirty) cache?.let { return it }
        val pm = ctx.packageManager
        val self = ComponentName(ctx, LauncherActivity::class.java)
        val seen = HashMap<String, AppEntry>()
        val withBanner = Prefs.of(ctx).cardStyle == Prefs.CardStyle.BANNER

        fun add(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName in seen) continue
                val cn = ComponentName(ai.packageName, ai.name)
                if (cn == self) continue
                val label = ri.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { ai.packageName }
                val iconDrawable = runCatching { ai.loadIcon(pm) }.getOrNull()
                    ?: pm.defaultActivityIcon
                val icon = renderIcon(iconDrawable, 96)
                val mark = Marks.of(ctx, ai.packageName, iconDrawable)
                val appInfo = ai.applicationInfo
                val entry = AppEntry(
                    pkg = ai.packageName,
                    component = cn,
                    label = label,
                    icon = icon,
                    accent = mark.color ?: Accent.from(icon, ai.packageName),
                    isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    isLeanback = leanback,
                    category = appInfo.category,
                    mark = mark,
                    markBitmap = runCatching { Marks.render(ctx, mark, cn, MARK_PX) }.getOrNull(),
                )
                if (withBanner) entry.banner = runCatching { ai.loadBanner(pm) ?: appInfo.loadBanner(pm) }.getOrNull()
                seen[ai.packageName] = entry
            }
        }
        add(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        add(Intent.CATEGORY_LAUNCHER, false)

        val list = seen.values.toList()
        applyUsage(ctx, list)
        cache = list
        dirty = false
        heroCache.evictAll()
        return list
    }

    /** Kullanıcının görmek istediği sıra: gizliler çıkarılmış, ayara göre sıralı. */
    fun arrange(ctx: Context, all: List<AppEntry>): List<AppEntry> {
        val prefs = Prefs.of(ctx)
        val hidden = prefs.hidden
        val visible = all.filter { it.pkg !in hidden }
        return when (prefs.sort) {
            Prefs.Sort.RECENT -> visible.sortedWith(
                compareByDescending<AppEntry> { it.lastUsed }.thenBy { it.label.lowercase() }
            )
            Prefs.Sort.CUSTOM -> {
                val order = prefs.order
                val index = order.withIndex().associate { it.value to it.index }
                val (known, rest) = visible.partition { it.pkg in index }
                known.sortedBy { index[it.pkg] } + rest.sortedBy { it.label.lowercase() }
            }
        }
    }

    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Son kullanım zamanlarını günceller; bir şey değiştiyse true döner. Arka planda çağrılmalı. */
    fun applyUsage(ctx: Context, list: List<AppEntry> = cache.orEmpty()): Boolean {
        if (list.isEmpty() || !hasUsageAccess(ctx)) return false
        val usm = ctx.getSystemService(UsageStatsManager::class.java) ?: return false
        val now = System.currentTimeMillis()
        val stats = runCatching {
            usm.queryAndAggregateUsageStats(now - 14L * 24 * 3600 * 1000, now)
        }.getOrNull() ?: return false
        var changed = false
        for (e in list) {
            val s = stats[e.pkg] ?: continue
            // Sadece ön planda gerçekten kullanılmış uygulamalar sayılır.
            val t = if (s.totalTimeInForeground > 0) s.lastTimeUsed else 0L
            if (t != e.lastUsed) {
                e.lastUsed = t; changed = true
            }
        }
        return changed
    }

    /** Ana ekrandaki büyük logo: karttaki sade simgenin hedef boyutta, büyütme bulanıklığı olmadan çizilmiş hali. */
    fun heroLogo(ctx: Context, entry: AppEntry, sizePx: Int): Bitmap? {
        heroCache.get(entry.pkg)?.let { return it }
        val bmp = runCatching { Marks.render(ctx, entry.mark, entry.component, sizePx) }.getOrNull() ?: return null
        heroCache.put(entry.pkg, bmp)
        return bmp
    }

    /** Kart simgesinin piksel boyutu (kartta ~40 dp; 2x yoğunlukta keskin kalsın). */
    private const val MARK_PX = 112

    fun renderIcon(d: Drawable, size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return bmp
    }

    fun categoryLabel(category: Int): String? = when (category) {
        ApplicationInfo.CATEGORY_VIDEO -> "Video"
        ApplicationInfo.CATEGORY_AUDIO -> "Müzik ve ses"
        ApplicationInfo.CATEGORY_GAME -> "Oyun"
        ApplicationInfo.CATEGORY_IMAGE -> "Fotoğraf"
        ApplicationInfo.CATEGORY_NEWS -> "Haberler"
        ApplicationInfo.CATEGORY_SOCIAL -> "Sosyal"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Araçlar"
        ApplicationInfo.CATEGORY_MAPS -> "Harita"
        else -> null
    }

    fun launchIntent(ctx: Context, e: AppEntry): Intent {
        val pm = ctx.packageManager
        val base = (if (e.isLeanback) pm.getLeanbackLaunchIntentForPackage(e.pkg) else null)
            ?: pm.getLaunchIntentForPackage(e.pkg)
            ?: Intent(Intent.ACTION_MAIN).setComponent(e.component)
        return base.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

/** İkondan canlı baskın renk çıkarır; ikon renksizse paket adına göre sabit güzel bir renk seçer. */
object Accent {
    private val fallback = intArrayOf(
        0xFF7C3AED.toInt(), 0xFF2563EB.toInt(), 0xFFDB2777.toInt(), 0xFF0891B2.toInt(),
        0xFF059669.toInt(), 0xFFEA580C.toInt(), 0xFF4F46E5.toInt(), 0xFFBE123C.toInt(),
    )

    fun from(bmp: Bitmap, key: String): Int {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val buckets = FloatArray(36)
        val sums = Array(36) { FloatArray(3) }
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            if (Color.alpha(p) < 128) continue
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.28f || hsv[2] < 0.22f) continue
            val b = (hsv[0] / 10f).toInt().coerceIn(0, 35)
            val w = hsv[1] * hsv[2]
            buckets[b] += w
            sums[b][0] += hsv[0] * w; sums[b][1] += hsv[1] * w; sums[b][2] += hsv[2] * w
        }
        if (small != bmp) small.recycle()
        var best = -1
        for (i in buckets.indices) if (best < 0 || buckets[i] > buckets[best]) best = i
        if (best < 0 || buckets[best] < 6f) return fallback[abs(key.hashCode()) % fallback.size]
        val w = buckets[best]
        val h = sums[best][0] / w
        val s = (sums[best][1] / w).coerceIn(0.55f, 0.95f)
        val v = (sums[best][2] / w).coerceIn(0.55f, 0.85f)
        return Color.HSVToColor(floatArrayOf(h, s, v))
    }
}
