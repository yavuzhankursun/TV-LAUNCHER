// Bu dosya playfair'in (UxPlay projesi, lib/playfair + lib/fairplay_playfair.c) Kotlin'e aktarımıdır.
// playfair GPL-3.0 lisanslı olduğundan bu dosya da GPL-3.0 lisanslıdır.
package com.hizir.sadehome.airplay

/**
 * AirPlay FairPlay (SAP) el sıkışması ve oturum anahtarı çözme.
 *
 * [tables]: assets/[ASSET] içeriği. Biçim (tüm sayılar little-endian):
 * "FPT1", u8 bölüm sayısı; her bölüm: u8 ad uzunluğu, ad (ASCII), u8 eleman genişliği (1 ya da 4),
 * u32 eleman sayısı, veri. Genişlik 4 olan bölümler uint32 tablolarıdır (IntArray'e okunur).
 *
 * C'deki uint32_t* dönüşümleri küçük uçlu (x86/ARM) varsayar; burada bayt bayt aynısı yapılır.
 * Çalışma tamponları IntArray (0..255) tutar; C'deki unsigned char atamaları `and 0xff` ile kesilir.
 */
class FairPlay(tables: ByteArray) {
    private val s1: ByteArray
    private val s2: ByteArray
    private val s3: ByteArray
    private val s4: ByteArray
    private val s5: IntArray
    private val s6: IntArray
    private val s7: IntArray
    private val s8: IntArray
    private val s9: IntArray
    private val s10: ByteArray
    private val messageKey: ByteArray
    private val messageIv: ByteArray
    private val zKey: ByteArray
    private val xKey: ByteArray
    private val tKey: ByteArray
    private val indexMangle: ByteArray
    private val initialSessionKey: ByteArray
    private val staticSource1: ByteArray
    private val staticSource2: ByteArray
    private val defaultSap: ByteArray
    private val replyMessage: ByteArray
    private val fpHeader: ByteArray

    /** Faz 2'de gelen 164 baytlık istek (C'deki keymsg). Faz 1 bunu sıfırlar. */
    private var keymsg: ByteArray? = null

    init {
        val bytes = HashMap<String, ByteArray>()
        val words = HashMap<String, IntArray>()
        require(tables.size >= 5 && tables[0] == 'F'.code.toByte() && tables[1] == 'P'.code.toByte() &&
            tables[2] == 'T'.code.toByte() && tables[3] == '1'.code.toByte()) { "fairplay tabloları geçersiz" }
        var p = 4
        val n = tables[p++].toInt() and 0xff
        repeat(n) {
            val nl = tables[p++].toInt() and 0xff
            val name = String(tables, p, nl, Charsets.US_ASCII); p += nl
            val w = tables[p++].toInt() and 0xff
            val count = le32(tables, p); p += 4
            when (w) {
                1 -> { bytes[name] = tables.copyOfRange(p, p + count); p += count }
                4 -> { words[name] = IntArray(count) { le32(tables, p + 4 * it) }; p += 4 * count }
                else -> throw IllegalArgumentException("fairplay tablo genişliği: $w")
            }
        }
        fun b(name: String, size: Int) = bytes[name]?.takeIf { it.size == size }
            ?: throw IllegalArgumentException("fairplay tablosu eksik: $name")
        fun w(name: String, size: Int) = words[name]?.takeIf { it.size == size }
            ?: throw IllegalArgumentException("fairplay tablosu eksik: $name")
        s1 = b("table_s1", 40 * 256)
        s2 = b("table_s2", 144 * 256)
        s3 = b("table_s3", 16 * 256)
        s4 = b("table_s4", 144 * 256)
        s5 = w("table_s5", 256)
        s6 = w("table_s6", 256)
        s7 = w("table_s7", 256)
        s8 = w("table_s8", 256)
        s9 = w("table_s9", 4 * 256)
        s10 = b("table_s10", 16 * 256)
        messageKey = b("message_key", 4 * 144)
        messageIv = b("message_iv", 4 * 16)
        zKey = b("z_key", 16)
        xKey = b("x_key", 16)
        tKey = b("t_key", 16)
        indexMangle = b("index_mangle", 11)
        initialSessionKey = b("initial_session_key", 16)
        staticSource1 = b("static_source_1", 17)
        staticSource2 = b("static_source_2", 47)
        defaultSap = b("default_sap", 276)
        replyMessage = b("reply_message", 4 * 142)
        fpHeader = b("fp_header", 12)
    }

    /** POST /fp-setup gövdesi → yanıt gövdesi. Faz 1 (16 bayt) → 142 bayt; faz 2 (164 bayt) → 32 bayt (keymsg saklanır). Geçersizse null. */
    @Synchronized
    fun setup(request: ByteArray): ByteArray? {
        when (request.size) {
            16 -> {
                if (request[4].toInt() != 0x03) return null // yalnız FairPlay sürüm 3
                val mode = request[14].toInt() and 0xff
                if (mode > 3) return null // C'de dizi dışı okuma olurdu
                keymsg = null
                return replyMessage.copyOfRange(mode * 142, mode * 142 + 142)
            }
            164 -> {
                if (request[4].toInt() != 0x03) return null
                keymsg = request.copyOf()
                val res = ByteArray(32)
                fpHeader.copyInto(res, 0)
                request.copyInto(res, 12, 144, 164)
                return res
            }
            else -> return null
        }
    }

