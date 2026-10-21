package com.hizir.sadehome.data

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object Net {
    fun get(url: String, timeoutMs: Int = 6000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs
        c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", "SadeHome/2.0 (Android TV)")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
