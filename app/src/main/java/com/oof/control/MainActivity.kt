package com.oof.control

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oof.control.databinding.ActivityMainBinding
import com.oof.control.fragments.*
import com.oof.control.services.ChargingControlService
import com.oof.control.utils.*
import kotlinx.coroutines.launch
	
class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    
    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val WRITE_SETTINGS_REQUEST_CODE = 1002
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply saved theme before super.onCreate()
        prefs = PrefsManager(this)
        prefs.applyTheme()
        
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            checkPermissions()
            setupBottomNavigation()
            
            if (savedInstanceState == null) {
                loadFragment(DisplayFragment())
            }
            
            // Check device support (non-blocking)
            checkDeviceSupport()
            
            // Start services if enabled (with delay to let UI load first)
            binding.root.postDelayed({
                try {
                    if (prefs.chargingServiceEnabled) {
                        ChargingControlService.startChargingOnly(this)
                    }
                    if (prefs.batteryStatsEnabled) {
                        ChargingControlService.startStatsOnly(this)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start services", e)
                }
            }, 1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    
    private fun checkPermissions() {
        // POST_NOTIFICATIONS for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
        
        // WRITE_SETTINGS for refresh rate control
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.System.canWrite(this)) {
                Log.i(TAG, "WRITE_SETTINGS permission not granted - showing dialog")
                showWriteSettingsDialog()
            }
        }
    }
    
    private fun showWriteSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("OOF Control needs permission to modify system settings for refresh rate control.\n\nThis is a standard Android permission.")
            .setPositiveButton("Grant Permission") { _, _ ->
                requestWriteSettingsPermission()
            }
            .setNegativeButton("Skip") { _, _ ->
                Toast.makeText(this, "Refresh rate control will be limited", Toast.LENGTH_LONG).show()
            }
            .show()
    }
    
    @Suppress("DEPRECATION")
    private fun requestWriteSettingsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            startActivityForResult(intent, WRITE_SETTINGS_REQUEST_CODE)
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (requestCode == WRITE_SETTINGS_REQUEST_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (Settings.System.canWrite(this)) {
                    Toast.makeText(this, "Permission granted - refresh rate control enabled", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Permission denied - refresh rate control limited", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
	private fun checkDeviceSupport() {
		try {
			if (!DeviceConfig.isSupported) {
				MaterialAlertDialogBuilder(this)
					.setTitle("Device Notice")
					.setMessage(
						"Your device (${DeviceConfig.deviceCodename}) may not be fully supported. " +
						"Some features might not work correctly.\n\n" +
						"Supported devices:\n" +
						"• POCO F6 (peridot)\n" +
						"• POCO F5 (marble)"
					)
					.setPositiveButton("Continue Anyway", null)
					.setNegativeButton("Exit") { _, _ -> finish() }
					.show()
			}
		} catch (e: Exception) {
			Log.e(TAG, "Error checking device support", e)
		}
	}

    private fun setupBottomNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_display -> DisplayFragment()
                R.id.nav_performance -> PerformanceFragment()
                R.id.nav_charging -> ChargingFragment()
                R.id.nav_about -> AboutFragment()
                else -> DisplayFragment()
            }
            loadFragment(fragment)
            true
        }
    }
    
    private fun loadFragment(fragment: Fragment) {
        try {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading fragment", e)
        }
    }
}