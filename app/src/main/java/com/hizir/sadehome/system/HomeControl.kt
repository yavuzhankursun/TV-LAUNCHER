package com.hizir.sadehome.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.hizir.sadehome.HomeWatcherService
import com.hizir.sadehome.adb.Adb

/** Google TV'nin kendi ana ekranı ve HOME yönlendirme servisiyle ilgili işler. */
object HomeControl {
    val STOCK_PACKAGES = setOf(
        "com.google.android.apps.tv.launcherx",
        "com.google.android.tvlauncher",
        "com.google.android.leanbacklauncher",
    )

    @Volatile private var stockActivities: Set<String>? = null

    /** Stok ana ekranı açan açık niyet (intent). */
    fun stockHomeIntent(ctx: Context): Intent? {
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val candidates = pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo }
            .filter { it.packageName != ctx.packageName && it.packageName != "com.android.settings" &&
                !it.packageName.contains("fallbackhome", ignoreCase = true) }
        val pick = candidates.firstOrNull { it.packageName in STOCK_PACKAGES } ?: candidates.firstOrNull()
            ?: return null
        return Intent(home).setComponent(ComponentName(pick.packageName, pick.name))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * Stok ana ekranın asıl HOME aktivitesinin sınıf adları. Servis sadece bu pencere öne
     * gelince yönlendirme yapar; Google TV'nin aynı paketteki diğer ekranları (ayar paneli vb.) bozulmaz.
     */
    fun stockHomeClasses(ctx: Context): Set<String> {
        stockActivities?.let { return it }
        val pm = ctx.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val set = HashSet<String>()
        for (ri in pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            val ai = ri.activityInfo
            if (ai.packageName !in STOCK_PACKAGES) continue
            set += ai.name
            ai.targetActivity?.let { set += it }
        }
        stockActivities = set
        return set
    }

    fun watcherComponent(ctx: Context) = ComponentName(ctx, HomeWatcherService::class.java)

    fun isWatcherEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = watcherComponent(ctx)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** WRITE_SECURE_SETTINGS izni varsa (ADB ile bir kez verilir) servisi kendimiz açarız. */
    fun enableWatcher(ctx: Context): Boolean {
        if (!Adb.hasSecureSettings(ctx)) return false
        return runCatching {
            val cr = ctx.contentResolver
            val me = watcherComponent(ctx).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val list = current.split(':').filter { it.isNotBlank() && it != me } + me
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            true
        }.getOrDefault(false)
    }

    fun disableWatcher(ctx: Context): Boolean {
        if (!Adb.hasSecureSettings(ctx)) return false
        return runCatching {
            val cr = ctx.contentResolver
            val me = watcherComponent(ctx).flattenToString()
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                current.split(':').filter { it.isNotBlank() && it != me }.joinToString(":"),
            )
            true
        }.getOrDefault(false)
    }

    /** Erişilebilirlik servisleri ve klavye: bunları asla kapatmayız. */
    fun protectedByUser(ctx: Context): Set<String> {
        val cr = ctx.contentResolver
        val out = HashSet<String>()
        Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.forEach {
            ComponentName.unflattenFromString(it)?.packageName?.let(out::add)
        }
        Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)?.let {
            ComponentName.unflattenFromString(it)?.packageName?.let(out::add)
        }
        return out
    }
}
