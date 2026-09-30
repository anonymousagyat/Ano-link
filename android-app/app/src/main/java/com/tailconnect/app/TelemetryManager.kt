package com.tailconnect.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs

data class TelemetryData(
    val battery: Int,
    val charging: Boolean,
    val temperature: Float,
    val voltage: Int,
    val freeStorageGb: Float,
    val totalStorageGb: Float,
    val freeRamGb: Float,
    val totalRamGb: Float,
    val model: String,
    val manufacturer: String,
    val androidVersion: String,
    val apiLevel: Int
)

object TelemetryManager {

    fun collect(context: Context): TelemetryData {
        // 1. Battery & Power
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val temperature = tempTenths / 10.0f
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        // 2. Storage
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalStorageGb = (totalBlocks * blockSize) / (1024f * 1024f * 1024f)
        val freeStorageGb = (availableBlocks * blockSize) / (1024f * 1024f * 1024f)

        // 3. RAM
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)

        val totalRamGb = memInfo.totalMem / (1024f * 1024f * 1024f)
        val freeRamGb = memInfo.availMem / (1024f * 1024f * 1024f)

        return TelemetryData(
            battery = batteryPct,
            charging = isCharging,
            temperature = temperature,
            voltage = voltage,
            freeStorageGb = freeStorageGb,
            totalStorageGb = totalStorageGb,
            freeRamGb = freeRamGb,
            totalRamGb = totalRamGb,
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            androidVersion = Build.VERSION.RELEASE,
            apiLevel = Build.VERSION.SDK_INT
        )
    }
}
