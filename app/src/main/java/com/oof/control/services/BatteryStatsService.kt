package com.oof.control.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.oof.control.MainActivity
import com.oof.control.R
import com.oof.control.utils.BatteryController
import com.oof.control.utils.ChargingController
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.FileUtils
import kotlinx.coroutines.*
import java.io.File

/**
 * Battery Statistics Service - AccuBattery-style stats in notification
 * Tracks screen on/off time, deep sleep, awake time, and drain rates
 */
class BatteryStatsService : Service() {
    
    private var serviceJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Battery stats tracking
    private var sessionStartTime = 0L
    private var sessionStartLevel = -1
    private var screenOnStartTime = 0L
    private var screenOffStartTime = 0L
    private var totalScreenOnTime = 0L
    private var totalScreenOffTime = 0L
    private var screenOnDrainMah = 0
    private var screenOffDrainMah = 0
    private var lastLevelForDrain = -1
    private var isScreenOn = true
    
    // Deep sleep tracking - using multiple methods
    private var deepSleepTime = 0L
    private var awakeTime = 0L
    private var lastDeepSleepMs = 0L
    private var screenOffDeepSleepStart = 0L
    
    // Screen state receiver
    private var screenReceiver: BroadcastReceiver? = null
    private var commandReceiver: BroadcastReceiver? = null
    
    companion object {
        private const val TAG = "BatteryStatsService"
        const val CHANNEL_ID = "oof_battery_stats"
        const val NOTIFICATION_ID = 1002
        const val UPDATE_INTERVAL = 5_000L // 5 seconds
        
        // Broadcast actions
        const val ACTION_RESET_STATS = "com.oof.control.RESET_BATTERY_STATS"
        
        fun start(context: Context) {
            try {
                val intent = Intent(context, BatteryStatsService::class.java)
                context.startForegroundService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service", e)
            }
        }
        
        fun stop(context: Context) {
            try {
                val intent = Intent(context, BatteryStatsService::class.java)
                context.stopService(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop service", e)
            }
        }
        
        fun resetStats(context: Context) {
            try {
                val intent = Intent(ACTION_RESET_STATS)
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send reset broadcast", e)
            }
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BatteryStatsService created")
        createNotificationChannel()
        registerScreenReceiver()
        registerCommandReceiver()
        initSession()
    }
    
    private fun initSession() {
        sessionStartTime = System.currentTimeMillis()
        screenOnStartTime = sessionStartTime
        isScreenOn = true
        lastDeepSleepMs = getSystemDeepSleepTime()
        screenOffDeepSleepStart = 0L
    }
    
    private fun registerCommandReceiver() {
        commandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_RESET_STATS -> {
                        val level = intent.getIntExtra("level", -1)
                        resetStatsManual(if (level > 0) level else 100)
                        Log.d(TAG, "Stats reset via broadcast")
                    }
                }
            }
        }
        
