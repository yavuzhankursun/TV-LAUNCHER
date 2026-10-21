package com.hizir.sadehome.system

import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.hizir.sadehome.adb.Adb

/**
 * "TV'yi hızlandır": arka planda RAM tüketen, kapatılması sorun çıkarmayan ön yüklü uygulamalar.
 *
 * Silme (uninstall) yapılmaz; `pm disable-user` ile kapatılır. Bu tamamen geri alınabilir
 * (`pm enable`), sistem güncellemeleri ve fabrika ayarına dönüş etkilenmez. Netflix, TV+, Play Store,
 * Google asistan/sesli arama, Chromecast, klavye ve Google TV ana ekranı bilinçli olarak listede yok.
 */
object Debloat {
    class Item(
        val pkg: String,
        val title: String,
        val note: String,
        /** true: önerilen (güvenle kapatılır). false: kullanıyorsan açık bırak. */
        val recommended: Boolean,
    )

    val ITEMS = listOf(
        Item("com.mitv.gallery", "Xiaomi Galeri", "Kullanılmayan fotoğraf görüntüleyici", true),
        Item("com.xiaomi.mitv.mediaexplorer", "Xiaomi Media Player", "USB'den video oynatmıyorsan gereksiz", true),
        Item("com.google.android.feedback", "Google Geri Bildirim", "Hata raporu gönderme aracı", true),
        Item("com.google.android.play.games", "Google Play Oyunlar", "TV'de oyun hesabı kullanmıyorsan", true),
        Item("com.android.printspooler", "Yazdırma hizmeti", "TV'de yazıcı kullanılmaz", true),
        Item("com.android.dreams.basic", "Basit ekran koruyucu", "Renk değiştiren eski ekran koruyucu", true),
        Item("com.android.dreams.phototable", "Fotoğraf masası koruyucu", "Eski fotoğraf ekran koruyucusu", true),
        Item("com.google.android.marvin.talkback", "TalkBack", "Ekran okuyucu; görme desteği gerekmiyorsa", true),
        Item("com.google.android.tvrecommendations", "Google TV önerileri", "Sadece Google TV ana ekranındaki öneri şeritleri", false),
        Item("com.google.android.youtube.tvkids", "YouTube Kids", "Çocuk profili kullanmıyorsan", false),
        Item("com.google.android.youtube.tvmusic", "YouTube Music", "Müzik için kullanıyorsan açık bırak", false),
        Item("com.xiaomi.mitv.smartshare", "Miracast", "Android telefondan yansıtma; Chromecast/AirPlay etkilenmez", false),
        Item("com.google.android.apps.tv.dreamx", "Ambient mod", "Google fotoğraflı ekran koruyucu; kapatınca sade koruyucu kullanılır", false),
    )

    private val ANIM_KEYS = listOf(
        Settings.Global.WINDOW_ANIMATION_SCALE,
        Settings.Global.TRANSITION_ANIMATION_SCALE,
        Settings.Global.ANIMATOR_DURATION_SCALE,
    )

    /** Cihazda kurulu olanlar ve şu anki durumları (true = açık). */
    fun installed(ctx: Context): List<Pair<Item, Boolean>> {
        val pm = ctx.packageManager
        return ITEMS.mapNotNull { item ->
            val ai = runCatching {
                pm.getApplicationInfo(item.pkg, PackageManager.MATCH_DISABLED_COMPONENTS)
            }.getOrNull() ?: return@mapNotNull null
            val state = runCatching { pm.getApplicationEnabledSetting(item.pkg) }.getOrDefault(0)
            val enabled = ai.enabled && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER &&
                state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            item to enabled
        }
    }

    fun setEnabled(ctx: Context, pkg: String, enable: Boolean, done: (Boolean) -> Unit) {
        require(ITEMS.any { it.pkg == pkg })
        val cmd = if (enable) "pm enable $pkg" else "am force-stop $pkg; pm disable-user --user 0 $pkg"
        Adb.shell(ctx, cmd) { out -> done(out != null && !out.contains("Exception") && !out.contains("Error")) }
    }

    /** Önerilenlerin hepsini kapatır; kapatılan sayısını döndürür. */
    fun disableRecommended(ctx: Context, done: (Int?) -> Unit) {
        val targets = installed(ctx).filter { (item, on) -> item.recommended && on }.map { it.first.pkg }
        if (targets.isEmpty()) return done(0)
        val cmd = targets.joinToString("; ") { "am force-stop $it; pm disable-user --user 0 $it" }
        Adb.shell(ctx, cmd) { out -> done(if (out == null) null else targets.size) }
    }

    fun restoreAll(ctx: Context, done: (Boolean) -> Unit) {
        val targets = installed(ctx).filter { !it.second }.map { it.first.pkg }
        if (targets.isEmpty()) return done(true)
        Adb.shell(ctx, targets.joinToString("; ") { "pm enable $it" }) { done(it != null) }
    }

    /** Pencere/geçiş animasyonlarının ölçeği (1.0 varsayılan). */
    fun animationScale(ctx: Context): Float =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1f)

    /** 0.5x animasyonlar menüleri ve uygulama açılışlarını gözle görülür hızlandırır. */
    fun setAnimationScale(ctx: Context, scale: Float, done: (Boolean) -> Unit) {
        val v = if (scale == 1f) "1.0" else scale.toString()
        Adb.shell(ctx, ANIM_KEYS.joinToString("; ") { "settings put global $it $v" }) { done(it != null) }
    }
}