    /** SETUP'taki 72 baytlık "ekey" → 16 baytlık AES anahtarı. setup() faz 2 tamamlanmış olmalı. */
    @Synchronized
    fun decrypt(ekey: ByteArray): ByteArray {
        val msg = keymsg ?: throw IllegalStateException("fp-setup faz 2 yapılmadı")
        require(ekey.size >= 72) { "ekey 72 bayt olmalı" }
        check((msg[12].toInt() and 0xff) <= 3) { "keymsg kipi geçersiz" } // C'de tanımsız davranış
        val sapKey = sessionKey(msg)
        val ks = keySchedule(sapKey)
        val block = IntArray(16) { (ekey[56 + it].toInt() xor zKey[it].toInt()) and 0xff }
        cycle(block, ks)
        return ByteArray(16) {
            (block[it] xor ekey[16 + it].toInt() xor xKey[it].toInt() xor zKey[it].toInt()).toByte()
        }
    }

    // ---- omg_hax.c ----

    private fun u(a: ByteArray, i: Int) = a[i].toInt() and 0xff

    /** generate_key_schedule: 11 tur × 16 bayt (uint32 sözcükleri bellekteki bayt sırasıyla). */
    private fun keySchedule(material: IntArray): IntArray {
        val ks = IntArray(176)
        val buf = IntArray(16) { material[it] xor u(tKey, it) }
        var ti = 0
        for (round in 0 until 11) {
            val o = round * 16
            for (b in 0..3) ks[o + b] = buf[b]
            val t1 = ((31 * ti) % 0x28) shl 8
            val t2 = ((31 * (ti + 1)) % 0x28) shl 8
            val t3 = ((31 * (ti + 2)) % 0x28) shl 8
            val t4 = ((31 * (ti + 3)) % 0x28) shl 8
            ti += 4
            buf[0] = buf[0] xor u(s1, t1 + buf[13]) xor u(indexMangle, round)
            buf[1] = buf[1] xor u(s1, t2 + buf[14])
            buf[2] = buf[2] xor u(s1, t3 + buf[15])
            buf[3] = buf[3] xor u(s1, t4 + buf[12])
            for (b in 0..3) ks[o + 4 + b] = buf[4 + b]
            for (b in 0..3) buf[4 + b] = buf[4 + b] xor buf[b]
            for (b in 0..3) ks[o + 8 + b] = buf[8 + b]
            for (b in 0..3) buf[8 + b] = buf[8 + b] xor buf[4 + b]
            for (b in 0..3) ks[o + 12 + b] = buf[12 + b]
            for (b in 0..3) buf[12 + b] = buf[12 + b] xor buf[8 + b]
        }
        return ks
    }

    private fun permuteBlock1(b: IntArray) {
        b[0] = u(s3, b[0])
        b[4] = u(s3, 0x400 + b[4])
        b[8] = u(s3, 0x800 + b[8])
        b[12] = u(s3, 0xc00 + b[12])
        var t = b[13]
        b[13] = u(s3, 0x100 + b[9])
        b[9] = u(s3, 0xd00 + b[5])
        b[5] = u(s3, 0x900 + b[1])
        b[1] = u(s3, 0x500 + t)
        t = b[2]
        b[2] = u(s3, 0xa00 + b[10])
        b[10] = u(s3, 0x200 + t)
        t = b[6]
        b[6] = u(s3, 0xe00 + b[14])
        b[14] = u(s3, 0x600 + t)
        t = b[3]
        b[3] = u(s3, 0xf00 + b[7])
        b[7] = u(s3, 0x300 + b[11])
        b[11] = u(s3, 0x700 + b[15])
        b[15] = u(s3, 0xb00 + t)
    }

    private fun pt2(i: Int, v: Int) = u(s4, (((71 * i) % 144) shl 8) + v)

    private fun permuteBlock2(b: IntArray, round: Int) {
        val r = round * 16
        b[0] = pt2(r + 0, b[0])
        b[4] = pt2(r + 4, b[4])
        b[8] = pt2(r + 8, b[8])
        b[12] = pt2(r + 12, b[12])
        var t = b[13]
        b[13] = pt2(r + 13, b[9])
        b[9] = pt2(r + 9, b[5])
        b[5] = pt2(r + 5, b[1])
        b[1] = pt2(r + 1, t)
        t = b[2]
        b[2] = pt2(r + 2, b[10])
        b[10] = pt2(r + 10, t)
        t = b[6]
        b[6] = pt2(r + 6, b[14])
        b[14] = pt2(r + 14, t)
        t = b[3]
        b[3] = pt2(r + 3, b[7])
        b[7] = pt2(r + 7, b[11])
        b[11] = pt2(r + 11, b[15])
        b[15] = pt2(r + 15, t)
    }

    private fun putWord(b: IntArray, o: Int, v: Int) {
        b[o] = v and 0xff
        b[o + 1] = (v ushr 8) and 0xff
        b[o + 2] = (v ushr 16) and 0xff
        b[o + 3] = v ushr 24
    }

    private fun getWord(b: IntArray, o: Int) =
        b[o] or (b[o + 1] shl 8) or (b[o + 2] shl 16) or (b[o + 3] shl 24)

    /** cycle: 16 baytlık blok üzerinde ters tur fonksiyonu. */
    private fun cycle(b: IntArray, ks: IntArray) {
        for (i in 0 until 16) b[i] = b[i] xor ks[160 + i]
        permuteBlock1(b)
        for (round in 0 until 9) {
            val k = (9 - round) * 16
            putWord(b, 0, s5[b[3] xor ks[k + 3]] xor s6[b[2] xor ks[k + 2]] xor
                s8[b[0] xor ks[k]] xor s7[b[1] xor ks[k + 1]])
            putWord(b, 4, s6[b[6] xor ks[k + 6]] xor s5[b[7] xor ks[k + 7]] xor
                s8[b[4] xor ks[k + 4]] xor s7[b[5] xor ks[k + 5]])
            putWord(b, 8, s5[b[11] xor ks[k + 11]] xor s6[b[10] xor ks[k + 10]] xor
                s7[b[9] xor ks[k + 9]] xor s8[b[8] xor ks[k + 8]])
            putWord(b, 12, s5[b[15] xor ks[k + 15]] xor s6[b[14] xor ks[k + 14]] xor
                s7[b[13] xor ks[k + 13]] xor s8[b[12] xor ks[k + 12]])
            permuteBlock2(b, 8 - round)
        }
        for (i in 0 until 16) b[i] = b[i] xor ks[i]
    }

