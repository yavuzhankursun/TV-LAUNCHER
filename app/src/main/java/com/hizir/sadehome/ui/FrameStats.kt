package com.hizir.sadehome.ui

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Gravity
import android.view.Window
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Köşede canlı kare istatistiği: son 1 saniyedeki kare sayısı, 16.7 ms'yi aşan (takılan) kare oranı
 * ve en yavaş kare. Ölçüm sistemin FrameMetrics verisinden; ölçümün kendisi ayrı iş parçacığında.
 * Ayarlar → Performans göstergesi ile açılır; normal kullanımda kapalıdır.
 */
class FrameStats private constructor(private val activity: Activity, private val label: TextView) {
    private val thread = HandlerThread("frame-stats").apply { start() }
    private val handler = Handler(thread.looper)
    private var frames = 0
    private var janky = 0
    private var worstNs = 0L
    private var windowStart = System.nanoTime()
    private var totalFrames = 0L
    private var totalJanky = 0L

    private val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
        val d = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
        frames++; totalFrames++
        if (d > 16_666_667L) { janky++; totalJanky++ }
        if (d > worstNs) worstNs = d
        val now = System.nanoTime()
        if (now - windowStart >= 1_000_000_000L) {
            val text = "%d kare · takılma %%%.1f · en yavaş %.1f ms · toplam %%%.1f".format(
                frames, janky * 100f / frames.coerceAtLeast(1), worstNs / 1e6,
                totalJanky * 100f / totalFrames.coerceAtLeast(1),
            )
            frames = 0; janky = 0; worstNs = 0; windowStart = now
            label.post { label.text = text }
        }
    }

    fun detach() {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        (label.parent as? FrameLayout)?.removeView(label)
        thread.quitSafely()
    }

    companion object {
        fun attach(activity: Activity, root: FrameLayout): FrameStats {
            val label = activity.text("ölçülüyor…", 11f, Palette.TEXT, Fonts.medium).apply {
                setBackgroundColor(0xB3000000.toInt())
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }
            // Sabit genişlik: her saniyeki metin değişimi ekranı yeniden yerleştirmesin (ölçümü bozmasın).
            root.addView(label, FrameLayout.LayoutParams(activity.dp(440), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
            val s = FrameStats(activity, label)
            activity.window.addOnFrameMetricsAvailableListener(s.listener, s.handler)
            return s
        }
    }
}
