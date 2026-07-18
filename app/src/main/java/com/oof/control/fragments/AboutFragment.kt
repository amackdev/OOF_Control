package com.oof.control.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
    }
    
    private fun loadDeviceInfo() {
        lifecycleScope.launch {
            // Device codename
            binding.tvDeviceCodename.text = DeviceConfig.deviceCodename.uppercase()
            
            // Brand
            binding.tvDeviceBrand.text = DeviceConfig.deviceBrand
            
            // Supported status
            if (DeviceConfig.isSupported) {
                binding.tvSupportStatus.text = "Device Supported"
                binding.tvSupportStatus.setTextColor(resources.getColor(com.oof.control.R.color.green, null))
            } else {
                binding.tvSupportStatus.text = "Unofficial Device"
                binding.tvSupportStatus.setTextColor(resources.getColor(com.oof.control.R.color.orange, null))
            }
            

        }
    }
    
    private fun setupSettings() {
        // Apply on boot switch
        binding.switchApplyOnBoot.isSaveEnabled = false
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

