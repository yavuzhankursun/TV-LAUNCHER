package com.hizir.sadehome.airplay

import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Alıcının kimliği ve yetenekleri. */
class AirPlayConfig(
    /** iPhone/Mac'te görünen ad. */
    val name: String,
    /** "aa:bb:cc:dd:ee:ff" biçiminde sabit cihaz kimliği (MAC gibi). */
    val deviceId: String,
    /** Kalıcı Ed25519 anahtarının 32 baytlık tohumu. */
    val edSeed: ByteArray,
    /** FairPlay tabloları (assets/airplay/fairplay.bin). */
    val fairPlayTables: ByteArray,
    val width: Int = 1920,
    val height: Int = 1080,
    val maxFps: Int = 60,
)

/**
 * AirPlay alıcısı (ekran yansıtma + ses), iPhone, iPad ve Mac ile uyumlu. "AirPlay 2 eski eşleştirme"
 * yolunu izler: pair-setup / pair-verify (Ed25519 + X25519), FairPlay (fp-setup) ile oturum anahtarı,
 * SETUP ile görüntü (H.264, TCP) ve ses (AAC-ELD / ALAC, UDP) akışları.
 *
 * Protokol akışı UxPlay (GPL-3.0) projesinden referans alınarak sıfırdan Kotlin'de yazıldı.
 * Android'e bağımlı değildir; ağ, kripto ve akış çözme masaüstü JVM'de de çalışır.
 */
