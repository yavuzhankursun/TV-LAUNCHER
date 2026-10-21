package com.hizir.sadehome.airplay

import java.io.ByteArrayOutputStream
import java.util.Date

/**
 * Apple ikili özellik listesi ("bplist00") okuyucu/yazıcı. AirPlay istekleri (/info, /pair-*, SETUP…) bunu kullanır.
 * Bozuk girdide her zaman [IllegalArgumentException] fırlatır; derinlik ve döngü korumalı.
 * Sadece java.* / kotlin.* kullanır.
 */
object BPlist {
    /** İç içe dizi/sözlük sınırı; AirPlay listeleri birkaç seviyeden derin olmaz. */
    private const val MAX_DEPTH = 128

    /** 2001-01-01T00:00:00Z, Unix ms cinsinden (Apple tarih başlangıcı). */
    private const val APPLE_EPOCH_MS = 978307200000L

    /** Kotlin değerleri: Map<String, Any?> (dict), List<Any?> (array), String, ByteArray (data), Long (integer), Double (real), Boolean, java.util.Date (date). */
    fun parse(bytes: ByteArray): Any? {
        try {
            return Reader(bytes).parse()
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: RuntimeException) {
            throw IllegalArgumentException("Bozuk bplist: ${e.message}", e)
        }
    }

    /**
     * Yazar. Kabul edilenler: null, Boolean, Long/Int/Short/Byte, Double/Float, String, ByteArray, Date,
     * Map (anahtarlar String, sıra korunur), Collection ve Array (dizi olarak).
     */
    fun write(root: Any?): ByteArray = Writer().write(root)

    // ---- okuma ----

    private class Reader(private val b: ByteArray) {
        private var refSize = 0
        private var offSize = 0
        private var count = 0
        private var tableOff = 0
        private lateinit var cache: Array<Any?>
        private lateinit var state: ByteArray // 0: okunmadı, 1: okunuyor (döngü), 2: hazır

        fun parse(): Any? {
            require(b.size >= 8 + 32) { "bplist çok kısa" }
            for (i in 0 until 7) require(b[i] == MAGIC[i]) { "bplist başlığı yok" }
            val t = b.size - 32
            offSize = u8(t + 6)
            refSize = u8(t + 7)
            val n = be(t + 8, 8)
            val top = be(t + 16, 8)
            val table = be(t + 24, 8)
            require(offSize in 1..8 && refSize in 1..8) { "Geçersiz tamsayı boyu" }
            require(table in 8..t.toLong()) { "Geçersiz ofset tablosu" }
            require(n in 1..t.toLong() && n * offSize <= t - table) { "Geçersiz nesne sayısı" }
            require(top in 0 until n) { "Geçersiz kök nesne" }
            count = n.toInt()
            tableOff = table.toInt()
            cache = arrayOfNulls(count)
            state = ByteArray(count)
            return obj(top, 0)
        }

        private fun u8(pos: Int): Int = b[pos].toInt() and 0xff

        /** İşaretsiz büyük uçlu okuma (8 baytta ham 64 bit). Sınır önceden denetlenmeli. */
        private fun be(pos: Int, len: Int): Long {
            var v = 0L
            for (i in 0 until len) v = (v shl 8) or (b[pos + i].toLong() and 0xff)
            return v
        }

        /** [pos, pos+len) nesne alanında mı. */
        private fun need(pos: Int, len: Long) {
            require(len >= 0 && pos >= 8 && pos + len <= tableOff) { "Nesne sınır dışında" }
        }

        private fun obj(ref: Long, depth: Int): Any? {
            require(ref in 0 until count) { "Geçersiz nesne başvurusu" }
            val r = ref.toInt()
            when (state[r].toInt()) {
                2 -> return cache[r]
                1 -> throw IllegalArgumentException("bplist'te döngü")
            }
            require(depth <= MAX_DEPTH) { "bplist çok derin" }
            state[r] = 1
            val o = be(tableOff + r * offSize, offSize)
            require(o in 8 until tableOff) { "Geçersiz nesne ofseti" }
            val v = read(o.toInt(), depth)
            cache[r] = v
            state[r] = 2
            return v
        }

