package com.oof.control

import java.io.BufferedReader
import java.io.InputStreamReader
import com.oof.control.utils.FileUtils

object SwitchUtils {
    
    // System paths
    const val BATTERY_CAPACITY = "/sys/class/power_supply/battery/capacity"
    const val BATTERY_TEMP = "/sys/class/power_supply/battery/temp"
    const val BATTERY_STATUS = "/sys/class/power_supply/battery/status"
    const val USB_TYPE = "/sys/class/power_supply/usb/type"
    const val POWER_MAX = "/sys/class/qcom-battery/power_max"
    
    /**
     * Execute a shell command
     */
    private fun executeCommand(command: String): CommandResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLines()
            val exitCode = process.waitFor()
            CommandResult(exitCode == 0, output)
        } catch (e: Exception) {
            CommandResult(false, emptyList())
        }
    }
    
    fun switchExists(path: String): Boolean {
        return FileUtils.fileExists(path)
    }
	
    fun readSwitch(path: String): String? {
        return if (FileUtils.isFileReadable(path)) {
            FileUtils.readOneLine(path)?.trim()
        } else {
            null
        }
    }

    fun writeSwitch(path: String, value: String): Boolean {
        return FileUtils.isFileWritable(path) &&
                FileUtils.writeLine(path, value)
    }

    fun getSwitchState(path: String): Boolean {
        return readSwitch(path) == "1"
    }

    fun setSwitchState(path: String, enabled: Boolean): Boolean {
        return writeSwitch(path, if (enabled) "1" else "0")
    }

    fun ensureSwitch(path: String, defaultValue: String = "0"): Boolean {
        return if (!switchExists(path)) {
            FileUtils.writeLine(path, defaultValue)
        } else {
            true
        }
    }

    fun deleteSwitch(path: String): Boolean {
        return FileUtils.delete(path)
    }
    
    fun readSystemValue(path: String): String? {
        return FileUtils.readOneLine(path)?.trim()
    }

    fun readSystemInt(path: String): Int {
        return FileUtils.readLineInt(path)
    }
    fun getBatteryLevel(): Int {
        return readSystemInt(BATTERY_CAPACITY)
    }

    fun getBatteryTemp(): Int {
        return readSystemInt(BATTERY_TEMP)
    }

    fun isCharging(): Boolean {
        return when (readSystemValue(BATTERY_STATUS)) {
            "Charging", "Full" -> true
            else -> false
        }
    }

    fun getUsbType(): String {
        return readSystemValue(USB_TYPE) ?: "Unknown"
    }

    fun getMaxPower(): Int {
        return readSystemInt(POWER_MAX)
    }

    fun getProperty(key: String, def: String = ""): String {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, key, def) as String
        } catch (e: Exception) {
            def
        }
    }

    fun setProperty(key: String, value: String): Boolean {
        return try {
            val cls = Class.forName("android.os.SystemProperties")
            val set = cls.getMethod("set", String::class.java, String::class.java)
            set.invoke(null, key, value)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun setRefreshRate(rate: Int): Boolean {
        val r1 = executeCommand("settings put system peak_refresh_rate $rate")
        val r2 = executeCommand("settings put system min_refresh_rate $rate")
        return r1.isSuccess && r2.isSuccess
    }

    fun getRefreshRate(): Int {
        val result = executeCommand("settings get system min_refresh_rate")
        return result.output.firstOrNull()
            ?.toFloatOrNull()
            ?.toInt()
            ?: 120
    }

    /* =========================
     * Device info helpers
     * ========================= */

	fun getDeviceCodename(): String {
		return getProperty("ro.amack.device").takeIf { it.isNotEmpty() }
			?: getProperty("ro.product.device").takeIf { it.isNotEmpty() }
			?: "unknown"
	}

    /* =========================
     * Internal result wrapper
     * ========================= */

    private data class CommandResult(
        val isSuccess: Boolean,
        val output: List<String>
    )
}