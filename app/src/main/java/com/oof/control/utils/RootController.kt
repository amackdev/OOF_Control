package com.oof.control.utils

import android.content.Context
import java.io.File

/**
 * Legacy facade for backward compatibility.
 *
 * This object delegates to the new specialized controllers:
 * - [TouchController] for touch and display operations
 * - [BatteryController] for battery information
 * - [ChargingController] for charging control
 * - [ShellExecutor] for low-level shell operations
 *
 * All methods are deprecated - migrate to the appropriate controller.
 */
@Suppress("DEPRECATION")
object RootController {

    // ============ INITIALIZATION ============

    @Deprecated(
        "Use BatteryController.isAvailable() instead",
        ReplaceWith("BatteryController.isAvailable()", "com.oof.control.utils.BatteryController")
    )
    suspend fun isRootAvailable(): Boolean = BatteryController.isAvailable()

    // ============ SYSTEM PROPERTY OPERATIONS ============

    @Deprecated(
        "Use ShellExecutor.setProperty() instead",
        ReplaceWith("ShellExecutor.setProperty(prop, value)", "com.oof.control.utils.ShellExecutor")
    )
    suspend fun setProperty(prop: String, value: String): Boolean = ShellExecutor.setProperty(prop, value)

    @Deprecated(
        "Use ShellExecutor.getProperty() instead",
        ReplaceWith("ShellExecutor.getProperty(prop)", "com.oof.control.utils.ShellExecutor")
    )
    suspend fun getProperty(prop: String): String? = ShellExecutor.getProperty(prop)

    // ============ DOUBLE TAP TO WAKE ============

    @Deprecated(
        "Use TouchController.setDT2W() instead",
        ReplaceWith("TouchController.setDT2W(enabled)", "com.oof.control.utils.TouchController")
    )
    suspend fun setDT2W(enabled: Boolean): Boolean = TouchController.setDT2W(enabled)

    @Deprecated(
        "Use TouchController.getDT2W() instead",
        ReplaceWith("TouchController.getDT2W()", "com.oof.control.utils.TouchController")
    )
    suspend fun getDT2W(): Boolean = TouchController.getDT2W()

    // ============ TOUCH RATE (480Hz) ============

    @Deprecated(
        "Use TouchController.setTouchRate() instead",
        ReplaceWith("TouchController.setTouchRate(highRate)", "com.oof.control.utils.TouchController")
    )
    suspend fun setTouchRate(highRate: Boolean): Boolean = TouchController.setTouchRate(highRate)

    @Deprecated(
        "Use TouchController.getTouchRate() instead",
        ReplaceWith("TouchController.getTouchRate()", "com.oof.control.utils.TouchController")
    )
    suspend fun getTouchRate(): Boolean = TouchController.getTouchRate()

    @Deprecated(
        "Use TouchController.isTouchRateSupported() instead",
        ReplaceWith("TouchController.isTouchRateSupported()", "com.oof.control.utils.TouchController")
    )
    suspend fun isTouchRateSupported(): Boolean = TouchController.isTouchRateSupported()

    // ============ REFRESH RATE ============

    @Deprecated(
        "Use TouchController.setRefreshRate() instead",
        ReplaceWith("TouchController.setRefreshRate(context, rate)", "com.oof.control.utils.TouchController")
    )
    suspend fun setRefreshRate(context: Context, rate: Int): Boolean = TouchController.setRefreshRate(context, rate)

    @Deprecated(
        "Use TouchController.getRefreshRate() instead",
        ReplaceWith("TouchController.getRefreshRate()", "com.oof.control.utils.TouchController")
    )
    suspend fun getRefreshRate(): Int = TouchController.getRefreshRate()

    // ============ PERFORMANCE MODE ============

    @Deprecated(
        "Use TouchController.setPerformanceMode() instead",
        ReplaceWith("TouchController.setPerformanceMode(enabled)", "com.oof.control.utils.TouchController")
    )
    suspend fun setPerformanceMode(enabled: Boolean): Boolean = TouchController.setPerformanceMode(enabled)

    @Deprecated(
        "Use TouchController.getPerformanceMode() instead",
        ReplaceWith("TouchController.getPerformanceMode()", "com.oof.control.utils.TouchController")
    )
    suspend fun getPerformanceMode(): Boolean = TouchController.getPerformanceMode()

    // ============ TOUCH BOOST ============

    @Deprecated(
        "Use TouchController.setTouchBoost() instead",
        ReplaceWith("TouchController.setTouchBoost(enabled)", "com.oof.control.utils.TouchController")
    )
    suspend fun setTouchBoost(enabled: Boolean): Boolean = TouchController.setTouchBoost(enabled)

    @Deprecated(
        "Use TouchController.getTouchBoost() instead",
        ReplaceWith("TouchController.getTouchBoost()", "com.oof.control.utils.TouchController")
    )
    suspend fun getTouchBoost(): Boolean = TouchController.getTouchBoost()

