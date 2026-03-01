package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Controller for charging-related operations including:
 * - Start/stop charging
 * - Charging current control
 * - Fast charging status
 * - USB type detection
 * - Sport mode (90W charging)
 */
object ChargingController {

    private const val TAG = "ChargingController"

    // ============ CHARGING CONTROL ============

    /**
     * Stop charging by suspending input.
     */
    suspend fun stopCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.writeFile("${DeviceConfig.chargingPath}/input_suspend", "1")
    }

    /**
     * Resume charging by un-suspending input.
     */
    suspend fun resumeCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.writeFile("${DeviceConfig.chargingPath}/input_suspend", "0")
    }

    /**
     * Check if charging is currently suspended.
     */
    suspend fun isChargingSuspended(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.readFile("${DeviceConfig.chargingPath}/input_suspend") == "1"
    }

    // ============ CHARGING CURRENT ============

    /**
     * Set maximum charging current in microamps.
     */
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

    /**
     * Get current charging current limit in microamps.
     */
    suspend fun getChargingCurrent(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.BATTERY_CURRENT)?.toIntOrNull() ?: 0
    }

    // ============ CHARGING STATUS ============

    /**
     * Check if device is currently charging.
     */
    suspend fun isCharging(): Boolean = withContext(Dispatchers.IO) {
        val status = ShellExecutor.readFile(DeviceConfig.BATTERY_STATUS)
        status == "Charging" || status == "Full"
    }

    /**
     * Check if device is fast charging.
     */
    suspend fun isFastCharging(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.FASTCHG_MODE) == "1"
    }

    /**
     * Get current USB connection type.
     * @return USB type string (e.g., "USB_PD", "USB_DCP", "Unknown")
     */
    suspend fun getUsbType(): String = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.USB_TYPE) ?: "Unknown"
    }

    /**
     * Check if a charger is connected.
     */
    suspend fun isChargerConnected(): Boolean = withContext(Dispatchers.IO) {
        getUsbType() != "Unknown"
    }

    // ============ SPORT MODE (90W CHARGING) ============

    /**
     * Check if sport mode (90W turbo charging) is supported on this device.
     */
    suspend fun isSportModeSupported(): Boolean = withContext(Dispatchers.IO) {
        File(DeviceConfig.SPORT_MODE_PATH).exists()
    }

    /**
     * Enable or disable sport mode (90W turbo charging).
     */
    suspend fun setSportMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.setProperty("persist.sys.oof_sport_mode", if (enabled) "1" else "0")
    }

    /**
     * Get current sport mode state.
     */
    suspend fun getSportMode(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.getProperty("persist.sys.oof_sport_mode") == "1"
    }

    // ============ POWER INFO ============

    /**
     * Get maximum charging power in microwatts.
     */
    suspend fun getMaxPower(): Int = withContext(Dispatchers.IO) {
        ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
    }

    /**
     * Get maximum charging power in watts.
     */
    suspend fun getMaxPowerWatts(): Float = withContext(Dispatchers.IO) {
        val microwatts = ShellExecutor.readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
        microwatts / 1_000_000f
    }

    // ============ CHARGING INFO ============

    /**
     * Get all charging info as a data class.
     */
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

    /**
     * Data class containing all charging information.
     */
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
