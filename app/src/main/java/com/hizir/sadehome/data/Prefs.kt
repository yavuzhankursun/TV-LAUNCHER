package com.hizir.sadehome.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Tüm ayarlar tek yerde. HOME yönlendirmesi modu hemen doğru okuyabilsin diye
 * mod değişikliği commit() ile senkron yazılıyor.
 */
class Prefs private constructor(private val sp: SharedPreferences) {

    enum class Mode { NEW, STOCK }
    enum class CardStyle { COLOR, BANNER }
    enum class Sort { CUSTOM, RECENT }

    var mode: Mode
        get() = enumOr(sp.getString(K_MODE, null), Mode.NEW)
        set(v) {
            sp.edit().putString(K_MODE, v.name).commit()
        }

    var cardStyle: CardStyle
        get() = enumOr(sp.getString(K_CARD, null), CardStyle.COLOR)
        set(v) = sp.edit().putString(K_CARD, v.name).apply()

    var sort: Sort
        get() = enumOr(sp.getString(K_SORT, null), Sort.CUSTOM)
        set(v) = sp.edit().putString(K_SORT, v.name).apply()

    /** Elle düzenlenmiş sıra (paket adları). Listede olmayanlar sona, alfabetik eklenir. */
    var order: List<String>
        get() = sp.getString(K_ORDER, "")!!.split(',').filter { it.isNotBlank() }
        set(v) = sp.edit().putString(K_ORDER, v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet(K_HIDDEN, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(K_HIDDEN, v).apply()

    /** ADB anahtarı cihaz tarafından en az bir kez kabul edildi mi. */
    var adbPaired: Boolean
        get() = sp.getBoolean(K_ADB_PAIRED, false)
        set(v) = sp.edit().putBoolean(K_ADB_PAIRED, v).apply()

    var adbPort: Int
        get() = sp.getInt(K_ADB_PORT, 5555)
        set(v) = sp.edit().putInt(K_ADB_PORT, v).apply()

    /** Elle seçilmiş şehir; boşsa IP'den otomatik konum. */
    var weatherCity: String?
        get() = sp.getString(K_W_CITY, null)
        set(v) = sp.edit().putString(K_W_CITY, v).apply()
    var weatherLat: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(K_W_LAT, 0))
        set(v) = sp.edit().putLong(K_W_LAT, java.lang.Double.doubleToRawLongBits(v)).apply()
    var weatherLon: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(K_W_LON, 0))
        set(v) = sp.edit().putLong(K_W_LON, java.lang.Double.doubleToRawLongBits(v)).apply()

    /** Yerleşik AirPlay alıcısı açık mı. */
    var airPlayEnabled: Boolean
        get() = sp.getBoolean(K_AIRPLAY, true)
        set(v) = sp.edit().putBoolean(K_AIRPLAY, v).apply()