    /** decryptMessage: 164 baytlık mesajın 128 baytlık gövdesini çözer. */
    private fun decryptMessage(msg: ByteArray): IntArray {
        val out = IntArray(128)
        val buf = IntArray(16)
        val mode = msg[12].toInt() and 0xff
        val mk = mode * 144
        for (i in 0 until 8) {
            for (j in 0 until 16) {
                buf[j] = if (mode == 3) u(msg, 0x80 - 0x10 * i + j) else u(msg, 0x10 * (i + 1) + j)
            }
            for (j in 0 until 9) {
                val base = 0x80 - 0x10 * j
                fun m(o: Int, v: Int) = u(s2, (((97 * (base + o)) % 144) shl 8) + v) xor u(messageKey, mk + base + o)
                buf[0x0] = m(0x0, buf[0x0])
                buf[0x4] = m(0x4, buf[0x4])
                buf[0x8] = m(0x8, buf[0x8])
                buf[0xc] = m(0xc, buf[0xc])
                var t = buf[0xd]
                buf[0xd] = m(0xd, buf[0x9])
                buf[0x9] = m(0x9, buf[0x5])
                buf[0x5] = m(0x5, buf[0x1])
                buf[0x1] = m(0x1, t)
                t = buf[0x2]
                buf[0x2] = m(0x2, buf[0xa])
                buf[0xa] = m(0xa, t)
                t = buf[0x6]
                buf[0x6] = m(0x6, buf[0xe])
                buf[0xe] = m(0xe, t)
                t = buf[0x3]
                buf[0x3] = m(0x3, buf[0x7])
                buf[0x7] = m(0x7, buf[0xb])
                buf[0xb] = m(0xb, buf[0xf])
                buf[0xf] = m(0xf, t)
                for (w in 0 until 4) {
                    val o = w * 4
                    putWord(buf, o, s9[buf[o]] xor s9[0x100 + buf[o + 1]] xor
                        s9[0x200 + buf[o + 2]] xor s9[0x300 + buf[o + 3]])
                }
            }
            fun q(o: Int, v: Int) = u(s10, (o shl 8) + v)
            buf[0x0] = q(0x0, buf[0x0])
            buf[0x4] = q(0x4, buf[0x4])
            buf[0x8] = q(0x8, buf[0x8])
            buf[0xc] = q(0xc, buf[0xc])
            var t = buf[0xd]
            buf[0xd] = q(0xd, buf[0x9])
            buf[0x9] = q(0x9, buf[0x5])
            buf[0x5] = q(0x5, buf[0x1])
            buf[0x1] = q(0x1, t)
            t = buf[0x2]
            buf[0x2] = q(0x2, buf[0xa])
            buf[0xa] = q(0xa, t)
            t = buf[0x6]
            buf[0x6] = q(0x6, buf[0xe])
            buf[0xe] = q(0xe, t)
            t = buf[0x3]
            buf[0x3] = q(0x3, buf[0x7])
            buf[0x7] = q(0x7, buf[0xb])
            buf[0xb] = q(0xb, buf[0xf])
            buf[0xf] = q(0xf, t)
            if (mode != 3) {
                val o = 0x10 * i
                for (j in 0 until 16) {
                    val iv = if (i > 0) u(msg, o + j) else u(messageIv, mode * 16 + j)
                    out[o + j] = buf[j] xor iv
                }
            } else {
                val o = 0x70 - 0x10 * i
                for (j in 0 until 16) {
                    val iv = if (i < 7) u(msg, o + j) else u(messageIv, mode * 16 + j)
                    out[o + j] = buf[j] xor iv
                }
            }
        }
        return out
    }

    /** generate_session_key(default_sap, msg): 16 baytlık SAP anahtarı. */
    private fun sessionKey(msg: ByteArray): IntArray {
        val dec = decryptMessage(msg)
        val sap = IntArray(320)
        for (i in 0 until 0x11) sap[i] = u(staticSource1, i)
        for (i in 0 until 0x80) sap[0x11 + i] = dec[i]
        for (i in 0 until 0x80) sap[0x91 + i] = u(defaultSap, 0x80 + i)
        for (i in 0 until 0x2f) sap[0x111 + i] = u(staticSource2, i)
        val key = IntArray(16) { u(initialSessionKey, it) }
        val md5 = IntArray(4)
        for (round in 0 until 5) {
            val base = round * 64
            modifiedMd5(sap, base, key, md5)
            sapHash(sap, base, key)
            for (i in 0 until 4) putWord(key, i * 4, getWord(key, i * 4) + md5[i])
        }
        val out = IntArray(16)
        for (i in 0 until 16 step 4) {
            out[i] = key[i + 3]; out[i + 1] = key[i + 2]; out[i + 2] = key[i + 1]; out[i + 3] = key[i]
        }
        for (i in 0 until 16) out[i] = out[i] xor 121
        return out
    }

    // ---- modified_md5.c ----

