package com.oof.control.utils

import android.util.Log

/**
 * Sends ioctl calls to /dev/xiaomi-touch directly via JNI (no su, no python).
 *
 * common_data_t in kernel (xiaomi_touch_type_common.h):
 *   s8  touch_id      @0
 *   u8  cmd           @1
 *   u16 mode          @2
 *   u16 data_len      @4
 *   padding 2 bytes   @6..7
 *   s32 data_buf[128] @8
 * sizeof(common_data_t) = 520
 */
object IoctlBridge {

    private const val TAG = "IoctlBridge"

    data class IoctlResult(val ok: Boolean, val value: Int = 0, val raw: String = "")

    // ioctl request values computed natively via platform _IOC macros
    private val IOCTL_SEL = NativeIoctl.reqSelectTouchId()
    private val IOCTL_COM = NativeIoctl.reqCommonData().also {
        Log.i(TAG, "IOCTL_SEL=0x${java.lang.Long.toHexString(NativeIoctl.reqSelectTouchId())}" +
                   " IOCTL_COM=0x${java.lang.Long.toHexString(it)}" +
                   " size=${((it shr 16) and 0x3fffL).toInt()}")
    }
    private val IOCTL_HW = NativeIoctl.reqHardwareParam()

    private const val O_RDWR = 2
    private const val SZ_COMMON_DATA = 520
    private const val SZ_HW_PARAM = 214

    // ── Public API ────────────────────────────────────────────────────

    suspend fun setMode(touchId: Int, mode: Int, value: Int): IoctlResult {
        Log.d(TAG, "setMode(touchId=$touchId, mode=$mode, value=$value)")
        val (r, _) = ioctlCommonRaw(touchId) { buf ->
            buf[0] = touchId.toByte()
            buf[1] = TouchConstants.CMD_SET_CUR_VALUE.toByte()
            putU16(buf, 2, mode)
            putU16(buf, 4, 1)
            putI32(buf, 8, value)
        }
        return if (r.ok) IoctlResult(true, value, "OK mode=$mode val=$value").also { Log.i(TAG, "setMode OK") }
        else r.also { Log.e(TAG, "setMode FAILED: ${r.raw}") }
    }

    suspend fun getMode(touchId: Int, mode: Int, cmd: Int = TouchConstants.CMD_GET_CUR_VALUE): IoctlResult {
        Log.d(TAG, "getMode(touchId=$touchId, mode=$mode, cmd=$cmd)")
        val (r, buf) = ioctlCommonRaw(touchId) { b ->
            b[0] = touchId.toByte()
            b[1] = (cmd and 0xFF).toByte()
            putU16(b, 2, mode)
            putU16(b, 4, 1)
        }
        if (!r.ok || buf == null) return r.also { Log.e(TAG, "getMode FAILED: ${r.raw}") }
        val v = getI32(buf, 8)
        Log.i(TAG, "getMode OK value=$v")
        return IoctlResult(true, v, "V:$v")
    }

    suspend fun resetMode(touchId: Int, mode: Int): IoctlResult {
        Log.d(TAG, "resetMode(touchId=$touchId, mode=$mode)")
        val (r, _) = ioctlCommonRaw(touchId) { buf ->
            buf[0] = touchId.toByte()
            buf[1] = TouchConstants.CMD_RESET_MODE.toByte()
            putU16(buf, 2, mode)
            putU16(buf, 4, 1)
        }
        return if (r.ok) IoctlResult(true, 0, "OK reset mode=$mode").also { Log.i(TAG, "resetMode OK") }
        else r.also { Log.e(TAG, "resetMode FAILED: ${r.raw}") }
    }

    suspend fun setModeLong(touchId: Int, mode: Int, values: IntArray): IoctlResult {
        Log.d(TAG, "setModeLong(touchId=$touchId, mode=$mode, count=${values.size})")
        val (r, _) = ioctlCommonRaw(touchId) { buf ->
            val vals = values.take(128)
            buf[0] = touchId.toByte()
            buf[1] = TouchConstants.CMD_SET_LONG_VALUE.toByte()
            putU16(buf, 2, mode)
            putU16(buf, 4, vals.size)
            for (i in vals.indices) putI32(buf, 8 + i * 4, vals[i])
        }
        return if (r.ok) IoctlResult(true, 0, "OK long val set").also { Log.i(TAG, "setModeLong OK") }
        else r.also { Log.e(TAG, "setModeLong FAILED: ${r.raw}") }
    }

    fun isDeviceAvailable(): Boolean {
        val fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
        return if (fd >= 0) {
            NativeIoctl.close(fd)
            Log.d(TAG, "isDeviceAvailable: OK")
            true
        } else {
            Log.e(TAG, "isDeviceAvailable: open failed rc=$fd")
            false
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────

    private inline fun ioctlCommonRaw(
        touchId: Int,
        fill: (ByteArray) -> Unit
    ): Pair<IoctlResult, ByteArray?> {
        var fd = -1
        return try {
            fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
            if (fd < 0) {
                Log.e(TAG, "open(${TouchConstants.DEV_NODE}) failed rc=$fd")
                return Pair(IoctlResult(false, 0, "ERR: open failed rc=$fd"), null)
            }
            if (!selectTouchId(fd, touchId)) {
                Log.e(TAG, "selectTouchId failed id=$touchId")
                return Pair(IoctlResult(false, 0, "ERR: select touch id failed (id=$touchId)"), null)
            }
            val buf = ByteArray(SZ_COMMON_DATA)
            fill(buf)
            val rc = NativeIoctl.ioctl(fd, IOCTL_COM, buf, buf.size)
            if (rc != 0) {
                Log.e(TAG, "ioctl COMMON_DATA failed rc=$rc")
                Pair(IoctlResult(false, 0, "ERR: ioctl common failed rc=$rc"), null)
            } else {
                Pair(IoctlResult(true, 0, "OK"), buf)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "ioctlCommonRaw exception: ${t.message}", t)
            Pair(IoctlResult(false, 0, "ERR: ${t.message ?: t.javaClass.simpleName}"), null)
        } finally {
            if (fd >= 0) NativeIoctl.close(fd)
        }
    }

    private fun selectTouchId(fd: Int, touchId: Int): Boolean {
        // Kernel expects arg = unsigned long touch_id (NOT a pointer)
        val rc = NativeIoctl.ioctlLong(fd, IOCTL_SEL, touchId.toLong())
        Log.d(TAG, "selectTouchId(fd=$fd, touchId=$touchId) rc=$rc")
        return rc == 0
    }

    // ── Little-endian struct helpers ──────────────────────────────────

    private fun putU16(b: ByteArray, off: Int, v: Int) {
        b[off]     = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun getU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun putI32(b: ByteArray, off: Int, v: Int) {
        b[off]     = (v and 0xFF).toByte()
        b[off + 1] = ((v ushr 8) and 0xFF).toByte()
        b[off + 2] = ((v ushr 16) and 0xFF).toByte()
        b[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun getI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
}
