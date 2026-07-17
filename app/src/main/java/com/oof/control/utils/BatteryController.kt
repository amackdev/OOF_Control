package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


object BatteryController {

    private const val TAG = "BatteryController"

    // Battery drain tracking (for rate calculation)
    private var lastCurrentNow = 0
    private var lastUpdateTime = 0L

    suspend fun getBatteryLevel(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.BATTERY_CAPACITY)?.toIntOrNull() ?: 0
    }

    /** Returns tenths of °C (divide by 10 for actual temp). */
    suspend fun getBatteryTemp(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.BATTERY_TEMP)?.toIntOrNull() ?: 0
    }

    suspend fun getBatteryTempCelsius(): Float = withContext(Dispatchers.IO) {
        val temp = ShellExecutor.readFile(DeviceConfig.BATTERY_TEMP)?.toIntOrNull() ?: 0
        temp / 10f
    }

    suspend fun getBatteryVoltage(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile("/sys/class/power_supply/battery/voltage_now")?.toIntOrNull() ?: 4000000
    }

    suspend fun getBatteryVoltageVolts(): Float = withContext(Dispatchers.IO) {
        val microvolts = ShellExecutor.readFile("/sys/class/power_supply/battery/voltage_now")?.toIntOrNull() ?: 4000000
        microvolts / 1_000_000f
    }

    /** charge_full / charge_full_design × 100. Returns 0.0 if unavailable, clamped to 120.0. */
    suspend fun getBatteryHealthPercent(): Double = withContext(Dispatchers.IO) {
        val full = ShellExecutor.readFile(DeviceConfig.BATTERY_FULL)?.toDoubleOrNull() ?: 0.0
        val design = ShellExecutor.readFile(DeviceConfig.BATTERY_FULL_DESIGN)?.toDoubleOrNull() ?: 0.0

        if (full <= 0.0 || design <= 0.0) return@withContext 0.0

        // Clamp to avoid silly values from some kernels
        val pct = (full / design) * 100.0
        pct.coerceIn(0.0, 120.0)
    }

    suspend fun getBatteryStatus(): String? = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.STATUS)
    }

    suspend fun isCharging(): Boolean = withContext(Dispatchers.IO) {
        val status = ShellExecutor.readFile(DeviceConfig.BATTERY_STATUS)
        status == "Charging" || status == "Full"
    }

    /** Returns drain in mA. Positive = charging, negative = discharging. */
    suspend fun getBatteryDrainRate(): Int = withContext(Dispatchers.IO) {
        try {
            // Try current_now first (microamps)
            val currentNowPath = "/sys/class/power_supply/battery/current_now"
            val currentNow = ShellExecutor.readFile(currentNowPath)?.toIntOrNull()

            if (currentNow != null) {
                // Convert microamps to milliamps
                val currentMa = currentNow / 1000

                // Negative current_now means charging on some devices
                // Positive current_now means discharging
                // We want: positive = charging, negative = discharging
                return@withContext -currentMa
            }

            // Fallback: try current_avg
            val currentAvgPath = "/sys/class/power_supply/battery/current_avg"
            val currentAvg = ShellExecutor.readFile(currentAvgPath)?.toIntOrNull()

            if (currentAvg != null) {
                return@withContext -(currentAvg / 1000)
            }

            // If all else fails, return 0
            0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting battery drain rate", e)
            0
        }
    }

    suspend fun getCurrentNow(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile("/sys/class/power_supply/battery/current_now")?.toIntOrNull() ?: 0
    }

    suspend fun getCurrentAvg(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile("/sys/class/power_supply/battery/current_avg")?.toIntOrNull() ?: 0
    }

    suspend fun getMaxPower(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
    }

    suspend fun getMaxPowerWatts(): Float = withContext(Dispatchers.IO) {
        val microwatts = ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
        microwatts / 1_000_000f
    }

    suspend fun isAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(DeviceConfig.BATTERY_CAPACITY)
            file.exists() && file.canRead()
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getBatteryInfo(): BatteryInfo = withContext(Dispatchers.IO) {
        BatteryInfo(
            level = getBatteryLevel(),
            temperature = getBatteryTemp(),
            voltage = getBatteryVoltage(),
            healthPercent = getBatteryHealthPercent(),
            status = getBatteryStatus(),
            drainRate = getBatteryDrainRate(),
            maxPower = getMaxPower()
        )
    }


    data class BatteryInfo(
        val level: Int,
        val temperature: Int,
        val voltage: Int,
        val healthPercent: Double,
        val status: String?,
        val drainRate: Int,
        val maxPower: Int
    )
}
