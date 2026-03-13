package com.oof.control

import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.FileUtils
import com.oof.control.utils.ShellExecutor

object SwitchUtils {
    
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
        return readSystemInt(DeviceConfig.BATTERY_CAPACITY)
    }

    fun getBatteryTemp(): Int {
        return readSystemInt(DeviceConfig.BATTERY_TEMP)
    }

    fun isCharging(): Boolean {
        return when (readSystemValue(DeviceConfig.BATTERY_STATUS)) {
            "Charging", "Full" -> true
            else -> false
        }
    }

    fun getUsbType(): String {
        return readSystemValue(DeviceConfig.USB_TYPE) ?: "Unknown"
    }

    fun getMaxPower(): Int {
        return readSystemInt(DeviceConfig.POWER_MAX)
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
        val r1 = ShellExecutor.executeShellSync("settings put system peak_refresh_rate $rate")
        val r2 = ShellExecutor.executeShellSync("settings put system min_refresh_rate $rate")
        return r1.isSuccess && r2.isSuccess
    }

    fun getRefreshRate(): Int {
        val result = ShellExecutor.executeShellSync("settings get system min_refresh_rate")
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
}