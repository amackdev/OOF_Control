package com.oof.control.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import com.oof.control.utils.FileUtils

/**
 * Handles all root/system operations
 * Uses MIUI ITouchFeature where available, direct file access as fallback
 */
object RootController {

    private const val TAG = "RootController"
    
    // Battery drain tracking
    private var lastCurrentNow = 0
    private var lastUpdateTime = 0L

    // ============ SHELL EXECUTION ============

    private fun executeCommandSync(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = reader.readLines()
            val errors = errorReader.readLines()
            val exitCode = process.waitFor()

            reader.close()
            errorReader.close()

            CommandResult(exitCode == 0, output, errors)
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: $command", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }

    private suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeCommandSync(command)
    }

    // ============ DIRECT FILE ACCESS ============

    private fun readFile(path: String): String? {
        return try {
            FileUtils.readOneLine(path)
                ?: run {
                    // Root fallback
                    val result = executeCommandSync("cat '$path' 2>/dev/null")
                    result.output.firstOrNull()?.trim()
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $path", e)
            null
        }
    }

    private fun writeFile(path: String, value: String): Boolean {
        return try {
            if (FileUtils.fileExists(path) && FileUtils.isFileWritable(path)) {
                FileUtils.writeLine(path, value)
            } else {
                // Root fallback
                executeCommandSync("echo '$value' > '$path'").isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing $path", e)
            executeCommandSync("echo '$value' > '$path'").isSuccess
        }
    }

    // ============ INITIALIZATION ============

    suspend fun isRootAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val file = File(DeviceConfig.BATTERY_CAPACITY)
            file.exists() && file.canRead()
        } catch (e: Exception) {
            false
        }
    }

    // ============ SYSTEM PROPERTY OPERATIONS ============

    suspend fun setProperty(prop: String, value: String): Boolean = withContext(Dispatchers.IO) {
        val result = executeCommand("setprop '$prop' '$value'")
        if (!result.isSuccess) {
            val resetResult = executeCommand("resetprop '$prop' '$value'")
            resetResult.isSuccess
        } else {
            true
        }
    }

    suspend fun getProperty(prop: String): String? = withContext(Dispatchers.IO) {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val result = reader.readLine()?.trim()
            reader.close()
            process.waitFor()
            result?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    // ============ DOUBLE TAP TO WAKE ============

    suspend fun setDT2W(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        val value = if (enabled) 1 else 0
        try {
            // Try MIUI Touch Feature first
            if (MiuiTouchFeature.isAvailable()) {
                if (MiuiTouchFeature.setModeValue(MiuiTouchFeature.TOUCH_ID_PRIMARY, MiuiTouchFeature.TOUCH_DOUBLETAP_MODE, value)) {
                    Log.i(TAG, "DT2W set via MiuiTouchFeature: $enabled")
                    return@withContext true
                }
            }
            // Fallback to file paths
            val strValue = if (enabled) "1" else "0"
            when {
                File("/proc/tp_gesture").exists() -> {
                    writeFile("/proc/tp_gesture", strValue)
                }
                File("/sys/touchpanel/double_tap").exists() -> {
                    writeFile("/sys/touchpanel/double_tap", strValue)
                }
                else -> {
                    Log.w(TAG, "No DT2W control method available")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting DT2W", e)
            false
        }
    }

    suspend fun getDT2W(): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try MIUI Touch Feature first
            if (MiuiTouchFeature.isAvailable()) {
                val value = MiuiTouchFeature.getModeValue(
                    MiuiTouchFeature.TOUCH_ID_PRIMARY,
                    MiuiTouchFeature.TOUCH_DOUBLETAP_MODE
                )
                if (value >= 0) {
                    return@withContext value == 1
                }
            }
            // Fallback to file paths
            when {
                File("/proc/tp_gesture").exists() -> { readFile("/proc/tp_gesture") == "1" }
                File("/sys/touchpanel/double_tap").exists() -> { readFile("/sys/touchpanel/double_tap") == "1" }
                else -> false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting DT2W", e)
            false
        }
    }

    // ============ TOUCH RATE (480Hz) ============

    suspend fun setTouchRate(highRate: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = DeviceConfig.touchRatePath
            if (path == null) {
                Log.w(TAG, "Touch rate path not available")
                return@withContext false
            }

            if (!File(path).exists()) {
                Log.w(TAG, "Touch rate file doesn't exist: $path")
                return@withContext false
            }

            val value = if (highRate) "1" else "0"
            val success = writeFile(path, value)

            if (success) {
                Log.i(TAG, "Touch rate set to ${if (highRate) "480Hz" else "240Hz"}")
            } else {
                Log.e(TAG, "Failed to set touch rate")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error setting touch rate", e)
            false
        }
    }

    suspend fun getTouchRate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = DeviceConfig.touchRatePath ?: return@withContext false
            if (!File(path).exists()) return@withContext false

            readFile(path)?.contains("480HZ", ignoreCase = true) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error getting touch rate", e)
            false
        }
    }