        val filter = IntentFilter(ACTION_RESET_STATS)
        registerReceiver(commandReceiver, filter, RECEIVER_NOT_EXPORTED)
    }
    
    /**
     * Get system deep sleep time from /sys/power/suspend_stats or SystemClock
     * This is the actual suspend time tracked by the kernel
     */
    private fun getSystemDeepSleepTime(): Long {
        // Method 1: Calculate from SystemClock difference
        // elapsedRealtime includes deep sleep, uptimeMillis does not
        val elapsedRealtime = SystemClock.elapsedRealtime()
        val uptimeMillis = SystemClock.uptimeMillis()
        return elapsedRealtime - uptimeMillis
    }
    
    /**
     * Read wakelock stats to determine if device is truly sleeping
     */
    private fun hasActiveWakelocks(): Boolean {
        return try {
            val wakelockFile = File("/sys/power/wake_lock")
            if (wakelockFile.exists()) {
                val content = wakelockFile.readText().trim()
                content.isNotEmpty()
            } else {
                // Fallback: check PowerManager
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                // If device is in idle mode, it's likely in deep sleep
                val isIdle = powerManager.isDeviceIdleMode
                !isIdle
            }
        } catch (e: Exception) {
            true // Assume awake on error
        }
    }
    
    private fun registerScreenReceiver() {
        screenReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                    Intent.ACTION_SCREEN_OFF -> onScreenOff()
                    Intent.ACTION_BATTERY_CHANGED -> onBatteryChanged(intent)
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        registerReceiver(screenReceiver, filter)
    }
    
    private fun onScreenOn() {
        val now = System.currentTimeMillis()
        
        if (!isScreenOn && screenOffStartTime > 0) {
            val offDuration = now - screenOffStartTime
            totalScreenOffTime += offDuration
            
            // Calculate deep sleep during this screen-off period
            // Deep sleep = difference in system suspend time
            val currentDeepSleep = getSystemDeepSleepTime()
            val sleepDuringOff = currentDeepSleep - screenOffDeepSleepStart
            
            if (sleepDuringOff > 0) {
                deepSleepTime += sleepDuringOff
            }
            
            // Awake time = screen off time - deep sleep time during that period
            val awakeDuringOff = offDuration - sleepDuringOff
            if (awakeDuringOff > 0) {
                awakeTime += awakeDuringOff
            }
            
            Log.d(TAG, "Screen ON - offDuration: ${offDuration/1000}s, deepSleep: ${sleepDuringOff/1000}s, awake: ${awakeDuringOff/1000}s")
        }
        
        isScreenOn = true
        screenOnStartTime = now
    }
    
    private fun onScreenOff() {
        val now = System.currentTimeMillis()
        
        if (isScreenOn && screenOnStartTime > 0) {
            val onDuration = now - screenOnStartTime
            totalScreenOnTime += onDuration
        }
        
        isScreenOn = false
        screenOffStartTime = now
        screenOffDeepSleepStart = getSystemDeepSleepTime()
        
        Log.d(TAG, "Screen OFF - total on: ${totalScreenOnTime / 1000}s")
    }
    
    private fun onBatteryChanged(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || 
                         status == BatteryManager.BATTERY_STATUS_FULL
        
        // Initialize session start level
        if (sessionStartLevel == -1) {
            sessionStartLevel = level
            lastLevelForDrain = level
        }
        
        // Track drain when level changes (only when discharging)
        if (!isCharging && level != lastLevelForDrain && lastLevelForDrain != -1) {
            val drainPercent = lastLevelForDrain - level
            if (drainPercent > 0) {
                val drainMah = (DeviceConfig.batteryCapacityMah * drainPercent) / 100
                if (isScreenOn) {
                    screenOnDrainMah += drainMah
                } else {
                    screenOffDrainMah += drainMah
                }
                Log.d(TAG, "Level drop: $lastLevelForDrain -> $level, drain: $drainMah mAh, screenOn: $isScreenOn")
            }
            lastLevelForDrain = level
        }
        
        // Reset stats when charging starts
        if (isCharging && sessionStartLevel != level) {
            resetStats(level)
        }
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BatteryStatsService started")
        try {
            startForeground(NOTIFICATION_ID, createNotification("Battery Stats", "Monitoring..."))
            startStatsLoop()
        } catch (e: Exception) {
            Log.e(TAG, "Error in onStartCommand", e)
            stopSelf()
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Log.d(TAG, "BatteryStatsService destroyed")
        serviceJob?.cancel()
        serviceScope.cancel()
        
        try {
            screenReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering screen receiver", e)
        }
        
        try {
            commandReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering command receiver", e)
        }
        
        super.onDestroy()
    }
    
    private fun createNotificationChannel() {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Battery Statistics",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows battery usage statistics"
                setShowBadge(false)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating notification channel", e)
        }
    }
    
    private fun createNotification(title: String, bigText: String?): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        
        if (bigText != null) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            builder.setContentText(bigText.lines().firstOrNull() ?: "")
        }
        
        return builder.build()
    }
    
    private fun updateNotification(title: String, bigText: String? = null) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, createNotification(title, bigText))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }
    
    private fun startStatsLoop() {
        serviceJob = serviceScope.launch {
            delay(2000)
            
            while (isActive) {
                try {
                    updateStats()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in stats loop", e)
                }
                delay(UPDATE_INTERVAL)
            }
        }
    }
    
    private suspend fun updateStats() {
        val batteryLevel = BatteryController.getBatteryLevel()
        val temp = BatteryController.getBatteryTemp()
        val currentMa = getCurrentNow()
        val isCharging = ChargingController.getUsbType() != "Unknown"

        if (isCharging) {
            val tempC = temp / 10.0
            val sportMode = ChargingController.getSportMode()
            
            // Get charging current (positive value) in mA
            val chargingCurrent = kotlin.math.abs(currentMa)
            
            val modeStr = if (sportMode) "⚡ Turbo" else ""
            
            val title = "$batteryLevel% • ${chargingCurrent}mA • ${String.format("%.1f", tempC)}°C $modeStr"
            
            updateNotification(title, null)
            return
        }
        
        // DISCHARGING - Show AccuBattery-style stats
        val tempC = temp / 10.0
        val timeLeft = calculateTimeLeft(batteryLevel, currentMa)
        
        // Calculate current screen time including ongoing period
        val now = System.currentTimeMillis()
        val currentScreenOnTime = totalScreenOnTime + if (isScreenOn && screenOnStartTime > 0) {
            now - screenOnStartTime
        } else 0L
        
        val currentScreenOffTime = totalScreenOffTime + if (!isScreenOn && screenOffStartTime > 0) {
            now - screenOffStartTime
        } else 0L
        
        // Calculate current deep sleep if screen is off
        var currentDeepSleep = deepSleepTime
        var currentAwake = awakeTime
        
        if (!isScreenOn && screenOffStartTime > 0) {
            val currentSystemDeepSleep = getSystemDeepSleepTime()
            val sleepDuringCurrentOff = currentSystemDeepSleep - screenOffDeepSleepStart
            if (sleepDuringCurrentOff > 0) {
                currentDeepSleep += sleepDuringCurrentOff
            }
            val offDuration = now - screenOffStartTime
            val awakeDuringCurrentOff = offDuration - sleepDuringCurrentOff
            if (awakeDuringCurrentOff > 0) {
                currentAwake += awakeDuringCurrentOff
            }
        }
        
        // Calculate drain rates (% per hour)
        val screenOnHours = currentScreenOnTime / 3600000.0
        val screenOffHours = currentScreenOffTime / 3600000.0
        
        val activeRate = if (screenOnHours > 0.01 && screenOnDrainMah > 0) {
            (screenOnDrainMah * 100.0 / DeviceConfig.batteryCapacityMah) / screenOnHours
        } else 0.0
        
        val idleRate = if (screenOffHours > 0.01 && screenOffDrainMah > 0) {
            (screenOffDrainMah * 100.0 / DeviceConfig.batteryCapacityMah) / screenOffHours
        } else 0.0
        
        // Calculate deep sleep and awake percentages of screen-off time
        val deepSleepPercent = if (currentScreenOffTime > 0) {
            (currentDeepSleep * 100.0 / currentScreenOffTime)
        } else 0.0
        
        val awakePercent = if (currentScreenOffTime > 0) {
            (currentAwake * 100.0 / currentScreenOffTime)
        } else 0.0
        
        // Build notification text
        val title = "Now: ${kotlin.math.abs(currentMa)} mA • ${String.format("%.1f", tempC)}° • $timeLeft"
        
        val screenOnPercent = screenOnDrainMah * 100.0 / DeviceConfig.batteryCapacityMah
        val screenOffPercent = screenOffDrainMah * 100.0 / DeviceConfig.batteryCapacityMah
        
        val statsText = StringBuilder()
        statsText.appendLine("Active: ${String.format("%.1f", activeRate)}%/h • Idle: ${String.format("%.1f", idleRate)}%/h")
        statsText.appendLine("Screen On: ${formatTime(currentScreenOnTime)} • ${String.format("%.1f", screenOnPercent)}% ($screenOnDrainMah mAh)")
        statsText.appendLine("Screen Off: ${formatTime(currentScreenOffTime)} • ${String.format("%.1f", screenOffPercent)}% ($screenOffDrainMah mAh)")
        statsText.appendLine("Deep sleep: ${formatTime(currentDeepSleep)} • ${String.format("%.1f", deepSleepPercent)}%")
        statsText.append("Awake: ${formatTime(currentAwake)} • ${String.format("%.1f", awakePercent)}%")
        
        updateNotification(title, statsText.toString())
    }
    
    private fun resetStats(currentLevel: Int) {
        sessionStartTime = System.currentTimeMillis()
        sessionStartLevel = currentLevel
        totalScreenOnTime = 0
        totalScreenOffTime = 0
        screenOnDrainMah = 0
        screenOffDrainMah = 0
        deepSleepTime = 0
        awakeTime = 0
        screenOnStartTime = sessionStartTime
        screenOffStartTime = 0
        lastLevelForDrain = currentLevel
        screenOffDeepSleepStart = 0
        lastDeepSleepMs = getSystemDeepSleepTime()
        isScreenOn = true
        Log.d(TAG, "Stats reset - charging detected")
    }
    
    private fun resetStatsManual(currentLevel: Int) {
        sessionStartTime = System.currentTimeMillis()
        sessionStartLevel = currentLevel
        totalScreenOnTime = 0
        totalScreenOffTime = 0
        screenOnDrainMah = 0
        screenOffDrainMah = 0
        deepSleepTime = 0
        awakeTime = 0
        screenOnStartTime = sessionStartTime
        screenOffStartTime = 0
        lastLevelForDrain = currentLevel
        screenOffDeepSleepStart = 0
        lastDeepSleepMs = getSystemDeepSleepTime()
        isScreenOn = true
        updateNotification("Stats Reset", "Monitoring started fresh")
        Log.d(TAG, "Stats manually reset")
    }
    
    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val mins = (totalSeconds % 3600) / 60
        val secs = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }
    
    private fun calculateTimeLeft(batteryLevel: Int, currentMa: Int): String {
        // current_now can be positive or negative depending on device
        // When discharging: some devices report negative, some report positive
        // We need the absolute drain rate
        val drainMa = kotlin.math.abs(currentMa)
        
        // Too low to calculate accurately
        if (drainMa < 50) return "--"
        
        // Calculate remaining mAh based on battery level
        val remainingMah = (DeviceConfig.batteryCapacityMah * batteryLevel) / 100.0
        
        // Hours left = remaining capacity / drain rate
        val hoursLeft = remainingMah / drainMa
        
        // Sanity check - max 100 hours
        if (hoursLeft > 100 || hoursLeft <= 0) return "--"
        
        val totalMins = (hoursLeft * 60).toInt()
        if (totalMins <= 0) return "--"
        
        val h = totalMins / 60
        val m = totalMins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
    
    private fun getCurrentNow(): Int {
        return try {
            val raw = FileUtils.readOneLine("/sys/class/power_supply/battery/current_now")?.toIntOrNull() ?: 0
            // Convert from microamps to milliamps
            raw / 1000
        } catch (e: Exception) {
            0
        }
    }
}
