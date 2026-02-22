package com.oof.control.utils

/**
 * Constants mirrored from xiaomi_touch_type_common.h / xiaomi_touch.h
 * Driver version: 2024.08.30-01
 */
object TouchConstants {

    // Device paths
    const val DEV_NODE   = "/dev/xiaomi-touch"
    const val SYSFS_BASE = "/sys/class/touch/touch_dev"
    const val PROC_BASE  = "/proc"

    // IOCTL magic 'T' = 0x54
    const val IOCTL_MAGIC = 0x54

    // ioctl command numbers (enum ioctl_cmd)
    const val IOCTL_COMMON_DATA     = 0
    const val IOCTL_HARDWARE_PARAM  = 1
    const val IOCTL_SELECT_MMAP     = 2
    const val IOCTL_SELECT_TOUCH_ID = 3
    const val IOCTL_GET_FRAME       = 4
    const val IOCTL_RAW_INDEX       = 5
    const val IOCTL_UPDATE_REPORT   = 6

    // common_data_t cmd values (enum common_data_cmd)
    const val CMD_SET_CUR_VALUE  = 0
    const val CMD_GET_CUR_VALUE  = 1
    const val CMD_GET_DEF_VALUE  = 2
    const val CMD_GET_MIN_VALUE  = 3
    const val CMD_GET_MAX_VALUE  = 4
    const val CMD_GET_MODE_VALUE = 5
    const val CMD_RESET_MODE     = 6
    const val CMD_SET_LONG_VALUE = 7

    // DATA_MODE ids (enum common_data_mode)
    const val MODE_GAME_MODE     = 0
    const val MODE_ACTIVE        = 1
    const val MODE_UP_THRESHOLD  = 2
    const val MODE_TOLERANCE     = 3
    const val MODE_AIM_SENSITIVITY = 4
    const val MODE_TAP_STABILITY = 5
    const val MODE_6             = 6
    const val MODE_EDGE_FILTER   = 7
    const val MODE_ORIENTATION   = 8
    const val MODE_REPORT_RATE   = 9
    const val MODE_FOD_LONGPRESS = 10
    const val MODE_SINGLETAP     = 11
    const val MODE_12            = 12
    const val MODE_13            = 13
    const val MODE_DOUBLETAP     = 14
    const val MODE_GRIP_LONG     = 15
    const val MODE_FOD_SINGLETAP = 16
    const val MODE_SLEEP_STATE   = 17
    const val MODE_LOG_LEVEL     = 18
    const val MODE_TOUCH_POWER   = 19
    const val MODE_STYLUS_20     = 20
    const val MODE_21            = 21
    const val MODE_STYLUS_22     = 22
    const val MODE_23            = 23
    const val MODE_STYLUS_QUICK  = 24
    const val MODE_IC_25         = 25
    const val MODE_PALM_CTRL     = 26
    const val MODE_SENSORHUB     = 27
    const val MODE_28            = 28
    const val MODE_STYLUS_29     = 29
    const val MODE_FLIP          = 30
    const val MODE_SENSOR_ENABLE = 31
    const val MODE_FOD_GESTURE   = 32
    const val MODE_STYLUS_33     = 33
    const val MODE_PAD_SINGLETAP = 34

    // Struct sizes
    const val COMMON_DATA_SIZE   = 1030   // s8+u8+u16+u16+s32[256]
    const val HARDWARE_PARAM_SIZE = 214

    const val MAX_TOUCH_PANELS   = 2
}