    /** Değiştirilmiş MD5 sıkıştırması; [out] = anahtar sözcükleri + A,B,C,D (uint32, küçük uçlu okunmuş). */
    private fun modifiedMd5(src: IntArray, off: Int, key: IntArray, out: IntArray) {
        val blk = src.copyOfRange(off, off + 64)
        var a = getWord(key, 0)
        var b = getWord(key, 4)
        var c = getWord(key, 8)
        var d = getWord(key, 12)
        for (i in 0 until 64) {
            val j = when {
                i < 16 -> i
                i < 32 -> (5 * i + 1) % 16
                i < 48 -> (3 * i + 5) % 16
                else -> 7 * i % 16
            }
            val input = (blk[4 * j] shl 24) or (blk[4 * j + 1] shl 16) or (blk[4 * j + 2] shl 8) or blk[4 * j + 3]
            var z = a + input + MD5_K[i]
            val f = when {
                i < 16 -> (b and c) or (b.inv() and d)
                i < 32 -> (b and d) or (c and d.inv())
                i < 48 -> b xor c xor d
                else -> c xor (b or d.inv())
            }
            z = Integer.rotateLeft(z + f, MD5_SHIFT[i])
            z += b
            val tmp = d
            d = c; c = b; b = z; a = tmp
            if (i == 31) {
                // sözcük takasları (blok yerel kopyada)
                swapWords(blk, a and 15, b and 15)
                swapWords(blk, c and 15, d and 15)
                swapWords(blk, (a ushr 4) and 15, (b ushr 4) and 15)
                swapWords(blk, (a ushr 8) and 15, (b ushr 8) and 15)
                swapWords(blk, (a ushr 12) and 15, (b ushr 12) and 15)
            }
        }
        out[0] = getWord(key, 0) + a
        out[1] = getWord(key, 4) + b
        out[2] = getWord(key, 8) + c
        out[3] = getWord(key, 12) + d
    }

    private fun swapWords(blk: IntArray, x: Int, y: Int) {
        for (k in 0..3) {
            val t = blk[4 * x + k]; blk[4 * x + k] = blk[4 * y + k]; blk[4 * y + k] = t
        }
    }

    // ---- sap_hash.c ----

