package com.oof.control.utils

import android.util.Log

/**
 * Device-specific configuration and system paths
 * System app version - no libsu dependency
 */
object DeviceConfig {
    
    private const val TAG = "DeviceConfig"
    
    // Supported devices
    const val DEVICE_PERIDOT = "peridot"  // POCO F6
    const val DEVICE_MARBLE = "marble"    // POCO F5
    
    // Cached values
    private var _deviceCodename: String? = null
    private var _deviceBrand: String? = null
    private var _chargingPath: String? = null
    private var _touchRatePath: String? = null
    private var _dt2wPath: String? = null
    private var _hasXiaomiTouch: Boolean? = null
    
    // Current device codename
    val deviceCodename: String
        get() {
            if (_deviceCodename == null) {
                _deviceCodename = getPropertySafe("ro.amack.device") 
                    ?: getPropertySafe("ro.product.device") 
                    ?: "unknown"
            }
            return _deviceCodename!!
        }
    
    val deviceBrand: String
        get() {
            if (_deviceBrand == null) {
                _deviceBrand = getPropertySafe("ro.product.product.brand") ?: "unknown"
            }
            return _deviceBrand!!
        }
    
    val isSupported: Boolean
        get() = deviceCodename in listOf(DEVICE_PERIDOT, DEVICE_MARBLE)
    
    val isOnePlus: Boolean
        get() = deviceBrand.equals("OnePlus", ignoreCase = true)
    
    val isXiaomi: Boolean
        get() = deviceBrand.equals("Xiaomi", ignoreCase = true) || 
                deviceBrand.equals("POCO", ignoreCase = true) ||
                deviceBrand.equals("Redmi", ignoreCase = true)
    
    // Battery capacity in mAh (device-specific)
    val batteryCapacityMah: Int
        get() = when (deviceCodename) {
            DEVICE_PERIDOT -> 5000  // POCO F6 Pro
            DEVICE_MARBLE -> 5000   // POCO F5
            else -> 5000            // Default
        }
    
    // ============ SYSTEM PATHS ============
    
    // Battery paths
    val chargingPath: String
        get() {
            if (_chargingPath == null) {
                _chargingPath = if (pathExistsSafe("/sys/class/qcom-battery/input_suspend")) {
                    "/sys/class/qcom-battery"
                } else {
                    "/sys/class/power_supply/battery"
                }
            }
            return _chargingPath!!
        }
    
    const val BATTERY_CAPACITY = "/sys/class/power_supply/battery/capacity"
    const val BATTERY_TEMP = "/sys/class/power_supply/battery/temp"
    const val BATTERY_FULL_DESIGN = "/sys/class/power_supply/battery/charge_full_design"
    const val BATTERY_FULL = "/sys/class/power_supply/battery/charge_full"
    const val BATTERY_STATUS = "/sys/class/power_supply/battery/status"
    const val BATTERY_CURRENT = "/sys/class/power_supply/battery/constant_charge_current"
    const val BATTERY_CHARGE_LIMIT = "/sys/class/power_supply/battery/charge_control_limit"
	const val STATUS = "/sys/class/power_supply/battery/status"
    const val USB_TYPE = "/sys/class/power_supply/usb/type"
    const val POWER_MAX = "/sys/class/qcom-battery/power_max"
    const val SPORT_MODE_PATH = "/sys/class/qcom-battery/sport_mode"
    const val FASTCHG_MODE = "/sys/class/qcom-battery/fastchg_mode"
    const val INPUT_SUSPEND = "/sys/class/qcom-battery/input_suspend"

    // Touch paths - detected at runtime
    val touchRatePath: String?
        get() {
            if (_touchRatePath == null) {
                _touchRatePath = when {
                    pathExistsSafe("/sys/devices/platform/goodix_ts.0/switch_report_rate") ->
                        "/sys/devices/platform/goodix_ts.0/switch_report_rate"
                    pathExistsSafe("/sys/bus/spi/drivers/focaltech_ts/spi1.0/switch_report_rate") ->
                        "/sys/bus/spi/drivers/focaltech_ts/spi1.0/switch_report_rate"
                    else -> ""
                }
            }
            return _touchRatePath?.takeIf { it.isNotEmpty() }
        }
    