class AirPlayServer(
    private val config: AirPlayConfig,
    private val video: VideoSink,
    private val audio: AudioSink,
    private val listener: AirPlayListener,
    private val log: (String) -> Unit,
) {
    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val connections = java.util.concurrent.CopyOnWriteArrayList<Conn>()
    /** Akış kurmuş olan tek bağlantı; yeni bir gönderen gelince eskisi kapatılır. */
    @Volatile private var active: Conn? = null

    val port: Int get() = server?.localPort ?: 0
    private val edPublic: ByteArray = Ed25519.publicKey(config.edSeed)
    private val pkHex = edPublic.joinToString("") { "%02x".format(it) }

    /** RAOP (ses) servis adı: "AABBCCDDEEFF@Ad". */
    val raopName: String get() = config.deviceId.replace(":", "").uppercase() + "@" + config.name

    fun airPlayTxt(): LinkedHashMap<String, String> = linkedMapOf(
        "deviceid" to config.deviceId,
        "features" to FEATURES,
        "pw" to "false",
        "flags" to "0x4",
        "model" to MODEL,
        "pk" to pkHex,
        "pi" to PI,
        "srcvers" to VERSION,
        "vv" to "2",
    )

    fun raopTxt(): LinkedHashMap<String, String> = linkedMapOf(
        "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2", "ft" to FEATURES,
        "am" to MODEL, "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sr" to "44100", "ss" to "16",
        "sv" to "false", "tp" to "UDP", "txtvers" to "1", "sf" to "0x4", "vs" to VERSION, "vn" to "65537",
        "pk" to pkHex,
    )

    fun start(preferredPort: Int = 7000) {
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(preferredPort))
        } catch (e: IOException) {
            s.bind(InetSocketAddress(0))
        }
        server = s
        running = true
        Thread({ acceptLoop(s) }, "airplay-rtsp").apply { isDaemon = true; start() }
        log("AirPlay alıcısı ${s.localPort} portunda: ${config.name}")
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        connections.forEach { it.close() }
        connections.clear()
    }

    /** Arayüzden "yansıtmayı durdur": aktif gönderenin bağlantısını keser. */
    fun disconnect() {
        active?.close()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running) {
            val socket = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            val c = Conn(socket)
            connections += c
            Thread({ c.run() }, "airplay-conn").apply { isDaemon = true; start() }
        }
    }

    private inner class Conn(val socket: Socket) {
        private var fairPlay: FairPlay? = null
        private var ourEcdhPrivate: ByteArray? = null
        private var ourEcdhPublic: ByteArray? = null
        private var theirEcdhPublic: ByteArray? = null
        private var theirEdPublic: ByteArray? = null
        private var ecdhSecret: ByteArray? = null
        private var verified = false
        private var aesKey: ByteArray? = null
        private var aesIv: ByteArray? = null
        private var ntp: NtpTiming? = null
        @Volatile private var mirror: MirrorReceiver? = null
        @Volatile private var audioRx: AudioReceiver? = null
        @Volatile private var closed = false

        fun run() {
            try {
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream())
                val output = socket.getOutputStream()
                while (!closed) {
                    val req = RtspRequest.read(input) ?: break
                    val res = handle(req)
                    res.writeTo(output)
                    if (res.disconnect) break
                }
            } catch (e: IOException) {
                if (!closed) log("Bağlantı koptu: ${e.message}")
            } catch (t: Throwable) {
                log("Bağlantı hatası: $t")
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { socket.close() }
            stopStreams(mirror = true, audio = true)
            ntp?.close(); ntp = null
            connections.remove(this)
            if (active === this) {
                active = null
                listener.onSessionEnd()
            }
        }

        private fun stopStreams(mirror: Boolean, audio: Boolean) {
            if (mirror) this.mirror?.let { this.mirror = null; it.close(); listener.onMirrorStop() }
            if (audio) audioRx?.let { audioRx = null; it.close(); this@AirPlayServer.audio.stop(); listener.onAudioStop() }
        }

        private fun handle(req: RtspRequest): RtspResponse {
            val res = RtspResponse(req.protocol)
            val cseq = req.header("CSeq")
            if (cseq != null && req.method != "RECORD") res.headers["Audio-Jack-Status"] = "connected; type=digital"
            try {
                when (req.method) {
                    "GET" -> if (req.url.startsWith("/info")) info(req, res)
                    "POST" -> when (req.url) {
                        "/pair-setup" -> pairSetup(req, res)
                        "/pair-verify" -> pairVerify(req, res)
                        "/fp-setup" -> fpSetup(req, res)
                        "/feedback", "/audioMode" -> Unit
                        else -> log("Bilinmeyen istek: POST ${req.url}")
                    }
                    "OPTIONS" -> res.headers["Public"] =
                        "SETUP, RECORD, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER"
                    "SETUP" -> setup(req, res)
                    "GET_PARAMETER" -> getParameter(req, res)
                    "SET_PARAMETER" -> setParameter(req)
                    "RECORD" -> {
                        res.headers["Audio-Latency"] = "0"
                        res.headers["Audio-Jack-Status"] = "connected; type=analog"
                    }
                    "FLUSH" -> audioRx?.flush()
                    "TEARDOWN" -> teardown(req, res)
                    else -> log("Bilinmeyen istek: ${req.method} ${req.url}")
                }
            } catch (t: Throwable) {
                log("${req.method} ${req.url} işlenemedi: $t")
                res.status(500, "Internal Server Error")
            }
            res.headers["Server"] = "AirTunes/$VERSION"
            if (cseq != null) res.headers["CSeq"] = cseq
            return res
        }

        private fun info(req: RtspRequest, res: RtspResponse) {
            val out = LinkedHashMap<String, Any?>()
            if (req.contentType?.contains("apple-binary-plist") == true) {
                // İlk sorgu: sadece istenen TXT kaydı.
                val q = (req.plist()["qualifier"] as? List<*>)?.firstOrNull() as? String
                if (q == "txtAirPlay") out["txtAirPlay"] = txtRecord(airPlayTxt())
                if (q == "txtRAOP") out["txtRAOP"] = txtRecord(raopTxt())
                res.plist(out)
                return
            }
            out["deviceID"] = config.deviceId
            out["macAddress"] = config.deviceId
            out["pk"] = edPublic
            out["features"] = FEATURES_VALUE
            out["name"] = config.name
            out["pi"] = PI
            out["vv"] = 2L
            out["statusFlags"] = 68L
            out["keepAliveLowPower"] = true
            out["sourceVersion"] = VERSION
            out["keepAliveSendStatsAsBody"] = true
            out["model"] = MODEL
            out["initialVolume"] = -15.0
            out["audioLatencies"] = listOf(
                linkedMapOf("type" to 100L, "inputLatencyMicros" to 0L, "audioType" to "default", "outputLatencyMicros" to false),
                linkedMapOf("type" to 101L, "audioType" to "default", "inputLatencyMicros" to 0L, "outputLatencyMicros" to false),
            )
            out["audioFormats"] = listOf(
                linkedMapOf("audioOutputFormats" to 0x3fffffcL, "type" to 100L, "audioInputFormats" to 0x3fffffcL),
                linkedMapOf("audioOutputFormats" to 0x3fffffcL, "type" to 101L, "audioInputFormats" to 0x3fffffcL),
            )
            out["displays"] = listOf(
                linkedMapOf(
                    "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925",
                    "widthPhysical" to 0L, "heightPhysical" to 0L,
                    "width" to config.width.toLong(), "height" to config.height.toLong(),
                    "widthPixels" to config.width.toLong(), "heightPixels" to config.height.toLong(),
                    "rotation" to false,
                    "refreshRate" to 1.0 / config.maxFps,
                    "maxFPS" to config.maxFps.toLong(),
                    "overscanned" to false,
                    "features" to 14L,
                )
            )
            res.plist(out)
        }

        private fun pairSetup(req: RtspRequest, res: RtspResponse) {
            if (req.body.size != 32) {
                log("Geçersiz pair-setup")
                return
            }
            res.octets(edPublic)
        }

        private fun pairVerify(req: RtspRequest, res: RtspResponse) {
            val d = req.body
            if (d.size < 4) return
            when (d[0].toInt()) {
                1 -> {
                    if (d.size != 4 + 32 + 32) return
                    val theirs = d.copyOfRange(4, 36)
                    theirEcdhPublic = theirs
                    theirEdPublic = d.copyOfRange(36, 68)
                    val priv = X25519.generatePrivateKey()
                    val pub = X25519.publicKey(priv)
                    ourEcdhPrivate = priv
                    ourEcdhPublic = pub
                    val secret = X25519.sharedSecret(priv, theirs)
                    ecdhSecret = secret
                    verified = false
                    val sig = Ed25519.sign(config.edSeed, pub + theirs)
                    val enc = verifyCipher(secret).update(sig)
                    res.octets(pub + enc)
                }
                0 -> {
                    val secret = ecdhSecret
                    val theirEd = theirEdPublic
                    if (d.size != 4 + 64 || secret == null || theirEd == null) return
                    val c = verifyCipher(secret)
                    c.update(ByteArray(64)) // ilk adımda kendi imzamız için kullanılan anahtar akışını atla
                    val sig = c.update(d, 4, 64)
                    if (!Ed25519.verify(theirEd, theirEcdhPublic!! + ourEcdhPublic!!, sig)) {
                        log("pair-verify imzası hatalı")
                        res.disconnect = true
                        return
                    }
                    verified = true
                    res.headers["Content-Type"] = "application/octet-stream"
                }
            }
        }

        private fun verifyCipher(secret: ByteArray): Cipher {
            val key = sha512("Pair-Verify-AES-Key".toByteArray(), secret).copyOf(16)
            val iv = sha512("Pair-Verify-AES-IV".toByteArray(), secret).copyOf(16)
            return Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            }
        }

        private fun fpSetup(req: RtspRequest, res: RtspResponse) {
            val d = req.body
            if (d.size == 16 && d[4].toInt() != 0x03) {
                log("Desteklenmeyen FairPlay türü ${d[4]}")
                res.status(501, "Not Implemented")
                return
            }
            val fp = fairPlay ?: FairPlay(config.fairPlayTables).also { fairPlay = it }
            val reply = fp.setup(d)
            if (reply == null) {
                log("Geçersiz fp-setup (${d.size} bayt)")
                return
            }
            res.octets(reply)
        }

        private fun setup(req: RtspRequest, res: RtspResponse) {
            val p = req.plist()
            val out = LinkedHashMap<String, Any?>()
            val ekey = p["ekey"] as? ByteArray
            val eiv = p["eiv"] as? ByteArray
            if (ekey != null && eiv != null) {
                // İlk SETUP: oturum anahtarı ve zaman eşitlemesi.
                val fp = fairPlay ?: throw IOException("fp-setup yapılmadan SETUP")
                var key = fp.decrypt(ekey)
                // Eşleştirme yapıldıysa anahtar ECDH sırrıyla karıştırılır.
                ecdhSecret?.let { key = sha512(key, it).copyOf(16) }
                aesKey = key
                aesIv = eiv.copyOf(16)
                val previous = active
                if (previous != null && previous !== this) {
                    log("Yeni gönderen bağlandı; önceki oturum kapatılıyor")
                    previous.close()
                }
                active = this
                listener.onClient((p["name"] as? String).orEmpty(), (p["model"] as? String).orEmpty())
                val timingPort = (p["timingPort"] as? Long)?.toInt() ?: 0
                ntp?.close()
                val t = NtpTiming(socket.inetAddress, timingPort, log)
                ntp = t
                out["timingPort"] = t.port.toLong()
                out["eventPort"] = 0L
            }
            val streams = p["streams"] as? List<*>
            if (streams != null) {
                val replies = ArrayList<Any?>()
                for (s in streams) {
                    val sd = s as? Map<*, *> ?: continue
                    when ((sd["type"] as? Long)?.toInt()) {
                        110 -> {
                            val key = aesKey ?: throw IOException("Anahtarsız görüntü SETUP")
                            val id = sd["streamConnectionID"] as? Long ?: 0L
                            stopStreams(mirror = true, audio = false)
                            val m = MirrorReceiver(key, id, video, log) {
                                if (mirror != null) {
                                    mirror = null
                                    listener.onMirrorStop()
                                }
                            }
                            mirror = m
                            listener.onMirrorStart()
                            replies += linkedMapOf("type" to 110L, "dataPort" to m.port.toLong())
                        }
                        96 -> {
                            val key = aesKey ?: throw IOException("Anahtarsız ses SETUP")
                            val ct = (sd["ct"] as? Long)?.toInt() ?: 0
                            val spf = (sd["spf"] as? Long)?.toInt() ?: if (ct == 2) 352 else 480
                            stopStreams(mirror = false, audio = true)
                            audio.start(ct, spf)
                            val a = AudioReceiver(key, aesIv ?: ByteArray(16), ct, audio, log)
                            audioRx = a
                            listener.onAudioStart()
                            replies += linkedMapOf("type" to 96L, "dataPort" to a.dataPort.toLong(), "controlPort" to a.controlPort.toLong())
                        }
                        else -> res.disconnect = true
                    }
                }
                out["streams"] = replies
            }
            res.plist(out)
        }

        private fun getParameter(req: RtspRequest, res: RtspResponse) {
            if (req.contentType == null) {
                res.status(451, "Parameter not understood")
                return
            }
            if (String(req.body).startsWith("volume")) res.text("text/parameters", "volume: 0.000000\r\n")
        }

        private fun setParameter(req: RtspRequest) {
            if (req.contentType != "text/parameters") return // kapak resmi / meta veri: şimdilik yok sayılır
            val text = String(req.body, Charsets.UTF_8)
            if (text.startsWith("volume:")) {
                text.substringAfter(':').trim().toFloatOrNull()?.let { audio.setVolume(it) }
            }
        }

        private fun teardown(req: RtspRequest, res: RtspResponse) {
            val types = (req.plist()["streams"] as? List<*>)?.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Long)?.toInt() }
                .orEmpty()
            res.headers["Connection"] = "close"
            when {
                96 in types -> stopStreams(mirror = false, audio = true)
                110 in types -> stopStreams(mirror = true, audio = false)
                else -> {
                    stopStreams(mirror = true, audio = true)
                    if (active === this) {
                        active = null
                        listener.onSessionEnd()
                    }
                }
            }
        }
    }

    companion object {
        /** UxPlay ile aynı: bit 27 "eski eşleştirme desteği" açık; H.265 ve HLS video kapalı. */
        const val FEATURES = "0x5A7FFEE6,0x0"
        const val FEATURES_VALUE = 0x5A7FFEE6L
        const val MODEL = "AppleTV3,2"
        const val VERSION = "220.68"
        const val PI = "2e388006-13ba-4041-9a67-25dd4a43d536"

        fun sha512(a: ByteArray, b: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(a)
            md.update(b)
            return md.digest()
        }

        /** DNS TXT kaydı biçimi: her girdi için [uzunluk]["anahtar=değer"]. */
        fun txtRecord(map: Map<String, String>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            for ((k, v) in map) {
                val b = "$k=$v".toByteArray(Charsets.UTF_8)
                out.write(minOf(b.size, 255))
                out.write(b, 0, minOf(b.size, 255))
            }
            return out.toByteArray()
        }
    }
}
