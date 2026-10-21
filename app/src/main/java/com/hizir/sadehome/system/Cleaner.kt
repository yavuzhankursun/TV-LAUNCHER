package com.hizir.sadehome.system

import android.content.Context
import android.content.pm.ApplicationInfo
import com.hizir.sadehome.adb.Adb
import com.hizir.sadehome.data.AppEntry
import com.hizir.sadehome.data.Memory
import com.hizir.sadehome.data.Prefs

/**
 * "Boşalt": arka planda çalışan uygulamaları ADB üzerinden `am force-stop` ile kapatır.
 * Uygulama verisi, oturum ve ayarlar silinmez; sadece süreç sonlanır.
 */
object Cleaner {
    class Result(val before: Int, val after: Int, val stopped: List<String>)

    /** Sistemin, kumandanın, Google hizmetlerinin ve Xiaomi TV servislerinin çalışması için şart olanlar. */
    private val NEVER = setOf(
        "android", "com.android.systemui", "com.android.phone", "com.android.bluetooth",
        "com.android.networkstack", "com.android.se", "com.android.nfc", "com.android.shell",
        "com.google.android.gms", "com.google.android.gsf", "com.google.android.ext.services",
        "com.google.android.permissioncontroller", "com.google.android.katniss",
        "com.google.android.tv.remote.service", "com.google.android.tvrecommendations",
        "com.google.android.apps.mediashell", // Chromecast (Google Cast) alıcısı
        "com.android.providers.media", "com.android.providers.tv", "com.android.providers.settings",
    )
    private val NEVER_PREFIX = listOf("com.android.providers.", "com.google.android.gms.", "com.android.inputmethod")
    private val SAFE_NAME = Regex("^[A-Za-z0-9_.]+$")

    fun run(ctx: Context, apps: List<AppEntry>, airPlayPkg: String?, done: (Result) -> Unit, failed: (String) -> Unit) {
        val app = ctx.applicationContext
        val prefs = Prefs.of(app)
        val launchable = apps.associateBy { it.pkg }
        Adb.session(app, allowPrompt = false, block = { sh ->
            val before = Memory.usedPercent(app)
            val running = runningPackages(sh("ps -A -o USER,NAME"))
            val protect = HashSet<String>().apply {
                addAll(NEVER)
                addAll(HomeControl.STOCK_PACKAGES)
                addAll(HomeControl.protectedByUser(app))
                add(app.packageName)
                if (prefs.keepAirPlay && airPlayPkg != null) add(airPlayPkg)
            }
            val pm = app.packageManager
            val targets = running.filter { pkg ->
                if (pkg in protect || NEVER_PREFIX.any { pkg.startsWith(it) } || !SAFE_NAME.matches(pkg)) return@filter false
                if (pkg in launchable) return@filter true
                // Başlatıcısı olmayan ama kullanıcının sonradan kurduğu paketler de kapatılabilir.
                val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@filter false
                (ai.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            }
            if (targets.isNotEmpty()) {
                sh(targets.joinToString("; ") { "am force-stop $it" })
            }
            // Önbellekteki (cached) arka plan süreçlerini de bırak.
            sh("am kill-all")
            Thread.sleep(1500)
            val after = Memory.usedPercent(app)
            val names = targets.map { launchable[it]?.label ?: it }
            com.hizir.sadehome.data.Bg.ui { done(Result(before, after, names)) }
        }, failed = failed)
    }

    /** `ps -A -o USER,NAME` çıktısından uygulama süreçlerinin paket adları. */
    fun runningPackages(ps: String): Set<String> = ps.lineSequence()
        .drop(1)
        .mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 2) return@mapNotNull null
            val user = parts[0]
            // Uygulama kullanıcıları u0_a123 biçimindedir; sistem süreçleri (system, root, shell) hariç.
            if (!user.matches(Regex("u\\d+_a\\d+"))) return@mapNotNull null
            parts[1].substringBefore(':').takeIf { it.contains('.') }
        }
        .toSet()

    fun forceStop(ctx: Context, pkg: String, done: (Boolean) -> Unit) {
        if (!SAFE_NAME.matches(pkg)) return done(false)
        Adb.shell(ctx, "am force-stop $pkg") { done(it != null) }
    }
}
