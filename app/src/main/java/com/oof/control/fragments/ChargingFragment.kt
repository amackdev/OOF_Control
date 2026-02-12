package com.oof.control.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.oof.control.databinding.FragmentChargingBinding
import com.oof.control.services.ChargingControlService
import com.oof.control.utils.BatteryStatsTracker
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class ChargingFragment : Fragment() {
    
    private var _binding: FragmentChargingBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    private lateinit var batteryStatsTracker: BatteryStatsTracker
    private var updateJob: Job? = null
    private var statsJob: Job? = null
    
    private var isUpdatingUI = false
    
    // Cache previous values to avoid unnecessary UI updates
    private var lastBatteryLevel = -1
    private var lastBatteryTemp = -1
    private var lastBatteryHealthPercent = -1.0
    private var lastIsCharging = false
    private var lastDrainRate = 0
    
    companion object {
        private const val TAG = "ChargingFragment"
        private const val UPDATE_INTERVAL = 2000L // 2 seconds
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChargingBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        batteryStatsTracker = BatteryStatsTracker(requireContext())
        
        setupListeners()
        loadSavedStates()
        startBatteryUpdates()
        startStatsMonitoring()
    }
    
    private fun loadSavedStates() {
        isUpdatingUI = true
        
        try {
            // Check if OS has charge limit feature
            val osHasChargeLimit = hasOsChargeLimit()
            
            // Hide charge limit controls if OS has the feature
            if (osHasChargeLimit) {
                binding.cardChargeLimitEnable.visibility = View.GONE
                binding.cardChargeLimit.visibility = View.GONE
            } else {
                binding.cardChargeLimitEnable.visibility = View.VISIBLE
                binding.cardChargeLimit.visibility = View.VISIBLE
                
                // Load UI state from preferences (instant)
                val chargeLimit = prefs.chargeLimit
                binding.sliderChargeLimit.value = chargeLimit.toFloat()
                binding.tvChargeLimitValue.text = "$chargeLimit%"
                binding.switchChargeLimit.isChecked = prefs.chargeLimitEnabled
                binding.cardChargeLimit.alpha = if (prefs.chargeLimitEnabled) 1.0f else 0.6f
                updateHealthIndicator(chargeLimit)
            }
            
            binding.switchChargingService.isChecked = prefs.chargingServiceEnabled
            binding.switchBatteryStats.isChecked = prefs.batteryStatsEnabled
            
            // Sport Mode - check support in coroutine
            viewLifecycleOwner.lifecycleScope.launch {
                val sportModeSupported = RootController.isSportModeSupported()
                binding.switchSportMode.isEnabled = sportModeSupported
                binding.cardSportMode.alpha = if (sportModeSupported) 1.0f else 0.5f
                
                if (sportModeSupported) {
                    binding.switchSportMode.isChecked = prefs.sportMode
                    updateSportModeStatus(prefs.sportMode)
                } else {
                    binding.tvSportModeStatus.text = "Not supported"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading states", e)
        } finally {
            isUpdatingUI = false
        }
    }
    
    private fun hasOsChargeLimit(): Boolean {
        return try {
            // Check if OS-level charge protection setting exists
            val switchState = android.provider.Settings.System.getInt(
                requireContext().contentResolver,
                "regular_charge_protection_switch_state",
                -1
            )
            // If the setting exists (not -1), OS has charge limit feature
            switchState != -1
        } catch (e: Exception) {
            false
        }
    }
    
    private fun updateHealthIndicator(limit: Int) {
        if (_binding == null) return
        when {
            limit <= 80 -> {
                binding.tvHealthIndicator.text = "✓ Optimal"
                binding.tvHealthIndicator.setTextColor(resources.getColor(com.oof.control.R.color.accent, null))
            }
            limit <= 90 -> {
                binding.tvHealthIndicator.text = "⚡ Good"
                binding.tvHealthIndicator.setTextColor(resources.getColor(com.oof.control.R.color.blue, null))
            }
            else -> {
                binding.tvHealthIndicator.text = "⚠ High"
                binding.tvHealthIndicator.setTextColor(resources.getColor(com.oof.control.R.color.orange, null))
            }
        }
    }
    
    private fun setupListeners() {
        // Sport Mode Switch
        binding.switchSportMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI || !binding.switchSportMode.isEnabled) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (!RootController.isSportModeSupported()) {
                        isUpdatingUI = true
                        binding.switchSportMode.isChecked = false
                        binding.switchSportMode.isEnabled = false
                        isUpdatingUI = false
                        showToast("Sport mode not available")
                        return@launch
                    }
                    
                    val success = RootController.setSportMode(isChecked)
                    if (success) {
                        prefs.sportMode = isChecked
                        updateSportModeStatus(isChecked)
                        showToast("90W Sport Mode ${if (isChecked) "enabled" else "disabled"}")
                    } else {
                        isUpdatingUI = true
                        binding.switchSportMode.isChecked = !isChecked
                        isUpdatingUI = false
                        showToast("Failed to update Sport Mode")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting sport mode", e)
                    isUpdatingUI = true
                    binding.switchSportMode.isChecked = !isChecked
                    isUpdatingUI = false
                    showToast("Error: ${e.message}")
                }
            }
        }
        
        // Charge Limit Enable Switch
        binding.switchChargeLimit.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            
            prefs.chargeLimitEnabled = isChecked
            binding.cardChargeLimit.alpha = if (isChecked) 1.0f else 0.6f
            showToast("Charge limit ${if (isChecked) "enabled" else "disabled"}")
        }
        
        // Charge Limit Slider (Material Slider)
        binding.sliderChargeLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val limit = value.toInt()
                binding.tvChargeLimitValue.text = "$limit%"
                updateHealthIndicator(limit)
            }
        }
        
        binding.sliderChargeLimit.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {}
            
            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                val limit = slider.value.toInt()
                prefs.chargeLimit = limit
                showToast("Charge limit set to $limit%")
            }
        })
        
        // Charging Service Switch
        binding.switchChargingService.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            
            prefs.chargingServiceEnabled = isChecked
            try {
                if (isChecked) {
                    ChargingControlService.startChargingOnly(requireContext())
                    showToast("Smart charging service started")
                } else {
                    ChargingControlService.stopChargingOnly(requireContext())
                    showToast("Smart charging service stopped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling charging service", e)
                showToast("Service error: ${e.message}")
            }
        }
        
        // Battery Stats Service Switch
        binding.switchBatteryStats.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            
            prefs.batteryStatsEnabled = isChecked
            try {
                if (isChecked) {
                    ChargingControlService.startStatsOnly(requireContext())
                    showToast("Battery stats service started")
                } else {
                    ChargingControlService.stopStatsOnly(requireContext())
                    showToast("Battery stats service stopped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error toggling stats service", e)
                showToast("Service error: ${e.message}")
            }
        }
        
        // Reset Stats Button
        binding.btnResetStats.setOnClickListener {
            ChargingControlService.resetStats(requireContext())
            showToast("Battery statistics reset")
        }
    }
    
    private fun startBatteryUpdates() {
        updateJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                try {
                    updateBatteryInfo()
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating battery info", e)
                }
                delay(UPDATE_INTERVAL)
            }
        }
    }
    
    private fun startStatsMonitoring() {
        statsJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                while (isActive) {
                    try {
                        batteryStatsTracker.updateStats()
                        
                        // Collect stats and update UI - only while active
                        batteryStatsTracker.stats.collect { stats ->
                            if (isActive && _binding != null) {
                                updateStatsDisplay(stats)
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error in stats monitoring loop", e)
                        }
                    }
                    delay(UPDATE_INTERVAL)
                }
            } catch (e: Exception) {
                // Job cancelled or other error - ignore if not active
                if (isActive) {
                    Log.e(TAG, "Stats monitoring stopped", e)
                }
            }
        }
    }
    
    @Suppress("UNUSED_PARAMETER")
    private fun updateStatsDisplay(stats: BatteryStatsTracker.BatteryStats) {
        // Stats are now shown in notification, not in UI
    }
    
    private suspend fun updateBatteryInfo() {
        if (_binding == null) return
        
        try {
            val batteryLevel = RootController.getBatteryLevel()
            val batteryTemp = RootController.getBatteryTemp()
            val isCharging = RootController.isCharging()
            val isFastCharging = RootController.isFastCharging()
            val maxPower = RootController.getMaxPower()
            val sportMode = RootController.getSportMode()
            val drainRate = RootController.getBatteryDrainRate()
            val healthPercent = RootController.getBatteryHealthPercent()
            
            // Update battery level
            if (batteryLevel != lastBatteryLevel) {
                binding.tvBatteryLevel.text = "$batteryLevel%"
                binding.progressBattery.progress = batteryLevel
                lastBatteryLevel = batteryLevel
            }

            // Update battery health (below %)
            if (healthPercent != lastBatteryHealthPercent) {
                if (healthPercent > 0.0) {
                    binding.tvBatteryHealth.text = "Health ${kotlin.math.round(healthPercent).toInt()}%"
                    binding.tvBatteryHealth.visibility = View.VISIBLE
                } else {
                    binding.tvBatteryHealth.visibility = View.GONE
                }
                lastBatteryHealthPercent = healthPercent
            }

            // Update temperature
            if (batteryTemp != lastBatteryTemp) {
                val tempCelsius = batteryTemp / 10.0
                binding.tvBatteryTemp.text = "${String.format("%.1f", tempCelsius)}°C"
                lastBatteryTemp = batteryTemp
            }
            
            // Update charging status
            if (isCharging != lastIsCharging) {
                binding.tvBatteryStatus.text = when {
                    isFastCharging && sportMode -> "🔥 TURBO CHARGING"
                    isFastCharging -> "⚡ FAST CHARGING"
                    isCharging -> "🔌 CHARGING"
                    else -> "BATTERY"
                }
                lastIsCharging = isCharging
            }
            
            // Update drain rate display
            if (drainRate != lastDrainRate) {
                // Always show drain rate (positive = charging, negative = discharging)
                if (drainRate > 0) {
                    binding.tvDrainRate.text = "+${drainRate}mA/h"
                    binding.tvDrainRate.setTextColor(resources.getColor(android.R.color.holo_green_light, null))
                } else if (drainRate < 0) {
                    binding.tvDrainRate.text = "${drainRate}mA/h"
                    binding.tvDrainRate.setTextColor(resources.getColor(android.R.color.holo_red_light, null))
                } else {
                    binding.tvDrainRate.text = "0mA/h"
                    binding.tvDrainRate.setTextColor(resources.getColor(android.R.color.white, null))
                }
                binding.tvDrainRate.visibility = View.VISIBLE
                
                lastDrainRate = drainRate
            }
            
            // Charging power (only when charging)
            if (isCharging && maxPower > 0) {
                binding.tvChargingPower.text = "${maxPower}W"
                binding.tvChargingPower.visibility = View.VISIBLE
            } else {
                binding.tvChargingPower.visibility = View.GONE
            }
            
            // Estimated time (only when charging)
            val chargeLimit = prefs.chargeLimit
            if (isCharging && batteryLevel < chargeLimit && prefs.chargeLimitEnabled) {
                val remaining = chargeLimit - batteryLevel
                val timeMin = if (sportMode && drainRate > 0) {
                    (remaining * 60) / (drainRate / 100)
                } else if (drainRate > 0) {
                    (remaining * 60) / (drainRate / 100)
                } else {
                    0
                }
                if (timeMin > 0) {
                    binding.tvEstimatedTime.text = "~$timeMin min to $chargeLimit%"
                    binding.tvEstimatedTime.visibility = View.VISIBLE
                } else {
                    binding.tvEstimatedTime.visibility = View.GONE
                }
            } else {
                binding.tvEstimatedTime.visibility = View.GONE
            }
            
            // Current charging current (only when fast charging)
            if (isFastCharging) {
                val current = DeviceConfig.getChargingCurrent(batteryTemp, batteryLevel, sportMode, maxPower)
                val currentA = current / 1000000.0
                binding.tvCurrentCurrent.text = "${String.format("%.2f", currentA)}A"
                binding.tvCurrentCurrent.visibility = View.VISIBLE
            } else {
                binding.tvCurrentCurrent.visibility = View.GONE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateBatteryInfo", e)
        }
    }
    
    private fun updateSportModeStatus(enabled: Boolean) {
        if (_binding == null) return
        binding.tvSportModeStatus.text = if (enabled) "🔥 Turbo Charging" else "⚡ Standard"
        binding.cardSportMode.alpha = if (enabled) 1.0f else 0.8f
        binding.layoutSportStats.visibility = if (enabled) View.VISIBLE else View.GONE
    }
    
    private fun showToast(message: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Don't reload everything, just continue updating
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        updateJob?.cancel()
        statsJob?.cancel()
        _binding = null
    }
}