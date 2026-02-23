package com.oof.control.utils

import android.util.Log

object IoctlBridge {

    private const val TAG = "IoctlBridge"
    private const val O_RDWR = 2
    private const val SZ_COMMON_DATA = 520

    data class IoctlResult(val ok: Boolean, val value: Int = 0, val raw: String = "")

    private val IOCTL_SEL = NativeIoctl.reqSelectTouchId()
    private val IOCTL_COM = NativeIoctl.reqCommonData()

    suspend fun setMode(touchId: Int, mode: Int, value: Int): IoctlResult {
        val (r, _) = ioctlCommonRaw(touchId) { buf ->
            buf[0] = touchId.toByte()
            buf[1] = TouchConstants.CMD_SET_CUR_VALUE.toByte()
            putU16(buf, 2, mode)
            putU16(buf, 4, 1)
            putI32(buf, 8, value)
        }
        return if (r.ok) IoctlResult(true, value, "OK") else r
    }

    suspend fun getMode(touchId: Int, mode: Int, cmd: Int = TouchConstants.CMD_GET_CUR_VALUE): IoctlResult {
        val (r, buf) = ioctlCommonRaw(touchId) { b ->
            b[0] = touchId.toByte()
            b[1] = (cmd and 0xFF).toByte()
            putU16(b, 2, mode)
            putU16(b, 4, 1)
        }
        if (!r.ok || buf == null) return r
        return IoctlResult(true, getI32(buf, 8), "OK")
    }

    suspend fun resetMode(touchId: Int, mode: Int): IoctlResult {
        val (r, _) = ioctlCommonRaw(touchId) { buf ->
            buf[0] = touchId.toByte()
            buf[1] = TouchConstants.CMD_RESET_MODE.toByte()
            putU16(buf, 2, mode)
            putU16(buf, 4, 1)
        }
        return if (r.ok) IoctlResult(true, 0, "OK") else r
    }

    fun isDeviceAvailable(): Boolean {
        val fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
        return if (fd >= 0) { NativeIoctl.close(fd); true } else false
    }

    private inline fun ioctlCommonRaw(touchId: Int, fill: (ByteArray) -> Unit): Pair<IoctlResult, ByteArray?> {
        var fd = -1
        return try {
            fd = NativeIoctl.open(TouchConstants.DEV_NODE, O_RDWR)
            if (fd < 0) return Pair(IoctlResult(false, 0, "open failed rc=$fd"), null)
            val selRc = NativeIoctl.ioctlLong(fd, IOCTL_SEL, touchId.toLong())
            if (selRc != 0) return Pair(IoctlResult(false, 0, "select touch id failed rc=$selRc"), null)
            val buf = ByteArray(SZ_COMMON_DATA)
            fill(buf)
            val rc = NativeIoctl.ioctl(fd, IOCTL_COM, buf, buf.size)
            if (rc != 0) Pair(IoctlResult(false, 0, "ioctl failed rc=$rc"), null)
            else Pair(IoctlResult(true, 0, "OK"), buf)
        } catch (t: Throwable) {
            Pair(IoctlResult(false, 0, "exception: ${t.message}"), null)
        } finally {
            if (fd >= 0) NativeIoctl.close(fd)
        }
    }

    private fun putU16(b: ByteArray, off: Int, v: Int) { b[off] = (v and 0xFF).toByte(); b[off+1] = ((v ushr 8) and 0xFF).toByte() }
    private fun putI32(b: ByteArray, off: Int, v: Int) { b[off] = (v and 0xFF).toByte(); b[off+1] = ((v ushr 8) and 0xFF).toByte(); b[off+2] = ((v ushr 16) and 0xFF).toByte(); b[off+3] = ((v ushr 24) and 0xFF).toByte() }
    private fun getI32(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off+1].toInt() and 0xFF) shl 8) or ((b[off+2].toInt() and 0xFF) shl 16) or ((b[off+3].toInt() and 0xFF) shl 24)
}
