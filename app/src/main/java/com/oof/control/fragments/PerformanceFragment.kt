package com.oof.control.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.oof.control.databinding.FragmentPerformanceBinding
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.RootController
import kotlinx.coroutines.launch

class PerformanceFragment : Fragment() {
    
    private var _binding: FragmentPerformanceBinding? = null
    private val binding get() = _binding!!
    private lateinit var prefs: PrefsManager
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPerformanceBinding.inflate(inflater, container, false)
        return binding.root
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())
        
        loadCurrentStates()
        setupListeners()
    }
    
    private fun loadCurrentStates() {
        lifecycleScope.launch {
            // Performance Mode - read actual property
            val perfEnabled = RootController.getPerformanceMode()
            binding.switchPerformance.isChecked = perfEnabled
            prefs.performanceMode = perfEnabled
            updatePerformanceStatus(perfEnabled)
            
            // Touch Boost - read actual property
            val touchBoostEnabled = RootController.getTouchBoost()
            binding.switchTouchBoost.isChecked = touchBoostEnabled
            prefs.touchBoost = touchBoostEnabled
        }
    }
    
    private fun setupListeners() {
        // Performance Mode Switch - directly sets property
        binding.switchPerformance.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val success = RootController.setPerformanceMode(isChecked)
                if (success) {
                    prefs.performanceMode = isChecked
                    updatePerformanceStatus(isChecked)
                    showToast("Performance Mode ${if (isChecked) "enabled" else "disabled"}")
                } else {
                    binding.switchPerformance.isChecked = !isChecked
                    showToast("Failed to update Performance Mode")
                }
            }
        }
        
        // Touch Boost Switch - directly sets property
        binding.switchTouchBoost.setOnCheckedChangeListener { _, isChecked ->
            lifecycleScope.launch {
                val success = RootController.setTouchBoost(isChecked)
                if (success) {
                    prefs.touchBoost = isChecked
                    showToast("Touch Boost ${if (isChecked) "enabled" else "disabled"}")
                } else {
                    binding.switchTouchBoost.isChecked = !isChecked
                    showToast("Failed to update Touch Boost")
                }
            }
        }
    }
    
    private fun updatePerformanceStatus(enabled: Boolean) {
        binding.tvPerformanceStatus.text = if (enabled) "🔥 Active" else "😴 Disabled"
        binding.cardPerformance.alpha = if (enabled) 1.0f else 0.8f
        binding.tvPerformanceWarning.visibility = if (enabled) View.VISIBLE else View.GONE
    }
    
    private fun showToast(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
