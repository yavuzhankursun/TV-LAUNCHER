package com.hizir.sadehome.data

import android.os.Handler
import android.os.Looper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Arka plan işleri için küçük, paylaşılan iş parçacığı havuzu ve ana iş parçacığı. */
object Bg {
    val io: ExecutorService = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "sade-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    val main = Handler(Looper.getMainLooper())

    fun run(block: () -> Unit) {
        io.execute {
            try {
                block()
            } catch (t: Throwable) {
                android.util.Log.w("SadeHome", "arka plan işi hata verdi", t)
            }
        }
    }

    fun ui(block: () -> Unit) {
        main.post(block)
    }
}
