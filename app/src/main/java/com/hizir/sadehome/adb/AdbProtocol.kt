package com.hizir.sadehome.adb

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Cihazın kendi adbd'sine (127.0.0.1:5555) bağlanan, bağımlılıksız küçük ADB istemcisi.
 * Böylece launcher, "bağlı bir bilgisayar" gibi davranıp `am force-stop` gibi
 * normalde uygulamalara yasak komutları çalıştırabiliyor. İlk bağlantıda TV'de
 * "Hata ayıklamaya izin verilsin mi?" penceresi çıkar; "her zaman izin ver" seçilince
 * anahtar kalıcı olarak güvenilir hale gelir.
 *
 * Sadece java.* kullanır; JVM üzerinde de test edilebilir.
 */
class AdbKeys private constructor(private val privateKey: PrivateKey, private val publicKey: RSAPublicKey) {

    /** adbd'nin beklediği imza: belirteç zaten SHA-1 özetiymiş gibi PKCS#1 v1.5 ile imzalanır. */
    fun sign(token: ByteArray): ByteArray {
        val sig = Signature.getInstance("NONEwithRSA")
        sig.initSign(privateKey)
        sig.update(SHA1_DIGEST_INFO)
        sig.update(token)
        return sig.sign()
    }

    /** Android'in RSAPublicKey yapısında, base64 + " kullanıcı@makine" biçiminde açık anahtar. */
    fun adbPublicKey(name: String): ByteArray {
        val n = publicKey.modulus
        val words = KEY_BITS / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toLong()
        val rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n)
        val buf = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(words)
        buf.putInt(n0inv.toInt())
        buf.put(littleEndian(n))
        buf.put(littleEndian(rr))
        buf.putInt(publicKey.publicExponent.toInt())
        val b64 = Base64.getEncoder().encodeToString(buf.array())
        return "$b64 $name".toByteArray(Charsets.UTF_8)
    }

    private fun littleEndian(v: BigInteger): ByteArray {
        val be = v.toByteArray()
        val out = ByteArray(KEY_BYTES)
        // toByteArray büyük uçlu ve başında işaret baytı olabilir; son KEY_BYTES baytı ters çevir.
        for (i in 0 until minOf(KEY_BYTES, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    companion object {
        private const val KEY_BITS = 2048
        private const val KEY_BYTES = KEY_BITS / 8
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** Anahtar çiftini [dir] içinde saklar; yoksa üretir. */
        fun loadOrCreate(dir: File): AdbKeys {
            val privFile = File(dir, "adbkey")
            val pubFile = File(dir, "adbkey.pub.der")
            val kf = KeyFactory.getInstance("RSA")
            if (privFile.exists() && pubFile.exists()) {
                runCatching {
                    val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                    val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes())) as RSAPublicKey
                    return AdbKeys(priv, pub)
                }
            }
            val gen = KeyPairGenerator.getInstance("RSA")
            gen.initialize(KEY_BITS)
            val kp = gen.generateKeyPair()
            dir.mkdirs()
            privFile.writeBytes(kp.private.encoded)
            pubFile.writeBytes(kp.public.encoded)
            return AdbKeys(kp.private, kp.public as RSAPublicKey)
        }
    }
}

class AdbAuthException(msg: String) : IOException(msg)

