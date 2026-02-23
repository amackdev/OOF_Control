package com.oof.control.utils

object NativeIoctl {
    init { System.loadLibrary("native_ioctl_v2") }

    @JvmStatic external fun reqSelectTouchId(): Long
    @JvmStatic external fun reqCommonData(): Long
    @JvmStatic external fun reqHardwareParam(): Long
    @JvmStatic external fun open(path: String, flags: Int): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun ioctl(fd: Int, request: Long, data: ByteArray, dataLen: Int): Int
    @JvmStatic external fun ioctlLong(fd: Int, request: Long, arg: Long): Int
}
