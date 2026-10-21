package com.hizir.sadehome.airplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.hizir.sadehome.data.Bg
import com.hizir.sadehome.data.Prefs
import java.security.SecureRandom

/** Arayüzün izlediği AirPlay durumu (ana iş parçacığında güncellenir). */
object AirPlayState {
    @Volatile var running = false
        private set
    @Volatile var client: String? = null
        private set
    @Volatile var mirroring = false
        private set
    @Volatile var audio = false
        private set
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    internal fun update(running: Boolean = this.running, client: String? = this.client,
                        mirroring: Boolean = this.mirroring, audio: Boolean = this.audio) {
        Bg.ui {
            this.running = running; this.client = client; this.mirroring = mirroring; this.audio = audio
            listeners.toList().forEach { it() }
        }
    }
}

/**
 * Yerleşik AirPlay alıcısını arka planda açık tutan ön plan servisi. iPhone/iPad/Mac'te
 * Denetim Merkezi → Ekran Yansıtma listesinde TV'nin adı görünür. Boşta CPU harcamaz:
 * iş parçacıkları sadece bağlantı beklerken bloklanır.
 */
class AirPlayService : Service() {
    private var server: AirPlayServer? = null
    private val audio = AudioPlayer()
    private var nsd: NsdManager? = null
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        Bg.run { startServer() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        instance = null
        unregisterMdns()
        server?.stop()
        server = null
        audio.stop()
        MirrorHub.reset()
        releaseWifiLock()
        AirPlayState.update(running = false, client = null, mirroring = false, audio = false)
        super.onDestroy()
    }

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "AirPlay", NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("AirPlay alıcısı açık")
            .setContentText("iPhone veya Mac'ten ekran yansıtabilirsin")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun startServer() {
        val prefs = Prefs.of(this)
        val tables = assets.open(FairPlay.ASSET).use { it.readBytes() }
        val config = AirPlayConfig(
            name = displayName(this),
            deviceId = deviceId(prefs),
            edSeed = seed(prefs),
            fairPlayTables = tables,
        )
        val s = AirPlayServer(config, MirrorHub, audio, listener, { Log.i(TAG, it) })
        s.start()
        server = s
        registerMdns(s, config.name)
        AirPlayState.update(running = true)
    }

    private val listener = object : AirPlayListener {
        override fun onClient(name: String, model: String) {
            AirPlayState.update(client = name.ifBlank { if (model.startsWith("Mac")) "Mac" else "iPhone" })
        }

        override fun onMirrorStart() {
            acquireWifiLock()
            AirPlayState.update(mirroring = true)
            // Yansıtma ekranını aç (erişilebilirlik servisi/üstte gösterme izni sayesinde arka plandan da açılır).
            runCatching {
                startActivity(Intent(this@AirPlayService, MirrorActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }.onFailure { Log.w(TAG, "yansıtma ekranı açılamadı", it) }
        }

        override fun onMirrorStop() {
            MirrorHub.reset()
            AirPlayState.update(mirroring = false)
            if (!AirPlayState.audio) releaseWifiLock()
        }

        override fun onAudioStart() {
            acquireWifiLock()
            AirPlayState.update(audio = true)
        }

        override fun onAudioStop() {
            AirPlayState.update(audio = false)
            if (!AirPlayState.mirroring) releaseWifiLock()
        }

        override fun allowClient(identity: String?, name: String, model: String): Boolean {
            val prefs = Prefs.of(this@AirPlayService)
            if (identity != null && identity in prefs.airPlayTrusted) return true
            return when (Consent.ask(this@AirPlayService, name, model, canRemember = identity != null)) {
                Consent.Answer.DENY -> false
                Consent.Answer.ONCE -> true
                Consent.Answer.ALWAYS -> {
                    identity?.let { prefs.airPlayTrusted = prefs.airPlayTrusted + it }
                    true
                }
            }
        }

        override fun onSessionEnd() {
            MirrorHub.reset()
            releaseWifiLock()
            AirPlayState.update(client = null, mirroring = false, audio = false)
        }
    }

    /** Yansıtmayı durdur (gönderenin bağlantısını keser). */
    fun disconnect() {
        Bg.run { server?.disconnect() }
    }

    private fun registerMdns(s: AirPlayServer, name: String) {
        val m = getSystemService(NsdManager::class.java) ?: return
        nsd = m
        fun register(serviceName: String, type: String, txt: Map<String, String>) {
            val info = NsdServiceInfo().apply {
                this.serviceName = serviceName
                serviceType = type
                port = s.port
                for ((k, v) in txt) setAttribute(k, v)
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) { Log.i(TAG, "duyuruldu: ${i.serviceName} $type") }
                override fun onRegistrationFailed(i: NsdServiceInfo, code: Int) { Log.w(TAG, "duyurulamadı $type: $code") }
                override fun onServiceUnregistered(i: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(i: NsdServiceInfo, code: Int) = Unit
            }
            registrations += l
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
        }
        register(name, "_airplay._tcp", s.airPlayTxt())
        register(s.raopName, "_raop._tcp", s.raopTxt())
    }

    private fun unregisterMdns() {
        val m = nsd ?: return
        registrations.forEach { runCatching { m.unregisterService(it) } }
        registrations.clear()
    }

    /** Yansıtma sırasında Wi-Fi güç tasarrufu kapalı: paket gecikmesi ve takılma azalır. */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wm.createWifiLock(mode, "sade:airplay").apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.release() }
        wifiLock = null
    }

