package com.oof.control.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks battery statistics similar to AccuBattery
 * Monitors active discharge rate, screen on/off drain, etc.
 */
class BatteryStatsTracker(private val context: Context) {
    
    companion object {
        private const val TAG = "BatteryStatsTracker"
        private const val PREFS_NAME = "battery_stats"
        private const val KEY_SESSION_START = "session_start"
        private const val KEY_LAST_LEVEL = "last_level"
        private const val KEY_SCREEN_ON_TIME = "screen_on_time"
        private const val KEY_SCREEN_OFF_TIME = "screen_off_time"
        private const val KEY_SCREEN_ON_DRAIN = "screen_on_drain"
        private const val KEY_SCREEN_OFF_DRAIN = "screen_off_drain"
        private const val KEY_DEEP_SLEEP_TIME = "deep_sleep_time"
        private const val KEY_AWAKE_TIME = "awake_time"
    }
    
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    data class BatteryStats(
        val currentMa: Int = 0,              // Current drain in mA
        val currentWatts: Double = 0.0,      // Current drain in watts
        val temperature: Double = 0.0,       // Temperature in Celsius
        val timeLeft: String = "",           // Estimated time left
        val activeRate: Double = 0.0,        // Active drain %/h
        val idleRate: Double = 0.0,          // Idle drain %/h
        val screenOnTime: Long = 0,          // Screen on time in seconds
        val screenOnRate: Double = 0.0,      // Screen on drain %/h
        val screenOnDrain: Int = 0,          // Screen on drain in mAh
        val screenOffTime: Long = 0,         // Screen off time in seconds
        val screenOffRate: Double = 0.0,     // Screen off drain %/h
        val screenOffDrain: Int = 0,         // Screen off drain in mAh
        val deepSleepTime: Long = 0,         // Deep sleep time in seconds
        val deepSleepRate: Double = 0.0,     // Deep sleep drain %/h
        val awakeTime: Long = 0,             // Awake time in seconds
        val awakeRate: Double = 0.0,         // Awake drain %/h
        val batteryLevel: Int = 0,           // Current battery level
        val voltage: Double = 0.0,           // Battery voltage in V
        val health: String = ""              // Battery health
    )
    
    private val _stats = MutableStateFlow(BatteryStats())
    val stats: StateFlow<BatteryStats> = _stats.asStateFlow()
    
    private var sessionStartTime = prefs.getLong(KEY_SESSION_START, System.currentTimeMillis())
    private var lastBatteryLevel = prefs.getInt(KEY_LAST_LEVEL, -1)
    private var screenOnStartTime = 0L
    private var screenOffStartTime = 0L
    private var isScreenOn = false
    
    // Accumulated times from prefs
    private var totalScreenOnTime = prefs.getLong(KEY_SCREEN_ON_TIME, 0)
    private var totalScreenOffTime = prefs.getLong(KEY_SCREEN_OFF_TIME, 0)
    private var totalScreenOnDrain = prefs.getInt(KEY_SCREEN_ON_DRAIN, 0)
    private var totalScreenOffDrain = prefs.getInt(KEY_SCREEN_OFF_DRAIN, 0)
    private var totalDeepSleepTime = prefs.getLong(KEY_DEEP_SLEEP_TIME, 0)
    private var totalAwakeTime = prefs.getLong(KEY_AWAKE_TIME, 0)
    
    // Track level changes
    private val levelHistory = mutableListOf<Pair<Long, Int>>() // timestamp, level

    // Cached battery status to avoid repeated registerReceiver calls
    @Volatile private var cachedBatteryIntent: Intent? = null
    private var lastBatteryIntentTime = 0L
    private val BATTERY_CACHE_TTL = 2000L // Cache for 2 seconds

    init {
        // Initialize session if needed
        if (lastBatteryLevel == -1) {
            lastBatteryLevel = getBatteryLevel()
            saveToPrefs()
        }
    }
    