    /** sap_hash: 64 baytlık bloktan 16 baytlık özet; sonucu [keyOut]'a yazar. */
    private fun sapHash(src: IntArray, off: Int, keyOut: IntArray) {
        val b0 = SAP_B0.copyOf()
        val b1 = IntArray(210)
        val b2 = SAP_B2.copyOf()
        val b3 = IntArray(132)
        val b4 = SAP_B4.copyOf()
        // sözcüklerin bayt sırası ters çevrilerek yüklenir
        for (i in 0 until 210) {
            val k = i % 64
            b1[i] = src[off + (k and 3.inv()) + (3 - (i % 4))]
        }
        // karıştırma (indeksler uint32 mod 210)
        for (i in 0 until 840) {
            val x = b1[Integer.remainderUnsigned(i - 155, 210)]
            val y = b1[Integer.remainderUnsigned(i - 57, 210)]
            val z = b1[Integer.remainderUnsigned(i - 13, 210)]
            val w = b1[i % 210]
            b1[i % 210] = (rol8(y, 5) + (rol8(z, 3) xor w) - rol8(x, 7)) and 0xff
        }
        garble(b0, b1, b2, b3, b4)
        for (i in 0 until 16) keyOut[i] = 0xE1
        for (i in 0 until 11) {
            keyOut[i] = if (i == 3) 0x3d else (keyOut[i] + b3[SAP_I0[i] * 4]) and 0xff
        }
        for (i in 0 until 20) keyOut[i % 16] = keyOut[i % 16] xor b0[i]
        for (i in 0 until 35) keyOut[i % 16] = keyOut[i % 16] xor b2[i]
        for (i in 0 until 210) keyOut[i % 16] = keyOut[i % 16] xor b1[i]
        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val x = keyOut[(i - 7) and 15]
                val y = keyOut[i]
                val z = keyOut[(i - 37) and 15]
                val w = keyOut[(i - 177) and 15]
                keyOut[i] = rol8(x, 1) xor y xor rol8(z, 6) xor rol8(w, 5)
            }
        }
    }

    // ---- hand_garble.c ----
    // C: unsigned char değerler int'e yükselir; A..Z unsigned int. Int toplama/çarpma/bit işlemleri
    // mod 2^32 aynıdır; bölme/kalan/sağa kaydırma yalnız negatif olamayan değerlerde yapılır
    // (tek istisna: işaretsiz bölme, Integer.divideUnsigned).

    private fun garble(b0: IntArray, b1: IntArray, b2: IntArray, b3: IntArray, b4: IntArray) {
        var a: Int
        var b: Int
        var c: Int
        var d: Int
        var e: Int
        var f: Int
        var g: Int

        b2[12] = (0x14 + (((b1[64] and 92) or ((b1[99] / 3) and 35)) and b4[rol8x(b4[b1[206] % 21], 4) % 21])) and 0xff
        b1[4] = ((b1[99] / 5) * (b1[99] / 5) * 2) and 0xff
        b2[34] = 0xb8
        b1[153] = (b1[153] xor (b2[b1[203] % 35] * b2[b1[203] % 35] * b1[190])) and 0xff
        b0[3] = (b0[3] - (((b4[b1[205] % 21] ushr 1) and 80) or 0xe6440)) and 0xff
        b0[16] = 0x93
        b0[13] = 0x62
        b1[33] = (b1[33] - (b4[b1[36] % 21] and 0xf6)) and 0xff
        val tmp2 = b2[b1[67] % 35]
        b2[12] = 0x07
        val tmp = b0[b1[181] % 20]
        b1[2] = (b1[2] - 3136) and 0xff
        b0[19] = b4[b1[58] % 21]
        b3[0] = (92 - b2[b1[32] % 35]) and 0xff
        b3[4] = (b2[b1[15] % 35] + 0x9e) and 0xff
        b1[34] = (b1[34] + b4[((b2[b1[15] % 35] + 0x9e) and 0xff) % 21] / 5) and 0xff
        b0[19] = (b0[19] + (0xfffffee6.toInt() - ((b0[b3[4] % 20] ushr 1) and 102))) and 0xff
        b1[15] = ((3 * (((b1[72] ushr (b4[b1[190] % 21] and 7)) xor (b1[72] shl ((7 - (b4[b1[190] % 21] - 1)) and 7))) -
            3 * b4[b1[126] % 21])) xor b1[15]) and 0xff
        b0[15] = (b0[15] xor (b2[b1[181] % 35] * b2[b1[181] % 35] * b2[b1[181] % 35])) and 0xff
        b2[4] = (b2[4] xor (b1[202] / 3)) and 0xff

        a = 92 - b0[b3[0] % 20]
        e = (a and 0xc6) or (b1[105].inv() and 0xc6) or (a and b1[105].inv())
        b2[1] = (b2[1] + e * e * e) and 0xff
        b0[19] = (b0[19] xor (((224 or (b4[b1[92] % 21] and 27)) * b2[b1[41] % 35]) / 3)) and 0xff
        b1[140] = (b1[140] + weirdRor8(92, b1[5] and 7)) and 0xff
        b2[12] = (b2[12] + ((((b1[4].inv() xor b2[b1[12] % 35]) or b1[182]) and 192) or
            ((b1[4].inv() xor b2[b1[12] % 35]) and b1[182]))) and 0xff
        b1[36] = (b1[36] + 125) and 0xff
        b1[124] = rol8x(((((74 and b1[138]) or ((74 or b1[138]) and b0[15])) and b0[b1[43] % 20]) or
            (((74 and b1[138]) or ((74 or b1[138]) and b0[15]) or b0[b1[43] % 20]) and 95)), 4) and 0xff
        b3[8] = ((((b0[b3[4] % 20] and 95) and ((b4[b1[68] % 21] and 46) shl 1)) or 16) xor 92) and 0xff

        a = b1[177] + b4[b1[79] % 21]
        d = (((a ushr 1) or ((3 * b1[148]) / 5)) and b2[1]) or ((a ushr 1) and ((3 * b1[148]) / 5))
        b3[12] = (-34 - d) and 0xff

        a = 8 - (b2[22] and 7)
        b = b1[33] ushr (a and 7)
        c = b1[33] shl (b2[22] and 7)
        b2[16] = (b2[16] + (((b2[b3[0] % 35] and 159) or b0[b3[4] % 20] or 8) - ((b xor c) or 128))) and 0xff
        b0[14] = (b0[14] xor b2[b3[12] % 35]) and 0xff

        a = weirdRol8(b4[b0[b1[201] % 20] % 21], (b2[b1[112] % 35] shl 1) and 7)
        d = (b0[b1[208] % 20] and 131) or (b0[b1[164] % 20] and 124)
        b1[19] = (b1[19] + ((a and (d / 5)) or ((a or (d / 5)) and 37))) and 0xff

        b2[8] = weirdRor8(140, ((b4[b1[45] % 21] + 92) * (b4[b1[45] % 21] + 92)) and 7) and 0xff
        b1[190] = 56
        b2[8] = (b2[8] xor b3[0]) and 0xff
        b1[53] = ((b0[b1[83] % 20] or 204) / 5).inv() and 0xff
        b0[13] = (b0[13] + b0[b1[41] % 20]) and 0xff
        b0[10] = (((b2[b3[0] % 35] and b1[2]) or ((b2[b3[0] % 35] or b1[2]) and b3[12])) / 15) and 0xff

        a = (((56 or (b4[b1[2] % 21] and 68)) or b2[b3[8] % 35]) and 42) or
            (((b4[b1[2] % 21] and 68) or 56) and b2[b3[8] % 35])
        b3[16] = (a * a + 110) and 0xff
        b3[20] = (202 - b3[16]) and 0xff
        b3[24] = b1[151]
        b2[13] = (b2[13] xor b4[b3[0] % 21]) and 0xff

        b = ((b2[b1[179] % 35] - 38) and 177) or (b3[12] and 177)
        c = (b2[b1[179] % 35] - 38) and b3[12]
        b3[28] = (30 + (b or c) * (b or c)) and 0xff
        b3[32] = (b3[28] + 62) and 0xff

        a = ((b3[20] + (b3[0] and 74)) or b4[b3[0] % 21].inv()) and 121
        b = (b3[20] + (b3[0] and 74)) and b4[b3[0] % 21].inv()
        val tmp3 = a or b
        c = ((((a or b) xor 0xffffffa6.toInt()) or b3[0]) and 4) or (((a or b) xor 0xffffffa6.toInt()) and b3[0])
        b1[47] = ((b2[b1[89] % 35] + c) xor b1[47]) and 0xff

        b3[36] = (((rol8(((tmp and 179) + 68) and 0xff, 2) and b0[3]) or (tmp2 and b0[3].inv())) - 15) and 0xff
        b1[123] = (b1[123] xor 221) and 0xff

        a = (b4[b3[0] % 21] / 3) - b2[b3[4] % 35]
        c = (((b3[0] and 163) + 92) and 246) or (b3[0] and 92)
        e = ((c or b3[24]) and 54) or (c and b3[24])
        b3[40] = (a - e) and 0xff
        b3[44] = (tmp3 xor 81 xor (((b3[0] ushr 1) and 101) + 26)) and 0xff
        b3[48] = b2[b3[4] % 35] and 27
        b3[52] = 27
        b3[56] = 199

        run {
            val q4 = b4[b3[0] % 21]
            val p1 = ((b3[40] or b3[24]) and 177) or (b3[40] and b3[24])
            val q1 = ((b4[b3[0] % 20] and 177) or 176) or (q4 and 3.inv())
            val r1 = ((b3[40] and b3[24]) or ((b3[40] or b3[24]) and 177)) and 199
            val t1 = (((q4 and 1) + 176) or (q4 and 3.inv())) and b3[56]
            b3[64] = (b3[4] + ((((p1 and q1) or (r1 or t1)) and b3[52].inv()) or b3[48])) and 0xff
        }

        b2[33] = (b2[33] xor b1[26]) and 0xff
        b1[106] = (b1[106] xor b3[20] xor 133) and 0xff
        b2[30] = (((b3[64] / 3) - (275 or (b3[0] and 247))) xor b0[b1[122] % 20]) and 0xff
        b1[22] = ((b2[b1[90] % 35] and 95) or 68) and 0xff

        a = (b4[b3[36] % 21] and 184) or (b2[b3[44] % 35] and 184.inv())
        b2[18] = (b2[18] + ((a * a * a) ushr 1)) and 0xff
        b2[5] = (b2[5] - b4[b1[92] % 21]) and 0xff

        a = (((b1[41] and 24.inv()) or (b2[b1[183] % 35] and 24)) and (b3[16] + 53)) or (b3[20] and b2[b3[20] % 35])
        b = (b1[17] and b3[44].inv()) or (b0[b1[59] % 20] and b3[44])
        b2[18] = (b2[18] xor (a * b)) and 0xff

        a = weirdRor8(b1[11], b2[b1[28] % 35] and 7) and 7
        b = (((b0[b1[93] % 20] and b0[14].inv()) or (b0[14] and 150)) and 28.inv()) or (b1[7] and 28)
        b2[22] = ((((b or weirdRol8(b2[b3[0] % 35], a)) and b2[33]) or (b and weirdRol8(b2[b3[0] % 35], a))) + 74) and 0xff

        a = b4[(b0[b1[39] % 20] xor 217) % 21]
        b0[15] = (b0[15] - (((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0])) and a) or
            ((((b3[20] or b3[0]) and 214) or (b3[20] and b3[0]) or a) and b3[32]))) and 0xff

        // T burada saklanmalı
        val x57 = b2[b1[57] % 35]
        val y64 = b0[b3[64] % 20]
        b = ((x57 and y64) or ((y64 or x57) and 95) or (b3[64] and 45) or 82) and 32
        c = ((x57 and y64) or ((x57 or y64) and 95)) and ((b3[64] and 45) or 82)
        d = ((b3[0] / 3) - (b3[64] or b1[22])) xor (b3[28] + 62) xor (b or c)
        val bigT = b0[(d and 0xff) % 20]

        run {
            val q = b0[b1[99] % 20]
            b3[68] = ((q * q * q * q) or b2[b3[64] % 35]) and 0xff
        }

        val bigU = b0[b1[50] % 20]
        val bigW = b2[b1[138] % 35]
        val bigX = b4[b1[39] % 21]
        val bigY = b0[b1[4] % 20]
        val bigZ = b4[b1[202] % 21]
        val bigV = b0[b1[151] % 20]
        val bigS = b2[b1[14] % 35]
        val bigR = b0[b1[145] % 20]

        a = (b2[b3[68] % 35] and b0[b1[209] % 20]) or ((b2[b3[68] % 35] or b0[b1[209] % 20]) and 24)
        b = weirdRol8(b4[b1[127] % 21], b2[b3[68] % 35] and 7)
        c = (a and b0[10]) or (b and b0[10].inv())
        d = 7 xor (b4[b2[b3[36] % 35] % 21] shl 1)
        b3[72] = ((c and 71) or (d and 71.inv())) and 0xff

        b2[2] = (b2[2] + ((((b0[b3[20] % 20] shl 1) and 159) or (b4[b1[190] % 21] and 159.inv())) and
            ((((b4[b3[64] % 21] and 110) or (b0[b1[25] % 20] and 110.inv())) and 150.inv()) or (b1[25] and 150)))) and 0xff
        b2[14] = (b2[14] - (((b2[b3[20] % 35] and (b3[72] xor b2[b1[100] % 35])) and 34.inv()) or (b1[97] and 34))) and 0xff
        b0[17] = 115

        run {
            val b4x = b4[b1[17] % 21]
            val b0y = b0[b3[20] % 20]
            val pp = ((b4x or b0y) and b3[72]) or (b4x and b0y)
            b1[23] = (b1[23] xor (((pp and (b1[50] / 3)) or ((pp or (b1[50] / 3)) and 246)) shl 1)) and 0xff
        }
        run {
            val g40 = b0[b3[40] % 20]
            b0[13] = ((((((g40 or b1[10]) and 82) or (g40 and b1[10])) and 209) or
                ((b0[b1[39] % 20] shl 1) and 46)) ushr 1) and 0xff
        }

        b2[33] = (b2[33] - (b1[113] and 9)) and 0xff
        b2[28] = (b2[28] - ((((2 or (b1[110] and 222)) ushr 1) and 223.inv()) or (b3[20] and 223))) and 0xff

        val bigJ = weirdRol8(bigV or bigZ, bigU and 7)
        a = (b2[16] and bigT) or (bigW and b2[16].inv())
        b = (b1[33] and 17) or (bigX and 17.inv())
        e = ((bigY or ((a + b) / 5)) and 147) or (bigY and ((a + b) / 5))
        val idx = ((b3[8] + bigJ + e) and 0xff) % 21
        val m = (b3[40] and b4[idx]) or ((b3[40] or b4[idx]) and b2[23])
        run {
            val c48 = b4[b3[20] % 21] - 48
            b0[15] = (((c48 and b1[184].inv()) or (c48 and 189) or (189 and b1[184].inv())) and (m * m * m)) and 0xff
        }

        b2[22] = (b2[22] + b1[183]) and 0xff
        b3[76] = ((3 * b4[b1[1] % 21]) xor b3[0]) and 0xff

        a = b2[((b3[8] + (bigJ + e)) and 0xff) % 35]
        f = (((b4[b1[178] % 21] and a) or ((b4[b1[178] % 21] or a) and 209)) * b0[b1[13] % 20]) * (b4[b1[26] % 21] ushr 1)
        g = (f + 0x733ffff9) * 198 - (((f + 0x733ffff9) * 396 + 212) and 212) + 85
        b3[80] = (b3[36] + (g xor 148) + ((g xor 107) shl 1) - 127) and 0xff

        b3[84] = ((b2[b3[64] % 35] and 245) or (b2[b3[20] % 35] and 10)) and 0xff

        a = b0[b3[68] % 20] or 81
        b2[18] = (b2[18] - (((a * a * a) and b0[15].inv()) or ((b3[80] / 15) and b0[15]))) and 0xff

        run {
            val je = b3[8] + bigJ + e
            b3[88] = (je - b0[b1[160] % 20] + b4[b0[(je and 255) % 20] % 21] / 3) and 0xff
        }

        b = ((bigR xor b3[72]) and 198.inv()) or ((bigS * bigS) and 198)
        f = (b4[b1[69] % 21] and b1[172]) or ((b4[b1[69] % 21] or b1[172]) and ((b3[12] - b) + 77))
        b0[16] = (147 - ((b3[72] and ((f and 251) or 1)) or (((f and 250) or b3[72]) and 198))) and 0xff

        c = (b4[b1[168] % 21] and b0[b1[29] % 20] and 7) or ((b4[b1[168] % 21] or b0[b1[29] % 20]) and 6)
        f = (b4[b1[155] % 21] and b1[105]) or ((b4[b1[155] % 21] or b1[105]) and 141)
        b0[3] = (b0[3] - b4[Integer.remainderUnsigned(weirdRol32(f, c), 21)]) and 0xff

        b1[5] = (weirdRor8(b0[12], (b0[b1[61] % 20] / 5) and 7) xor
            Integer.divideUnsigned(b2[b3[84] % 35].inv(), 5)) and 0xff
        b1[198] = (b1[198] + b1[3]) and 0xff

        a = 162 or b2[b3[64] % 35]
        b1[164] = (b1[164] + (a * a) / 5) and 0xff

        g = weirdRor8(139, b3[80] and 7)
        run {
            val c64 = b4[b3[64] % 21]
            c = ((c64 * c64 * c64) and 95) or (b0[b3[40] % 20] and 95.inv())
        }
        b3[92] = ((g and 12) or (b0[b3[20] % 20] and 12) or (g and b0[b3[20] % 20]) or c) and 0xff

        b2[12] = (b2[12] + ((b1[103] and 32) or (b3[92] and (b1[103] or 60)) or 16) / 3) and 0xff
        b3[96] = b1[143]
        b3[100] = 27
        val h = ((b3[40] and b2[8].inv()) or (b1[35] and b2[8])) and b3[64]
        b3[104] = (h xor 119) and 0xff
        b3[108] = (238 and (h shl 1)) and 0xff
        val k = b3[64].inv() and (b3[84] / 3)
        b3[112] = (k xor 49) and 0xff
        b3[116] = (98 and (k shl 1)) and 0xff

        // final
        a = (b1[35] and b2[8]) or (b3[40] and b2[8].inv())
        b = (a and b3[64]) or ((b3[84] / 3) and b3[64].inv())
        b1[143] = (b3[96] - ((b and (86 + ((b1[172] and 64) ushr 1))) or
            (((((b1[172] and 65) ushr 1) xor 86) or (k or h)) and b3[100]))) and 0xff

        b2[29] = 162

        a = ((b4[b3[88] % 21] and 160) or (b0[b1[125] % 20] and 95)) ushr 1
        b = b2[b1[149] % 35] xor (b1[43] * b1[43])
        b0[15] = (b0[15] + ((b and a) or ((a or b) and 115))) and 0xff

        b3[120] = (b3[64] - b0[b3[40] % 20]) and 0xff
        b1[95] = b4[b3[20] % 21]

        run {
            val s17 = b2[b1[17] % 35]
            a = weirdRor8(b2[b3[80] % 35], (s17 * s17 * s17) and 7)
        }
        b0[7] = (b0[7] - a * a) and 0xff

        run {
            val z202 = b4[b1[202] % 21]
            b2[8] = (b2[8] - b1[184] + z202 * z202 * z202) and 0xff
        }
        b0[16] = ((b2[b1[102] % 35] shl 1) and 132) and 0xff
        b3[124] = ((b4[b3[40] % 21] ushr 1) xor b3[68]) and 0xff

        b0[7] = (b0[7] - (b0[b1[191] % 20] - (((b4[b1[80] % 21] shl 1) and 177.inv()) or
            (b4[b4[b3[88] % 21] % 21] and 177)))) and 0xff
        b0[6] = b0[b1[119] % 20]

        a = (b4[b1[190] % 21] and 209.inv()) or (b1[118] and 209)
        b = b0[b3[120] % 20] * b0[b3[120] % 20]
        b0[12] = ((b0[b3[84] % 20] xor (b2[b1[71] % 35] + b2[b1[15] % 35])) and ((a and b) or ((a or b) and 27))) and 0xff

        b = (b1[32] and b2[b3[88] % 35]) or ((b1[32] or b2[b3[88] % 35]) and 23)
        d = ((b4[b1[57] % 21] * 231) and 169) or (b and 86)
        f = (((b0[b1[82] % 20] and 29.inv()) or (b4[b3[124] % 21] and 29)) and 190) or (b4[(d / 5) % 21] and 190.inv())
        run {
            val g40 = b0[b3[40] % 20]
            val hh = g40 * g40 * g40
            val kk = (hh and b1[82]) or (hh and 92) or (b1[82] and 92)
            b3[128] = (((f and kk) or ((f or kk) and 192)) xor (d / 5)) and 0xff
        }

        b2[25] = (b2[25] xor (((b0[b3[120] % 20] shl 1) * b1[5]) -
            (weirdRol8(b3[76], b4[b3[124] % 21] and 7) and (b3[20] + 110)))) and 0xff
    }

    private fun rol8(x: Int, n: Int): Int {
        val v = x and 0xff
        return ((v shl n) and 0xff) or (v ushr (8 - n))
    }

    private fun rol8x(x: Int, n: Int): Int {
        val v = x and 0xff
        return (v shl n) or (v ushr (8 - n))
    }

    private fun weirdRor8(x: Int, n: Int): Int {
        if (n == 0) return 0
        val v = x and 0xff
        return ((v ushr n) and 0xff) or (v shl (8 - n))
    }

    private fun weirdRol8(x: Int, n: Int): Int {
        if (n == 0) return 0
        val v = x and 0xff
        return ((v shl n) and 0xff) or (v ushr (8 - n))
    }

    private fun weirdRol32(x: Int, n: Int): Int {
        if (n == 0) return 0
        val v = x and 0xff
        return (v shl n) xor (v ushr (8 - n))
    }

    companion object {
        const val ASSET = "airplay/fairplay.bin"

        private fun le32(a: ByteArray, p: Int) =
            (a[p].toInt() and 0xff) or ((a[p + 1].toInt() and 0xff) shl 8) or
                ((a[p + 2].toInt() and 0xff) shl 16) or ((a[p + 3].toInt() and 0xff) shl 24)

        private val MD5_SHIFT = intArrayOf(
            7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
            5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
            4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
            6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21,
        )

        /** (int)(2^32 * |sin(i + 1)|): standart MD5 sabitleri. */
        private val MD5_K = intArrayOf(
            0xd76aa478.toInt(), 0xe8c7b756.toInt(), 0x242070db, 0xc1bdceee.toInt(),
            0xf57c0faf.toInt(), 0x4787c62a, 0xa8304613.toInt(), 0xfd469501.toInt(),
            0x698098d8, 0x8b44f7af.toInt(), 0xffff5bb1.toInt(), 0x895cd7be.toInt(),
            0x6b901122, 0xfd987193.toInt(), 0xa679438e.toInt(), 0x49b40821,
            0xf61e2562.toInt(), 0xc040b340.toInt(), 0x265e5a51, 0xe9b6c7aa.toInt(),
            0xd62f105d.toInt(), 0x02441453, 0xd8a1e681.toInt(), 0xe7d3fbc8.toInt(),
            0x21e1cde6, 0xc33707d6.toInt(), 0xf4d50d87.toInt(), 0x455a14ed,
            0xa9e3e905.toInt(), 0xfcefa3f8.toInt(), 0x676f02d9, 0x8d2a4c8a.toInt(),
            0xfffa3942.toInt(), 0x8771f681.toInt(), 0x6d9d6122, 0xfde5380c.toInt(),
            0xa4beea44.toInt(), 0x4bdecfa9, 0xf6bb4b60.toInt(), 0xbebfbc70.toInt(),
            0x289b7ec6, 0xeaa127fa.toInt(), 0xd4ef3085.toInt(), 0x04881d05,
            0xd9d4d039.toInt(), 0xe6db99e5.toInt(), 0x1fa27cf8, 0xc4ac5665.toInt(),
            0xf4292244.toInt(), 0x432aff97, 0xab9423a7.toInt(), 0xfc93a039.toInt(),
            0x655b59c3, 0x8f0ccc92.toInt(), 0xffeff47d.toInt(), 0x85845dd1.toInt(),
            0x6fa87e4f, 0xfe2ce6e0.toInt(), 0xa3014314.toInt(), 0x4e0811a1,
            0xf7537e82.toInt(), 0xbd3af235.toInt(), 0x2ad7d2bb, 0xeb86d391.toInt(),
        )

        // sap_hash.c'deki yerel sabit tamponlar (her çağrıda kopyalanır, garble değiştirir)
        private val SAP_B0 = intArrayOf(
            0x96, 0x5F, 0xC6, 0x53, 0xF8, 0x46, 0xCC, 0x18, 0xDF, 0xBE,
            0xB2, 0xF8, 0x38, 0xD7, 0xEC, 0x22, 0x03, 0xD1, 0x20, 0x8F,
        )
        private val SAP_B2 = intArrayOf(
            0x43, 0x54, 0x62, 0x7A, 0x18, 0xC3, 0xD6, 0xB3, 0x9A, 0x56, 0xF6, 0x1C,
            0x14, 0x3F, 0x0C, 0x1D, 0x3B, 0x36, 0x83, 0xB1, 0x39, 0x51, 0x4A, 0xAA,
            0x09, 0x3E, 0xFE, 0x44, 0xAF, 0xDE, 0xC3, 0x20, 0x9D, 0x42, 0x3A,
        )
        private val SAP_B4 = intArrayOf(
            0xED, 0x25, 0xD1, 0xBB, 0xBC, 0x27, 0x9F, 0x02, 0xA2, 0xA9, 0x11,
            0x00, 0x0C, 0xB3, 0x52, 0xC0, 0xBD, 0xE3, 0x1B, 0x49, 0xC7,
        )
        private val SAP_I0 = intArrayOf(18, 22, 23, 0, 5, 19, 32, 31, 10, 21, 30)
    }
}
