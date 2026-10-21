package com.hizir.sadehome

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.hizir.sadehome.data.Prefs
import com.hizir.sadehome.system.HomeControl

/**
 * Google TV varsayılan ana ekran ayarını yok sayıp HOME'u hep kendi ekranına gönderiyor.
 * Bu servis HOME tuşunu yakalar ve bu ana ekranı açar; kaçırırsa Google TV ana ekranı öne
 * geldiği anda yönlendirir. Ekran içeriğini OKUMAZ (canRetrieveWindowContent=false);
 * sadece HOME tuşunu ve öndeki pencerenin paket/sınıf adını görür.
 * Google TV arayüzü modunda hiçbir şey yapmaz.
 */
class HomeWatcherService : AccessibilityService() {
    private var lastRedirect = 0L

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (Prefs.of(this).mode != Prefs.Mode.NEW) return false
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) goHome()
        return true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in HomeControl.STOCK_PACKAGES) return
        if (Prefs.of(this).mode != Prefs.Mode.NEW) return
        // Google TV'nin ana ekranı. Ana ekran öne gelince olay bazen aktivite adıyla değil, kök görünümün
        // sınıfıyla (FrameLayout) geliyor; sadece aktivite adına bakınca yönlendirme kaçıyordu (cihazda görüldü).
        val cls = event.className?.toString() ?: return
        if (cls !in HomeControl.stockHomeClasses(this) && cls != ROOT_VIEW_CLASS) return
        if (SystemClock.elapsedRealtime() - lastRedirect < 800) return
        goHome()
    }

    private fun goHome() {
        lastRedirect = SystemClock.elapsedRealtime()
        try {
            startActivity(
                Intent(this, LauncherActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
                )
            )
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val ROOT_VIEW_CLASS = "android.widget.FrameLayout"
    }
}