    /**
     * Update battery statistics
     */
    suspend fun updateStats() {
        try {
            val currentLevel = getBatteryLevel()
            val currentMa = getCurrentNow()
            val voltage = getVoltage()
            val temperature = getTemperature()
            val isCharging = isCharging()
            
            // Track level changes
            if (currentLevel != lastBatteryLevel) {
                levelHistory.add(Pair(System.currentTimeMillis(), currentLevel))

                // Keep only last hour of history - use iterator for efficiency
                val oneHourAgo = System.currentTimeMillis() - 3600000
                levelHistory.removeIf { it.first < oneHourAgo }

                lastBatteryLevel = currentLevel
                saveToPrefs()
            }
            
            // Calculate drain rates
            val (activeRate, idleRate) = calculateDrainRates()
            val (screenOnRate, screenOffRate) = calculateScreenRates()
            val (deepSleepRate, awakeRate) = calculateSleepRates()
            
            // Calculate time left
            val timeLeft = if (!isCharging && currentMa < 0) {
                val drainMaH = -currentMa / 1000.0
                if (drainMaH > 0) {
                    val batteryCapacity = 5000 // mAh - adjust for your device
                    val remainingCapacity = batteryCapacity * (currentLevel / 100.0)
                    val hoursLeft = remainingCapacity / drainMaH
                    formatTimeLeft(hoursLeft)
                } else {
                    ""
                }
            } else {
                ""
            }
            
            _stats.value = BatteryStats(
                currentMa = currentMa / 1000, // Convert to mA
                currentWatts = (currentMa / 1000.0) * (voltage / 1000.0),
                temperature = temperature / 10.0,
                timeLeft = timeLeft,
                activeRate = activeRate,
                idleRate = idleRate,
                screenOnTime = totalScreenOnTime,
                screenOnRate = screenOnRate,
                screenOnDrain = totalScreenOnDrain,
                screenOffTime = totalScreenOffTime,
                screenOffRate = screenOffRate,
                screenOffDrain = totalScreenOffDrain,
                deepSleepTime = totalDeepSleepTime,
                deepSleepRate = deepSleepRate,
                awakeTime = totalAwakeTime,
                awakeRate = awakeRate,
                batteryLevel = currentLevel,
                voltage = voltage / 1000000.0,
                health = getBatteryHealth()
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating stats", e)
        }
    }
    
    /**
     * Notify screen state changed
     */
    fun onScreenStateChanged(screenOn: Boolean) {
        val now = System.currentTimeMillis()
        
        if (screenOn && !isScreenOn) {
            // Screen turned on
            screenOnStartTime = now
            if (screenOffStartTime > 0) {
                totalScreenOffTime += (now - screenOffStartTime) / 1000
            }
        } else if (!screenOn && isScreenOn) {
            // Screen turned off
            screenOffStartTime = now
            if (screenOnStartTime > 0) {
                totalScreenOnTime += (now - screenOnStartTime) / 1000
            }
        }
        
        isScreenOn = screenOn
        saveToPrefs()
    }
    
    /**
     * Calculate active and idle drain rates
     */
    private fun calculateDrainRates(): Pair<Double, Double> {
        if (levelHistory.size < 2) return Pair(0.0, 0.0)
        
        val now = System.currentTimeMillis()
        val sessionDuration = (now - sessionStartTime) / 3600000.0 // hours
        
        if (sessionDuration < 0.1) return Pair(0.0, 0.0)
        
        // Calculate from recent history
        val recentHistory = levelHistory.takeLast(10)
        if (recentHistory.size < 2) return Pair(0.0, 0.0)
        
        val first = recentHistory.first()
        val last = recentHistory.last()
        val timeDiffHours = (last.first - first.first) / 3600000.0
        
        if (timeDiffHours < 0.01) return Pair(0.0, 0.0)
        
        val levelDiff = first.second - last.second
        val rate = levelDiff / timeDiffHours
        
        // Active rate (when screen on or high usage)
        val activeRate = if (isScreenOn) rate else 0.0
        
        // Idle rate (when screen off)
        val idleRate = if (!isScreenOn) rate else 0.0
        
        return Pair(activeRate, idleRate)
    }
    
    /**
     * Calculate screen on/off drain rates
     */
    private fun calculateScreenRates(): Pair<Double, Double> {
        val screenOnRate = if (totalScreenOnTime > 0) {
            val hours = totalScreenOnTime / 3600.0
            (totalScreenOnDrain / 5000.0) * 100 / hours // Assuming 5000mAh battery
        } else {
            0.0
        }
        
        val screenOffRate = if (totalScreenOffTime > 0) {
            val hours = totalScreenOffTime / 3600.0
            (totalScreenOffDrain / 5000.0) * 100 / hours
        } else {
            0.0
        }
        
        return Pair(screenOnRate, screenOffRate)
    }
    
    /**
     * Calculate deep sleep and awake drain rates
     */
    private fun calculateSleepRates(): Pair<Double, Double> {
        // This would need integration with doze mode detection
        // For now, estimate based on screen off time
        val deepSleepRate = if (totalDeepSleepTime > 0) {
            val hours = totalDeepSleepTime / 3600.0
            // Estimate very low drain during deep sleep
            0.1 / hours
        } else {
            0.0
        }
        
        val awakeRate = if (totalAwakeTime > 0) {
            val hours = totalAwakeTime / 3600.0
            // Estimate higher drain when awake
            2.0 / hours
        } else {
            0.0
        }
        
        return Pair(deepSleepRate, awakeRate)
    }
    
    /**
     * Reset session statistics
     */
    fun resetSession() {
        sessionStartTime = System.currentTimeMillis()
        lastBatteryLevel = getBatteryLevel()
        totalScreenOnTime = 0
        totalScreenOffTime = 0
        totalScreenOnDrain = 0
        totalScreenOffDrain = 0
        totalDeepSleepTime = 0
        totalAwakeTime = 0
        levelHistory.clear()
        saveToPrefs()
    }
    
    private fun saveToPrefs() {
        prefs.edit {
            putLong(KEY_SESSION_START, sessionStartTime)
            putInt(KEY_LAST_LEVEL, lastBatteryLevel)
            putLong(KEY_SCREEN_ON_TIME, totalScreenOnTime)
            putLong(KEY_SCREEN_OFF_TIME, totalScreenOffTime)
            putInt(KEY_SCREEN_ON_DRAIN, totalScreenOnDrain)
            putInt(KEY_SCREEN_OFF_DRAIN, totalScreenOffDrain)
            putLong(KEY_DEEP_SLEEP_TIME, totalDeepSleepTime)
            putLong(KEY_AWAKE_TIME, totalAwakeTime)
        }
    }
    
    private fun formatTimeLeft(hours: Double): String {
        val totalMinutes = (hours * 60).toInt()
        val h = totalMinutes / 60
        val m = totalMinutes % 60
        return if (h > 0) "${h}h ${m}m left" else "${m}m left"
    }
    
    // Battery info helpers - with caching to avoid repeated registerReceiver calls
    private fun getBatteryIntent(): Intent? {
        val now = System.currentTimeMillis()
        if (cachedBatteryIntent != null && (now - lastBatteryIntentTime) < BATTERY_CACHE_TTL) {
            return cachedBatteryIntent
        }
        cachedBatteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        lastBatteryIntentTime = now
        return cachedBatteryIntent
    }

    private fun getBatteryLevel(): Int {
        val batteryStatus = getBatteryIntent()
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) (level * 100 / scale) else 0
    }
    
    private fun getCurrentNow(): Int {
        return try {
            FileUtils.readOneLine("/sys/class/power_supply/battery/current_now")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun getVoltage(): Int {
        return try {
            FileUtils.readOneLine("/sys/class/power_supply/battery/voltage_now")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun getTemperature(): Int {
        return try {
            FileUtils.readOneLine("/sys/class/power_supply/battery/temp")?.toIntOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
    
    private fun isCharging(): Boolean {
        val batteryStatus = getBatteryIntent()
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getBatteryHealth(): String {
        val batteryStatus = getBatteryIntent()
        return when (batteryStatus?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }
    }
}
