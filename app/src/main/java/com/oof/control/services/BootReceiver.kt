package com.oof.control.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Applies saved settings ONCE on device boot
 * Settings are then monitored by ChargingControlService
 */
class BootReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DELAY = 15000L // 15 seconds - wait for system to stabilize
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            
            val prefs = PrefsManager(context)
            
            if (!prefs.applyOnBoot) {
                Log.i(TAG, "Apply on boot is disabled")
                return
            }
            
            Log.i(TAG, "Boot detected - scheduling settings application")
            
            CoroutineScope(Dispatchers.IO).launch {
                applySettingsOnBoot(context, prefs)
            }
        }
    }
    
    private suspend fun applySettingsOnBoot(context: Context, prefs: PrefsManager) {
        try {
            // Wait for system to be ready
            delay(BOOT_DELAY)
            
            Log.i(TAG, "Applying saved settings...")
            
            // Check root access
            if (!RootController.isRootAvailable()) {
                Log.e(TAG, "Root not available - cannot apply settings")
                return
            }
            
            // Apply each setting with error handling
            try {
                RootController.setDT2W(prefs.dt2wEnabled)
                Log.i(TAG, "DT2W applied: ${prefs.dt2wEnabled}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply DT2W", e)
            }
            
            delay(500)
            
            try {
                if (RootController.isTouchRateSupported()) {
                    RootController.setTouchRate(prefs.touchRateEnabled)
                    Log.i(TAG, "Touch rate applied: ${prefs.touchRateEnabled}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply touch rate", e)
            }
            
            delay(500)
            
            try {
                RootController.setRefreshRate(context, prefs.refreshRate)
                Log.i(TAG, "Refresh rate applied: ${prefs.refreshRate}Hz")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply refresh rate", e)
            }
            
            delay(500)
            
            try {
                RootController.setPerformanceMode(prefs.performanceMode)
                Log.i(TAG, "Performance mode applied: ${prefs.performanceMode}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply performance mode", e)
            }
            
            delay(500)
            
            try {
                RootController.setTouchBoost(prefs.touchBoost)
                Log.i(TAG, "Touch boost applied: ${prefs.touchBoost}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply touch boost", e)
            }
            
            delay(500)
            
			// Apply Sport Mode via prop only
			try {
				if (RootController.isSportModeSupported()) {
					val success = RootController.setSportMode(prefs.sportMode)
					if (success) {
						Log.i(TAG, "Sport mode applied via prop: ${prefs.sportMode}")
					} else {
						Log.e(TAG, "Failed to apply sport mode - prop set failed")
					}
				} else {
					Log.w(TAG, "Sport mode not supported on this device")
				}
			} catch (e: Exception) {
				Log.e(TAG, "Failed to apply sport mode", e)
			}
            delay(500)
            
            // Start charging control service if enabled
            if (prefs.chargingServiceEnabled) {
                try {
                    ChargingControlService.startChargingOnly(context)
                    Log.i(TAG, "Smart charging service started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start charging service", e)
                }
            }
            
            // Start battery stats service if enabled
            if (prefs.batteryStatsEnabled) {
                try {
                    ChargingControlService.startStatsOnly(context)
                    Log.i(TAG, "Battery stats service started")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start battery stats service", e)
                }
            }
            
            Log.i(TAG, "Boot initialization complete")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during boot initialization", e)
        }
    }
}