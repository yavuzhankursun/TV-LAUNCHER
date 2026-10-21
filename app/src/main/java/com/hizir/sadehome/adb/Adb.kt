package com.hizir.sadehome.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.hizir.sadehome.data.Bg
import com.hizir.sadehome.data.Prefs
import java.io.IOException
import java.net.ConnectException
import java.util.concurrent.Executors

/**
 * Tek bir kalıcı ADB bağlantısını yönetir. Tüm ADB işleri tek bir iş parçacığında sırayla çalışır.
 */
object Adb {
    enum class Status { UNKNOWN, READY, NEEDS_SETUP, PROMPTING, NOT_LISTENING, ERROR }

    @Volatile var status: Status = Status.UNKNOWN
        private set
    @Volatile var lastError: String? = null
        private set

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "sade-adb").apply { isDaemon = true } }
    private var conn: AdbConnection? = null
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun setStatus(s: Status, err: String? = null) {
        status = s
        lastError = err
        Bg.ui { listeners.toList().forEach { it() } }
    }

    fun isDebuggingEnabled(ctx: Context): Boolean =
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    /** Bağlantı kurulmuş ya da daha önce kabul edilmiş anahtar var mı (pencere çıkmadan bağlanabilir). */
    fun isUsable(ctx: Context) = status == Status.READY || Prefs.of(ctx).adbPaired

    /**
     * Gerekirse bağlanıp komutu çalıştırır. [allowPrompt] false ise TV'de izin penceresi açtırmaz.
     * Sonuç ana iş parçacığında döner; hata olursa null.
     */
    fun shell(ctx: Context, command: String, allowPrompt: Boolean = false, done: (String?) -> Unit = {}) {
        val app = ctx.applicationContext
        exec.execute {
            val out = runCatching { shellBlocking(app, command, allowPrompt) }.getOrNull()
            Bg.ui { done(out) }
        }
    }

    /** Sadece ADB iş parçacığında ya da [exec] üzerinden çağrılmalı. */
    private fun shellBlocking(ctx: Context, command: String, allowPrompt: Boolean): String {
        repeat(2) { attempt ->
            val c = ensure(ctx, allowPrompt)
            try {
                return c.shell(command)
            } catch (e: IOException) {
                // Bağlantı kopmuş olabilir (adbd yeniden başladı vb.); bir kez yeniden dene.
                c.close()
                conn = null
                if (attempt == 1) throw e
            }
        }
        error("ulaşılamaz")
    }

    /** Birden fazla komutu aynı sırayla, arka arkaya çalıştırmak için. */
    fun session(ctx: Context, allowPrompt: Boolean, block: (run: (String) -> String) -> Unit, failed: (String) -> Unit = {}) {
        val app = ctx.applicationContext
        exec.execute {
            try {
                ensure(app, allowPrompt)
                block { cmd -> shellBlocking(app, cmd, false) }
            } catch (t: Throwable) {
                val msg = lastError ?: t.message ?: "ADB hatası"
                Bg.ui { failed(msg) }
            }
        }
    }

    fun connect(ctx: Context, allowPrompt: Boolean = true, done: (Boolean) -> Unit = {}) {
        val app = ctx.applicationContext
        exec.execute {
            val ok = runCatching { ensure(app, allowPrompt) }.isSuccess
            Bg.ui { done(ok) }
        }
    }

    private fun ensure(ctx: Context, allowPrompt: Boolean): AdbConnection {
        conn?.let { if (it.isOpen) return it }
        val prefs = Prefs.of(ctx)
        if (!isDebuggingEnabled(ctx)) {
            setStatus(Status.NEEDS_SETUP, "Geliştirici seçeneklerinde USB hata ayıklama kapalı")
            throw IOException("adb kapalı")
        }
        val keys = AdbKeys.loadOrCreate(ctx.filesDir.resolve("adb"))
        try {
            val c = AdbConnection.open(
                host = "127.0.0.1",
                port = prefs.adbPort,
                keys = keys,
                keyName = "sadehome@tv",
                promptTimeoutMs = 60_000,
                allowPrompt = allowPrompt,
                onPrompt = { setStatus(Status.PROMPTING) },
            )
            conn = c
            val firstTime = !prefs.adbPaired
            prefs.adbPaired = true
            setStatus(Status.READY)
            if (firstTime || !hasSecureSettings(ctx)) grantSelf(ctx, c)
            return c
        } catch (e: ConnectException) {
            setStatus(Status.NOT_LISTENING, "127.0.0.1:${prefs.adbPort} dinlemiyor")
            throw e
        } catch (e: AdbAuthException) {
            prefs.adbPaired = false
            setStatus(Status.NEEDS_SETUP, e.message)
            throw e
        } catch (e: IOException) {
            setStatus(Status.ERROR, e.message)
            throw e
        }
    }

    fun hasSecureSettings(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * İlk bağlantıda kendimize izin veriyoruz:
     * - WRITE_SECURE_SETTINGS: HOME yönlendirme servisini kendimiz açabilelim (zorla durdurulsa bile).
     * - GET_USAGE_STATS: "Kaldığın yerden devam et" ve son kullanılana göre sıralama.
     */
    private fun grantSelf(ctx: Context, c: AdbConnection) {
        val pkg = ctx.packageName
        runCatching {
            c.shell("pm grant $pkg android.permission.WRITE_SECURE_SETTINGS; appops set $pkg GET_USAGE_STATS allow")
        }
    }

    fun disconnect() {
        exec.execute {
            conn?.close()
            conn = null
        }
    }
}
