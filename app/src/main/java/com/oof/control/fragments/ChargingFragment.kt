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
import com.oof.control.utils.BatteryController
import com.oof.control.utils.ChargingController
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.PrefsManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.fragment.app.activityViewModels
import com.oof.control.viewmodel.MainViewModel

class ChargingFragment : Fragment() {
    
    private var _binding: FragmentChargingBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    private val mainViewModel: MainViewModel by activityViewModels()
    private var updateJob: Job? = null
    
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
        
        binding.switchSportMode.isSaveEnabled = false
        binding.switchChargeLimit.isSaveEnabled = false
        binding.switchSmartService.isSaveEnabled = false
        binding.switchStatsService.isSaveEnabled = false
        
        setupListeners()
        loadSavedStates()
        startUpdates()
    }
    
    private fun loadSavedStates() {
        isUpdatingUI = true
        
        try {
            // Check if OS has charge limit feature
            val osHasChargeLimit = hasOsChargeLimit()
            
            // Hide charge limit controls if OS has the feature
            if (osHasChargeLimit) {
                binding.cardChargeLimit.visibility = View.GONE
            } else {
                binding.cardChargeLimit.visibility = View.VISIBLE
                val chargeLimit = prefs.chargeLimit
                binding.seekChargeLimit.progress = chargeLimit
                binding.tvLimitVal.text = "$chargeLimit%"
                val isEnabled = prefs.chargeLimitEnabled
                binding.switchChargeLimit.isChecked = isEnabled
                binding.layoutChargeLimitSlider.visibility = if (isEnabled) View.VISIBLE else View.GONE
            }
            
            binding.switchSmartService.isChecked = prefs.chargingServiceEnabled
            binding.switchStatsService.isChecked = prefs.batteryStatsEnabled
            
            // Sport Mode - check support in coroutine (hide completely on marble)
            if (DeviceConfig.deviceCodename == DeviceConfig.DEVICE_MARBLE) {
                binding.cardSportMode.visibility = View.GONE
            } else {
                binding.cardSportMode.visibility = View.VISIBLE
                viewLifecycleOwner.lifecycleScope.launch {
                    val sportModeSupported = ChargingController.isSportModeSupported()
                    binding.switchSportMode.isEnabled = sportModeSupported
                    binding.cardSportMode.alpha = if (sportModeSupported) 1.0f else 0.5f

                    if (sportModeSupported) {
                        binding.switchSportMode.isChecked = prefs.sportMode
                        updateSportModeStatus(prefs.sportMode)
                    } else {
                        binding.tvSportStatus.text = "Not supported"
                    }
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
    
    private fun setupListeners() {
        // Sport Mode Switch
        binding.switchSportMode.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI || !binding.switchSportMode.isEnabled) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (!ChargingController.isSportModeSupported()) {
                        isUpdatingUI = true
                        binding.switchSportMode.isChecked = false
                        binding.switchSportMode.isEnabled = false
                        isUpdatingUI = false
                        showToast("Sport mode not available")
                        return@launch
                    }

                    val success = ChargingController.setSportMode(isChecked)
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
            binding.layoutChargeLimitSlider.visibility = if (isChecked) View.VISIBLE else View.GONE
            showToast("Charge limit ${if (isChecked) "enabled" else "disabled"}")
            updateServiceState()
        }
        
        // Charge Limit SeekBar
        binding.seekChargeLimit.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                val limit = progress.coerceIn(50, 100)
                if (fromUser) {
                    binding.tvLimitVal.text = "$limit%"
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                val limit = (seekBar?.progress ?: 80).coerceIn(50, 100)
                prefs.chargeLimit = limit
                showToast("Charge limit set to $limit%")
                if (prefs.chargeLimitEnabled) {
                    updateServiceState()
                }
            }
        })
        
        binding.switchSmartService.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            prefs.chargingServiceEnabled = isChecked
            showToast("Smart charging service ${if (isChecked) "enabled" else "disabled"}")
            updateServiceState()
        }
        
        binding.switchStatsService.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            prefs.batteryStatsEnabled = isChecked
            showToast("Battery statistics ${if (isChecked) "enabled" else "disabled"}")
            updateServiceState()
        }
    }
    
    private fun updateServiceState() {
        try {
            if (prefs.chargingServiceEnabled) ChargingControlService.startChargingOnly(requireContext())
            else ChargingControlService.stopChargingOnly(requireContext())
            
            if (prefs.batteryStatsEnabled) ChargingControlService.startStatsOnly(requireContext())
            else ChargingControlService.stopStatsOnly(requireContext())
        } catch (e: Exception) {
            Log.e(TAG, "Error updating services", e)
        }
    }
    
    /** Reactively update battery info when broadcast is received */
    private fun startUpdates() {
        updateJob = viewLifecycleOwner.lifecycleScope.launch {
            mainViewModel.batteryUpdateTrigger.collect {
                try {
                    updateBatteryInfo()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in update collection", e)
                }
            }
        }
    }

    private fun stopUpdates() {
        updateJob?.cancel()
        updateJob = null
    }
    
    private suspend fun updateBatteryInfo() {
        if (_binding == null) return
        
        try {
            val batteryLevel = BatteryController.getBatteryLevel()
            val batteryTemp = BatteryController.getBatteryTemp()
            val isCharging = ChargingController.isCharging()
            val isFastCharging = ChargingController.isFastCharging()
            val maxPower = ChargingController.getMaxPower()
            val sportMode = ChargingController.getSportMode()
            val drainRate = BatteryController.getBatteryDrainRate()
            val healthPercent = BatteryController.getBatteryHealthPercent()
            
            // Update battery level
            if (batteryLevel != lastBatteryLevel) {
                binding.tvBatteryLevel.text = "$batteryLevel%"
                binding.progressBattery.setProgress(batteryLevel)
                lastBatteryLevel = batteryLevel
            }

            // Update charging status text
            val statusText = when {
                isCharging && batteryLevel >= 100 && Math.abs(drainRate) < 50 -> "FULLY CHARGED"
                isFastCharging && sportMode -> "TURBO CHARGING"
                isFastCharging -> "FAST CHARGING"
                isCharging -> "CHARGING"
                else -> "DISCHARGING"
            }
            binding.tvBatteryStatus.text = statusText
            binding.tvBatteryStatus.setTextColor(
                resources.getColor(if (isCharging) com.oof.control.R.color.blue else com.oof.control.R.color.text_secondary, null)
            )

            // Update Time
            val targetLevel = if (prefs.chargeLimitEnabled) prefs.chargeLimit else 100
            
            if (isCharging) {
                if (batteryLevel < targetLevel) {
                    val remainingPct = targetLevel - batteryLevel
                    val currentMa = Math.abs(drainRate)
                    
                    // Avoid division by zero, assume at least 100mA charging for a baseline estimate
                    val safeCurrentMa = if (currentMa < 100) 100 else currentMa
                    
                    // Rough heuristic: assuming ~5000mAh battery -> 50mAh per 1%
                    // Minutes = (mAh needed / currentmA) * 60
                    val timeMin = (remainingPct * 50 * 60) / safeCurrentMa
                    
                    binding.tvTimeVal.text = "~$timeMin m"
                    binding.tvTimeLbl.text = "Target $targetLevel%"
                } else {
                    binding.tvTimeVal.text = "--"
                    binding.tvTimeLbl.text = "Target Reached"
                }
            } else {
                val currentMa = Math.abs(drainRate)
                if (currentMa >= 50) {
                    val remainingMah = (com.oof.control.utils.DeviceConfig.batteryCapacityMah * batteryLevel) / 100.0
                    val hoursLeft = remainingMah / currentMa
                    
                    if (hoursLeft > 0 && hoursLeft <= 100) {
                        val totalMins = (hoursLeft * 60).toInt()
                        val h = totalMins / 60
                        val m = totalMins % 60
                        binding.tvTimeVal.text = if (h > 0) "${h}h ${m}m" else "${m}m"
                        binding.tvTimeLbl.text = "Remaining"
                    } else {
                        binding.tvTimeVal.text = "--"
                        binding.tvTimeLbl.text = "Remaining"
                    }
                } else {
                    binding.tvTimeVal.text = "--"
                    binding.tvTimeLbl.text = "Remaining"
                }
            }

            // Update Health
            binding.tvHealthVal.text = "${kotlin.math.round(healthPercent).toInt()}%"

            // Update Temp Macro Bar
            val tempCelsius = batteryTemp / 10.0
            binding.tvTempVal.text = "${String.format("%.1f", tempCelsius)}°C"
            binding.barTemp.progress = (batteryTemp / 10).coerceIn(0, 100)
            
            // Update Power and Current Macro Bars
            if (isCharging) {
                binding.tvPowerVal.text = String.format("%.1fW", maxPower.toFloat())
                binding.barPower.progress = maxPower.toInt().coerceIn(0, 100)
                
                val currentA = Math.abs(drainRate) / 1000.0
                binding.tvCurrentVal.text = String.format("+%.2fA", currentA)
                binding.barCurrent.progress = (currentA * 1000).toInt().coerceIn(0, 10000)
                
                binding.barPower.progressDrawable = resources.getDrawable(com.oof.control.R.drawable.macro_bar_green, null)
                binding.barCurrent.progressDrawable = resources.getDrawable(com.oof.control.R.drawable.macro_bar_green, null)
            } else {
                // Not plugged in — power is meaningless, show drain only
                binding.tvPowerVal.text = "--"
                binding.barPower.progress = 0
                
                binding.tvCurrentVal.text = "-${Math.abs(drainRate)}mA"
                binding.barCurrent.progress = Math.abs(drainRate).coerceIn(0, 10000)
                
                binding.barPower.progressDrawable = resources.getDrawable(com.oof.control.R.drawable.macro_bar_yellow, null)
                binding.barCurrent.progressDrawable = resources.getDrawable(com.oof.control.R.drawable.macro_bar_orange, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateBatteryInfo", e)
        }
    }
    
    private fun updateSportModeStatus(enabled: Boolean) {
        if (_binding == null) return
        binding.tvSportStatus.text = if (enabled) "Turbo Charging" else "Standard"
        binding.cardSportMode.alpha = if (enabled) 1.0f else 0.8f
    }
    
    private fun showToast(message: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Restart updates when fragment becomes visible again
        if (updateJob == null || updateJob?.isActive != true) {
            startUpdates()
        }
    }

    override fun onPause() {
        super.onPause()
        // Stop updates when fragment is not visible to save battery
        stopUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopUpdates()
        _binding = null
    }
}