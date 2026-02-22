package com.oof.control.utils

/**
 * JNI bridge for open()/close()/ioctl() and for computing ioctl request numbers
 * using platform _IOC() macros (to match the kernel driver exactly).
 */
object NativeIoctl {

    init {
        System.loadLibrary("native_ioctl_v2")
    }

    // ioctl request numbers (computed natively)
    @JvmStatic external fun reqSelectTouchId(): Long
    @JvmStatic external fun reqCommonData(): Long
    @JvmStatic external fun reqHardwareParam(): Long

    // fd lifecycle
    @JvmStatic external fun open(path: String, flags: Int): Int
    @JvmStatic external fun close(fd: Int)

    /** ioctl where arg is a user pointer to a buffer (e.g. COMMON_DATA_CMD, HARDWARE_PARAM_CMD). */
    @JvmStatic external fun ioctl(fd: Int, request: Long, data: ByteArray, dataLen: Int): Int

    /** ioctl where arg is an immediate unsigned long value (e.g. SELECT_TOUCH_ID). */
    @JvmStatic external fun ioctlLong(fd: Int, request: Long, arg: Long): Int
}
