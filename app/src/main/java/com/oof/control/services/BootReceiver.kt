package com.oof.control.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oof.control.utils.BatteryController
import com.oof.control.utils.ChargingController
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.TouchController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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

            // Check root access
            if (!BatteryController.isAvailable()) {
                Log.e(TAG, "Root not available - cannot apply settings")
                return
            }

            // Apply settings in parallel for faster boot (saves ~3 seconds)
            val scope = CoroutineScope(Dispatchers.IO)
            listOf(
                scope.async { applySetting("Touch rate") {
                    if (TouchController.isTouchRateSupported()) TouchController.setTouchRate(prefs.touchRateEnabled)
                } },
                scope.async { applySetting("Refresh rate") { TouchController.setRefreshRate(context, prefs.refreshRate) } },
                scope.async { applySetting("Performance mode") { TouchController.setPerformanceMode(prefs.performanceMode) } },
                scope.async { applySetting("Touch boost") { TouchController.setTouchBoost(prefs.touchBoost) } },
                scope.async { applySetting("Sport mode") {
                    if (ChargingController.isSportModeSupported()) ChargingController.setSportMode(prefs.sportMode)
                } }
            ).awaitAll()

            // Start services (must be sequential for Android service binding)
            if (prefs.chargingServiceEnabled) {
                try { ChargingControlService.startChargingOnly(context) } catch (_: Exception) { }
            }
            if (prefs.batteryStatsEnabled) {
                try { ChargingControlService.startStatsOnly(context) } catch (_: Exception) { }
            }
            if (prefs.gameServiceEnabled) {
                try {
                    val gmIntent = Intent(context, GameModeService::class.java).apply {
                        action = GameModeService.ACTION_START
                    }
                    context.startForegroundService(gmIntent)
                } catch (_: Exception) { }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during boot initialization", e)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private inline fun applySetting(name: String, block: () -> Unit) {
        try { block() } catch (_: Exception) { }
    }
}