        private fun read(pos: Int, depth: Int): Any? {
            need(pos, 1)
            val m = u8(pos)
            val lo = m and 0xf
            return when (m ushr 4) {
                0x0 -> when (lo) {
                    0x0, 0xf -> null
                    0x8 -> false
                    0x9 -> true
                    else -> bad(m)
                }
                0x1 -> readInt(pos)
                0x2 -> when (lo) {
                    2 -> { need(pos + 1, 4); java.lang.Float.intBitsToFloat(be(pos + 1, 4).toInt()).toDouble() }
                    3 -> { need(pos + 1, 8); java.lang.Double.longBitsToDouble(be(pos + 1, 8)) }
                    else -> bad(m)
                }
                0x3 -> {
                    if (lo != 3) bad(m)
                    need(pos + 1, 8)
                    val s = java.lang.Double.longBitsToDouble(be(pos + 1, 8))
                    require(!s.isNaN() && Math.abs(s) < 1e14) { "Geçersiz tarih" }
                    Date(APPLE_EPOCH_MS + Math.round(s * 1000.0))
                }
                0x4 -> {
                    val (len, start) = length(pos, lo)
                    need(start, len.toLong())
                    b.copyOfRange(start, start + len)
                }
                0x5 -> {
                    val (len, start) = length(pos, lo)
                    need(start, len.toLong())
                    String(b, start, len, Charsets.ISO_8859_1)
                }
                0x6 -> {
                    val (len, start) = length(pos, lo)
                    need(start, 2L * len)
                    val c = CharArray(len) { ((u8(start + 2 * it) shl 8) or u8(start + 2 * it + 1)).toChar() }
                    String(c)
                }
                0x8 -> {
                    val n = lo + 1
                    need(pos + 1, n.toLong())
                    if (n > 8) be(pos + 1 + n - 8, 8) else be(pos + 1, n)
                }
                0xA, 0xB, 0xC -> {
                    val (len, start) = length(pos, lo)
                    need(start, len.toLong() * refSize)
                    val list = ArrayList<Any?>(len)
                    for (i in 0 until len) list.add(obj(be(start + i * refSize, refSize), depth + 1))
                    list
                }
                0xD -> {
                    val (len, start) = length(pos, lo)
                    need(start, 2L * len * refSize)
                    val map = LinkedHashMap<String, Any?>()
                    val valStart = start + len * refSize
                    for (i in 0 until len) {
                        val k = obj(be(start + i * refSize, refSize), depth + 1)
                        require(k is String) { "Sözlük anahtarı metin değil" }
                        map[k] = obj(be(valStart + i * refSize, refSize), depth + 1)
                    }
                    map
                }
                else -> bad(m)
            }
        }

        /** 1/2/4 bayt işaretsiz, 8 bayt işaretli; 16 baytta düşük 64 bit alınır. */
        private fun readInt(pos: Int): Long {
            val lo = u8(pos) and 0xf
            require(lo <= 4) { "Geçersiz tamsayı boyu" }
            val n = 1 shl lo
            need(pos + 1, n.toLong())
            return if (n == 16) be(pos + 9, 8) else be(pos + 1, n)
        }

        /** Nesne uzunluğu ve içeriğin başladığı konum; 0xF ise ardından tamsayı nesnesi gelir. */
        private fun length(pos: Int, lo: Int): Pair<Int, Int> {
            if (lo != 0xf) return lo to pos + 1
            need(pos + 1, 1)
            val m = u8(pos + 1)
            require(m ushr 4 == 1 && (m and 0xf) <= 3) { "Geçersiz uzunluk işareti" }
            val n = 1 shl (m and 0xf)
            need(pos + 2, n.toLong())
            val v = be(pos + 2, n)
            require(v in 0..Int.MAX_VALUE.toLong()) { "Geçersiz uzunluk" }
            return v.toInt() to pos + 2 + n
        }

        private fun bad(m: Int): Nothing = throw IllegalArgumentException("Bilinmeyen bplist işareti 0x" + Integer.toHexString(m))
    }

    // ---- yazma ----

    private class Arr(val refs: IntArray)
    private class Dict(val keys: IntArray, val values: IntArray)

    private class Writer {
        /** Düzleştirilmiş nesneler; kapsayıcılar [Arr]/[Dict] olarak çocuk numaralarını tutar. */
        private val objs = ArrayList<Any?>()

