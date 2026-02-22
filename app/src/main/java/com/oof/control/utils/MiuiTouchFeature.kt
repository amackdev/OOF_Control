package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Touch feature interface backed by /dev/xiaomi-touch direct ioctl calls.
 * Replaces the former AIDL ITouchFeature binder implementation.
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
    const val TOUCH_WGH_STEP                = TouchConstants.MODE_6
    const val TOUCH_EDGE_FILTER             = TouchConstants.MODE_EDGE_FILTER
    const val TOUCH_MODE_DIRECTION          = TouchConstants.MODE_ORIENTATION
    const val TOUCH_DOUBLETAP_MODE          = TouchConstants.MODE_DOUBLETAP
    const val TOUCH_EDGE_MODE               = TouchConstants.MODE_GRIP_LONG
    const val TOUCH_DEBUG_LEVEL             = TouchConstants.MODE_LOG_LEVEL
    const val TOUCH_STYLUS_MODE             = TouchConstants.MODE_STYLUS_20
    const val TOUCH_PERFORMANCE_MODE        = TouchConstants.MODE_21
    const val TOUCH_STYLUS_HOPPING_MODE     = TouchConstants.MODE_STYLUS_22
    const val TOUCH_PASSIVE_PEN_MODE        = TouchConstants.MODE_23
    const val TOUCH_STYLUS_QUICK_NOTE_MODE  = TouchConstants.MODE_STYLUS_QUICK
    const val TOUCH_TP_EDGE_MODE            = TouchConstants.MODE_IC_25
    const val TOUCH_STYLUS_SLEEP_STATE      = TouchConstants.MODE_STYLUS_29
    const val TOUCH_DISPLAY_ID_STATE        = TouchConstants.MODE_FLIP
    const val TOUCH_SINGLETAP_MODE          = TouchConstants.MODE_PAD_SINGLETAP

    const val TOUCH_ID_PRIMARY   = 0
    const val TOUCH_ID_SECONDARY = 1

    // ── Availability ───────────────────────────────────────────────────

    @Volatile private var _available: Boolean? = null

    fun isAvailable(): Boolean = _available ?: IoctlBridge.isDeviceAvailable().also {
        _available = it
        if (it) Log.i(TAG, "xiaomi-touch available – direct ioctl mode")
        else    Log.e(TAG, "xiaomi-touch NOT available")
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun getModeValue(touchId: Int, mode: Int): Int {
        Log.d(TAG, "getModeValue(touchId=$touchId, mode=$mode)")
        return runCatching {
            val r = runBlocking { IoctlBridge.getMode(touchId, mode, TouchConstants.CMD_GET_CUR_VALUE) }
            if (r.ok) { Log.i(TAG, "getModeValue=${r.value}"); r.value }
            else { Log.e(TAG, "getModeValue FAILED: ${r.raw}"); -1 }
        }.getOrElse { Log.e(TAG, "getModeValue exception", it); -1 }
    }

    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean {
        Log.d(TAG, "setModeValue(touchId=$touchId, mode=$mode, value=$value)")
        return runCatching {
            val r = runBlocking { IoctlBridge.setMode(touchId, mode, value) }
            if (r.ok) { Log.i(TAG, "setModeValue OK"); true }
            else { Log.e(TAG, "setModeValue FAILED: ${r.raw}"); false }
        }.getOrElse { Log.e(TAG, "setModeValue exception", it); false }
    }

    fun resetMode(touchId: Int, mode: Int): Boolean {
        Log.d(TAG, "resetMode(touchId=$touchId, mode=$mode)")
        return runCatching {
            val r = runBlocking { IoctlBridge.resetMode(touchId, mode) }
            if (r.ok) { Log.i(TAG, "resetMode OK"); true }
            else { Log.e(TAG, "resetMode FAILED: ${r.raw}"); false }
        }.getOrElse { Log.e(TAG, "resetMode exception", it); false }
    }

    fun getModeDefaultValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { IoctlBridge.getMode(touchId, mode, TouchConstants.CMD_GET_DEF_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeDefaultValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeDefaultValue exception", it); -1 }

    fun getModeMaxValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { IoctlBridge.getMode(touchId, mode, TouchConstants.CMD_GET_MAX_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeMaxValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeMaxValue exception", it); -1 }

    fun getModeMinValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { IoctlBridge.getMode(touchId, mode, TouchConstants.CMD_GET_MIN_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeMinValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeMinValue exception", it); -1 }
}
