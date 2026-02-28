package com.oof.control.utils

import android.util.Log

/**
 * Marble-specific ioctl bridge for /dev/xiaomi-touch.
 *
 * Marble driver protocol:
 *   - Buffer: int buf[256] (1024 bytes)
 *   - buf[0] = touchId, buf[1] = mode, buf[2] = value
 *   - Kernel uses _IOC_NR(cmd) as the MODE_CMD enum (0-7)
 *   - copy_from_user/copy_to_user on full int[256]
 *   - No separate SELECT_TOUCH_ID ioctl
 *
 * Ioctl codes computed in pure Kotlin. No C++/JNI changes needed.
 */
object MarbleIoctlBridge {

    private const val TAG = "MarbleIoctlBridge"

    // Linux _IOC bit layout
    private const val IOC_NRSHIFT   = 0
    private const val IOC_TYPESHIFT = 8
    private const val IOC_SIZESHIFT = 16
    private const val IOC_DIRSHIFT  = 30
    private const val IOC_WRITE     = 1L
    private const val IOC_READ      = 2L
    private const val MAGIC         = 'T'.code.toLong()

    private fun iocWR(nr: Int, size: Int): Long =
        ((IOC_READ or IOC_WRITE) shl IOC_DIRSHIFT) or
        (MAGIC shl IOC_TYPESHIFT) or
        (nr.toLong() shl IOC_NRSHIFT) or
        (size.toLong() shl IOC_SIZESHIFT)

    private fun marbleCmd(cmdNr: Int): Long = iocWR(cmdNr, TouchConstants.MARBLE_BUF_BYTES)

    private val IOCTL_SET_CUR  = marbleCmd(TouchConstants.CMD_SET_CUR_VALUE)
    private val IOCTL_GET_CUR  = marbleCmd(TouchConstants.CMD_GET_CUR_VALUE)
    private val IOCTL_GET_DEF  = marbleCmd(TouchConstants.CMD_GET_DEF_VALUE)
    private val IOCTL_GET_MIN  = marbleCmd(TouchConstants.CMD_GET_MIN_VALUE)
    private val IOCTL_GET_MAX  = marbleCmd(TouchConstants.CMD_GET_MAX_VALUE)
    private val IOCTL_GET_MODE = marbleCmd(TouchConstants.CMD_GET_MODE_VALUE)
    private val IOCTL_RESET    = marbleCmd(TouchConstants.CMD_RESET_MODE)
    private val IOCTL_SET_LONG = marbleCmd(TouchConstants.CMD_SET_LONG_VALUE)

    private const val O_RDWR = 2

    init {
        Log.i(TAG, "Marble ioctl codes: SET=0x${java.lang.Long.toHexString(IOCTL_SET_CUR)}" +
                " GET=0x${java.lang.Long.toHexString(IOCTL_GET_CUR)}" +
                " RESET=0x${java.lang.Long.toHexString(IOCTL_RESET)}")
    }

    // ── Public API ────────────────────────────────────────────────────

    suspend fun setMode(touchId: Int, mode: Int, value: Int): IoctlBridge.IoctlResult {
        Log.d(TAG, "setMode(touchId=$touchId, mode=$mode, value=$value)")
        val r = doIoctl(IOCTL_SET_CUR) { buf ->
            putI32(buf, 0, touchId)
            putI32(buf, 4, mode)
            putI32(buf, 8, value)
        }
        return if (r.first.ok) IoctlBridge.IoctlResult(true, value, "OK mode=$mode val=$value")
        else r.first
    }

    suspend fun getMode(touchId: Int, mode: Int, cmd: Int = TouchConstants.CMD_GET_CUR_VALUE): IoctlBridge.IoctlResult {
        Log.d(TAG, "getMode(touchId=$touchId, mode=$mode, cmd=$cmd)")
        val ioctlReq = when (cmd) {
            TouchConstants.CMD_GET_CUR_VALUE -> IOCTL_GET_CUR
            TouchConstants.CMD_GET_DEF_VALUE -> IOCTL_GET_DEF
            TouchConstants.CMD_GET_MIN_VALUE -> IOCTL_GET_MIN
            TouchConstants.CMD_GET_MAX_VALUE -> IOCTL_GET_MAX
            TouchConstants.CMD_GET_MODE_VALUE -> IOCTL_GET_MODE
            else -> return IoctlBridge.IoctlResult(false, 0, "ERR: unknown cmd $cmd")
        }
        val r = doIoctl(ioctlReq) { b ->
            putI32(b, 0, touchId)
            putI32(b, 4, mode)
        }
        if (!r.first.ok || r.second == null) return r.first
        val v = getI32(r.second!!, 0)
        return IoctlBridge.IoctlResult(true, v, "V:$v")
    }

    suspend fun resetMode(touchId: Int, mode: Int): IoctlBridge.IoctlResult {
        Log.d(TAG, "resetMode(touchId=$touchId, mode=$mode)")
        val r = doIoctl(IOCTL_RESET) { buf ->
            putI32(buf, 0, touchId)
            putI32(buf, 4, mode)
        }
        return if (r.first.ok) IoctlBridge.IoctlResult(true, 0, "OK reset mode=$mode")
        else r.first
    }

    suspend fun setModeLong(touchId: Int, mode: Int, values: IntArray): IoctlBridge.IoctlResult {
        Log.d(TAG, "setModeLong(touchId=$touchId, mode=$mode, count=${values.size})")
        val maxVals = TouchConstants.MARBLE_BUF_INTS - 3
        val r = doIoctl(IOCTL_SET_LONG) { buf ->
            val count = values.size.coerceAtMost(maxVals)
            putI32(buf, 0, touchId)
            putI32(buf, 4, mode)
            putI32(buf, 8, count)
            for (i in 0 until count) putI32(buf, (3 + i) * 4, values[i])
        }
        return if (r.first.ok) IoctlBridge.IoctlResult(true, 0, "OK long val set")
        else r.first
    }

    fun isDeviceAvailable(): Boolean {
        val fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
        if (fd >= 0) { NativeIoctl.close(fd); return true }
        return false
    }

    // ── Internals ─────────────────────────────────────────────────────

    private inline fun doIoctl(
        request: Long,
        fill: (ByteArray) -> Unit
    ): Pair<IoctlBridge.IoctlResult, ByteArray?> {
        var fd = -1
        return try {
            fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
            if (fd < 0)
                return Pair(IoctlBridge.IoctlResult(false, 0, "ERR: open failed rc=$fd"), null)
            val buf = ByteArray(TouchConstants.MARBLE_BUF_BYTES)
            fill(buf)
            val rc = NativeIoctl.ioctl(fd, request, buf, buf.size)
            if (rc != 0)
                Pair(IoctlBridge.IoctlResult(false, 0, "ERR: ioctl failed rc=$rc"), null)
            else
                Pair(IoctlBridge.IoctlResult(true, 0, "OK"), buf)
        } catch (t: Throwable) {
            Pair(IoctlBridge.IoctlResult(false, 0, "ERR: ${t.message}"), null)
        } finally {
            if (fd >= 0) NativeIoctl.close(fd)
        }
    }

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
