package com.hizir.sadehome.airplay

/**
 * AirPlay için ALAC (Apple Lossless) çözücü: 16 bit, 1-2 kanal.
 *
 * Apple'ın açık kaynak başvuru çözücüsünü (ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c)
 * birebir izler. Sadece java/kotlin kullanır; çerçeve başına bellek ayırmaz.
 * İş parçacığı güvenli değildir: her akış için ayrı bir örnek kullan.
 * [maxRun] başvuru çözücüsünde de kullanılmaz; yalnızca yapılandırmada durur.
 */
class AlacDecoder(
    val frameLength: Int = 352, val bitDepth: Int = 16, val pb: Int = 40, val mb: Int = 10,
    val kb: Int = 14, val channels: Int = 2, val maxRun: Int = 255,
) {
    init {
        require(bitDepth == 16) { "Yalnızca 16 bit destekleniyor" }
        require(channels in 1..2) { "Yalnızca 1-2 kanal destekleniyor" }
        require(frameLength in 1..65536) { "Geçersiz çerçeve uzunluğu" }
        require(kb in 1..16 && pb in 0..255 && mb in 0..255) { "Geçersiz ALAC parametresi" }
    }

    // Yeniden kullanılan tamponlar
    private val pred = IntArray(frameLength)
    private val mixU = IntArray(frameLength)
    private val mixV = IntArray(frameLength)
    private val shiftBuf = IntArray(frameLength * 2)
    private val coefsU = IntArray(32)
    private val coefsV = IntArray(32)

    // Girdinin sonu sıfırla doldurulmuş kopyası: 32 bitlik okumalar taşmasın
    private var buf = ByteArray(frameLength * 4 + 64 + PAD)
    private var pos = 0     // bit konumu
    private var limit = 0   // toplam bit
    private var ffClamp = false   // true: FFmpeg kırpma kuralı (n + zmode), false: Apple (n)
    private var preferFf = false
    private var edgeHit = false   // bu çerçevede iki kuralın ayrıştığı durum görüldü mü
    private var cleanEnd = false  // çerçeve END + bayt hizasıyla tam bitti mi

    /** Tek bir ALAC çerçevesini çözer; [out]'a iç içe (interleaved) 16 bit PCM yazar. Dönen değer: kanal başına örnek sayısı. Bozuk çerçevede 0 döner, istisna fırlatmaz. */
    fun decode(input: ByteArray, offset: Int, length: Int, out: ShortArray): Int {
        if (offset < 0 || length <= 0 || length > input.size - offset) return 0
        if (length > 1 shl 24) return 0
        if (buf.size < length + PAD) buf = ByteArray(length + PAD) // nadiren: beklenenden büyük paket
        System.arraycopy(input, offset, buf, 0, length)
        java.util.Arrays.fill(buf, length, length + PAD, 0)
        limit = length shl 3

        // Apple ile FFmpeg kodlayıcıları ortalama kırpmasında tek bir uç durumda ayrışır
        // (sıfır dizisinden hemen sonra n = 0xFFFF). Bu durum görülür ve çerçeve temiz
        // bitmezse öbür kuralla yeniden dene; işe yarayan kuralı sonraki çerçeveler için hatırla.
        ffClamp = preferFf
        val n = attempt(out)
        if (!edgeHit || (n > 0 && cleanEnd)) return n
        ffClamp = !preferFf
        val n2 = attempt(out)
        if (n2 > 0 && cleanEnd) { preferFf = ffClamp; return n2 }
        ffClamp = preferFf
        return attempt(out)
    }

    private fun attempt(out: ShortArray): Int {
        pos = 0
        edgeHit = false
        cleanEnd = false
        return try {
            decodeFrame(out)
        } catch (e: Bozuk) {
            0
        } catch (e: RuntimeException) {
            0 // emniyet: beklenmedik dizi taşmaları vb.
        }
    }

    private fun decodeFrame(out: ShortArray): Int {
        var ch = 0
        var n = frameLength
        loop@ while (true) {
            if (limit - pos < 3) {
                if (ch > 0) break else throw BOZUK
            }
            when (bits(3)) {
                ID_SCE, ID_LFE -> {
                    if (ch + 1 > channels) break@loop
                    n = element(false, ch, n, out)
                    ch += 1
                }
                ID_CPE -> {
                    if (ch + 2 > channels) break@loop
                    n = element(true, ch, n, out)
                    ch += 2
                }
                ID_DSE -> {
                    bits(4)
                    val align = bits(1)
                    var count = bits(8)
                    if (count == 255) count += bits(8)
                    if (align != 0) pos = (pos + 7) and 7.inv()
                    skip(count * 8)
                }
                ID_FIL -> {
                    var count = bits(4)
                    if (count == 15) count += bits(8) - 1
                    skip(count * 8)
                }
                ID_END -> {
                    pos = (pos + 7) and 7.inv()
                    cleanEnd = pos == limit
                    break@loop
                }
                else -> throw BOZUK // CCE / PCE desteklenmiyor
            }
            if (ch >= channels) {
                // Kanallar tamam; ardından END ve bayt hizası gelmeli (yalnızca denetim için)
                cleanEnd = limit - pos >= 3 && peek40(pos, 3) == ID_END && ((pos + 10) and 7.inv()) == limit
                break
            }
        }
        if (ch == 0) return 0
        if (ch < channels) {
            // Stereo beklerken mono geldiyse sol kanalı sağa kopyala
            if (out.size < n * channels) throw BOZUK
            var j = 0
            for (i in 0 until n) { out[j + 1] = out[j]; j += 2 }
        }
        return n
    }

    /** SCE (mono) ya da CPE (stereo) öğesini çözer, [out]'a yazar; örnek sayısını döner. */
    private fun element(stereo: Boolean, ch: Int, defaultN: Int, out: ShortArray): Int {
        bits(4) // öğe etiketi
        if (bits(12) != 0) throw BOZUK
        val hdr = bits(4)
        val partial = hdr ushr 3
        var bytesShifted = (hdr ushr 1) and 3
        val escape = hdr and 1
        if (bytesShifted == 3) throw BOZUK
        val shift = bytesShifted * 8
        if (bitDepth - shift < 1) throw BOZUK
        val chanBits = bitDepth - shift + (if (stereo) 1 else 0)

        var n = defaultN
        if (partial != 0) n = (bits(16) shl 16) or bits(16)
        if (n <= 0 || n > frameLength) throw BOZUK
        if (out.size < n * channels) throw BOZUK

        var mixBits = 0
        var mixRes = 0
        if (escape == 0) {
            mixBits = bits(8)
            mixRes = (bits(8) shl 24) shr 24
            var h = bits(8)
            val modeU = h ushr 4
            val denU = h and 15
            h = bits(8)
            val pbU = h ushr 5
            val numU = h and 31
            for (i in 0 until numU) coefsU[i] = (bits(16) shl 16) shr 16
            var modeV = 0; var denV = 0; var pbV = 0; var numV = 0
            if (stereo) {
                h = bits(8); modeV = h ushr 4; denV = h and 15
                h = bits(8); pbV = h ushr 5; numV = h and 31
                for (i in 0 until numV) coefsV[i] = (bits(16) shl 16) shr 16
            }
            // Kaydırılmış alt baytlar artıkların önünde durur; yerini hatırla, atla
            var shiftPos = 0
            if (bytesShifted != 0) {
                shiftPos = pos
                skip(shift * (if (stereo) 2 else 1) * n)
            }
            dynDecomp(n, chanBits, (pb * pbU) / 4)
            if (modeU != 0) unpcBlock(pred, pred, n, coefsU, 31, chanBits, 0)
            unpcBlock(pred, mixU, n, coefsU, numU, chanBits, denU)
            if (stereo) {
                dynDecomp(n, chanBits, (pb * pbV) / 4)
                if (modeV != 0) unpcBlock(pred, pred, n, coefsV, 31, chanBits, 0)
                unpcBlock(pred, mixV, n, coefsV, numV, chanBits, denV)
            }
            if (bytesShifted != 0) {
                val save = pos
                pos = shiftPos
                for (i in 0 until n * (if (stereo) 2 else 1)) shiftBuf[i] = bits(shift)
                pos = save
            }
        } else {
            // Sıkıştırılmamış (kaçış) çerçeve: örnekler ham bitDepth bit, kanallar iç içe
            val s = 32 - bitDepth
            if (stereo) {
                for (i in 0 until n) {
                    mixU[i] = (bits(bitDepth) shl s) shr s
                    mixV[i] = (bits(bitDepth) shl s) shr s
                }
            } else {
                for (i in 0 until n) mixU[i] = (bits(bitDepth) shl s) shr s
            }
            bytesShifted = 0
        }

        // Çıkış: stereo ayrıştırma (mixRes), varsa alt baytları geri ekle, 16 bite indir
        val stride = channels
        if (stereo) {
            var j = ch
            if (mixRes != 0) {
                for (i in 0 until n) {
                    val v = mixV[i]
                    var l = mixU[i] + v - ((mixRes * v) shr mixBits)
                    var r = l - v
                    if (bytesShifted != 0) {
                        l = (l shl shift) or shiftBuf[2 * i]
                        r = (r shl shift) or shiftBuf[2 * i + 1]
                    }
                    out[j] = l.toShort(); out[j + 1] = r.toShort()
                    j += stride
                }
            } else {
                for (i in 0 until n) {
                    var l = mixU[i]
                    var r = mixV[i]
                    if (bytesShifted != 0) {
                        l = (l shl shift) or shiftBuf[2 * i]
                        r = (r shl shift) or shiftBuf[2 * i + 1]
                    }
                    out[j] = l.toShort(); out[j + 1] = r.toShort()
                    j += stride
                }
            }
        } else {
            var j = ch
            for (i in 0 until n) {
                var v = mixU[i]
                if (bytesShifted != 0) v = (v shl shift) or shiftBuf[i]
                out[j] = v.toShort()
                j += stride
            }
        }
        return n
    }

    /** Uyarlamalı Golomb çözümü (ag_dec.c: dyn_decomp). Sonuç [pred]'e yazılır. */
    private fun dynDecomp(num: Int, maxSize: Int, pbL: Int) {
        val b = buf
        val dst = pred
        val maxPos = limit
        val kbL = kb
        val wb = (1 shl kbL) - 1
        var bitPos = pos
        var mbv = mb
        var zmode = 0
        var c = 0
        while (c < num) {
            if (bitPos >= maxPos) throw BOZUK
            var k = 31 - Integer.numberOfLeadingZeros((mbv ushr QBSHIFT) + 3)
            if (k > kbL) k = kbL
            val m = (1 shl k) - 1

            // dyn_get_32bit
            var p = bitPos ushr 3
            var stream = (((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
                ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)) shl (bitPos and 7)
            var n = Integer.numberOfLeadingZeros(stream.inv())
            if (n >= MAX_PREFIX_32) {
                n = streamBits(bitPos + MAX_PREFIX_32, maxSize)
                bitPos += MAX_PREFIX_32 + maxSize
            } else {
                bitPos += n + 1
                if (k != 1) {
                    val v = (stream shl (n + 1)) ushr (32 - k)
                    bitPos += k - 1
                    n *= m
                    if (v >= 2) { n += v - 1; bitPos++ }
                }
            }

            // En düşük bit işaret biti
            val nd = n + zmode
            dst[c++] = (nd ushr 1) xor -(nd and 1)

            mbv = pbL * nd + mbv - ((pbL * mbv) ushr QBSHIFT)
            if (Integer.compareUnsigned(n, N_MAX_MEAN_CLAMP) > 0) {
                mbv = N_MAX_MEAN_CLAMP
            } else if (n == N_MAX_MEAN_CLAMP && zmode != 0) {
                // Apple kırpmaz, FFmpeg kırpar (n + zmode > 0xFFFF)
                edgeHit = true
                if (ffClamp) mbv = N_MAX_MEAN_CLAMP
            }
            zmode = 0

            // Ortalama küçükse ardından bir sıfır dizisi uzunluğu gelir
            if (Integer.compareUnsigned(mbv shl MMULSHIFT, QB) < 0 && c < num) {
                zmode = 1
                val k2 = Integer.numberOfLeadingZeros(mbv) - BITOFF + ((mbv + MOFF) ushr MDENSHIFT)
                if (k2 < 1 || k2 > 16) throw BOZUK
                val mz = ((1 shl k2) - 1) and wb
                // dyn_get
                p = bitPos ushr 3
                stream = (((b[p].toInt() and 0xff) shl 24) or ((b[p + 1].toInt() and 0xff) shl 16) or
                    ((b[p + 2].toInt() and 0xff) shl 8) or (b[p + 3].toInt() and 0xff)) shl (bitPos and 7)
                val pre = Integer.numberOfLeadingZeros(stream.inv())
                var run: Int
                if (pre >= MAX_PREFIX_16) {
                    run = (stream shl MAX_PREFIX_16) ushr 16
                    bitPos += MAX_PREFIX_16 + 16
                } else {
                    bitPos += pre + 1
                    val v = (stream shl (pre + 1)) ushr (32 - k2)
                    bitPos += k2
                    run = pre * mz + v - 1
                    if (v < 2) { run -= v - 1; bitPos-- }
                }
                if (run < 0 || run > num - c) throw BOZUK
                java.util.Arrays.fill(dst, c, c + run, 0)
                c += run
                if (run >= 65535) zmode = 0
                mbv = 0
            }
        }
        if (bitPos > maxPos) throw BOZUK
        pos = bitPos
    }

    /** Uyarlamalı doğrusal öngörücüyü geri alır (dp_dec.c: unpc_block). [src] ile [dst] aynı olabilir (yalnız 31 modunda). */
    private fun unpcBlock(src: IntArray, dst: IntArray, num: Int, coefs: IntArray, numActive: Int, chanBits: Int, denShift: Int) {
        val cs = 32 - chanBits
        dst[0] = src[0]
        if (numActive == 0) {
            if (num > 1 && src !== dst) System.arraycopy(src, 1, dst, 1, num - 1)
            return
        }
        if (numActive == 31) {
            // Birinci derece tümleyici
            var prev = dst[0]
            for (j in 1 until num) {
                prev = ((src[j] + prev) shl cs) shr cs
                dst[j] = prev
            }
            return
        }
        val warm = if (numActive < num - 1) numActive else num - 1
        for (j in 1..warm) dst[j] = ((src[j] + dst[j - 1]) shl cs) shr cs

        val lim = numActive + 1
        val denHalf = if (denShift == 0) 0 else 1 shl (denShift - 1)
        for (j in lim until num) {
            val top = dst[j - lim]
            val base = j - 1
            var sum = 0
            for (k in 0 until numActive) sum += coefs[k] * (dst[base - k] - top)
            var del0 = src[j]
            dst[j] = ((del0 + top + ((sum + denHalf) shr denShift)) shl cs) shr cs
            if (del0 > 0) {
                var k = numActive - 1
                while (k >= 0) {
                    val dd = top - dst[base - k]
                    val sgn = (dd shr 31) or (-dd ushr 31)
                    coefs[k] = ((coefs[k] - sgn) shl 16) shr 16 // int16 taşması başvurudaki gibi
                    del0 -= (numActive - k) * ((sgn * dd) shr denShift)
                    if (del0 <= 0) break
                    k--
                }
            } else if (del0 < 0) {
                var k = numActive - 1
                while (k >= 0) {
                    val dd = top - dst[base - k]
                    val sgn = (dd shr 31) or (-dd ushr 31)
                    coefs[k] = ((coefs[k] + sgn) shl 16) shr 16
                    del0 -= (numActive - k) * ((-sgn * dd) shr denShift)
                    if (del0 >= 0) break
                    k--
                }
            }
        }
    }

    /** Sınır denetimli okuma: [n] bit (1..32), işaretsiz. */
    private fun bits(n: Int): Int {
        if (n > limit - pos) throw BOZUK
        val r = peek40(pos, n)
        pos += n
        return r
    }

    private fun skip(n: Int) {
        if (n < 0 || n > limit - pos) throw BOZUK
        pos += n
    }

    /** Kaçış değeri için [bitOffset]'ten [n] bit (getstreambits); sınır denetimi dyn_decomp sonunda. */
    private fun streamBits(bitOffset: Int, n: Int): Int = peek40(bitOffset, n)

    private fun peek40(bitPos: Int, n: Int): Int {
        val b = buf
        val p = bitPos ushr 3
        val w = ((b[p].toLong() and 0xff) shl 32) or ((b[p + 1].toLong() and 0xff) shl 24) or
            ((b[p + 2].toLong() and 0xff) shl 16) or ((b[p + 3].toLong() and 0xff) shl 8) or
            (b[p + 4].toLong() and 0xff)
        return ((w shl (24 + (bitPos and 7))) ushr (64 - n)).toInt()
    }

    private class Bozuk : RuntimeException(null, null, false, false)

    companion object {
        private const val ID_SCE = 0
        private const val ID_CPE = 1
        private const val ID_LFE = 3
        private const val ID_DSE = 4
        private const val ID_FIL = 6
        private const val ID_END = 7

        private const val QBSHIFT = 9
        private const val QB = 1 shl QBSHIFT
        private const val MMULSHIFT = 2
        private const val MDENSHIFT = QBSHIFT - MMULSHIFT - 1
        private const val MOFF = 1 shl (MDENSHIFT - 2)
        private const val BITOFF = 24
        private const val MAX_PREFIX_16 = 9
        private const val MAX_PREFIX_32 = 9
        private const val N_MAX_MEAN_CLAMP = 0xffff
        private const val PAD = 32

        private val BOZUK = Bozuk()

        /**
         * SDP "a=fmtp" satırından çözücü kurar, ör. "96 352 0 16 40 10 14 2 255 0 0 44100".
         * Sıra: frameLength, compatibleVersion, bitDepth, pb, mb, kb, numChannels, maxRun,
         * maxFrameBytes, avgBitRate, sampleRate (baştaki yük türü "96" olabilir de olmayabilir de).
         * Desteklenmeyen değerlerde null döner.
         */
        fun fromFmtp(fmtp: String): AlacDecoder? {
            val f = fmtp.substringAfter("fmtp:").trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull() }
            val o = if (f.size == 11) 0 else 1
            if (f.size < 8 + o) return null
            return try {
                AlacDecoder(frameLength = f[o], bitDepth = f[o + 2], pb = f[o + 3], mb = f[o + 4], kb = f[o + 5],
                    channels = f[o + 6], maxRun = f[o + 7])
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
