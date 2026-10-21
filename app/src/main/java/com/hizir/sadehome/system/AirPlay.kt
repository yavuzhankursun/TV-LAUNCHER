package com.hizir.sadehome.system

import com.hizir.sadehome.data.AppEntry

/**
 * iPhone/Mac'ten kablosuz görüntü aktarımı için kurulu bir AirPlay alıcısı (AirScreen, AirPin…) bulur.
 * Bulunursa üst çubukta "AirPlay" çipi görünür.
 */
object AirPlay {
    private val KNOWN = listOf(
        "com.ionitech.airscreen",
        "com.waxrain.airplaydmr",
        "com.waxrain.airplaydmr3",
        "com.waxrain.airplaydmr_SDK",
        "com.xindawn.droidtv",
    )
    /** Alıcı kurulu değilse Play Store'da önerilen: AirScreen (ücretsiz, Google TV'de çalışıyor). */
    const val SUGGESTED = "com.ionitech.airscreen"

    private val NAME_HINTS = listOf("airplay", "airscreen", "airpin", "airreceiver")

    fun find(apps: List<AppEntry>): AppEntry? {
        val byPkg = apps.associateBy { it.pkg }
        KNOWN.firstNotNullOfOrNull { byPkg[it] }?.let { return it }
        return apps.firstOrNull { e -> NAME_HINTS.any { e.label.lowercase().contains(it) } }
    }
}
