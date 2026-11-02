package com.gpudash.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import java.io.File

object Sensors {
    val coreCount: Int = Runtime.getRuntime().availableProcessors()

    private fun readText(path: String): String? =
        try { File(path).readText().trim() } catch (e: Exception) { null }

    private fun readLong(path: String): Long? = readText(path)?.toLongOrNull()

    fun coreFreqGhz(i: Int): Float? =
        readLong("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")?.let { it / 1_000_000f }

    fun coreMaxGhz(i: Int): Float? =
        readLong("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")?.let { it / 1_000_000f }

    fun batteryTemp(ctx: Context): Float {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    fun cpuTemp(ctx: Context): Float {
        try {
            val zones = File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            if (zones != null) for (z in zones) {
                val type = readText(File(z, "type").path)?.lowercase() ?: continue
                if (type.contains("cpu") || type.contains("soc") || type.contains("tsens")) {
                    val v = readLong(File(z, "temp").path) ?: continue
                    val c = if (v > 1000) v / 1000f else v.toFloat()
                    if (c in 10f..120f) return c
                }
            }
        } catch (e: Exception) { }
        return batteryTemp(ctx)
    }

    fun gpuLoad(): Int? {
        for (p in listOf("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy")) {
            val t = readText(p) ?: continue
            val d = t.filter { it.isDigit() }
            if (d.isNotEmpty()) return d.toInt().coerceIn(0, 100)
        }
        return null
    }
}
