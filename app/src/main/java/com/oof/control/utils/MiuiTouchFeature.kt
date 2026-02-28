package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Touch feature interface backed by /dev/xiaomi-touch direct ioctl calls.
 * Replaces the former AIDL ITouchFeature binder implementation.
 *
 * Auto-detects driver variant:
 *   - Peridot: common_data_t struct, separate SELECT_TOUCH_ID
 *   - Marble:  int buf[256], touchId inside buffer
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
    const val TOUCH_DOUBLETAP_MODE          = TouchConstants.MODE_DOUBLETAP
    const val TOUCH_EDGE_MODE               = TouchConstants.MODE_GRIP_LONG

    const val TOUCH_ID_PRIMARY   = 0
    const val TOUCH_ID_SECONDARY = 1

    private enum class Variant { PERIDOT, MARBLE }

    @Volatile private var _available: Boolean? = null
    @Volatile private var variant: Variant = Variant.PERIDOT

    private val useMarble: Boolean get() = variant == Variant.MARBLE

    fun isAvailable(): Boolean = _available ?: detect().also { _available = it }

    private fun detect(): Boolean {
        if (!IoctlBridge.isDeviceAvailable()) {
            Log.e(TAG, "xiaomi-touch NOT available")
            return false
        }
        val codename = DeviceConfig.deviceCodename
        variant = when (codename) {
            DeviceConfig.DEVICE_MARBLE -> Variant.MARBLE
            DeviceConfig.DEVICE_PERIDOT -> Variant.PERIDOT
            else -> probe()
        }
        Log.i(TAG, "xiaomi-touch available — variant=$variant (device=$codename)")
        return true
    }

    private fun probe(): Variant {
        val ok = runCatching {
            runBlocking { IoctlBridge.getMode(0, TouchConstants.MODE_GAME_MODE, TouchConstants.CMD_GET_CUR_VALUE) }.ok
        }.getOrDefault(false)
        if (ok) return Variant.PERIDOT

        val mOk = runCatching {
            runBlocking { MarbleIoctlBridge.getMode(0, TouchConstants.MODE_GAME_MODE, TouchConstants.CMD_GET_CUR_VALUE) }.ok
        }.getOrDefault(false)
        if (mOk) return Variant.MARBLE

        return Variant.PERIDOT
    }

    private suspend fun doGet(touchId: Int, mode: Int, cmd: Int): IoctlBridge.IoctlResult {
        return if (useMarble) MarbleIoctlBridge.getMode(touchId, mode, cmd)
        else IoctlBridge.getMode(touchId, mode, cmd)
    }

    private suspend fun doSet(touchId: Int, mode: Int, value: Int): IoctlBridge.IoctlResult {
        return if (useMarble) MarbleIoctlBridge.setMode(touchId, mode, value)
        else IoctlBridge.setMode(touchId, mode, value)
    }

    private suspend fun doReset(touchId: Int, mode: Int): IoctlBridge.IoctlResult {
        return if (useMarble) MarbleIoctlBridge.resetMode(touchId, mode)
        else IoctlBridge.resetMode(touchId, mode)
    }

    private suspend fun doSetLong(touchId: Int, mode: Int, values: IntArray): IoctlBridge.IoctlResult {
        return if (useMarble) MarbleIoctlBridge.setModeLong(touchId, mode, values)
        else IoctlBridge.setModeLong(touchId, mode, values)
    }

    // ── Public API ─────────────────────────────────────────────────────

    fun getModeValue(touchId: Int, mode: Int): Int {
        Log.d(TAG, "getModeValue(touchId=$touchId, mode=$mode)")
        return runCatching {
            val r = runBlocking { doGet(touchId, mode, TouchConstants.CMD_GET_CUR_VALUE) }
            if (r.ok) { Log.i(TAG, "getModeValue=${r.value}"); r.value }
            else { Log.e(TAG, "getModeValue FAILED: ${r.raw}"); -1 }
        }.getOrElse { Log.e(TAG, "getModeValue exception", it); -1 }
    }

    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean {
        Log.d(TAG, "setModeValue(touchId=$touchId, mode=$mode, value=$value)")
        return runCatching {
            val r = runBlocking { doSet(touchId, mode, value) }
            if (r.ok) { Log.i(TAG, "setModeValue OK"); true }
            else { Log.e(TAG, "setModeValue FAILED: ${r.raw}"); false }
        }.getOrElse { Log.e(TAG, "setModeValue exception", it); false }
    }

    fun resetMode(touchId: Int, mode: Int): Boolean {
        Log.d(TAG, "resetMode(touchId=$touchId, mode=$mode)")
        return runCatching {
            val r = runBlocking { doReset(touchId, mode) }
            if (r.ok) { Log.i(TAG, "resetMode OK"); true }
            else { Log.e(TAG, "resetMode FAILED: ${r.raw}"); false }
        }.getOrElse { Log.e(TAG, "resetMode exception", it); false }
    }

    fun getModeDefaultValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { doGet(touchId, mode, TouchConstants.CMD_GET_DEF_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeDefaultValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeDefaultValue exception", it); -1 }

    fun getModeMaxValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { doGet(touchId, mode, TouchConstants.CMD_GET_MAX_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeMaxValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeMaxValue exception", it); -1 }

    fun getModeMinValue(touchId: Int, mode: Int): Int = runCatching {
        val r = runBlocking { doGet(touchId, mode, TouchConstants.CMD_GET_MIN_VALUE) }
        if (r.ok) r.value else { Log.e(TAG, "getModeMinValue FAILED: ${r.raw}"); -1 }
    }.getOrElse { Log.e(TAG, "getModeMinValue exception", it); -1 }
}