class AdbConnection private constructor(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
    private val output: OutputStream = socket.getOutputStream()
    private var nextLocalId = 1

    private class Message(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

    val isOpen: Boolean get() = !socket.isClosed

    /** Komutu `shell:` servisinde çalıştırır ve tüm çıktısını döndürür. */
    @Synchronized
    fun shell(command: String, timeoutMs: Int = 15_000): String {
        socket.soTimeout = timeoutMs
        val localId = nextLocalId++
        send(A_OPEN, localId, 0, "shell:$command\u0000".toByteArray(Charsets.UTF_8))
        val out = ByteArrayOutputStream()
        while (true) {
            val m = read()
            if (m.arg1 != localId) continue // başka bir akışın (eski) mesajı
            when (m.command) {
                A_OKAY -> Unit
                A_WRTE -> {
                    out.write(m.data)
                    send(A_OKAY, localId, m.arg0, EMPTY)
                }
                A_CLSE -> {
                    if (m.arg0 != 0) send(A_CLSE, localId, m.arg0, EMPTY)
                    return out.toString("UTF-8")
                }
            }
        }
    }

    private fun send(command: Int, arg0: Int, arg1: Int, data: ByteArray) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (b in data) sum += b.toInt() and 0xff
        header.putInt(command).putInt(arg0).putInt(arg1).putInt(data.size).putInt(sum).putInt(command.inv())
        output.write(header.array())
        if (data.isNotEmpty()) output.write(data)
        output.flush()
    }

    private fun read(): Message {
        val h = ByteArray(24)
        input.readFully(h)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val command = bb.int
        val arg0 = bb.int
        val arg1 = bb.int
        val len = bb.int
        bb.int // checksum
        val magic = bb.int
        if (magic != command.inv()) throw IOException("ADB: bozuk mesaj başlığı")
        if (len < 0 || len > MAX_PAYLOAD * 4) throw IOException("ADB: geçersiz uzunluk $len")
        val data = ByteArray(len)
        input.readFully(data)
        return Message(command, arg0, arg1, data)
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val A_CNXN = 0x4e584e43
        private const val A_AUTH = 0x48545541
        private const val A_OPEN = 0x4e45504f
        private const val A_OKAY = 0x59414b4f
        private const val A_CLSE = 0x45534c43
        private const val A_WRTE = 0x45545257
        private const val A_STLS = 0x534c5453
        private const val VERSION = 0x01000001
        private const val MAX_PAYLOAD = 256 * 1024
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        private val EMPTY = ByteArray(0)

        /**
         * Bağlanır ve kimlik doğrular. Anahtar henüz güvenilir değilse ve [allowPrompt] açıksa
         * açık anahtarı gönderir, [onPrompt] çağrılır ve kullanıcı TV'deki pencereyi onaylayana kadar [promptTimeoutMs] bekler.
         */
        fun open(
            host: String,
            port: Int,
            keys: AdbKeys,
            keyName: String,
            promptTimeoutMs: Int = 60_000,
            allowPrompt: Boolean = true,
            onPrompt: () -> Unit = {},
        ): AdbConnection {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), 3000)
            socket.soTimeout = 5000
            val conn = AdbConnection(socket)
            try {
                conn.handshake(keys, keyName, promptTimeoutMs, allowPrompt, onPrompt)
            } catch (t: Throwable) {
                conn.close()
                throw t
            }
            return conn
        }
    }

    private fun handshake(keys: AdbKeys, keyName: String, promptTimeoutMs: Int, allowPrompt: Boolean, onPrompt: () -> Unit) {
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\u0000".toByteArray(Charsets.UTF_8))
        var sentSignature = false
        var sentKey = false
        while (true) {
            val m = try {
                read()
            } catch (e: SocketTimeoutException) {
                throw AdbAuthException(if (sentKey) "TV'deki izin penceresi onaylanmadı" else "ADB yanıt vermedi")
            }
            when (m.command) {
                A_CNXN -> {
                    socket.soTimeout = 15_000
                    return
                }
                A_STLS -> throw IOException("Bu port TLS istiyor (Kablosuz hata ayıklama); 5555 numaralı klasik ADB portunu kullanın")
                A_AUTH -> {
                    if (m.arg0 != AUTH_TOKEN) throw IOException("ADB: beklenmeyen AUTH türü ${m.arg0}")
                    when {
                        !sentSignature -> {
                            sentSignature = true
                            send(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(m.data))
                        }
                        !allowPrompt -> throw AdbAuthException("Anahtar henüz onaylanmamış")
                        !sentKey -> {
                            sentKey = true
                            send(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.adbPublicKey(keyName) + 0.toByte())
                            socket.soTimeout = promptTimeoutMs
                            onPrompt()
                        }
                        else -> throw AdbAuthException("İzin reddedildi")
                    }
                }
                else -> Unit
            }
        }
    }
}
