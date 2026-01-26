package com.oof.control.services

import android.content.Context
import android.util.Log
import com.oof.control.utils.PrefsManager

/**
 * Legacy wrapper - now starts both SmartChargingService and BatteryStatsService
 */
object ChargingControlService {
    
    private const val TAG = "ChargingControlService"
    
    fun start(context: Context) {
        val prefs = PrefsManager(context)
        Log.d(TAG, "Starting services based on preferences")
        
        if (prefs.chargingServiceEnabled) {
            SmartChargingService.start(context)
        }
        if (prefs.batteryStatsEnabled) {
            BatteryStatsService.start(context)
        }
    }
    
    fun stop(context: Context) {
        Log.d(TAG, "Stopping all services")
        SmartChargingService.stop(context)
        BatteryStatsService.stop(context)
    }
    
    fun startChargingOnly(context: Context) {
        SmartChargingService.start(context)
    }
    
    fun stopChargingOnly(context: Context) {
        SmartChargingService.stop(context)
    }
    
    fun startStatsOnly(context: Context) {
        BatteryStatsService.start(context)
    }
    
    fun stopStatsOnly(context: Context) {
        BatteryStatsService.stop(context)
    }
    
    fun resetStats(context: Context) {
        BatteryStatsService.resetStats(context)
    }
}
