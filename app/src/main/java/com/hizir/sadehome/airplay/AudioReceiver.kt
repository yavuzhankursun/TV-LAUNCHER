package com.hizir.sadehome.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AirPlay ses akışı (SETUP'ta type 96): RTP/UDP. Her paketin gövdesi, tam 16 baytlık blokları
 * AES-CBC ile şifrelenmiş (her pakette IV baştan), kalan baytlar şifresiz bir ses çerçevesidir.
 * Kontrol portuna gelen 0x56 paketleri yeniden gönderilmiş ses paketleridir (4 baytlık önek + RTP).
 */
class AudioReceiver(
    private val aesKey: ByteArray,
    private val aesIv: ByteArray,
    val ct: Int,
    /** Sadece RTSP'ye bağlanan cihazdan gelen ses paketleri kabul edilir. */
    private val peer: java.net.InetAddress,
    private val sink: AudioSink,
    private val log: (String) -> Unit,
) {
    private val data = DatagramSocket(0).apply { receiveBufferSize = 1 shl 20 }
    private val control = DatagramSocket(0)
    val dataPort: Int get() = data.localPort
    val controlPort: Int get() = control.localPort
    @Volatile private var running = true
    private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
    private val keySpec = SecretKeySpec(aesKey, "AES")
    private val ivSpec = IvParameterSpec(aesIv)
    private val out = ByteArray(16 * 1024)
    /** Son oynatılan sıra numarası; tekrar gelen/geç kalan paketler atlanır. */
    private var lastSeq = -1

    init {
        Thread({ receive(data, 0) }, "airplay-audio").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
        Thread({ receive(control, 4) }, "airplay-audio-ctl").apply { isDaemon = true; start() }
    }

    private fun receive(socket: DatagramSocket, prefix: Int) {
        val buf = ByteArray(16 * 1024)
        val packet = DatagramPacket(buf, buf.size)
        try {
            while (running) {
                packet.setLength(buf.size)
                socket.receive(packet)
                if (packet.address != peer) continue
                val len = packet.length
                if (prefix > 0) {
                    // Kontrol portu: sadece yeniden gönderilen ses paketleri (tür 0x56) işe yarar.
                    if (len < 8 || (buf[1].toInt() and 0x7f) != 0x56) continue
                }
                handle(buf, prefix, len - prefix)
            }
        } catch (e: SocketException) {
            if (running) log("Ses soketi kapandı: ${e.message}")
        } catch (t: Throwable) {
            log("Ses alıcısı hatası: $t")
        }
    }

    @Synchronized
    private fun handle(buf: ByteArray, off: Int, len: Int) {
        if (len <= 12) return // zaman eşitlemesinden önce gelen boş paketler
        if ((buf[off + 1].toInt() and 0x7f) != 0x60) return
        if (ct == 2 && len == 44) return // ALAC biçim bilgisi paketleri, ses içermez
        val seq = ((buf[off + 2].toInt() and 0xff) shl 8) or (buf[off + 3].toInt() and 0xff)
        if (lastSeq >= 0) {
            val d = ((seq - lastSeq) and 0xffff).toShort().toInt()
            if (d <= 0) return
        }
        lastSeq = seq
        val size = len - 12
        if (size > out.size) return
        val enc = size / 16 * 16
        if (enc > 0) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            cipher.doFinal(buf, off + 12, enc, out, 0)
        }
        System.arraycopy(buf, off + 12 + enc, out, enc, size - enc)
        sink.onPacket(seq, out, size)
    }

    /** FLUSH: duraklatma/ileri sarma sonrası sıra numarası sıfırdan kabul edilir. */
    @Synchronized
    fun flush() {
        lastSeq = -1
        sink.flush()
    }

    fun close() {
        running = false
        data.close()
        control.close()
    }
}
