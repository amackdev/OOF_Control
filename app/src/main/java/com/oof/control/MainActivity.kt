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
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.oof.control.databinding.ActivityMainBinding
import com.oof.control.fragments.*
import com.oof.control.services.GameModeService
import com.oof.control.services.ChargingControlService
import com.oof.control.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val WRITE_SETTINGS_REQUEST_CODE = 1002
    }

    private val navOrder = listOf(R.id.nav_display, R.id.nav_performance, R.id.nav_game_mode, R.id.nav_charging, R.id.nav_about)
    private var currentNavId = R.id.nav_display
    private val fragments = mutableMapOf<Int, Fragment>()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        prefs = PrefsManager(this)
        prefs.applyTheme()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            // Fragment container: top padding = status bar height
            ViewCompat.setOnApplyWindowInsetsListener(binding.fragmentContainer) { view, insets ->
                val statusBar = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                view.setPadding(0, statusBar, 0, 0)
                insets
            }

            // Bottom nav card: adjust bottom margin to float above system navigation bar
            ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavCard) { view, insets ->
                val navBar = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                val layoutParams = view.layoutParams as androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams
                val baseMargin = (24 * view.resources.displayMetrics.density).toInt()
                layoutParams.bottomMargin = baseMargin + navBar
                view.layoutParams = layoutParams
                insets
            }

            checkPermissions()
            setupBottomNav()

            if (savedInstanceState == null) {
                loadFragment(R.id.nav_display, { DisplayFragment() }, animate = false, forward = true)
            }

            checkDeviceSupport()

            lifecycleScope.launch {
                delay(1000)
                try {
                    if (prefs.chargingServiceEnabled) ChargingControlService.startChargingOnly(this@MainActivity)
                    if (prefs.batteryStatsEnabled) ChargingControlService.startStatsOnly(this@MainActivity)
                    if (prefs.gameServiceEnabled) {
                        val gmIntent = Intent(this@MainActivity, GameModeService::class.java).apply {
                            action = GameModeService.ACTION_START
                        }
                        startForegroundService(gmIntent)
                    }
                } catch (e: Exception) { Log.e(TAG, "Failed to start services", e) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate", e)
            Toast.makeText(this, "Error initializing app: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupBottomNav() {
        val navItems = listOf(
            NavigationItem(R.id.nav_display, binding.navDisplay, binding.navIndicatorDisplay, binding.navIconDisplay) { DisplayFragment() },
            NavigationItem(R.id.nav_performance, binding.navPerformance, binding.navIndicatorPerformance, binding.navIconPerformance) { PerformanceFragment() },
            NavigationItem(R.id.nav_game_mode, binding.navGameMode, binding.navIndicatorGameMode, binding.navIconGameMode) { GameModeFragment() },
            NavigationItem(R.id.nav_charging, binding.navCharging, binding.navIndicatorCharging, binding.navIconCharging) { ChargingFragment() },
            NavigationItem(R.id.nav_about, binding.navAbout, binding.navIndicatorAbout, binding.navIconAbout) { AboutFragment() }
        )

        navItems.forEach { item ->
            item.button.setOnClickListener {
                if (item.id == currentNavId) return@setOnClickListener
                
                val forward = navOrder.indexOf(item.id) > navOrder.indexOf(currentNavId)
                currentNavId = item.id
                
                // Update UI selection states
                navItems.forEach { other ->
                    val isActive = other.id == item.id
                    other.indicator.visibility = if (isActive) android.view.View.VISIBLE else android.view.View.INVISIBLE
                    other.icon.imageTintList = ContextCompat.getColorStateList(
                        this,
                        if (isActive) R.color.text_primary else R.color.text_secondary
                    )
                }

                // Load fragment
                loadFragment(item.id, item.fragmentFactory, animate = true, forward = forward)
            }
        }
    }

    private data class NavigationItem(
        val id: Int,
        val button: android.view.View,
        val indicator: android.view.View,
        val icon: android.widget.ImageView,
        val fragmentFactory: () -> Fragment
    )

    private fun loadFragment(id: Int, factory: (() -> Fragment)? = null, animate: Boolean = true, forward: Boolean = true) {
        try {
            val transaction = supportFragmentManager.beginTransaction()
            if (animate) {
                if (forward) {
                    transaction.setCustomAnimations(R.anim.fragment_slide_in_right, R.anim.fragment_slide_out_left)
                } else {
                    transaction.setCustomAnimations(R.anim.fragment_slide_in_left, R.anim.fragment_slide_out_right)
                }
            }
            
            // Hide all current fragments
            fragments.values.forEach { transaction.hide(it) }
            
            // Show or add the requested fragment
            var fragment = fragments[id]
            if (fragment == null && factory != null) {
                fragment = factory()
                fragments[id] = fragment
                transaction.add(R.id.fragment_container, fragment, id.toString())
            }
            
            fragment?.let { transaction.show(it) }
            transaction.commit()
        } catch (e: Exception) { Log.e(TAG, "Error loading fragment", e) }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), PERMISSION_REQUEST_CODE)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(this)) {
            showWriteSettingsDialog()
        }
    }

    private fun showWriteSettingsDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Permission Required")
            .setMessage("OOF Control needs permission to modify system settings for refresh rate control.")
            .setPositiveButton("Grant Permission") { _, _ -> requestWriteSettingsPermission() }
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
        if (requestCode == WRITE_SETTINGS_REQUEST_CODE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val msg = if (Settings.System.canWrite(this)) "Permission granted" else "Permission denied"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkDeviceSupport() {
        try {
            if (!DeviceConfig.isSupported) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Device Notice")
                    .setMessage("Your device (${DeviceConfig.deviceCodename}) may not be fully supported.")
                    .setPositiveButton("Continue Anyway", null)
                    .setNegativeButton("Exit") { _, _ -> finish() }
                    .show()
            }
        } catch (e: Exception) { Log.e(TAG, "Error checking device support", e) }
    }
}
