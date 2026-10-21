package com.hizir.sadehome.airplay

import java.security.SecureRandom

/**
 * GF(2^255-19) alan aritmetiği. TweetNaCl'deki gösterim: 16 adet 16 bitlik uzuv (LongArray, küçük uç önce).
 * X25519 ve Ed25519 ortak kullanır. Gizli bitlere göre dallanma yok; seçim [sel] ile maskeyle yapılır.
 * Sadece java.* / kotlin.* kullanır; masaüstü JVM'de de test edilebilir.
 */
internal object F25519 {
    fun gf(v: Long = 0L) = LongArray(16).also { it[0] = v }

    fun set(o: LongArray, a: LongArray) = System.arraycopy(a, 0, o, 0, 16)

    /** Elde yayma; sonuç uzuvları yaklaşık 16 bit olur (tam indirgeme değil). */
    fun car(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 1L shl 16
            val c = o[i] shr 16
            if (i < 15) o[i + 1] += c - 1 else o[0] += 38 * (c - 1)
            o[i] -= c shl 16
        }
    }

    /** b == 1 ise p ile q'yu takas eder, b == 0 ise dokunmaz (sabit zamanlı). */
    fun sel(p: LongArray, q: LongArray, b: Int) {
        val c = (b - 1).toLong().inv()
        for (i in 0 until 16) {
            val t = c and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    /** Tam indirgenmiş 32 baytlık küçük uçlu kodlama. */
    fun pack(o: ByteArray, off: Int, n: LongArray) {
        val t = n.copyOf()
        val m = LongArray(16)
        car(t); car(t); car(t)
        repeat(2) {
            m[0] = t[0] - 0xffed
            for (i in 1 until 15) {
                m[i] = t[i] - 0xffff - ((m[i - 1] shr 16) and 1)
                m[i - 1] = m[i - 1] and 0xffff
            }
            m[15] = t[15] - 0x7fff - ((m[14] shr 16) and 1)
            val b = ((m[15] shr 16) and 1).toInt()
            m[14] = m[14] and 0xffff
            sel(t, m, 1 - b)
        }
        for (i in 0 until 16) {
            o[off + 2 * i] = (t[i] and 0xff).toByte()
            o[off + 2 * i + 1] = ((t[i] shr 8) and 0xff).toByte()
        }
    }

    fun pack(n: LongArray) = ByteArray(32).also { pack(it, 0, n) }

    /** 32 bayttan okur; en üst bit (255.) yok sayılır. */
    fun unpack(o: LongArray, n: ByteArray, off: Int = 0) {
        for (i in 0 until 16) {
            o[i] = (n[off + 2 * i].toLong() and 0xff) or ((n[off + 2 * i + 1].toLong() and 0xff) shl 8)
        }
        o[15] = o[15] and 0x7fff
    }

    fun neq(a: LongArray, b: LongArray): Boolean {
        val c = pack(a)
        val d = pack(b)
        var r = 0
        for (i in 0 until 32) r = r or (c[i].toInt() xor d[i].toInt())
        return r != 0
    }

    fun par(a: LongArray): Int = pack(a)[0].toInt() and 1

    fun add(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] + b[i]
    }

    fun sub(o: LongArray, a: LongArray, b: LongArray) {
        for (i in 0 until 16) o[i] = a[i] - b[i]
    }

    fun mul(o: LongArray, a: LongArray, b: LongArray) {
        val t = LongArray(31)
        for (i in 0 until 16) {
            val ai = a[i]
            for (j in 0 until 16) t[i + j] += ai * b[j]
        }
        for (i in 0 until 15) t[i] += 38 * t[i + 16]
        System.arraycopy(t, 0, o, 0, 16)
        car(o)
        car(o)
    }

    fun sqr(o: LongArray, a: LongArray) = mul(o, a, a)

    /** a^(p-2), yani tersi. */
    fun inv(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 253 downTo 0) {
            sqr(c, c)
            if (a != 2 && a != 4) mul(c, c, i)
        }
        set(o, c)
    }

    /** a^((p-5)/8); karekök almada kullanılır. */
    fun pow2523(o: LongArray, i: LongArray) {
        val c = i.copyOf()
        for (a in 250 downTo 0) {
            sqr(c, c)
            if (a != 1) mul(c, c, i)
        }
        set(o, c)
    }
}

/**
 * X25519 Diffie-Hellman (RFC 7748). AirPlay eşleştirmesi (pair-setup / pair-verify) için.
 * Android'in Conscrypt'inde X25519 garanti olmadığından kendi uygulamamız.
 */
object X25519 {
    private val A24 = F25519.gf(121665)
    private val BASE = ByteArray(32).also { it[0] = 9 }

    /** 32 baytlık rastgele gizli anahtar (kırpma, kullanımda yapılır). */
    fun generatePrivateKey(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also { random.nextBytes(it) }

    /** Gizli anahtardan açık anahtar: taban noktası u=9 ile çarpım. */
    fun publicKey(privateKey: ByteArray): ByteArray = scalarMult(privateKey, BASE)

    /**
     * Ortak sır. Karşı tarafın anahtarı düşük mertebeli bir noktaysa sonuç sıfır olur;
     * bu durumda [IllegalArgumentException] fırlatılır (RFC 7748 §6.1 önerisi).
     */
    fun sharedSecret(privateKey: ByteArray, peerPublic: ByteArray): ByteArray {
        val r = scalarMult(privateKey, peerPublic)
        var acc = 0
        for (b in r) acc = acc or b.toInt()
        require(acc != 0) { "Geçersiz X25519 açık anahtarı (sıfır ortak sır)" }
        return r
    }

    /** Montgomery merdiveni (TweetNaCl crypto_scalarmult). Skaler RFC 7748'e göre kırpılır. */
    internal fun scalarMult(n: ByteArray, p: ByteArray): ByteArray {
        require(n.size == 32) { "X25519 gizli anahtarı 32 bayt olmalı" }
        require(p.size == 32) { "X25519 açık anahtarı 32 bayt olmalı" }
        val f = F25519
        val z = n.copyOf()
        z[31] = ((z[31].toInt() and 127) or 64).toByte()
        z[0] = (z[0].toInt() and 248).toByte()
        val x = LongArray(16)
        f.unpack(x, p)
        val a = f.gf(1)
        val b = x.copyOf()
        val c = f.gf()
        val d = f.gf(1)
        val e = f.gf()
        val ff = f.gf()
        for (i in 254 downTo 0) {
            val r = (z[i ushr 3].toInt() ushr (i and 7)) and 1
            f.sel(a, b, r)
            f.sel(c, d, r)
            f.add(e, a, c)
            f.sub(a, a, c)
            f.add(c, b, d)
            f.sub(b, b, d)
            f.sqr(d, e)
            f.sqr(ff, a)
            f.mul(a, c, a)
            f.mul(c, b, e)
            f.add(e, a, c)
            f.sub(a, a, c)
            f.sqr(b, a)
            f.sub(c, d, ff)
            f.mul(a, c, A24)
            f.add(a, a, d)
            f.mul(c, c, a)
            f.mul(a, d, ff)
            f.mul(d, b, x)
            f.sqr(b, e)
            f.sel(a, b, r)
            f.sel(c, d, r)
        }
        f.inv(c, c)
        f.mul(a, a, c)
        z.fill(0)
        return f.pack(a)
    }
}
