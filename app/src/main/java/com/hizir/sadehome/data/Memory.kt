package com.hizir.sadehome.data

import android.app.ActivityManager
import android.content.Context

object Memory {
    /** Kullanılan RAM yüzdesi (0-100). */
    fun usedPercent(ctx: Context): Int {
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return 0
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return 0
        return ((1.0 - info.availMem.toDouble() / info.totalMem) * 100).toInt().coerceIn(0, 100)
    }
}