    // DT2W paths
    val dt2wPath: String?
        get() {
            if (_dt2wPath == null) {
                _dt2wPath = when {
                    pathExistsSafe("/proc/tp_gesture") -> "/proc/tp_gesture"
                    pathExistsSafe("/sys/touchpanel/double_tap") -> "/sys/touchpanel/double_tap"
                    hasXiaomiTouch -> "xiaomi_touch"
                    else -> ""
                }
            }
            return _dt2wPath?.takeIf { it.isNotEmpty() }
        }
    
    val hasXiaomiTouch: Boolean
        get() {
            if (_hasXiaomiTouch == null) {
                _hasXiaomiTouch = pathExistsSafe("/sys/class/misc/xiaomi-touch")
            }
            return _hasXiaomiTouch!!
        }
    
    // ============ CHARGING CURRENT TABLES ============
    
    /**
     * Get charging current based on temperature, battery level, and sport mode
     * Returns current in microamps (μA)
     */
    fun getChargingCurrent(
        temp: Int,          // Temperature in tenths of degree (e.g., 350 = 35.0°C)
        batteryLevel: Int,  // 0-100
        sportMode: Boolean,
        powerInput: Int     // Max power input in watts
    ): Int {
        // Above 80%, use taper charging
        if (batteryLevel > 95) {
            return when (deviceCodename) {
                DEVICE_PERIDOT -> 1900000
                DEVICE_MARBLE -> 1500000
                else -> 1500000
            }
        }
        
        if (batteryLevel > 90) {
            return when (deviceCodename) {
                DEVICE_PERIDOT -> 2650000
                DEVICE_MARBLE -> 2400000
                else -> 2400000
            }
        }
        
        if (batteryLevel > 80) {
            return when (deviceCodename) {
                DEVICE_PERIDOT -> 3250000
                DEVICE_MARBLE -> 2750000
                else -> 2750000
            }
        }
        
        // Normal charging (0-80%)
        return when (deviceCodename) {
            DEVICE_PERIDOT -> getPeridotCurrent(temp, batteryLevel, sportMode, powerInput)
            DEVICE_MARBLE -> getMarbleCurrent(temp)
            else -> 5000000 // Default safe current
        }
    }
    
    private fun getPeridotCurrent(temp: Int, batteryLevel: Int, sportMode: Boolean, powerInput: Int): Int {
        if (sportMode && powerInput >= 90) {
            // Sport mode 90W charging
            return if (batteryLevel < 50) {
                when {
                    temp < 360 -> 14000000
                    temp < 380 -> 12760000
                    temp < 400 -> 9960000
                    temp < 420 -> 8250000
                    temp < 430 -> 5050000
                    else -> 205000 // Thermal protection
                }
            } else {
                when {
                    temp < 360 -> 12760000
                    temp < 380 -> 10000000
                    temp < 400 -> 9850000
                    temp < 420 -> 7250000
                    temp < 430 -> 5050000
                    else -> 205000
                }
            }
        } else {
            // Standard charging
            return when {
                temp < 380 -> 9750000
                temp < 400 -> 8450000
                temp < 420 -> 7250000
                temp < 430 -> 5050000
                else -> 205000
            }
        }
    }
    
    private fun getMarbleCurrent(temp: Int): Int {
        return when {
            temp <= 390 -> 8750000
            temp <= 400 -> 6550000
            temp <= 420 -> 5050000
            temp <= 430 -> 3250000
            else -> 205000
        }
    }
    
    // ============ HELPER FUNCTIONS - SAFE VERSIONS ============

    private fun getPropertySafe(prop: String): String? {
        return try {
            ShellExecutor.getPropertySync(prop)
        } catch (e: Exception) {
            null
        }
    }

    private fun pathExistsSafe(path: String): Boolean {
        return ShellExecutor.pathExists(path)
    }
    
    /**
     * Clear cached values - useful if device state changes
     */
    fun clearCache() {
        _deviceCodename = null
        _deviceBrand = null
        _chargingPath = null
        _touchRatePath = null
        _dt2wPath = null
        _hasXiaomiTouch = null
    }
}
