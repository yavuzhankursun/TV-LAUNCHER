package com.hizir.sadehome.airplay

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Ekran yansıtma görüntü akışı (SETUP'ta type 110). iPhone/Mac bu porta TCP ile bağlanır; her paket
 * 128 baytlık başlık + gövdedir:
 * - tür 0: AES-CTR ile şifreli H.264 NAL'leri (4 baytlık büyük-uçlu uzunluk önekli),
 * - tür 1: şifresiz avcC (SPS + PPS) — başlangıçta ve görüntü boyutu değişince,
 * - tür 2/5: canlılık/istatistik, yok sayılır.
 * CTR anahtar akışı paketler boyunca kesintisiz devam eder (tek bir Cipher yeterli).
 */
class MirrorReceiver(
    aesKey: ByteArray,
    streamConnectionId: Long,
    private val sink: VideoSink,
    private val log: (String) -> Unit,
    private val onEnd: () -> Unit,
) {
    private val server = ServerSocket(0)
    val port: Int get() = server.localPort
    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    private val cipher: Cipher
    /** Son paket alındığı an (izleme köpeği için). */
    @Volatile var lastPacketAt = System.currentTimeMillis()
        private set

    init {
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(), aesKey).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(), aesKey).copyOf(16)
        cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        Thread({ run() }, "airplay-mirror").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }
    }

    private fun run() {
        try {
            val s = server.accept()
            socket = s
            s.tcpNoDelay = true
            s.receiveBufferSize = 1 shl 20
            server.close()
            log("Görüntü akışı bağlandı: ${s.inetAddress.hostAddress}")
            loop(DataInputStream(s.getInputStream().buffered(256 * 1024)))
        } catch (e: EOFException) {
            log("Görüntü akışı kapandı")
        } catch (e: IOException) {
            if (running) log("Görüntü akışı hatası: ${e.message}")
        } finally {
            close()
            onEnd()
        }
    }

    private fun loop(input: DataInputStream) {
        val header = ByteArray(128)
        val hb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        var payload = ByteArray(512 * 1024)
        var sequenceHeader = false
        var paused = false
        while (running) {
            input.readFully(header)
            val size = hb.getInt(0)
            if (size < 0 || size > 32 * 1024 * 1024) throw IOException("Geçersiz paket boyu: $size")
            if (payload.size < size) payload = ByteArray(maxOf(size, payload.size * 2))
            input.readFully(payload, 0, size)
            lastPacketAt = System.currentTimeMillis()
            when (header[4].toInt() and 0xff) {
                0x00 -> {
                    if (size == 0) continue
                    cipher.update(payload, 0, size, payload, 0)
                    if (toAnnexB(payload, size)) {
                        sink.onFrame(payload, size, sequenceHeader)
                        sequenceHeader = false
                    } else {
                        log("Bozuk görüntü paketi atlandı ($size bayt)")
                    }
                }
                0x01 -> {
                    val option = header[6].toInt() and 0xff
                    val sleeping = option == 0x56 || option == 0x5e
                    if (sleeping != paused) {
                        paused = sleeping
                        sink.onPaused(paused)
                    }
                    if (size < 11) continue
                    if (payload[4] == 'h'.code.toByte() && payload[5] == 'v'.code.toByte()) {
                        log("HEVC akışı desteklenmiyor") // H.265 özelliğini duyurmadığımız için gelmemeli
                        continue
                    }
                    val spsLen = be16(payload, 6)
                    if (8 + spsLen + 3 > size) continue
                    val ppsLen = be16(payload, 8 + spsLen + 1)
                    val ppsAt = 8 + spsLen + 3
                    if (ppsAt + ppsLen > size) continue
                    val sps = payload.copyOfRange(8, 8 + spsLen)
                    val pps = payload.copyOfRange(ppsAt, ppsAt + ppsLen)
                    val w = hb.getFloat(56).toInt().takeIf { it > 0 } ?: hb.getFloat(40).toInt()
                    val h = hb.getFloat(60).toInt().takeIf { it > 0 } ?: hb.getFloat(44).toInt()
                    sink.onCodec(sps, pps, w, h)
                    sequenceHeader = true
                }
                else -> Unit
            }
        }
    }

    /** 4 baytlık uzunluk öneklerini Annex-B başlangıç koduna çevirir (boy değişmez). Geçersizse false. */
    private fun toAnnexB(buf: ByteArray, size: Int): Boolean {
        var i = 0
        while (i < size) {
            if (i + 4 > size) return false
            val n = ((buf[i].toInt() and 0xff) shl 24) or ((buf[i + 1].toInt() and 0xff) shl 16) or
                ((buf[i + 2].toInt() and 0xff) shl 8) or (buf[i + 3].toInt() and 0xff)
            if (n <= 0 || n > size - i - 4) return false
            buf[i] = 0; buf[i + 1] = 0; buf[i + 2] = 0; buf[i + 3] = 1
            if (buf[i + 4].toInt() and 0x80 != 0) return false // forbidden_zero_bit
            i += 4 + n
        }
        return i == size
    }

    private fun be16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 8) or (b[at + 1].toInt() and 0xff)

    fun close() {
        running = false
        runCatching { server.close() }
        runCatching { socket?.close() }
    }

    companion object {
        fun sha512(a: ByteArray, b: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            md.update(a)
            md.update(b)
            return md.digest()
        }
    }
}
