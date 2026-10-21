package com.hizir.sadehome.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** AirPlay'in RTSP/HTTP benzeri istekleri: "METHOD /url RTSP/1.0", başlıklar, Content-Length gövde. */
class RtspRequest(
    val method: String,
    val url: String,
    val protocol: String,
    /** Başlık adları küçük harfe çevrilmiş. */
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]

    val contentType: String? get() = header("content-type")

    /** İkili plist gövdesi (yoksa boş harita). */
    fun plist(): Map<String, Any?> =
        if (body.isEmpty()) emptyMap()
        else (runCatching { BPlist.parse(body) }.getOrNull() as? Map<*, *>)
            ?.entries?.associate { (k, v) -> k.toString() to v } ?: emptyMap()

    companion object {
        private const val MAX_LINE = 8 * 1024
        private const val MAX_BODY = 4 * 1024 * 1024

        /** Akıştan bir istek okur; bağlantı temiz kapandıysa null. */
        fun read(input: InputStream): RtspRequest? {
            var first: String
            do {
                first = readLine(input) ?: return null
            } while (first.isEmpty()) // istekler arasındaki boş satırları atla
            val parts = first.split(' ')
            if (parts.size < 3) throw IOException("Geçersiz istek satırı: $first")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: throw IOException("Başlıklar yarıda kesildi")
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i <= 0) continue
                headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val len = headers["content-length"]?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) throw IOException("Geçersiz gövde uzunluğu: $len")
            val body = ByteArray(len)
            var off = 0
            while (off < len) {
                val n = input.read(body, off, len - off)
                if (n < 0) throw IOException("Gövde yarıda kesildi")
                off += n
            }
            return RtspRequest(parts[0], parts[1], parts.subList(2, parts.size).joinToString(" "), headers, body)
        }

        private fun readLine(input: InputStream): String? {
            val buf = ByteArrayOutputStream(64)
            while (true) {
                val b = input.read()
                if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
                if (b == '\n'.code) break
                if (b != '\r'.code) buf.write(b)
                if (buf.size() > MAX_LINE) throw IOException("Satır çok uzun")
            }
            return buf.toString("UTF-8")
        }
    }
}

class RtspResponse(val protocol: String) {
    var status = 200
    var reason = "OK"
    val headers = LinkedHashMap<String, String>()
    var body: ByteArray = ByteArray(0)
    /** Yanıttan sonra bağlantı kapatılsın. */
    var disconnect = false

    fun status(code: Int, text: String) = apply { status = code; reason = text }

    fun plist(value: Any?) = apply {
        body = BPlist.write(value)
        headers["Content-Type"] = "application/x-apple-binary-plist"
    }

    fun octets(data: ByteArray) = apply {
        body = data
        headers["Content-Type"] = "application/octet-stream"
    }

    fun text(type: String, value: String) = apply {
        body = value.toByteArray(Charsets.UTF_8)
        headers["Content-Type"] = type
    }

    fun writeTo(out: OutputStream) {
        val sb = StringBuilder()
        sb.append(protocol).append(' ').append(status).append(' ').append(reason).append("\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }
}
