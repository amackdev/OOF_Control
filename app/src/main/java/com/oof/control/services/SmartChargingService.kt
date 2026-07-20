package com.oof.control.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.oof.control.utils.BatteryController
import com.oof.control.utils.ChargingController
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.PrefsManager
import kotlinx.coroutines.*

/**
 * Smart Charging Service - Handles charging current control and charge limit
 * Runs as hidden background service (no notification)
 */
class SmartChargingService : Service() {
    
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PrefsManager
    private var isChargingSuspended = false
    
    // Cache to avoid unnecessary operations
    private var lastBatteryLevel = -1
    private var lastTemp = -1
    private var lastSportMode = false
    
    companion object {
        private const val TAG = "SmartChargingService"
        private const val UPDATE_INTERVAL = 3_000L // 3 seconds when charging
        private const val IDLE_INTERVAL = 10_000L  // 10 seconds when not charging
        const val CHARGE_LIMIT_HYSTERESIS = 5
        
        fun start(context: Context) {
            try {
                val intent = Intent(context, SmartChargingService::class.java)
                context.startService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
        
        fun stop(context: Context) {
            try {
                val intent = Intent(context, SmartChargingService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SmartChargingService created")
        prefs = PrefsManager(this)
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SmartChargingService started")
        try {
            startChargingControlLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        serviceJob?.cancel()
        serviceScope.cancel()

        // Resume charging on destroy — must complete before service is gone
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                ChargingController.resumeCharging()
            }
        } catch (_: Exception) { }
        super.onDestroy()
    }
    
    private fun startChargingControlLoop() {
        serviceJob?.cancel()
        serviceJob = serviceScope.launch {
            while (isActive) {
                val interval = try {
                    controlCharging()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in charging control loop", e)
                    UPDATE_INTERVAL
                }
                delay(interval)
            }
        }
    }

    /** Returns the delay for next iteration based on charging state */
    private suspend fun controlCharging(): Long {
        try {
            val usbType = ChargingController.getUsbType()
            val isChargerConnected = usbType != "Unknown"

            // If not charging, clear state and use longer polling interval
            if (!isChargerConnected) {
                if (isChargingSuspended) {
                    ChargingController.resumeCharging()
                    isChargingSuspended = false
                }
                return IDLE_INTERVAL // Sleep longer when not charging
            }

            val batteryLevel = BatteryController.getBatteryLevel()

            // Get effective charge limit - prefer OS value if available, else use app setting
            val effectiveLimit = getEffectiveChargeLimit()

            // Charge limit control - check resume FIRST before suspend
            if (effectiveLimit != null) {
                val resumeThreshold = effectiveLimit - CHARGE_LIMIT_HYSTERESIS

                // Check if we should RESUME charging (battery dropped below threshold)
                if (isChargingSuspended && batteryLevel <= resumeThreshold) {
                    ChargingController.resumeCharging()
                    isChargingSuspended = false
                    Log.i(TAG, "Charging resumed - battery at $batteryLevel% (threshold: $resumeThreshold%)")
                }

                // Check if we should SUSPEND charging (battery reached limit)
                if (!isChargingSuspended && batteryLevel >= effectiveLimit && usbType != "USB") {
                    ChargingController.stopCharging()
                    isChargingSuspended = true
                    Log.i(TAG, "Charging paused at $batteryLevel% (limit: $effectiveLimit%)")
                    return UPDATE_INTERVAL
                }
            } else {
                // No charge limit active - ensure charging is resumed if it was suspended
                if (isChargingSuspended) {
                    ChargingController.resumeCharging()
                    isChargingSuspended = false
                    Log.i(TAG, "Charging resumed - charge limit disabled")
                }
            }

            // If charging is suspended, don't control current
            if (isChargingSuspended) {
                return UPDATE_INTERVAL
            }

            // Fast charging - control current
            val isFastCharging = ChargingController.isFastCharging()
            if (isFastCharging) {
                val temp = BatteryController.getBatteryTemp()
                val sportMode = ChargingController.getSportMode()
                val maxPower = ChargingController.getMaxPower()

                val needsUpdate = (batteryLevel != lastBatteryLevel) ||
                    (kotlin.math.abs(temp - lastTemp) > 10) ||
                    (sportMode != lastSportMode)

                if (needsUpdate) {
                    val current = DeviceConfig.getChargingCurrent(temp, batteryLevel, sportMode, maxPower)
                    ChargingController.setChargingCurrent(current)

                    lastBatteryLevel = batteryLevel
                    lastTemp = temp
                    lastSportMode = sportMode
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in controlCharging", e)
        }
        return UPDATE_INTERVAL
    }
    
    private fun getEffectiveChargeLimit(): Int? {
        return try {
            // First check if OS-level charge protection is enabled
            val switchState = android.provider.Settings.System.getInt(
                contentResolver,
                "regular_charge_protection_switch_state",
                0
            )
            
            if (switchState == 1) {
                // OS charge protection is enabled, get the limit value
                val osLimit = android.provider.Settings.System.getInt(
                    contentResolver,
                    "regular_charge_protection_open_power_level",
                    80
                )
                return osLimit
            }
            
            // OS protection not enabled, use app setting if enabled
            if (prefs.chargeLimitEnabled) {
                return prefs.chargeLimit
            }
            
            null
        } catch (e: Exception) {
            // Fallback to app setting
            if (prefs.chargeLimitEnabled) prefs.chargeLimit else null
        }
    }
}
