package com.oof.control.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.oof.control.utils.GameAppEntry
import org.json.JSONArray

/**
 * Manages app preferences/settings
 */
class PrefsManager(context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    companion object {
        private const val PREFS_NAME = "oof_control_prefs"
        
        // Keys
        const val KEY_DT2W_ENABLED = "dt2w_enabled"
        const val KEY_TOUCH_RATE_ENABLED = "touch_rate_enabled"
        const val KEY_REFRESH_RATE = "refresh_rate"
        const val KEY_PERFORMANCE_MODE = "performance_mode"
        const val KEY_TOUCH_BOOST = "touch_boost"
        const val KEY_SPORT_MODE = "sport_mode"
        const val KEY_CHARGE_LIMIT = "charge_limit"
        const val KEY_CHARGE_LIMIT_ENABLED = "charge_limit_enabled"
        const val KEY_CHARGING_SERVICE_ENABLED = "charging_service_enabled"
        const val KEY_BATTERY_STATS_ENABLED = "battery_stats_enabled"
        const val KEY_APPLY_ON_BOOT = "apply_on_boot"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_GAME_MODE_ENABLED = "game_mode_enabled"
        const val KEY_GAME_APPS = "game_apps"
        const val KEY_GAME_MODE_PROFILE = "game_mode_profile"
        const val KEY_GAME_SERVICE_ENABLED = "game_service_enabled"
        
        // Theme modes
        const val THEME_SYSTEM = 0
        const val THEME_LIGHT = 1
        const val THEME_DARK = 2
    }
    
    // DT2W
    var dt2wEnabled: Boolean
        get() = prefs.getBoolean(KEY_DT2W_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_DT2W_ENABLED, value) }
    
    // Touch Rate
    var touchRateEnabled: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_RATE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_TOUCH_RATE_ENABLED, value) }
    
    // Refresh Rate
    var refreshRate: Int
        get() = prefs.getInt(KEY_REFRESH_RATE, 120)
        set(value) = prefs.edit { putInt(KEY_REFRESH_RATE, value) }
    
    // Performance Mode
    var performanceMode: Boolean
        get() = prefs.getBoolean(KEY_PERFORMANCE_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_PERFORMANCE_MODE, value) }
    
    // Touch Boost
    var touchBoost: Boolean
        get() = prefs.getBoolean(KEY_TOUCH_BOOST, false)
        set(value) = prefs.edit { putBoolean(KEY_TOUCH_BOOST, value) }
    
    // Sport Mode
    var sportMode: Boolean
        get() = prefs.getBoolean(KEY_SPORT_MODE, false)
        set(value) = prefs.edit { putBoolean(KEY_SPORT_MODE, value) }
    
    // Charge Limit
    var chargeLimit: Int
        get() = prefs.getInt(KEY_CHARGE_LIMIT, 80)
        set(value) = prefs.edit { putInt(KEY_CHARGE_LIMIT, value) }
    
    var chargeLimitEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHARGE_LIMIT_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_CHARGE_LIMIT_ENABLED, value) }
    
    // Charging Service
    var chargingServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_SERVICE_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_CHARGING_SERVICE_ENABLED, value) }
    
    // Battery Stats Service
    var batteryStatsEnabled: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_STATS_ENABLED, true)
        set(value) = prefs.edit { putBoolean(KEY_BATTERY_STATS_ENABLED, value) }
    
    // Apply on Boot
    var applyOnBoot: Boolean
        get() = prefs.getBoolean(KEY_APPLY_ON_BOOT, true)
        set(value) = prefs.edit { putBoolean(KEY_APPLY_ON_BOOT, value) }
    
    // Theme Mode
    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_SYSTEM)
        set(value) = prefs.edit { putInt(KEY_THEME_MODE, value) }
    
    // Game Mode
    var gameModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_GAME_MODE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GAME_MODE_ENABLED, value) }

    var gameServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_GAME_SERVICE_ENABLED, false)
        set(value) = prefs.edit { putBoolean(KEY_GAME_SERVICE_ENABLED, value) }

    var gameModeProfileJson: String
        get() = prefs.getString(KEY_GAME_MODE_PROFILE, "") ?: ""
        set(value) = prefs.edit { putString(KEY_GAME_MODE_PROFILE, value) }

    fun getGameApps(): List<GameAppEntry> {
        val json = prefs.getString(KEY_GAME_APPS, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { GameAppEntry.fromJson(arr.getString(it)) }
        } catch (e: Exception) { emptyList() }
    }

    fun setGameApps(apps: List<GameAppEntry>) {
        val arr = JSONArray()
        apps.forEach { arr.put(it.toJson()) }
        prefs.edit { putString(KEY_GAME_APPS, arr.toString()) }
    }

    fun addGameApp(app: GameAppEntry) {
        val current = getGameApps().toMutableList()
        if (current.none { it.packageName == app.packageName }) {
            current.add(app)
            setGameApps(current)
        }
    }

    fun removeGameApp(packageName: String) {
        val updated = getGameApps().filter { it.packageName != packageName }
        setGameApps(updated)
    }

    fun applyTheme() {
        val nightMode = when (themeMode) {
            THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
    
    fun getThemeModeDisplayName(): String {
        return when (themeMode) {
            THEME_LIGHT -> "Light"
            THEME_DARK -> "Dark"
            else -> "System default"
        }
    }
}
