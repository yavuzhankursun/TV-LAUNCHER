package com.hizir.sadehome.airplay

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Ed25519 imza (RFC 8032, saf sürüm; ph/ctx yok). AirPlay eşleştirmesinde uzun ömürlü kimlik anahtarı için.
 * Alan aritmetiği [F25519]; noktalar genişletilmiş koordinatlarda (X, Y, Z, T). TweetNaCl'den uyarlandı.
 * Sadece java.* / kotlin.* kullanır.
 */
object Ed25519 {
    private val P: BigInteger = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19))

    // Sabitler açılışta BigInteger ile bir kez hesaplanır (elle yazılmış sayı hatası olmasın diye).
    private val D: LongArray
    private val D2: LongArray
    private val BX: LongArray
    private val BY: LongArray
    private val SQRTM1: LongArray

    /** Grup mertebesi L = 2^252 + 27742317777372353535851937790883648493, küçük uçlu baytlar. */
    private val L = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
    )

    init {
        val d = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
        val i = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)
        val y = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P)
        val y2 = y.multiply(y).mod(P)
        val x2 = y2.subtract(BigInteger.ONE).multiply(d.multiply(y2).add(BigInteger.ONE).modInverse(P)).mod(P)
        var x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P)
        if (x.multiply(x).mod(P) != x2) x = x.multiply(i).mod(P)
        if (x.testBit(0)) x = P.subtract(x)
        D = limbs(d)
        D2 = limbs(d.shiftLeft(1).mod(P))
        BX = limbs(x)
        BY = limbs(y)
        SQRTM1 = limbs(i)
    }

    private fun limbs(v: BigInteger) = LongArray(16) { v.shiftRight(16 * it).toLong() and 0xffff }

    /** 32 baytlık rastgele tohum (RFC 8032'deki "gizli anahtar"). */
    fun generateSeed(random: SecureRandom = SecureRandom()): ByteArray =
        ByteArray(32).also { random.nextBytes(it) }

    fun publicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 tohumu 32 bayt olmalı" }
        val h = sha512(seed)
        clamp(h)
        val p = point()
        scalarBase(p, h)
        return encode(p)
    }

    /** Belirlenimci imza (64 bayt: R || S). */
    fun sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "Ed25519 tohumu 32 bayt olmalı" }
        val h = sha512(seed)
        clamp(h)
        val p = point()
        scalarBase(p, h)
        val pub = encode(p)

        val r = sha512(h.copyOfRange(32, 64), message)
        reduce(r)
        scalarBase(p, r)
        val sig = ByteArray(64)
        System.arraycopy(encode(p), 0, sig, 0, 32)

        val k = sha512(sig.copyOfRange(0, 32), pub, message)
        reduce(k)
        val x = LongArray(64)
        for (i in 0 until 32) x[i] = r[i].toLong() and 0xff
        for (i in 0 until 32) {
            val ki = k[i].toLong() and 0xff
            for (j in 0 until 32) x[i + j] += ki * (h[j].toLong() and 0xff)
        }
        modL(sig, 32, x)
        h.fill(0); r.fill(0)
        return sig
    }

    /**
     * İmzayı doğrular. Hatalı girdide istisna fırlatmaz, false döner.
     * Kanonik olmayan S (S >= L), kanonik olmayan y (>= p) ve eğri dışı noktalar reddedilir.
     */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        return try {
            verifyInner(publicKey, message, signature)
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun verifyInner(pk: ByteArray, m: ByteArray, sig: ByteArray): Boolean {
        if (!lessThanL(sig, 32)) return false
        if (!canonicalY(pk)) return false
        val q = point()
        if (!decodeNeg(q, pk)) return false

        val h = sha512(sig.copyOfRange(0, 32), pk, m)
        reduce(h)
        val p = point()
        scalarMult(p, q, h)          // p = h·(-A)
        scalarBase(q, sig.copyOfRange(32, 64)) // q = S·B
        add(p, q)
        val t = encode(p)
        var diff = 0
        for (i in 0 until 32) diff = diff or (t[i].toInt() xor sig[i].toInt())
        return diff == 0
    }

    // ---- yardımcılar ----

    private fun sha512(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-512")
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun clamp(h: ByteArray) {
        h[0] = (h[0].toInt() and 248).toByte()
        h[31] = ((h[31].toInt() and 127) or 64).toByte()
    }

    private fun point() = arrayOf(LongArray(16), LongArray(16), LongArray(16), LongArray(16))

    /** S < L mi (küçük uçlu, 32 bayt, [off]'tan itibaren). */
    private fun lessThanL(b: ByteArray, off: Int): Boolean {
        for (i in 31 downTo 0) {
            val x = b[off + i].toLong() and 0xff
            if (x < L[i]) return true
            if (x > L[i]) return false
        }
        return false // eşit
    }

    /** Kodlanmış y (işaret biti hariç) p'den küçük mü. */
    private fun canonicalY(b: ByteArray): Boolean {
        // p = 2^255 - 19: üst 31 bayt 0xff/0x7f ise ve ilk bayt >= 0xed ise kanonik değil.
        if ((b[31].toInt() and 0x7f) != 0x7f) return true
        for (i in 30 downTo 1) if ((b[i].toInt() and 0xff) != 0xff) return true
        return (b[0].toInt() and 0xff) < 0xed
    }

    /** p += q (genişletilmiş koordinatlarda birleşik toplama). */
    private fun add(p: Array<LongArray>, q: Array<LongArray>) {
        val f = F25519
        val a = LongArray(16); val b = LongArray(16); val c = LongArray(16); val d = LongArray(16)
        val t = LongArray(16); val e = LongArray(16); val ff = LongArray(16); val g = LongArray(16)
        val h = LongArray(16)
        f.sub(a, p[1], p[0])
        f.sub(t, q[1], q[0])
        f.mul(a, a, t)
        f.add(b, p[0], p[1])
        f.add(t, q[0], q[1])
        f.mul(b, b, t)
        f.mul(c, p[3], q[3])
        f.mul(c, c, D2)
        f.mul(d, p[2], q[2])
        f.add(d, d, d)
        f.sub(e, b, a)
        f.sub(ff, d, c)
        f.add(g, d, c)
        f.add(h, b, a)
        f.mul(p[0], e, ff)
        f.mul(p[1], h, g)
        f.mul(p[2], g, ff)
        f.mul(p[3], e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, b: Int) {
        for (i in 0 until 4) F25519.sel(p[i], q[i], b)
    }

    private fun encode(p: Array<LongArray>): ByteArray {
        val f = F25519
        val zi = LongArray(16); val tx = LongArray(16); val ty = LongArray(16)
        f.inv(zi, p[2])
        f.mul(tx, p[0], zi)
        f.mul(ty, p[1], zi)
        val r = f.pack(ty)
        r[31] = (r[31].toInt() xor (f.par(tx) shl 7)).toByte()
        return r
    }

    /** p = s·q (256 bitlik skaler, sabit zamanlı). q değişir. */
    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        val f = F25519
        f.set(p[0], f.gf(0)); f.set(p[1], f.gf(1)); f.set(p[2], f.gf(1)); f.set(p[3], f.gf(0))
        for (i in 255 downTo 0) {
            val b = (s[i ushr 3].toInt() ushr (i and 7)) and 1
            cswap(p, q, b)
            add(q, p)
            add(p, p)
            cswap(p, q, b)
        }
    }

    private fun scalarBase(p: Array<LongArray>, s: ByteArray) {
        val f = F25519
        val q = point()
        f.set(q[0], BX)
        f.set(q[1], BY)
        f.set(q[2], f.gf(1))
        f.mul(q[3], BX, BY)
        scalarMult(p, q, s)
    }

    /** Açık anahtarı çözüp -A olarak r'ye yazar. Eğri üzerinde değilse false. */
    private fun decodeNeg(r: Array<LongArray>, p: ByteArray): Boolean {
        val f = F25519
        val t = LongArray(16); val chk = LongArray(16); val num = LongArray(16); val den = LongArray(16)
        val den2 = LongArray(16); val den4 = LongArray(16); val den6 = LongArray(16)
        f.set(r[2], f.gf(1))
        f.unpack(r[1], p)
        f.sqr(num, r[1])
        f.mul(den, num, D)
        f.sub(num, num, r[2])
        f.add(den, r[2], den)

        f.sqr(den2, den)
        f.sqr(den4, den2)
        f.mul(den6, den4, den2)
        f.mul(t, den6, num)
        f.mul(t, t, den)

        f.pow2523(t, t)
        f.mul(t, t, num)
        f.mul(t, t, den)
        f.mul(t, t, den)
        f.mul(r[0], t, den)

        f.sqr(chk, r[0])
        f.mul(chk, chk, den)
        if (f.neq(chk, num)) f.mul(r[0], r[0], SQRTM1)

        f.sqr(chk, r[0])
        f.mul(chk, chk, den)
        if (f.neq(chk, num)) return false

        val sign = (p[31].toInt() ushr 7) and 1
        // x = 0 iken işaret biti 1 olamaz (RFC 8032 §5.1.3 adım 4).
        if (sign == 1 && !f.neq(r[0], f.gf(0))) return false
        if (f.par(r[0]) == sign) f.sub(r[0], f.gf(0), r[0])

        f.mul(r[3], r[0], r[1])
        return true
    }

    /** 64 uzuvlu (bayt başına) sayıyı L'ye göre indirger, 32 bayt yazar. */
    private fun modL(r: ByteArray, off: Int, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            var j = i - 32
            while (j < i - 12) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)]
                carry = (x[j] + 128) shr 8
                x[j] -= carry shl 8
                j++
            }
            x[j] += carry
            x[i] = 0
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255
        }
        for (j in 0 until 32) x[j] -= carry * L[j]
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[off + i] = (x[i] and 255).toByte()
        }
    }

    /** 64 baytlık özeti yerinde L'ye göre indirger (sonuç ilk 32 bayt, kalanı sıfır). */
    private fun reduce(r: ByteArray) {
        val x = LongArray(64) { r[it].toLong() and 0xff }
        r.fill(0)
        modL(r, 0, x)
    }
}
