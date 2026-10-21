package com.hizir.sadehome.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Process
import android.util.Log
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * AirPlay sesini çalar: AAC-ELD (ekran yansıtma sesi) donanım/sistem çözücüsüyle, ALAC (müzik)
 * kendi çözücümüzle. Ağdan gelen paketler önceden ayrılmış tamponlara kopyalanır (pakette bellek
 * ayırma yok), ayrı bir iş parçacığı çözüp AudioTrack'e yazar.
 */
class AudioPlayer : AudioSink {
    private class Packet(val data: ByteArray = ByteArray(MAX_PACKET), var length: Int = 0)

    private val free = ArrayBlockingQueue<Packet>(POOL)
    private val queue = ArrayBlockingQueue<Packet>(POOL)
    @Volatile private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var gain = 1f
    @Volatile private var flushRequested = false
    private var track: AudioTrack? = null

    init {
        repeat(POOL) { free.add(Packet()) }
    }

    override fun start(ct: Int, samplesPerFrame: Int) {
        stop()
        running = true
        thread = Thread({ play(ct, samplesPerFrame) }, "airplay-audio-out").apply { isDaemon = true; start() }
    }

    override fun onPacket(seq: Int, data: ByteArray, length: Int) {
        if (!running || length > MAX_PACKET) return
        // Havuz biterse (oynatma takıldıysa) en eski paketi feda et: gecikme birikmesin.
        val p = free.poll() ?: queue.poll() ?: return
        System.arraycopy(data, 0, p.data, 0, length)
        p.length = length
        queue.offer(p)
    }

    override fun setVolume(db: Float) {
        gain = if (db <= -30f) 0f else ((db + 30f) / 30f).coerceIn(0f, 1f).let { it * it }
        track?.setVolume(gain)
    }

    override fun flush() {
        flushRequested = true
    }

    override fun stop() {
        running = false
        thread?.let {
            it.interrupt()
            runCatching { it.join(500) }
        }
        thread = null
        while (true) free.offer(queue.poll() ?: break)
    }

    private fun newTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * 2, RATE * 4 / 8)) // ~125 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private fun play(ct: Int, spf: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val t = runCatching { newTrack() }.getOrElse {
            Log.e(TAG, "AudioTrack açılamadı", it); return
        }
        track = t
        t.setVolume(gain)
        var codec: MediaCodec? = null
        val alac = if (ct == 2) AlacDecoder(frameLength = spf.coerceIn(1, 4096)) else null
        val pcm = ShortArray(4096 * 2)
        try {
            if (ct == 8 || ct == 4) codec = aacDecoder(ct)
            val info = MediaCodec.BufferInfo()
            var started = false
            while (running) {
                if (flushRequested) {
                    flushRequested = false
                    while (true) free.offer(queue.poll() ?: break)
                    t.pause(); t.flush(); started = false
                    codec?.flush()
                }
                val p = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                try {
                    if (alac != null) {
                        val n = alac.decode(p.data, 0, p.length, pcm)
                        if (n > 0) t.write(pcm, 0, n * 2)
                    } else if (codec != null) {
                        val i = codec.dequeueInputBuffer(10_000)
                        if (i >= 0) {
                            val b = codec.getInputBuffer(i)!!
                            b.clear(); b.put(p.data, 0, p.length)
                            codec.queueInputBuffer(i, 0, p.length, 0, 0)
                        }
                        while (true) {
                            val o = codec.dequeueOutputBuffer(info, 0)
                            if (o < 0) break
                            val ob = codec.getOutputBuffer(o)!!
                            ob.position(info.offset); ob.limit(info.offset + info.size)
                            t.write(ob, info.size, AudioTrack.WRITE_BLOCKING)
                            codec.releaseOutputBuffer(o, false)
                        }
                    }
                } finally {
                    free.offer(p)
                }
                if (!started) {
                    t.play(); started = true
                }
            }
        } catch (e: InterruptedException) {
            // durduruldu
        } catch (t2: Throwable) {
            Log.e(TAG, "ses çalınamadı", t2)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { t.stop() }
            t.release()
            track = null
        }
    }

    /** AAC-ELD 44.1 kHz stereo, 480 örneklik çerçeve (ekran yansıtma). ct=4: AAC-LC. */
    private fun aacDecoder(ct: Int): MediaCodec {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2)
        if (ct == 8) {
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
        } else {
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            f.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
        }
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        val c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        c.configure(f, null, null, 0)
        c.start()
        return c
    }

    companion object {
        private const val TAG = "SadeAirPlay"
        private const val RATE = 44100
        private const val POOL = 512
        private const val MAX_PACKET = 4096
    }
}