    companion object {
        private const val TAG = "SadeAirPlay"
        private const val CHANNEL = "airplay"
        private const val NOTIFICATION_ID = 7000
        @Volatile var instance: AirPlayService? = null
            private set

        fun start(ctx: Context) {
            if (!Prefs.of(ctx).airPlayEnabled) return
            runCatching { ctx.startForegroundService(Intent(ctx, AirPlayService::class.java)) }
                .onFailure { Log.w(TAG, "AirPlay servisi başlatılamadı", it) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, AirPlayService::class.java))
        }

        fun restart(ctx: Context) {
            stop(ctx)
            Bg.main.postDelayed({ start(ctx) }, 600)
        }

        /**
         * Görünen ad: ayarlardan, yoksa TV'nin cihaz adı + " AirPlay". Cihaz adının kendisi kullanılamaz:
         * Google TV kumanda servisi (_androidtvremote2) aynı adla duyuruluyor ve Android'in mDNS'i aynı
         * adlı ikinci servisi sessizce hiç duyurmuyor (cihazda görüldü) → iPhone listesinde TV çıkmıyordu.
         */
        fun displayName(ctx: Context): String {
            val device = Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)?.trim()?.takeIf { it.isNotEmpty() }
            val base = Prefs.of(ctx).airPlayName?.trim()?.takeIf { it.isNotEmpty() } ?: device ?: "Mi Box"
            return if (device != null && base.equals(device, ignoreCase = true)) "$base AirPlay" else base
        }

        private fun deviceId(prefs: Prefs): String {
            prefs.airPlayId?.let { return it }
            val b = ByteArray(6).also { SecureRandom().nextBytes(it) }
            b[0] = ((b[0].toInt() and 0xfc) or 0x02).toByte() // yerel yönetilen, tekil adres
            val id = b.joinToString(":") { "%02x".format(it) }
            prefs.airPlayId = id
            return id
        }

        private fun seed(prefs: Prefs): ByteArray {
            prefs.airPlaySeed?.let { s -> runCatching { Base64.decode(s, Base64.NO_WRAP) }.getOrNull()?.takeIf { it.size == 32 }?.let { return it } }
            val seed = Ed25519.generateSeed()
            prefs.airPlaySeed = Base64.encodeToString(seed, Base64.NO_WRAP)
            return seed
        }
    }
}
