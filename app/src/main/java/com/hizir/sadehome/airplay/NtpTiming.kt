package com.hizir.sadehome.airplay

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * AirPlay zaman eşitlemesi (NTP benzeri, UDP). Alıcı, gönderenin timingPort'una 3 saniyede bir
 * zaman sorgusu (0xd2) gönderir; gönderen 0xd3 ile yanıtlar. Gönderenden gelen sorgular da yanıtlanır.
 */
class NtpTiming(private val client: InetAddress, private val clientPort: Int, private val log: (String) -> Unit) {
    private val socket = DatagramSocket(0).apply { soTimeout = 500 }
    val port: Int get() = socket.localPort
    @Volatile private var running = true

    init {
        Thread({ run() }, "airplay-ntp").apply { isDaemon = true; start() }
    }

    private fun run() {
        val buf = ByteArray(128)
        val packet = DatagramPacket(buf, buf.size)
        var nextQuery = 0L
        try {
            while (running) {
                val now = System.currentTimeMillis()
                if (clientPort > 0 && now >= nextQuery) {
                    val q = ByteArray(32)
                    q[0] = 0x80.toByte(); q[1] = 0xd2.toByte(); q[2] = 0x00; q[3] = 0x07
                    putNtp(q, 24, now)
                    socket.send(DatagramPacket(q, q.size, client, clientPort))
                    nextQuery = now + 3000
                }
                try {
                    packet.setLength(buf.size)
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                if (packet.length >= 32 && (buf[1].toInt() and 0x7f) == 0x52) {
                    // Gönderenin zaman sorgusu: yanıtla (origin = onun gönderme zamanı).
                    val r = ByteArray(32)
                    r[0] = 0x80.toByte(); r[1] = 0xd3.toByte(); r[2] = 0x00; r[3] = 0x07
                    System.arraycopy(buf, 24, r, 8, 8)
                    val t = System.currentTimeMillis()
                    putNtp(r, 16, t)
                    putNtp(r, 24, t)
                    socket.send(DatagramPacket(r, r.size, packet.address, packet.port))
                }
            }
        } catch (t: Throwable) {
            if (running) log("Zaman eşitleme durdu: $t")
        }
    }

    private fun putNtp(b: ByteArray, at: Int, millis: Long) {
        val secs = millis / 1000 + 2_208_988_800L // 1900'den itibaren
        val frac = ((millis % 1000) shl 32) / 1000
        for (i in 0 until 4) b[at + i] = (secs ushr (24 - 8 * i)).toByte()
        for (i in 0 until 4) b[at + 4 + i] = (frac ushr (24 - 8 * i)).toByte()
    }

    fun close() {
        running = false
        socket.close()
    }
}
