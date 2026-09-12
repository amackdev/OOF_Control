package com.oof.control.fragments

import android.net.Uri
import android.os.Bundle
import android.util.Base64
import android.util.Log
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.oof.control.R
import com.oof.control.databinding.FragmentDisplayBinding
import com.oof.control.utils.PrefsManager
import com.oof.control.utils.ShellExecutor
import com.oof.control.utils.TouchController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

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
		const val PROP_DISABLE_FLAG_SECURE = "persist.sys.oof_secureflag"

        // PIF Updater props
        const val PROP_UPDATE_FINGERPRINT = "persist.custom_pif.update"
        const val PROP_UPDATE_KEYBOX_PATH = "ro.custom_keybox.updatepath"
    }


    private val keyboxFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { handleKeyboxFileSelected(it) }
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
        
        // Prevent Android from restoring switch states and firing listeners on theme change
        binding.switchTouchRate.isSaveEnabled = false
        binding.switchPif.isSaveEnabled = false
        binding.switchBlspoof.isSaveEnabled = false
        binding.switchKeybox.isSaveEnabled = false
        binding.switchUnlimphotos.isSaveEnabled = false
        binding.switchSpoofProvider.isSaveEnabled = false
        binding.switchDisableFlagSecure.isSaveEnabled = false
        
        setupListeners()
        loadSavedStates()
    }
    
    private fun loadSavedStates() {
        isUpdatingUI = true
        
        try {
            // Load UI state from preferences (instant)
            updateRefreshRateButtons(prefs.refreshRate)
            
            // Touch Rate - check support in coroutine (hide completely on marble)
            if (com.oof.control.utils.DeviceConfig.deviceCodename == com.oof.control.utils.DeviceConfig.DEVICE_MARBLE) {
                binding.tvHeaderInteraction.visibility = View.GONE
                binding.cardTouchRate.visibility = View.GONE
                
                // Still run PIF and prop checks
                viewLifecycleOwner.lifecycleScope.launch {
                    checkPifImplementation()
                    loadPropStates()
                }
            } else {
                binding.tvHeaderInteraction.visibility = View.VISIBLE
                binding.cardTouchRate.visibility = View.VISIBLE
                viewLifecycleOwner.lifecycleScope.launch {
                    val touchRateSupported = TouchController.isTouchRateSupported()
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
            binding.cardSpoofProvider.visibility = visibility
            binding.cardDisableFlagSecure.visibility = visibility
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
        binding.cardSpoofProvider.visibility = View.GONE
        binding.cardDisableFlagSecure.visibility = View.GONE
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
            binding.switchSpoofProvider.isChecked = getPropBool(PROP_SPOOF_PROVIDER)
            binding.switchDisableFlagSecure.isChecked = getPropBool(PROP_DISABLE_FLAG_SECURE)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading prop states", e)
        } finally {
            isUpdatingUI = false
        }
    }
    
    private fun setupListeners() {
        // ============ TOUCH SETTINGS ============
        
        // Touch Rate Switch
        binding.switchTouchRate.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI || !binding.switchTouchRate.isEnabled) return@setOnCheckedChangeListener
            
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    if (!TouchController.isTouchRateSupported()) {
                        isUpdatingUI = true
                        binding.switchTouchRate.isChecked = false
                        binding.switchTouchRate.isEnabled = false
                        isUpdatingUI = false
                        showToast("Touch rate not available")
                        return@launch
                    }
                    
                    val success = TouchController.setTouchRate(isChecked)
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
        
        binding.toggleRefreshRate.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (isChecked && !isUpdatingUI) {
                val rate = when (checkedId) {
                    R.id.btn_60hz -> 60
                    R.id.btn_90hz -> 90
                    R.id.btn_120hz -> 120
                    else -> 60
                }
                binding.tvRefreshVal.text = "${rate}Hz"
                setRefreshRate(rate)
            }
        }
        
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
        binding.switchSpoofProvider.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setProp(PROP_SPOOF_PROVIDER, isChecked, "Spoof Provider")
        }
        
        // Disable Secure Flag
        binding.switchDisableFlagSecure.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUI) return@setOnCheckedChangeListener
            setFlagSecureProp(isChecked)
        }

        // ============ PIF UPDATER ============

        // Update Fingerprint
        binding.cardUpdateFingerprint.setOnClickListener {
            updateFingerprint()
        }

        // Update Keybox
        binding.cardUpdateKeybox.setOnClickListener {
            keyboxFilePicker.launch(arrayOf("application/xml", "text/xml"))
        }
    }
    
    private fun setRefreshRate(rate: Int) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = TouchController.setRefreshRate(requireContext(), rate)
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
        val buttonId = when(selectedRate) {
            60 -> R.id.btn_60hz
            90 -> R.id.btn_90hz
            120 -> R.id.btn_120hz
            else -> R.id.btn_60hz
        }
        isUpdatingUI = true
        binding.toggleRefreshRate.check(buttonId)
        isUpdatingUI = false
        binding.tvRefreshVal.text = "${selectedRate}Hz"
    }
    
    private fun setProp(propName: String, value: Boolean, displayName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = TouchController.setSystemProp(propName, value.toString())
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
    
    private fun setFlagSecureProp(enabled: Boolean) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val value = if (enabled) "1" else "0"
                val success = TouchController.setSystemProp(PROP_DISABLE_FLAG_SECURE, value)
                if (success) {
                    showToast("Screenshot Protection Disable ${if (enabled) "enabled" else "disabled"}")
                } else {
                    showToast("Failed to set Screenshot Protection Disable")
                    isUpdatingUI = true
                    binding.switchDisableFlagSecure.isChecked = !enabled
                    isUpdatingUI = false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting prop $PROP_DISABLE_FLAG_SECURE", e)
                showToast("Error: ${e.message}")
                isUpdatingUI = true
                binding.switchDisableFlagSecure.isChecked = !enabled
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
            PROP_SPOOF_PROVIDER -> binding.switchSpoofProvider.isChecked = value
        }
    }
    
    private suspend fun getPropBool(propName: String): Boolean {
        val value = TouchController.getSystemProp(propName)
        return value.equals("true", ignoreCase = true) || value == "1"
    }

    private fun updateFingerprint() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val success = TouchController.setSystemProp(PROP_UPDATE_FINGERPRINT, "true")
                if (success) {
                    showToast("Fingerprint update triggered")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        delay(10_000)
                        TouchController.setSystemProp(PROP_UPDATE_FINGERPRINT, "false")
                    }
                } else {
                    showToast("Failed to trigger fingerprint update")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating fingerprint", e)
                showToast("Error: ${e.message}")
            }
        }
    }

    private fun handleKeyboxFileSelected(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not persist URI permission", e)
            }

            try {
                val inputStream = requireContext().contentResolver.openInputStream(uri)
                val xmlContent = inputStream?.bufferedReader()?.use { it.readText() } ?: throw Exception("Failed to read file")
                inputStream?.close()

                val encryptedBase64 = Base64.encodeToString(xmlContent.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                val outputPath = "/sdcard/Download/Keylogger.zsl"

                val file = File(outputPath)
                file.writeText(encryptedBase64)

                binding.tvKeyboxPath.text = outputPath
                showToast("Keybox written to $outputPath")

                try {
                    TouchController.setSystemProp("persist.custom_keybox.state", "true")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        delay(10_000)
                        TouchController.setSystemProp("persist.custom_keybox.state", "false")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to toggle keybox state", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing keybox", e)
                showToast("Error: ${e.message}")
            }
        }
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