        fun write(root: Any?): ByteArray {
            flatten(root, 0)
            val n = objs.size
            // CoreFoundation, 2^(8*boy) <= nesne sayısı ise reddeder; boyu sayıya göre seç.
            val refSize = sizeFor(n.toLong())
            val out = ByteArrayOutputStream()
            out.write(MAGIC)
            val offsets = LongArray(n)
            for (i in 0 until n) {
                offsets[i] = out.size().toLong()
                writeObj(out, objs[i], refSize)
            }
            val table = out.size().toLong()
            val offSize = sizeFor(table)
            for (o in offsets) be(out, o, offSize)
            repeat(6) { out.write(0) }
            out.write(offSize)
            out.write(refSize)
            be(out, n.toLong(), 8)
            be(out, 0, 8)
            be(out, table, 8)
            return out.toByteArray()
        }

        private fun flatten(v: Any?, depth: Int): Int {
            require(depth <= MAX_DEPTH) { "Çok derin ya da döngülü yapı" }
            val id = objs.size
            objs.add(null)
            objs[id] = when (v) {
                null, is Boolean, is String, is ByteArray, is Date, is Long, is Double -> v
                is Int, is Short, is Byte -> (v as Number).toLong()
                is Float -> v.toDouble()
                is Map<*, *> -> {
                    val ks = ArrayList<Any?>(v.size)
                    val vs = ArrayList<Any?>(v.size)
                    for ((k, x) in v) {
                        require(k is String) { "Sözlük anahtarı String olmalı" }
                        ks.add(k); vs.add(x)
                    }
                    val keys = IntArray(ks.size) { flatten(ks[it], depth + 1) }
                    Dict(keys, IntArray(vs.size) { flatten(vs[it], depth + 1) })
                }
                is Collection<*> -> {
                    val items = v.toList()
                    Arr(IntArray(items.size) { flatten(items[it], depth + 1) })
                }
                is Array<*> -> Arr(IntArray(v.size) { flatten(v[it], depth + 1) })
                else -> throw IllegalArgumentException("Desteklenmeyen tür: " + v.javaClass.name)
            }
            return id
        }

        private fun writeObj(out: ByteArrayOutputStream, v: Any?, refSize: Int) {
            when (v) {
                null -> out.write(0x00)
                is Boolean -> out.write(if (v) 0x09 else 0x08)
                is Long -> writeInt(out, v)
                is Double -> { out.write(0x23); be(out, java.lang.Double.doubleToRawLongBits(v), 8) }
                is Date -> {
                    out.write(0x33)
                    be(out, java.lang.Double.doubleToRawLongBits((v.time - APPLE_EPOCH_MS) / 1000.0), 8)
                }
                is ByteArray -> { header(out, 0x4, v.size); out.write(v) }
                is String -> {
                    if (v.all { it.code < 0x80 }) {
                        header(out, 0x5, v.length)
                        for (c in v) out.write(c.code)
                    } else {
                        // UTF-16BE, uzunluk kod birimi sayısı; eşsiz vekiller olduğu gibi yazılır.
                        header(out, 0x6, v.length)
                        for (c in v) { out.write(c.code ushr 8); out.write(c.code and 0xff) }
                    }
                }
                is Arr -> {
                    header(out, 0xA, v.refs.size)
                    for (r in v.refs) be(out, r.toLong(), refSize)
                }
                is Dict -> {
                    header(out, 0xD, v.keys.size)
                    for (r in v.keys) be(out, r.toLong(), refSize)
                    for (r in v.values) be(out, r.toLong(), refSize)
                }
                else -> throw IllegalStateException()
            }
        }

        /** Apple gibi: 0..2^32-1 için en küçük 1/2/4 bayt (işaretsiz), diğerleri 8 bayt işaretli. */
        private fun writeInt(out: ByteArrayOutputStream, v: Long) {
            when {
                v < 0 -> { out.write(0x13); be(out, v, 8) }
                v < 0x100 -> { out.write(0x10); be(out, v, 1) }
                v < 0x10000 -> { out.write(0x11); be(out, v, 2) }
                v < 0x100000000L -> { out.write(0x12); be(out, v, 4) }
                else -> { out.write(0x13); be(out, v, 8) }
            }
        }

        private fun header(out: ByteArrayOutputStream, type: Int, len: Int) {
            if (len < 15) {
                out.write((type shl 4) or len)
            } else {
                out.write((type shl 4) or 0xf)
                writeInt(out, len.toLong())
            }
        }

        private fun sizeFor(x: Long) = when {
            x < 0x100 -> 1
            x < 0x10000 -> 2
            x < 0x100000000L -> 4
            else -> 8
        }

        private fun be(out: ByteArrayOutputStream, v: Long, len: Int) {
            for (i in len - 1 downTo 0) out.write((v ushr (8 * i)).toInt() and 0xff)
        }
    }

    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)
}
