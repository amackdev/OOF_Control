package com.oof.control.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.oof.control.databinding.FragmentAboutBinding
import com.oof.control.utils.ChargingController
import com.oof.control.utils.DeviceConfig
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.TouchController
import kotlinx.coroutines.launch

class AboutFragment : Fragment() {
    
    private var _binding: FragmentAboutBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAboutBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        
        loadDeviceInfo()
        setupSettings()
        setupTheme()
    }
    
    private fun setupTheme() {
        binding.tvCurrentTheme.text = prefs.getThemeModeDisplayName()
        
        binding.btnThemeSystem.setOnClickListener {
            setTheme(PrefsManager.THEME_SYSTEM)
        }
        binding.btnThemeLight.setOnClickListener {
            setTheme(PrefsManager.THEME_LIGHT)
        }
        binding.btnThemeDark.setOnClickListener {
            setTheme(PrefsManager.THEME_DARK)
        }
    }
    
    private fun setTheme(mode: Int) {
        prefs.themeMode = mode
        binding.tvCurrentTheme.text = prefs.getThemeModeDisplayName()
        
        val nightMode = when (mode) {
            PrefsManager.THEME_LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            PrefsManager.THEME_DARK -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
    
    private fun loadDeviceInfo() {
        lifecycleScope.launch {
            // Device codename
            binding.tvDeviceCodename.text = DeviceConfig.deviceCodename.uppercase()
            
            // Brand
            binding.tvDeviceBrand.text = DeviceConfig.deviceBrand
            
            // Supported status
            if (DeviceConfig.isSupported) {
                binding.tvSupportStatus.text = "✓ Fully Supported"
                binding.tvSupportStatus.setTextColor(com.google.android.material.color.MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorPrimary))
            } else {
                binding.tvSupportStatus.text = "⚠ Partial Support"
                binding.tvSupportStatus.setTextColor(resources.getColor(android.R.color.holo_orange_light, null))
            }
            
            // Highlight current device in list
            when (DeviceConfig.deviceCodename.lowercase()) {
                DeviceConfig.DEVICE_PERIDOT -> binding.tvDevicePeridot.alpha = 1.0f
                DeviceConfig.DEVICE_MARBLE -> binding.tvDeviceMarble.alpha = 1.0f
            }
            
            // Feature support
            binding.tvTouchRateSupport.text = if (TouchController.isTouchRateSupported()) "✓" else "✗"
            binding.tvSportModeSupport.text = if (ChargingController.isSportModeSupported()) "✓" else "✗"
        }
    }
    
    private fun setupSettings() {
        // Apply on boot switch
        binding.switchApplyOnBoot.isChecked = prefs.applyOnBoot
        binding.switchApplyOnBoot.setOnCheckedChangeListener { _, isChecked ->
            prefs.applyOnBoot = isChecked
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
