package com.hizir.sadehome.data

import android.content.Context
import com.hizir.sadehome.ui.Glyph
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/** Anahtarsız, ücretsiz Open-Meteo ile hava durumu. Konum: elle seçilen şehir ya da IP'den. */
object Weather {
    class Now(val tempC: Int, val glyph: Glyph, val city: String)
    class Place(val name: String, val detail: String, val lat: Double, val lon: Double)

    @Volatile var current: Now? = null
        private set
    @Volatile private var fetchedAt = 0L
    @Volatile private var attemptedAt = 0L

    /** 30 dakikada bir yenilenir; başarısız denemeden sonra 5 dakika beklenir. */
    fun isStale(): Boolean {
        val now = System.currentTimeMillis()
        return now - fetchedAt > 30 * 60 * 1000L && now - attemptedAt > 5 * 60 * 1000L
    }

    fun invalidate() {
        fetchedAt = 0
        attemptedAt = 0
    }

    /** Arka planda çağrılmalı. */
    fun refresh(ctx: Context): Now? {
        attemptedAt = System.currentTimeMillis()
        val prefs = Prefs.of(ctx)
        val place: Place = prefs.weatherCity?.let { Place(it, "", prefs.weatherLat, prefs.weatherLon) }
            ?: autoPlace()
            ?: return current
        val json = JSONObject(
            Net.get(
                "https://api.open-meteo.com/v1/forecast?latitude=${place.lat}&longitude=${place.lon}" +
                    "&current=temperature_2m,weather_code,is_day&timezone=auto"
            )
        ).getJSONObject("current")
        val now = Now(
            tempC = json.getDouble("temperature_2m").roundToInt(),
            glyph = glyphFor(json.getInt("weather_code"), json.optInt("is_day", 1) == 1),
            city = place.name,
        )
        current = now
        fetchedAt = System.currentTimeMillis()
        return now
    }

    /** IP'den konum; şehir adı Türkçe yazımıyla (London → Londra) geocoding'den alınır. */
    private fun autoPlace(): Place? {
        val ip = runCatching { JSONObject(Net.get("https://ipwho.is/?fields=city,latitude,longitude,success")) }
            .getOrNull() ?: return null
        if (!ip.optBoolean("success", false)) return null
        val city = ip.optString("city")
        val lat = ip.getDouble("latitude")
        val lon = ip.getDouble("longitude")
        val trName = runCatching { search(city).firstOrNull()?.name }.getOrNull() ?: city
        return Place(trName, "", lat, lon)
    }

    /** Şehir araması (Türkçe adlarla). Arka planda çağrılmalı. */
    fun search(query: String): List<Place> {
        val json = JSONObject(
            Net.get("https://geocoding-api.open-meteo.com/v1/search?name=${Net.enc(query)}&count=8&language=tr&format=json")
        )
        val arr = json.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Place(
                name = o.getString("name"),
                detail = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotBlank() }.joinToString(", "),
                lat = o.getDouble("latitude"),
                lon = o.getDouble("longitude"),
            )
        }
    }

    private fun glyphFor(code: Int, day: Boolean): Glyph = when (code) {
        0 -> if (day) Glyph.SUN else Glyph.MOON
        1, 2 -> if (day) Glyph.PARTLY else Glyph.CLOUD
        3 -> Glyph.CLOUD
        45, 48 -> Glyph.FOG
        in 51..67, in 80..82 -> Glyph.RAIN
        in 71..77, 85, 86 -> Glyph.SNOW
        in 95..99 -> Glyph.STORM
        else -> Glyph.CLOUD
    }

    fun format(now: Now) = String.format(Locale.forLanguageTag("tr"), "%d°", now.tempC)
}
