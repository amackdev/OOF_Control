package com.oof.control.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oof.control.MainActivity
import com.oof.control.R
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.*

/**
 * Smart Charging Service - Handles charging current control and charge limit
 * Runs silently without notification (BatteryStatsService handles notification)
 */
class SmartChargingService : Service() {
    
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: PrefsManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var isChargingSuspended = false
    
    // Cache to avoid unnecessary operations
    private var lastBatteryLevel = -1
    private var lastTemp = -1
    private var lastSportMode = false
    
    companion object {
        private const val TAG = "SmartChargingService"
        const val CHANNEL_ID = "oof_smart_charging"
        const val NOTIFICATION_ID = 1001
        const val UPDATE_INTERVAL = 3_000L // 3 seconds
        const val CHARGE_LIMIT_HYSTERESIS = 5
        
        fun start(context: Context) {
            try {
                val intent = Intent(context, SmartChargingService::class.java)
                context.startForegroundService(intent)
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
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SmartChargingService started")
        try {
            // Minimal silent notification required for foreground service
            startForeground(NOTIFICATION_ID, createSilentNotification())
            acquireWakeLock()
            startChargingControlLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "SmartChargingService destroyed")
        serviceJob?.cancel()
        serviceScope.cancel()
        releaseWakeLock()
        
        // Resume charging on destroy
        runBlocking {
            try {
                RootController.resumeCharging()
            } catch (e: Exception) {
                Log.e(TAG, "Error resuming charging on destroy", e)
            }
        }
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smart Charging",
                NotificationManager.IMPORTANCE_MIN  // Lowest importance - no sound, no popup
            ).apply {
                description = "Controls charging current and limits"
                setShowBadge(false)
                setSound(null, null)
                enableLights(false)
                enableVibration(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }
    
    private fun createSilentNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Smart Charging")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)  // Hide from lock screen
            .build()
    }
    
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "OOFControl::SmartCharging"
            ).apply {
                acquire(10*60*1000L)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing wake lock", e)
        }
    }
    
    private fun startChargingControlLoop() {
        serviceJob = serviceScope.launch {
            delay(2000)
            
            while (isActive) {
                try {
                    controlCharging()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in charging control loop", e)
                }
                delay(UPDATE_INTERVAL)
            }
        }
    }
    
    private suspend fun controlCharging() {
        try {
            val usbType = RootController.getUsbType()
            val isChargerConnected = usbType != "Unknown"
            
            // If not charging, clear suspended state
            if (!isChargerConnected) {
                if (isChargingSuspended) {
                    RootController.resumeCharging()
                    isChargingSuspended = false
                    Log.i(TAG, "Charging latch cleared on disconnect")
                }
                return
            }
            
            val isFastCharging = RootController.isFastCharging()
            val temp = RootController.getBatteryTemp()
            val batteryLevel = RootController.getBatteryLevel()
            val sportMode = RootController.getSportMode()
            val maxPower = RootController.getMaxPower()
            
            // Get effective charge limit - prefer OS value if available, else use app setting
            val effectiveLimit = getEffectiveChargeLimit()

            // Charge limit control
            if (effectiveLimit != null) {
                val resumeThreshold = effectiveLimit - CHARGE_LIMIT_HYSTERESIS
                
                if (isFastCharging && batteryLevel >= effectiveLimit && !isChargingSuspended && usbType != "USB") {
                    RootController.stopCharging()
                    isChargingSuspended = true
                    Log.i(TAG, "Charging paused at $effectiveLimit%")
                    return
                }
                
                if (isChargingSuspended && batteryLevel < resumeThreshold) {
                    RootController.resumeCharging()
                    isChargingSuspended = false
                    Log.i(TAG, "Charging resumed - battery dropped to $batteryLevel%")
                }
            }
            
            if (isChargingSuspended) {
                return
            }

            // Fast charging - control current
            if (isFastCharging) {
                val needsUpdate = (batteryLevel != lastBatteryLevel) ||
                    (kotlin.math.abs(temp - lastTemp) > 10) ||
                    (sportMode != lastSportMode)

                if (needsUpdate) {
                    val current = DeviceConfig.getChargingCurrent(temp, batteryLevel, sportMode, maxPower)
                    RootController.setChargingCurrent(current)

                    lastBatteryLevel = batteryLevel
                    lastTemp = temp
                    lastSportMode = sportMode
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error in controlCharging", e)
        }
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
