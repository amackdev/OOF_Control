package com.oof.control.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.oof.control.utils.BatteryStatsTracker

/**
 * Receives screen on/off events to track battery drain per screen state
 */
class ScreenStateReceiver : BroadcastReceiver() {
    
    companion object {
        private const val TAG = "ScreenStateReceiver"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Screen turned ON")
                val tracker = BatteryStatsTracker(context)
                tracker.onScreenStateChanged(true)
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen turned OFF")
                val tracker = BatteryStatsTracker(context)
                tracker.onScreenStateChanged(false)
            }
        }
    }
}
