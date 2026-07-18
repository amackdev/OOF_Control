package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


object ChargingController {

    private const val TAG = "ChargingController"

    suspend fun stopCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.writeFile("${DeviceConfig.chargingPath}/input_suspend", "1")
    }

    suspend fun resumeCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.writeFile("${DeviceConfig.chargingPath}/input_suspend", "0")
    }

    suspend fun isChargingSuspended(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.readFile("${DeviceConfig.chargingPath}/input_suspend") == "1"
    }

    suspend fun setChargingCurrent(current: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // First disable charge limit to allow current control
            ShellExecutor.writeFile(DeviceConfig.BATTERY_CHARGE_LIMIT, "0")
            // Then set the charging current
            ShellExecutor.writeFile(DeviceConfig.BATTERY_CURRENT, current.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting charging current", e)
            false
        }
    }

    suspend fun getChargingCurrent(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.BATTERY_CURRENT)?.toIntOrNull() ?: 0
    }

    suspend fun isCharging(): Boolean = withContext(Dispatchers.IO) {
        val status = ShellExecutor.readFile(DeviceConfig.BATTERY_STATUS)
        status == "Charging" || status == "Full"
    }

    suspend fun isFastCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.FASTCHG_MODE) == "1"
    }

    /** @return USB type string (e.g. "USB_PD", "USB_DCP", "Unknown") */
    suspend fun getUsbType(): String = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.USB_TYPE) ?: "Unknown"
    }

    suspend fun isChargerConnected(): Boolean = withContext(Dispatchers.IO) {
        val type = getUsbType()
        if (type != "Unknown" && type.isNotBlank()) return@withContext true
        // Fallback: if battery is actively charging or full, it must be plugged in
        val status = ShellExecutor.readFile(DeviceConfig.BATTERY_STATUS)
        status == "Charging" || status == "Full"
    }

    suspend fun isSportModeSupported(): Boolean = withContext(Dispatchers.IO) {
        File(DeviceConfig.SPORT_MODE_PATH).exists()
    }

    suspend fun setSportMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.setProperty("persist.sys.oof_sport_mode", if (enabled) "1" else "0")
    }

    suspend fun getSportMode(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.getProperty("persist.sys.oof_sport_mode") == "1"
    }

    suspend fun getMaxPower(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
    }

    suspend fun getMaxPowerWatts(): Float = withContext(Dispatchers.IO) {
        val microwatts = ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
        microwatts / 1_000_000f
    }

    suspend fun getChargingInfo(): ChargingInfo = withContext(Dispatchers.IO) {
        ChargingInfo(
            isCharging = isCharging(),
            isFastCharging = isFastCharging(),
            isSuspended = isChargingSuspended(),
            usbType = getUsbType(),
            sportMode = getSportMode(),
            maxPower = getMaxPower(),
            currentLimit = getChargingCurrent()
        )
    }

    data class ChargingInfo(
        val isCharging: Boolean,
        val isFastCharging: Boolean,
        val isSuspended: Boolean,
        val usbType: String,
        val sportMode: Boolean,
        val maxPower: Int,
        val currentLimit: Int
    )
}
