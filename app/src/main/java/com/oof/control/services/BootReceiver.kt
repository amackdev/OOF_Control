package com.oof.control.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oof.control.game.GameWatcherService
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        private const val BOOT_DELAY = 15000L
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
            delay(BOOT_DELAY)
            Log.i(TAG, "Applying saved settings...")

            if (!RootController.isRootAvailable()) {
                Log.e(TAG, "Root not available - cannot apply settings")
                return
            }

            try { RootController.setDT2W(prefs.dt2wEnabled) } catch (e: Exception) { Log.e(TAG, "DT2W failed", e) }
            delay(500)
            try { if (RootController.isTouchRateSupported()) RootController.setTouchRate(prefs.touchRateEnabled) } catch (e: Exception) { Log.e(TAG, "TouchRate failed", e) }
            delay(500)
            try { RootController.setRefreshRate(context, prefs.refreshRate) } catch (e: Exception) { Log.e(TAG, "RefreshRate failed", e) }
            delay(500)
            try { RootController.setPerformanceMode(prefs.performanceMode) } catch (e: Exception) { Log.e(TAG, "PerfMode failed", e) }
            delay(500)
            try { RootController.setTouchBoost(prefs.touchBoost) } catch (e: Exception) { Log.e(TAG, "TouchBoost failed", e) }
            delay(500)
            try {
                if (RootController.isSportModeSupported()) RootController.setSportMode(prefs.sportMode)
            } catch (e: Exception) { Log.e(TAG, "SportMode failed", e) }
            delay(500)

            // Start Game Watcher + Overlay services (always active)
            try {
                context.startService(Intent(context, GameWatcherService::class.java))
                context.startService(Intent(context, com.oof.control.game.GameOverlayService::class.java))
                Log.i(TAG, "Game services started")
            } catch (e: Exception) { Log.e(TAG, "Game services start failed", e) }

            // Start charging services
            if (prefs.chargingServiceEnabled) {
                try { ChargingControlService.startChargingOnly(context) } catch (e: Exception) { Log.e(TAG, "ChargingService failed", e) }
            }
            if (prefs.batteryStatsEnabled) {
                try { ChargingControlService.startStatsOnly(context) } catch (e: Exception) { Log.e(TAG, "StatsService failed", e) }
            }

            Log.i(TAG, "Boot initialization complete")
        } catch (e: Exception) {
            Log.e(TAG, "Error during boot initialization", e)
        }
    }
}
