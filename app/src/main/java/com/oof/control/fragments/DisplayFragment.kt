package com.oof.control.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.oof.control.databinding.FragmentDisplayBinding
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.launch

class DisplayFragment : Fragment() {
    
    private var _binding: FragmentDisplayBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    
    private var isUpdatingUI = false
    
    companion object {
        private const val TAG = "DisplayFragment"
        
        // Prop names
        const val PROP_PIF = "persist.sys.oof.pif"
        const val PROP_BLSPOOF = "persist.sys.oof.blspoof"
        const val PROP_KEYBOX = "persist.oof_keybox.enabled"
        const val PROP_UNLIM_PHOTOS = "persist.sys.oof-utils.unligphotos"
        const val PROP_SPOOF_PROVIDER = "persist.sys.oof-utils.spoofprovider"
        const val PROP_PIF_IMPLEMENTED = "ro.oof_pif.implemented"
    }
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDisplayBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        
        setupListeners()
        loadSavedStates()
    }
    
    private fun loadSavedStates() {
        isUpdatingUI = true
        
        try {
            // Load UI state from preferences (instant)
            binding.switchDt2w.isChecked = prefs.dt2wEnabled
            binding.switchTouchBoost.isChecked = prefs.touchBoost
            updateRefreshRateButtons(prefs.refreshRate)
            
            // Touch Rate - check support in coroutine
            viewLifecycleOwner.lifecycleScope.launch {
                val touchRateSupported = RootController.isTouchRateSupported()
                binding.switchTouchRate.isEnabled = touchRateSupported
                binding.cardTouchRate.alpha = if (touchRateSupported) 1.0f else 0.5f
                
                if (touchRateSupported) {
                    binding.switchTouchRate.isChecked = prefs.touchRateEnabled
                }
                
                // Check if PIF is implemented
                checkPifImplementation()
                
                // Load prop states
                loadPropStates()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading saved states", e)
        } finally {
            isUpdatingUI = false
        }
    }
    
    private suspend fun checkPifImplementation() {
        try {
            val pifImplemented = getPropBool(PROP_PIF_IMPLEMENTED)
            
            // Hide/show spoofing switches based on ro.oof_pif.implemented
            val visibility = if (pifImplemented) View.VISIBLE else View.GONE
            
            binding.cardPif.visibility = visibility
            binding.cardBlspoof.visibility = visibility
            binding.cardKeybox.visibility = visibility
            binding.cardSpoofprovider.visibility = visibility
            binding.cardUnlimphotos.visibility = visibility
            binding.cardSpoofInfo.visibility = visibility
            binding.tvSpoofingSection.visibility = visibility
            
            if (!pifImplemented) {
                Log.i(TAG, "PIF not implemented - spoofing switches hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PIF implementation", e)
            // On error, hide the switches to be safe
            hideSpoofingSwitches()
        }
    }
    
    private fun hideSpoofingSwitches() {
        binding.cardPif.visibility = View.GONE
        binding.cardBlspoof.visibility = View.GONE
        binding.cardKeybox.visibility = View.GONE
        binding.cardSpoofprovider.visibility = View.GONE
        binding.cardUnlimphotos.visibility = View.GONE
        binding.cardSpoofInfo.visibility = View.GONE
        binding.tvSpoofingSection.visibility = View.GONE
    }
    
    private suspend fun loadPropStates() {
        try {
            isUpdatingUI = true
            binding.switchPif.isChecked = getPropBool(PROP_PIF)
            binding.switchBlspoof.isChecked = getPropBool(PROP_BLSPOOF)
            binding.switchKeybox.isChecked = getPropBool(PROP_KEYBOX)
            binding.switchUnlimphotos.isChecked = getPropBool(PROP_UNLIM_PHOTOS)
            binding.switchSpoofprovider.isChecked = getPropBool(PROP_SPOOF_PROVIDER)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prop states", e)
        } finally {
            isUpdatingUI = false
        }
    }
    
    private fun setupListeners() {
        // ============ TOUCH SETTINGS ============
        
        // DT2W Switch
        binding.switchDt2w.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val success = RootController.setDT2W(isChecked)
                    if (success) {
                        prefs.dt2wEnabled = isChecked
                        showToast("Double Tap to Wake ${if (isChecked) "enabled" else "disabled"}")
                    } else {
                        isUpdatingUI = true
                        binding.switchDt2w.isChecked = !isChecked
                        isUpdatingUI = false
                        showToast("Failed to update DT2W")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting DT2W", e)
                    isUpdatingUI = true
                    binding.switchDt2w.isChecked = !isChecked
                    isUpdatingUI = false
                    showToast("Error: ${e.message}")
                }
            }
        }
        
        // Touch Rate Switch
        binding.switchTouchRate.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI || !binding.switchTouchRate.isEnabled) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (!RootController.isTouchRateSupported()) {
                        isUpdatingUI = true
                        binding.switchTouchRate.isChecked = false
                        binding.switchTouchRate.isEnabled = false
                        isUpdatingUI = false
                        showToast("Touch rate not available")
                        return@launch
                    }
                    
                    val success = RootController.setTouchRate(isChecked)
                    if (success) {
                        prefs.touchRateEnabled = isChecked
                        showToast("High Touch Rate ${if (isChecked) "enabled" else "disabled"}")
                    } else {
                        isUpdatingUI = true
                        binding.switchTouchRate.isChecked = !isChecked
                        isUpdatingUI = false
                        showToast("Failed to update Touch Rate")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting Touch Rate", e)
                    isUpdatingUI = true
                    binding.switchTouchRate.isChecked = !isChecked
                    isUpdatingUI = false
                    showToast("Error: ${e.message}")
                }
            }
        }
        
        // Touch Boost Switch
        binding.switchTouchBoost.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val success = RootController.setTouchBoost(isChecked)
                    if (success) {
                        prefs.touchBoost = isChecked
                        showToast("Touch Boost ${if (isChecked) "enabled" else "disabled"}")
                    } else {
                        isUpdatingUI = true
                        binding.switchTouchBoost.isChecked = !isChecked
                        isUpdatingUI = false
                        showToast("Failed to update Touch Boost")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error setting Touch Boost", e)
                    isUpdatingUI = true
                    binding.switchTouchBoost.isChecked = !isChecked
                    isUpdatingUI = false
                    showToast("Error: ${e.message}")
                }
            }
        }
        
        // Refresh Rate Buttons
        binding.btn60hz.setOnClickListener { setRefreshRate(60) }
        binding.btn90hz.setOnClickListener { setRefreshRate(90) }
        binding.btn120hz.setOnClickListener { setRefreshRate(120) }
        
        // ============ SPOOFING PROPS ============
        
        // PIF Spoof
        binding.switchPif.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_PIF, isChecked, "PIF Spoof")
        }
        
        // BLS Spoof
        binding.switchBlspoof.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_BLSPOOF, isChecked, "BLS Spoof")
        }
        
        // Keybox Spoof
        binding.switchKeybox.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_KEYBOX, isChecked, "Keybox Spoof")
        }
        
        // Unlimited Photos
        binding.switchUnlimphotos.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_UNLIM_PHOTOS, isChecked, "Unlimited Photos")
        }
        
        // Spoof Provider
        binding.switchSpoofprovider.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_SPOOF_PROVIDER, isChecked, "Spoof Provider")
        }
    }
    
    private fun setRefreshRate(rate: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = RootController.setRefreshRate(requireContext(), rate)
                if (success) {
                    prefs.refreshRate = rate
                    updateRefreshRateButtons(rate)
                    showToast("Refresh rate set to ${rate}Hz")
                } else {
                    showToast("Failed to set refresh rate")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting refresh rate", e)
                showToast("Error: ${e.message}")
            }
        }
    }
    
    private fun updateRefreshRateButtons(selectedRate: Int) {
        if (_binding == null) return
        binding.btn60hz.isSelected = selectedRate == 60
        binding.btn90hz.isSelected = selectedRate == 90
        binding.btn120hz.isSelected = selectedRate == 120
        binding.tvCurrentRr.text = "${selectedRate}Hz"
    }
    
    private fun setProp(propName: String, value: Boolean, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = RootController.setSystemProp(propName, value.toString())
                if (success) {
                    showToast("$displayName ${if (value) "enabled" else "disabled"}")
                } else {
                    showToast("Failed to set $displayName")
                    // Revert switch
                    isUpdatingUI = true
                    revertSwitch(propName, !value)
                    isUpdatingUI = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting prop $propName", e)
                showToast("Error: ${e.message}")
                // Revert switch
                isUpdatingUI = true
                revertSwitch(propName, !value)
                isUpdatingUI = false
            }
        }
    }
    
    private fun revertSwitch(propName: String, value: Boolean) {
        when (propName) {
            PROP_PIF -> binding.switchPif.isChecked = value
            PROP_BLSPOOF -> binding.switchBlspoof.isChecked = value
            PROP_KEYBOX -> binding.switchKeybox.isChecked = value
            PROP_UNLIM_PHOTOS -> binding.switchUnlimphotos.isChecked = value
            PROP_SPOOF_PROVIDER -> binding.switchSpoofprovider.isChecked = value
        }
    }
    
    private suspend fun getPropBool(propName: String): Boolean {
        val value = RootController.getSystemProp(propName)
        return value.equals("true", ignoreCase = true) || value == "1"
    }
    
    private fun showToast(message: String) {
        if (isAdded && context != null) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Don't reload - settings are already applied in background
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}