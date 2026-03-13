package com.oof.control.utils

import android.util.Log

/**
 * Touch feature interface backed by the daemon config writer.
 * Replaces the former direct ioctl JNI implementation.
 */
object MiuiTouchFeature {

    private const val TAG = "MiuiTouchFeature"

    // ── Touch mode IDs ─────────────────────────────────────────────────
    const val TOUCH_GAME_MODE               = TouchConstants.MODE_GAME_MODE
    const val TOUCH_ACTIVE_MODE             = TouchConstants.MODE_ACTIVE
    const val TOUCH_UP_THRESHOLD            = TouchConstants.MODE_UP_THRESHOLD
    const val TOUCH_TOLERANCE               = TouchConstants.MODE_TOLERANCE
    const val TOUCH_WGH_MIN                 = TouchConstants.MODE_AIM_SENSITIVITY
    const val TOUCH_WGH_MAX                 = TouchConstants.MODE_TAP_STABILITY
    const val TOUCH_WGH_STEP                = TouchConstants.MODE_EXPERT
    const val TOUCH_EDGE_FILTER             = TouchConstants.MODE_EDGE_FILTER
    const val TOUCH_EDGE_MODE               = TouchConstants.MODE_GRIP_LONG

    const val TOUCH_ID_PRIMARY   = 0
    const val TOUCH_ID_SECONDARY = 1

    @Volatile private var _available: Boolean? = null

    // Cache to return locally set values, as we can no longer query the kernel directly via ioctl.
    private val modeCache = mutableMapOf<Int, Int>()

    fun isAvailable(): Boolean = _available ?: detect().also { _available = it }

    private fun detect(): Boolean {
        // Assume available if the device is supported, as the daemon will handle the requests.
        val codename = DeviceConfig.deviceCodename
        Log.i(TAG, "xiaomi-touch via daemon available (device=\$codename)")
        return true
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun getModeValue(touchId: Int, mode: Int): Int {
        Log.d(TAG, "getModeValue(touchId=\$touchId, mode=\$mode)")
        return modeCache[mode] ?: getModeDefaultValue(touchId, mode)
    }

    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean {
        Log.d(TAG, "setModeValue(touchId=\$touchId, mode=\$mode, value=\$value)")
        modeCache[mode] = value
        // The GameModeManager now batch-writes settings, but for isolated calls,
        // we can trigger a write of the current cache here.
        DaemonConfigWriter.writeConfig(modeCache, null)
        return true
    }

    fun resetMode(touchId: Int, mode: Int): Boolean {
        Log.d(TAG, "resetMode(touchId=\$touchId, mode=\$mode)")
        modeCache.remove(mode)
        DaemonConfigWriter.writeConfig(modeCache, null)
        return true
    }

    fun getModeDefaultValue(touchId: Int, mode: Int): Int {
        // Fallback default values
        return when (mode) {
            TOUCH_GAME_MODE -> 0
            TOUCH_ACTIVE_MODE -> 0
            TOUCH_UP_THRESHOLD -> 0
            TOUCH_TOLERANCE -> 0
            TOUCH_WGH_MIN -> 0
            TOUCH_WGH_MAX -> 0
            TOUCH_WGH_STEP -> 0
            TOUCH_EDGE_FILTER -> 0
            TOUCH_EDGE_MODE -> 0
            else -> 0
        }
    }

    fun getModeMaxValue(touchId: Int, mode: Int): Int {
        // Estimated max values from typical profiles
        return 100 
    }

    fun getModeMinValue(touchId: Int, mode: Int): Int {
        return 0
    }
}
