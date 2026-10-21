package com.hizir.sadehome.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Cloudflare'in herkese açık hız testi uç noktalarıyla gecikme / indirme / yükleme ölçümü.
 * Paralel bağlantılar kullanılır; ilk saniye (TCP ısınması) hesaba katılmaz.
 */
class SpeedTest(private val listener: Listener) {
    interface Listener {
        fun onPing(ms: Int)
        fun onDownload(mbps: Double, done: Boolean)
        fun onUpload(mbps: Double, done: Boolean)
        fun onError(message: String)
        fun onFinished()
    }

    private val cancelled = AtomicBoolean(false)

    fun cancel() = cancelled.set(true)

    fun start() {
        thread(name = "sade-speed", isDaemon = true) {
            try {
                val ping = measurePing()
                if (cancelled.get()) return@thread
                Bg.ui { listener.onPing(ping) }
                val down = measure(download = true, streams = 4, seconds = 8)
                if (cancelled.get()) return@thread
                Bg.ui { listener.onDownload(down, true) }
                val up = measure(download = false, streams = 2, seconds = 6)
                if (cancelled.get()) return@thread
                Bg.ui { listener.onUpload(up, true); listener.onFinished() }
            } catch (e: Exception) {
                if (!cancelled.get()) Bg.ui { listener.onError(e.message ?: "Bağlantı hatası") }
            }
        }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 8000
        useCaches = false
        setRequestProperty("User-Agent", "SadeHome/2.0")
    }

    private fun measurePing(): Int {
        val samples = ArrayList<Long>()
        repeat(7) { i ->
            val t0 = System.nanoTime()
            val c = open("$BASE/__down?bytes=0")
            try {
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
            // İlk istek DNS + TLS el sıkışmasını içerdiği için atılır.
            if (i > 0) samples += (System.nanoTime() - t0) / 1_000_000
        }
        samples.sort()
        return samples[samples.size / 2].toInt()
    }

    private fun measure(download: Boolean, streams: Int, seconds: Int): Double {
        val bytes = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val errors = AtomicLong(0)
        val workers = (0 until streams).map {
            thread(isDaemon = true) {
                val buf = ByteArray(64 * 1024)
                while (!stop.get() && !cancelled.get()) {
                    try {
                        if (download) {
                            val c = open("$BASE/__down?bytes=25000000")
                            try {
                                c.inputStream.use { s ->
                                    while (!stop.get()) {
                                        val n = s.read(buf)
                                        if (n < 0) break
                                        bytes.addAndGet(n.toLong())
                                    }
                                }
                            } finally {
                                c.disconnect()
                            }
                        } else {
                            val size = 8 * 1024 * 1024
                            val c = open("$BASE/__up")
                            c.requestMethod = "POST"
                            c.doOutput = true
                            c.setFixedLengthStreamingMode(size)
                            c.setRequestProperty("Content-Type", "application/octet-stream")
                            try {
                                c.outputStream.use { o ->
                                    var left = size
                                    while (left > 0 && !stop.get()) {
                                        val n = minOf(left, buf.size)
                                        o.write(buf, 0, n)
                                        bytes.addAndGet(n.toLong())
                                        left -= n
                                    }
                                }
                                if (!stop.get()) c.responseCode
                            } finally {
                                c.disconnect()
                            }
                        }
                    } catch (e: IOException) {
                        if (errors.incrementAndGet() > 6) break
                    }
                }
            }
        }
        val start = System.nanoTime()
        var warmBytes = 0L
        var warmTime = 0L
        var result = 0.0
        val end = start + seconds * 1_000_000_000L
        while (System.nanoTime() < end && !cancelled.get()) {
            Thread.sleep(250)
            val now = System.nanoTime()
            if (warmTime == 0L && now - start >= 1_000_000_000L) {
                warmTime = now; warmBytes = bytes.get()
            }
            if (warmTime != 0L && now > warmTime) {
                result = (bytes.get() - warmBytes) * 8.0 / ((now - warmTime) / 1e9) / 1e6
                val r = result
                Bg.ui { if (download) listener.onDownload(r, false) else listener.onUpload(r, false) }
            }
            if (errors.get() > 6 && bytes.get() == 0L) break
        }
        stop.set(true)
        workers.forEach { it.join(1500) }
        if (bytes.get() == 0L) throw IOException("Sunucuya ulaşılamadı")
        return result
    }

    companion object {
        private const val BASE = "https://speed.cloudflare.com"
    }
}
