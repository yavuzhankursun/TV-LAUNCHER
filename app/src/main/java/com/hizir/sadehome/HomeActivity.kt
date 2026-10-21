package com.hizir.sadehome

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.hizir.sadehome.data.Prefs
import com.hizir.sadehome.system.HomeControl

/**
 * HOME tuşunun görünmez yönlendiricisi. Seçili moda göre bu ana ekranı ya da Google TV'ninkini açar.
 * Bir hata olursa her zaman stok ekrana düşer; kutu asla ana ekransız kalmaz.
 */
class HomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stock = HomeControl.stockHomeIntent(this)
        val target = if (Prefs.of(this).mode == Prefs.Mode.NEW || stock == null) {
            Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            stock
        }
        try {
            startActivity(target)
        } catch (t: Exception) {
            stock?.let { runCatching { startActivity(it) } }
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
