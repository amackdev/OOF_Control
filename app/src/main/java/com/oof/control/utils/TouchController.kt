package com.oof.control.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File


object TouchController {

    private const val TAG = "TouchController"

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
            val success = ShellExecutor.writeFile(path, value)

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

    /** @return true if high rate (480Hz) */
    suspend fun getTouchRate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val path = DeviceConfig.touchRatePath ?: return@withContext false
            if (!File(path).exists()) return@withContext false

            ShellExecutor.readFile(path)?.contains("480HZ", ignoreCase = true) == true
        } catch (e: Exception) {
            Log.e(TAG, "Error getting touch rate", e)
            false
        }
    }

    suspend fun isTouchRateSupported(): Boolean = withContext(Dispatchers.IO) {
        val path = DeviceConfig.touchRatePath
        path != null && File(path).exists()
    }

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

            // Method 2: Shell settings command (safe - rate is an Int)
            val cmd1 = ShellExecutor.settings("system", "put", "peak_refresh_rate", "$rate.0")
            val cmd2 = ShellExecutor.settings("system", "put", "min_refresh_rate", "$rate.0")

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
            val result = ShellExecutor.settings("system", "get", "min_refresh_rate")
            if (result.isSuccess && result.output.isNotEmpty()) {
                result.output[0].trim().toFloatOrNull()?.toInt() ?: 120
            } else 120
        } catch (e: Exception) {
            120
        }
    }

    suspend fun setPerformanceMode(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.setProperty("persist.xiaomi.performance", if (enabled) "enable" else "disable")
    }

    suspend fun getPerformanceMode(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.getProperty("persist.xiaomi.performance") == "enable"
    }

    suspend fun setTouchBoost(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.setProperty("persist.oof_touchboost.enable", if (enabled) "true" else "false")
    }

    suspend fun getTouchBoost(): Boolean = withContext(Dispatchers.IO) {
        ShellExecutor.getProperty("persist.oof_touchboost.enable") == "true"
    }

    suspend fun getSystemProp(propName: String): String = withContext(Dispatchers.IO) {
        try {
            ShellExecutor.getProperty(propName) ?: ""
        } catch (e: Exception) {
            Log.e(TAG, "Error getting prop $propName", e)
            ""
        }
    }

    suspend fun setSystemProp(propName: String, value: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val success = ShellExecutor.setProperty(propName, value)
            if (success) {
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
}
