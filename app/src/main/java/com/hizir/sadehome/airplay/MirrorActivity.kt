package com.hizir.sadehome.airplay

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.hizir.sadehome.ui.Fonts
import com.hizir.sadehome.ui.Palette
import com.hizir.sadehome.ui.dp
import com.hizir.sadehome.ui.roundRect
import com.hizir.sadehome.ui.text

/**
 * Tam ekran yansıtma. Görüntü doğrudan SurfaceView'e (donanım katmanı) çözülür; uygulamanın kendi
 * çizim hattından geçmez, bu yüzden arayüz yükü sıfıra yakındır. Görüntü oranı korunur (dikey iPhone
 * ekranı ortada, yanlar siyah). Geri tuşu yansıtmayı bitirir.
 */
class MirrorActivity : Activity(), SurfaceHolder.Callback {
    private lateinit var root: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var status: TextView
    private var videoW = 16
    private var videoH = 9

    private val stateListener: () -> Unit = {
        if (!AirPlayState.mirroring) finish()
        else status.text = statusText()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        video.holder.addCallback(this)
        root.addView(video, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        status = text(statusText(), 16f, Palette.TEXT, Fonts.medium).apply {
            background = roundRect(0xCC15151B.toInt(), dp(20f))
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }
        root.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)
        window.setBackgroundDrawable(null) // siyah kök zaten her şeyi kaplıyor: fazladan tam ekran dolgu yok

        MirrorHub.onVideoSize = { w, h -> runOnUiThread { videoW = w; videoH = h; fit() } }
        MirrorHub.onFirstFrame = { runOnUiThread { status.animate().alpha(0f).setDuration(250).withEndAction { status.visibility = View.GONE }.start() } }
        MirrorHub.onPausedChange = { paused ->
            runOnUiThread {
                status.text = if (paused) "Gönderen ekranı kilitli · görüntü duraklatıldı" else statusText()
                status.visibility = View.VISIBLE
                status.alpha = if (paused) 1f else 0f
            }
        }
        AirPlayState.addListener(stateListener)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> fit() }
    }

    private fun statusText() = "AirPlay · ${AirPlayState.client ?: "Bağlanıyor"}"

    /** Görüntüyü oranını koruyarak ekrana sığdırır. */
    private fun fit() {
        val W = root.width
        val H = root.height
        if (W == 0 || H == 0 || videoW <= 0 || videoH <= 0) return
        val scale = minOf(W.toFloat() / videoW, H.toFloat() / videoH)
        val w = (videoW * scale).toInt()
        val h = (videoH * scale).toInt()
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w; lp.height = h
            video.layoutParams = lp
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        MirrorHub.attach(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        MirrorHub.detach()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) {
                AirPlayService.instance?.disconnect()
                finish()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // HOME ile çıkıldıysa yansıtmayı da bitir; arka planda görünmeyen bir akış boşuna kaynak yemesin.
        if (!isFinishing && AirPlayState.mirroring) {
            AirPlayService.instance?.disconnect()
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AirPlayState.removeListener(stateListener)
        MirrorHub.onVideoSize = null
        MirrorHub.onFirstFrame = null
        MirrorHub.onPausedChange = null
    }
}