    // ============ SPORT MODE (90W CHARGING) ============

    @Deprecated(
        "Use ChargingController.isSportModeSupported() instead",
        ReplaceWith("ChargingController.isSportModeSupported()", "com.oof.control.utils.ChargingController")
    )
    suspend fun isSportModeSupported(): Boolean = ChargingController.isSportModeSupported()

    @Deprecated(
        "Use ChargingController.setSportMode() instead",
        ReplaceWith("ChargingController.setSportMode(enabled)", "com.oof.control.utils.ChargingController")
    )
    suspend fun setSportMode(enabled: Boolean): Boolean = ChargingController.setSportMode(enabled)

    @Deprecated(
        "Use ChargingController.getSportMode() instead",
        ReplaceWith("ChargingController.getSportMode()", "com.oof.control.utils.ChargingController")
    )
    suspend fun getSportMode(): Boolean = ChargingController.getSportMode()

    // ============ CHARGING CONTROL ============

    @Deprecated(
        "Use ChargingController.stopCharging() instead",
        ReplaceWith("ChargingController.stopCharging()", "com.oof.control.utils.ChargingController")
    )
    suspend fun stopCharging(): Boolean = ChargingController.stopCharging()

    @Deprecated(
        "Use ChargingController.resumeCharging() instead",
        ReplaceWith("ChargingController.resumeCharging()", "com.oof.control.utils.ChargingController")
    )
    suspend fun resumeCharging(): Boolean = ChargingController.resumeCharging()

    @Deprecated(
        "Use ChargingController.setChargingCurrent() instead",
        ReplaceWith("ChargingController.setChargingCurrent(current)", "com.oof.control.utils.ChargingController")
    )
    suspend fun setChargingCurrent(current: Int): Boolean = ChargingController.setChargingCurrent(current)

    @Deprecated(
        "Use ChargingController.isCharging() instead",
        ReplaceWith("ChargingController.isCharging()", "com.oof.control.utils.ChargingController")
    )
    suspend fun isCharging(): Boolean = ChargingController.isCharging()

    @Deprecated(
        "Use ChargingController.isFastCharging() instead",
        ReplaceWith("ChargingController.isFastCharging()", "com.oof.control.utils.ChargingController")
    )
    suspend fun isFastCharging(): Boolean = ChargingController.isFastCharging()

    @Deprecated(
        "Use ChargingController.getUsbType() instead",
        ReplaceWith("ChargingController.getUsbType()", "com.oof.control.utils.ChargingController")
    )
    suspend fun getUsbType(): String = ChargingController.getUsbType()

    // ============ BATTERY INFO ============

    @Deprecated(
        "Use BatteryController.getBatteryLevel() instead",
        ReplaceWith("BatteryController.getBatteryLevel()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryLevel(): Int = BatteryController.getBatteryLevel()

    @Deprecated(
        "Use BatteryController.getBatteryHealthPercent() instead",
        ReplaceWith("BatteryController.getBatteryHealthPercent()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryHealthPercent(): Double = BatteryController.getBatteryHealthPercent()

    @Deprecated(
        "Use BatteryController.getBatteryTemp() instead",
        ReplaceWith("BatteryController.getBatteryTemp()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryTemp(): Int = BatteryController.getBatteryTemp()

    @Deprecated(
        "Use BatteryController.getBatteryVoltage() instead",
        ReplaceWith("BatteryController.getBatteryVoltage()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryVoltage(): Int = BatteryController.getBatteryVoltage()

    @Deprecated(
        "Use BatteryController.getMaxPower() or ChargingController.getMaxPower() instead",
        ReplaceWith("BatteryController.getMaxPower()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getMaxPower(): Int = BatteryController.getMaxPower()

    @Deprecated(
        "Use BatteryController.getBatteryStatus() instead",
        ReplaceWith("BatteryController.getBatteryStatus()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryStatus(): String? = BatteryController.getBatteryStatus()

    @Deprecated(
        "Use BatteryController.getBatteryDrainRate() instead",
        ReplaceWith("BatteryController.getBatteryDrainRate()", "com.oof.control.utils.BatteryController")
    )
    suspend fun getBatteryDrainRate(): Int = BatteryController.getBatteryDrainRate()

    // ============ SYSTEM PROPS ============

    @Deprecated(
        "Use TouchController.getSystemProp() instead",
        ReplaceWith("TouchController.getSystemProp(propName)", "com.oof.control.utils.TouchController")
    )
    suspend fun getSystemProp(propName: String): String = TouchController.getSystemProp(propName)

    @Deprecated(
        "Use TouchController.setSystemProp() instead",
        ReplaceWith("TouchController.setSystemProp(propName, value)", "com.oof.control.utils.TouchController")
    )
    suspend fun setSystemProp(propName: String, value: String): Boolean = TouchController.setSystemProp(propName, value)
}