    /** "Her zaman izin ver" denmiş gönderenlerin doğrulanmış Ed25519 açık anahtarları (hex). */
    var airPlayTrusted: Set<String>
        get() = sp.getStringSet(K_AIRPLAY_TRUSTED, emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet(K_AIRPLAY_TRUSTED, v).apply()

    /** iPhone/Mac'te görünen ad; boşsa TV'nin cihaz adı. */
    var airPlayName: String?
        get() = sp.getString(K_AIRPLAY_NAME, null)
        set(v) = sp.edit().putString(K_AIRPLAY_NAME, v).apply()

    /** Sabit AirPlay cihaz kimliği ve Ed25519 tohumu (gönderenler TV'yi aynı cihaz olarak tanısın). */
    var airPlayId: String?
        get() = sp.getString(K_AIRPLAY_ID, null)
        set(v) = sp.edit().putString(K_AIRPLAY_ID, v).commit().let { }
    var airPlaySeed: String?
        get() = sp.getString(K_AIRPLAY_SEED, null)
        set(v) = sp.edit().putString(K_AIRPLAY_SEED, v).commit().let { }

    /** Akıcı mod: uygulama açıldıktan sonra arka plandakileri kapatıp RAM'i ona bırak (ADB). */
    var smoothMode: Boolean
        get() = sp.getBoolean(K_SMOOTH, true)
        set(v) = sp.edit().putBoolean(K_SMOOTH, v).apply()

    /**
     * Akıcı modun kapatmayacağı uygulamalar (uzun basma menüsünden seçilir). Hiç ayarlanmadıysa Play Store:
     * arka planda güncelleme indirirken yarıda kesilmesin.
     */
    var smoothKeep: Set<String>
        get() = if (sp.contains(K_SMOOTH_KEEP)) sp.getStringSet(K_SMOOTH_KEEP, emptySet())!!.toSet() else DEFAULT_SMOOTH_KEEP
        set(v) = sp.edit().putStringSet(K_SMOOTH_KEEP, v).apply()

    /** Köşede kare hızı / takılma göstergesi (geliştirme ve doğrulama için). */
    var perfOverlay: Boolean
        get() = sp.getBoolean(K_PERF, false)
        set(v) = sp.edit().putBoolean(K_PERF, v).apply()

    var browserDesktop: Boolean
        get() = sp.getBoolean(K_BROWSER_DESKTOP, true)
        set(v) = sp.edit().putBoolean(K_BROWSER_DESKTOP, v).apply()

    /** "Başlık|url" satırları. */
    var bookmarks: List<Pair<String, String>>
        get() = sp.getString(K_BOOKMARKS, null)?.lines()?.mapNotNull { line ->
            val i = line.indexOf('|')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        } ?: DEFAULT_BOOKMARKS
        set(v) = sp.edit().putString(K_BOOKMARKS, v.joinToString("\n") {
            "${it.first.replace('|', ' ').replace('\n', ' ')}|${it.second}"
        }).apply()

    private inline fun <reified T : Enum<T>> enumOr(name: String?, def: T): T =
        name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: def

    companion object {
        private const val K_MODE = "mode"
        private const val K_CARD = "card_style"
        private const val K_SORT = "sort"
        private const val K_ORDER = "order"
        private const val K_HIDDEN = "hidden"
        private const val K_ADB_PAIRED = "adb_paired"
        private const val K_ADB_PORT = "adb_port"
        private const val K_W_CITY = "weather_city"
        private const val K_W_LAT = "weather_lat"
        private const val K_W_LON = "weather_lon"
        private const val K_BROWSER_DESKTOP = "browser_desktop"
        private const val K_BOOKMARKS = "bookmarks"
        private const val K_AIRPLAY = "airplay"
        private const val K_AIRPLAY_NAME = "airplay_name"
        private const val K_AIRPLAY_ID = "airplay_id"
        private const val K_AIRPLAY_SEED = "airplay_seed"
        private const val K_AIRPLAY_TRUSTED = "airplay_trusted"
        private const val K_SMOOTH = "smooth_mode"
        private const val K_SMOOTH_KEEP = "smooth_keep"
        val DEFAULT_SMOOTH_KEEP = setOf("com.android.vending")
        private const val K_PERF = "perf_overlay"

        val DEFAULT_BOOKMARKS = listOf(
            "Google" to "https://www.google.com",
            "YouTube" to "https://www.youtube.com",
            "Vikipedi" to "https://tr.wikipedia.org",
            "Haberler" to "https://news.google.com/?hl=tr&gl=TR&ceid=TR:tr",
            "X" to "https://x.com",
            "Reddit" to "https://www.reddit.com",
            "Ekşi Sözlük" to "https://eksisozluk.com",
            "Hava durumu" to "https://www.mgm.gov.tr",
        )

        @Volatile private var instance: Prefs? = null

        fun of(ctx: Context): Prefs = instance ?: synchronized(this) {
            instance ?: Prefs(
                // Eski sürümün ayar dosyasından bağımsız, yeni bir dosya.
                ctx.applicationContext.getSharedPreferences("launcher2", Context.MODE_PRIVATE)
            ).also { instance = it }
        }
    }
}