    suspend fun isTouchRateSupported(): Boolean = withContext(Dispatchers.IO) {
        val path = DeviceConfig.touchRatePath
        path != null && File(path).exists()
    }

    // ============ REFRESH RATE ============

    suspend fun setRefreshRate(context: Context, rate: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            // Method 1: Direct Settings.System (requires WRITE_SETTINGS)
            try {
                android.provider.Settings.System.putFloat(
                    context.contentResolver,
                    "peak_refresh_rate",
                    rate.toFloat()
                )
                android.provider.Settings.System.putFloat(
                    context.contentResolver,
                    "min_refresh_rate",
                    rate.toFloat()
                )
                Log.i(TAG, "Refresh rate set via Settings.System: ${rate}Hz")
                return@withContext true
            } catch (se: SecurityException) {
                Log.w(TAG, "No WRITE_SETTINGS permission, trying alternatives")
            }

            // Method 2: Shell settings command
            val cmd1 = executeCommand("settings put system peak_refresh_rate $rate.0")
            val cmd2 = executeCommand("settings put system min_refresh_rate $rate.0")

            if (cmd1.isSuccess && cmd2.isSuccess) {
                Log.i(TAG, "Refresh rate set via shell: ${rate}Hz")
                return@withContext true
            }

            Log.e(TAG, "All refresh rate methods failed")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error setting refresh rate", e)
            false
        }
    }

    suspend fun getRefreshRate(): Int = withContext(Dispatchers.IO) {
        try {
            val result = executeCommand("settings get system min_refresh_rate")
            if (result.isSuccess && result.output.isNotEmpty()) {
                result.output[0].trim().toFloatOrNull()?.toInt() ?: 120
            } else 120
        } catch (e: Exception) {
            120
        }
    }

    // ============ PERFORMANCE MODE ============

    suspend fun setPerformanceMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        setProperty("persist.xiaomi.performance", if (enabled) "enable" else "disable")
    }

    suspend fun getPerformanceMode(): Boolean = withContext(Dispatchers.IO) {
        getProperty("persist.xiaomi.performance") == "enable"
    }

    // ============ TOUCH BOOST ============

    suspend fun setTouchBoost(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        setProperty("persist.oof_touchboost.enable", if (enabled) "true" else "false")
    }

    suspend fun getTouchBoost(): Boolean = withContext(Dispatchers.IO) {
        getProperty("persist.oof_touchboost.enable") == "true"
    }

    // ============ SPORT MODE (90W CHARGING) ============
    suspend fun isSportModeSupported(): Boolean = withContext(Dispatchers.IO) {
        File(DeviceConfig.SPORT_MODE_PATH).exists()
    }

	suspend fun setSportMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
		setProperty("persist.sys.oof_sport_mode", if (enabled) "1" else "0")
	}

	suspend fun getSportMode(): Boolean = withContext(Dispatchers.IO) {
		getProperty("persist.sys.oof_sport_mode") == "1"
	}

    // ============ CHARGING CONTROL ============

    suspend fun stopCharging(): Boolean = withContext(Dispatchers.IO) {
        writeFile("${DeviceConfig.chargingPath}/input_suspend", "1")
    }

    suspend fun resumeCharging(): Boolean = withContext(Dispatchers.IO) {
        writeFile("${DeviceConfig.chargingPath}/input_suspend", "0")
    }

    suspend fun setChargingCurrent(current: Int): Boolean = withContext(Dispatchers.IO) {
        try {
            writeFile(DeviceConfig.BATTERY_CHARGE_LIMIT, "0")
            writeFile(DeviceConfig.BATTERY_CURRENT, current.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error setting charging current", e)
            false
        }
    }

    suspend fun isCharging(): Boolean = withContext(Dispatchers.IO) {
        val status = readFile(DeviceConfig.BATTERY_STATUS)
        status == "Charging" || status == "Full"
    }

    suspend fun isFastCharging(): Boolean = withContext(Dispatchers.IO) {
        readFile(DeviceConfig.FASTCHG_MODE) == "1"
    }

    suspend fun getUsbType(): String = withContext(Dispatchers.IO) {
        readFile(DeviceConfig.USB_TYPE) ?: "Unknown"
    }

    suspend fun getBatteryLevel(): Int = withContext(Dispatchers.IO) {
        readFile(DeviceConfig.BATTERY_CAPACITY)?.toIntOrNull() ?: 0
    }

    suspend fun getBatteryTemp(): Int = withContext(Dispatchers.IO) {
        readFile(DeviceConfig.BATTERY_TEMP)?.toIntOrNull() ?: 0
    }

    suspend fun getBatteryVoltage(): Int = withContext(Dispatchers.IO) {
        // Returns voltage in microvolts
        readFile("/sys/class/power_supply/battery/voltage_now")?.toIntOrNull() ?: 4000000
    }

    suspend fun getMaxPower(): Int = withContext(Dispatchers.IO) {
        readFile(DeviceConfig.POWER_MAX)?.toIntOrNull() ?: 0
    }

	suspend fun getBatteryStatus(): String? = withContext(Dispatchers.IO) {
		readFile(DeviceConfig.STATUS)?.toString()
	}

    /**
     * Get battery drain rate in mA/h
     * Positive = charging, Negative = discharging
     */
    suspend fun getBatteryDrainRate(): Int = withContext(Dispatchers.IO) {
        try {
            // Try current_now first (microamps)
            val currentNowPath = "/sys/class/power_supply/battery/current_now"
            val currentNow = readFile(currentNowPath)?.toIntOrNull()
            
            if (currentNow != null) {
                // Convert microamps to milliamps
                val currentMa = currentNow / 1000
                
                // Negative current_now means charging on some devices
                // Positive current_now means discharging
                // We want: positive = charging, negative = discharging
                val drainRate = -currentMa
                
                return@withContext drainRate
            }
            
            // Fallback: try current_avg
            val currentAvgPath = "/sys/class/power_supply/battery/current_avg"
            val currentAvg = readFile(currentAvgPath)?.toIntOrNull()
            
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

    // ============ WAKELOCKS ============
    
    suspend fun getWakelockInfo(): List<WakelockData> = withContext(Dispatchers.IO) {
        val wakelocks = mutableListOf<WakelockData>()
        
        try {
            // Try reading from /sys/kernel/debug/wakeup_sources first (needs root)
            val debugResult = executeCommand("cat /sys/kernel/debug/wakeup_sources 2>/dev/null || cat /d/wakeup_sources 2>/dev/null")
            
            if (debugResult.isSuccess && debugResult.output.isNotEmpty()) {
                // Parse wakeup_sources format:
                // name    active_count  event_count  wakeup_count  expire_count  active_since  total_time  max_time  last_change  prevent_suspend_time
                val lines = debugResult.output.drop(1)
                for (line in lines) {
                    try {
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 7) {
                            val name = parts[0]
                            val activeCount = parts[1].toLongOrNull() ?: 0L
                            val totalTime = parts[6].toLongOrNull() ?: 0L // total_time in ms
                            if (totalTime > 0 || activeCount > 0) {
                                wakelocks.add(WakelockData(name, totalTime, activeCount.toInt()))
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed lines
                    }
                }
            }
            
            // If debug method failed, try /sys/power/wake_lock
            if (wakelocks.isEmpty()) {
                val wakeLockResult = executeCommand("cat /sys/power/wake_lock 2>/dev/null")
                if (wakeLockResult.isSuccess && wakeLockResult.output.isNotEmpty()) {
                    val firstLine = wakeLockResult.output.firstOrNull() ?: ""
                    val locks = firstLine.trim().split(Regex("\\s+"))
                    for (name in locks) {
                        if (name.isNotBlank()) {
                            wakelocks.add(WakelockData(name, 0L, 1)) // Time unknown from this source
                        }
                    }
                }
            }
            
            // Also try dumpsys power for kernel wakelocks
            if (wakelocks.isEmpty()) {
                val dumpsysResult = executeCommand("dumpsys power 2>/dev/null | grep -A 100 'Wake Locks:' | head -50")
                if (dumpsysResult.isSuccess) {
                    for (line in dumpsysResult.output) {
                        // Parse lines like: "PARTIAL_WAKE_LOCK 'AudioMix' held=true"
                        val match = Regex("'([^']+)'.*?(\\d+)ms").find(line)
                        if (match != null) {
                            val name = match.groupValues[1]
                            val time = match.groupValues[2].toLongOrNull() ?: 0L
                            wakelocks.add(WakelockData(name, time, 1))
                        } else if (line.contains("WAKE_LOCK") && line.contains("'")) {
                            val nameMatch = Regex("'([^']+)'").find(line)
                            if (nameMatch != null) {
                                wakelocks.add(WakelockData(nameMatch.groupValues[1], 0L, 1))
                            }
                        }
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error getting wakelock info", e)
        }
        
        wakelocks
    }

    // ============ SYSTEM PROPS ============
    
    suspend fun getSystemProp(propName: String): String = withContext(Dispatchers.IO) {
        try {
            val result = executeCommand("getprop $propName")
            if (result.isSuccess && result.output.isNotEmpty()) {
                result.output.first().trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prop $propName", e)
            ""
        }
    }
    
    suspend fun setSystemProp(propName: String, value: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeCommand("setprop $propName $value")
            if (result.isSuccess) {
                // Verify the prop was set
                val verify = getSystemProp(propName)
                verify.equals(value, ignoreCase = true)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting prop $propName", e)
            false
        }
    }

    // ============ DATA CLASSES ============
    
    data class WakelockData(
        val name: String,
        val totalTimeMs: Long,
        val activateCount: Int
    )

    private data class CommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
        val errors: List<String>
    )
}