package com.hizir.sadehome.airplay

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Donanım H.264 çözücü (MediaCodec, senkron mod). Ağ iş parçacığı girişleri doğrudan codec
 * tamponlarına yazar (kare başına bellek ayırma yok); ayrı bir iş parçacığı çıkan kareleri hemen
 * ekrana verir. Gecikmeyi düşük tutmak için kareler "şimdi" zaman damgasıyla bırakılır: ağdan
 * yığılarak gelen kareler birikmez, SurfaceFlinger her yenilemede en günceli gösterir.
 */
class VideoDecoder(
    private val surface: Surface,
    sps: ByteArray,
    pps: ByteArray,
    width: Int,
    height: Int,
    private val onSize: (Int, Int) -> Unit,
    private val onFirstFrame: () -> Unit,
) {
    private val codec: MediaCodec = MediaCodec.createDecoderByType(MIME)
    /** Çözünürlük değişikliğini (iPhone döndürülünce) yeniden kurmadan kaldırabiliyor mu. */
    val adaptive: Boolean
    @Volatile private var running = true
    private var pts = 0L
    private val lock = Object()
    private var csd: ByteArray = startCode + sps + startCode + pps

    init {
        val caps = codec.codecInfo.getCapabilitiesForType(MIME)
        adaptive = caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_AdaptivePlayback)
        val f = MediaFormat.createVideoFormat(MIME, width.coerceAtLeast(16), height.coerceAtLeast(16))
        f.setByteBuffer("csd-0", ByteBuffer.wrap(startCode + sps))
        f.setByteBuffer("csd-1", ByteBuffer.wrap(startCode + pps))
        if (adaptive) {
            f.setInteger(MediaFormat.KEY_MAX_WIDTH, 1920)
            f.setInteger(MediaFormat.KEY_MAX_HEIGHT, 1920)
        }
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // gerçek zamanlı
        if (Build.VERSION.SDK_INT >= 30 &&
            caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        ) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        codec.configure(f, surface, null, 0)
        codec.start()
        Thread({ drain() }, "airplay-vout").apply { priority = Thread.MAX_PRIORITY; isDaemon = true; start() }
    }

    /** Yeni SPS/PPS: bir sonraki karenin başına eklenir (uyarlanabilir çözücü bunu kendisi uygular). */
    fun updateCsd(sps: ByteArray, pps: ByteArray) {
        csd = startCode + sps + startCode + pps
    }

    /** Ağ iş parçacığından: bir erişim birimini çözücüye verir. Çözücü dolu kalırsa kare atlanır. */
    fun feed(data: ByteArray, offset: Int, length: Int, sequenceHeader: Boolean) {
        synchronized(lock) {
            if (!running) return
            try {
                var index = -1
                var waited = 0
                while (index < 0 && waited < 200 && running) {
                    index = codec.dequeueInputBuffer(10_000)
                    waited += 10
                }
                if (index < 0) {
                    Log.w(TAG, "çözücü girişi dolu, kare atlandı")
                    return
                }
                val buf = codec.getInputBuffer(index) ?: return
                buf.clear()
                var size = length
                if (sequenceHeader && csd.size + length <= buf.capacity()) {
                    buf.put(csd)
                    size += csd.size
                }
                if (size > buf.capacity()) {
                    codec.queueInputBuffer(index, 0, 0, pts, 0)
                    return
                }
                buf.put(data, offset, length)
                pts += 16_666
                codec.queueInputBuffer(index, 0, size, pts, 0)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "çözücü giriş hatası", e)
            }
        }
    }

    private fun drain() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        val info = MediaCodec.BufferInfo()
        var first = true
        while (running) {
            try {
                val index = codec.dequeueOutputBuffer(info, 20_000)
                when {
                    index >= 0 -> {
                        codec.releaseOutputBuffer(index, System.nanoTime())
                        if (first) {
                            first = false
                            onFirstFrame()
                        }
                    }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        else f.getInteger(MediaFormat.KEY_WIDTH)
                        val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                        else f.getInteger(MediaFormat.KEY_HEIGHT)
                        onSize(w, h)
                    }
                }
            } catch (e: IllegalStateException) {
                if (!running) break
                Thread.sleep(5)
            }
        }
    }

    fun release() {
        running = false
        synchronized(lock) {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }

    companion object {
        private const val TAG = "SadeAirPlay"
        private const val MIME = "video/avc"
        private val startCode = byteArrayOf(0, 0, 0, 1)
    }
}

/**
 * Ağ (MirrorReceiver) ile ekran (MirrorActivity) arasındaki köprü. Yansıtma başlarken ekran henüz
 * açılmamış olabilir; o arada gelen kareler (ilk anahtar kare dahil) saklanır ve çözücü hazır olunca
 * sırayla verilir. Böylece görüntü ilk kareden itibaren temiz gelir.
 */
object MirrorHub : VideoSink {
    private val lock = Object()
    private var surface: Surface? = null
    private var decoder: VideoDecoder? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var width = 0
    private var height = 0
    private val early = ArrayList<ByteArray>()

    @Volatile var onVideoSize: ((Int, Int) -> Unit)? = null
    @Volatile var onFirstFrame: (() -> Unit)? = null
    @Volatile var onPausedChange: ((Boolean) -> Unit)? = null

    override fun onCodec(sps: ByteArray, pps: ByteArray, width: Int, height: Int) {
        synchronized(lock) {
            val sizeChanged = width != this.width || height != this.height
            this.sps = sps; this.pps = pps; this.width = width; this.height = height
            early.clear()
            val d = decoder
            if (d != null && sizeChanged && !d.adaptive) {
                // Döndürme: uyarlanamayan çözücü yeni boyutla baştan kurulur.
                d.release()
                decoder = null
            } else {
                d?.updateCsd(sps, pps)
            }
            if (decoder == null) createLocked()
        }
        if (width > 0 && height > 0) onVideoSize?.invoke(width, height)
    }

    override fun onFrame(data: ByteArray, length: Int, sequenceHeader: Boolean) {
        val d = synchronized(lock) {
            decoder ?: run {
                if (sps != null && early.size < 240) early += data.copyOf(length)
                null
            }
        } ?: return
        d.feed(data, 0, length, sequenceHeader)
    }

    override fun onPaused(paused: Boolean) {
        onPausedChange?.invoke(paused)
    }

    fun attach(s: Surface) {
        synchronized(lock) {
            surface = s
            if (decoder == null) createLocked()
        }
    }

    fun detach() {
        synchronized(lock) {
            surface = null
            decoder?.release()
            decoder = null
        }
    }

    /** Oturum bitti: her şeyi sıfırla. */
    fun reset() {
        synchronized(lock) {
            decoder?.release()
            decoder = null
            sps = null; pps = null; width = 0; height = 0
            early.clear()
        }
    }

    private fun createLocked() {
        val s = surface ?: return
        val sp = sps ?: return
        val pp = pps ?: return
        val d = try {
            VideoDecoder(s, sp, pp, width, height, { w, h -> onVideoSize?.invoke(w, h) }, { onFirstFrame?.invoke() })
        } catch (t: Throwable) {
            Log.e("SadeAirPlay", "çözücü açılamadı", t)
            return
        }
        decoder = d
        // Ekran açılmadan önce gelen kareleri sırayla ver (ilk anahtar kare burada).
        for (f in early) d.feed(f, 0, f.size, false)
        early.clear()
    }
}
