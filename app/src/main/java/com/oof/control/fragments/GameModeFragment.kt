package com.oof.control.fragments

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.oof.control.adapters.GameAppAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.oof.control.R
import com.oof.control.services.GameModeService
import com.oof.control.utils.GameAppEntry
import com.oof.control.utils.GameModeManager
import com.oof.control.utils.GameModeProfile
import com.oof.control.utils.PrefsManager
import kotlinx.coroutines.*

class GameModeFragment : Fragment() {

    private lateinit var prefs: PrefsManager

    // Views
    private lateinit var switchGameMode: MaterialSwitch
    private lateinit var switchAutoGame: MaterialSwitch
    private lateinit var tvStatus: TextView
    private lateinit var layoutActiveBadge: LinearLayout
    private lateinit var tvActiveGame: TextView
    private lateinit var seekActiveMode: SeekBar
    private lateinit var tvActiveModeVal: TextView
    private lateinit var seekAim: SeekBar
    private lateinit var tvAimVal: TextView
    private lateinit var seekFollow: SeekBar
    private lateinit var tvFollowVal: TextView
    private lateinit var layoutGameApps: RecyclerView
    private lateinit var btnAddGame: View
    private lateinit var gameAppAdapter: GameAppAdapter

    private var currentProfile: GameModeProfile = GameModeProfile.DEFAULT
    private var isBusy = false
    private var activePkgJob: Job? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_game_mode, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = PrefsManager(requireContext())

        bindViews(view)
        loadProfile()
        loadGameApps()
        updateActiveBadge()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateActiveBadge()
        startActivePkgMonitor()
    }

    override fun onPause() {
        stopActivePkgMonitor()
        super.onPause()
    }

    private fun bindViews(v: View) {
        switchGameMode   = v.findViewById(R.id.switch_game_mode)
        switchAutoGame   = v.findViewById(R.id.switch_auto_game)
        tvStatus         = v.findViewById(R.id.tv_game_mode_status)
        layoutActiveBadge = v.findViewById(R.id.layout_active_badge)
        tvActiveGame     = v.findViewById(R.id.tv_active_game)
        seekActiveMode   = v.findViewById(R.id.seek_active_mode)
        tvActiveModeVal  = v.findViewById(R.id.tv_active_mode_val)
        seekAim          = v.findViewById(R.id.seek_aim)
        tvAimVal         = v.findViewById(R.id.tv_aim_val)
        seekFollow       = v.findViewById(R.id.seek_follow)
        tvFollowVal      = v.findViewById(R.id.tv_follow_val)
        layoutGameApps   = v.findViewById(R.id.rv_games)
        btnAddGame       = v.findViewById(R.id.btn_add_game)

        gameAppAdapter = GameAppAdapter(mutableListOf()) { pkg ->
            prefs.removeGameApp(pkg)
            loadGameApps()
        }
        layoutGameApps.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        layoutGameApps.adapter = gameAppAdapter
    }

    private fun loadProfile() {
        currentProfile = GameModeProfile.fromJson(prefs.gameModeProfileJson) ?: GameModeProfile.DEFAULT
        switchGameMode.isChecked  = prefs.gameModeEnabled
        switchAutoGame.isChecked  = prefs.gameServiceEnabled
        seekActiveMode.progress   = currentProfile.activeMode
        tvActiveModeVal.text      = currentProfile.activeMode.toString()
        seekAim.progress          = currentProfile.aimSensitivity
        tvAimVal.text             = currentProfile.aimSensitivity.toString()
        seekFollow.progress       = currentProfile.tapStability
        tvFollowVal.text          = currentProfile.tapStability.toString()

        updateStatusText(prefs.gameModeEnabled)
    }

    private fun buildProfileFromUI(): GameModeProfile = currentProfile.copy(
        activeMode     = seekActiveMode.progress,
        aimSensitivity = seekAim.progress,
        tapStability   = seekFollow.progress
    )

    private fun saveProfile() {
        currentProfile = buildProfileFromUI()
        prefs.gameModeProfileJson = currentProfile.toJson()
    }

    private fun applyGameMode(enable: Boolean) {
        if (isBusy) return
        isBusy = true
        val profile = buildProfileFromUI()

        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (enable) GameModeManager.enable(profile)
                else GameModeManager.disable()
            }
            isBusy = false
            if (!ok) {
                switchGameMode.isChecked = !enable
                showError(if (enable) "Failed to enable Game Mode" else "Failed to disable Game Mode")
            } else {
                prefs.gameModeEnabled = enable
                updateStatusText(enable)
                saveProfile()
            }
        }
    }

    private fun applyParamChange() {
        val profile = buildProfileFromUI()
        saveProfile()
        // If either manual Game Mode is on, OR auto game service is running and a game is active:
        val shouldApplyLive = prefs.gameModeEnabled || (prefs.gameServiceEnabled && GameModeService.activeGamePackage != null)
        if (!shouldApplyLive || isBusy) return

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                GameModeManager.applyProfile(profile)
            }
        }
    }

    private fun setAutoGameService(enable: Boolean) {
        if (enable && !hasUsageStatsPermission()) {
            switchAutoGame.isChecked = false
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Permission Required")
                .setMessage(
                    "Auto-detect games requires the 'Usage Access' permission.\n\n" +
                    "Grant it in:\nSettings → Apps → Special app access → Usage access → OOF Control\n\n" +
                    "Or via adb:\nadb shell appops set com.oof.control GET_USAGE_STATS allow"
                )
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
            return
        }
        prefs.gameServiceEnabled = enable
        val intent = Intent(requireContext(), GameModeService::class.java)
        if (enable) {
            intent.action = GameModeService.ACTION_START
            requireContext().startForegroundService(intent)
        } else {
            intent.action = GameModeService.ACTION_STOP
            requireContext().startService(intent)
        }
    }

    private fun loadGameApps() {
        val apps = prefs.getGameApps()
        gameAppAdapter.updateApps(apps)
    }

    private fun showAppPickerDialog() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { getLauncherApps() }
            val alreadyAdded = prefs.getGameApps().map { it.packageName }.toSet()
            val available = apps.filter { it.packageName !in alreadyAdded }

            if (available.isEmpty()) {
                showError("All installed apps are already added")
                return@launch
            }

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_game, null)
            val rvPicker = dialogView.findViewById<RecyclerView>(R.id.rv_game_picker)
            val btnAdd = dialogView.findViewById<View>(R.id.btn_add)
            val btnCancel = dialogView.findViewById<View>(R.id.btn_cancel)

            rvPicker.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(requireContext())
            val adapter = com.oof.control.adapters.GamePickerAdapter(available, requireContext().packageManager)
            rvPicker.adapter = adapter

            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setView(dialogView)
                .create()
            
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

            btnCancel.setOnClickListener {
                dialog.dismiss()
            }

            btnAdd.setOnClickListener {
                val profileJson = buildProfileFromUI().toJson()
                val pm = requireContext().packageManager
                available.forEachIndexed { i, info ->
                    if (adapter.checkedItems[i]) {
                        prefs.addGameApp(GameAppEntry(
                            packageName = info.packageName,
                            label = info.loadLabel(pm).toString(),
                            profileJson = profileJson
                        ))
                    }
                }
                loadGameApps()
                dialog.dismiss()
            }

            dialog.show()
        }
    }

    private fun getLauncherApps(): List<ApplicationInfo> {
        val pm = requireContext().packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.map { it.activityInfo.applicationInfo }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .distinctBy { it.packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }
    }

    private fun setupListeners() {
        switchGameMode.setOnCheckedChangeListener { _, checked ->
            applyGameMode(checked)
        }

        switchAutoGame.setOnCheckedChangeListener { _, checked ->
            setAutoGameService(checked)
        }

        btnAddGame.setOnClickListener {
            showAppPickerDialog()
        }

        fun seekListener(tv: TextView, onChange: (Int) -> Unit) = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) { tv.text = progress.toString(); onChange(progress) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) { applyParamChange() }
        }

        seekActiveMode.setOnSeekBarChangeListener(seekListener(tvActiveModeVal) {})
        seekAim.setOnSeekBarChangeListener(seekListener(tvAimVal) {})
        seekFollow.setOnSeekBarChangeListener(seekListener(tvFollowVal) {})
    }

    private fun updateStatusText(enabled: Boolean) {
        tvStatus.text = if (enabled) "Active — touch optimised for gaming" else "Optimise touch for gaming"
    }

    private fun updateActiveBadge() {
        val activeApp = GameModeService.activeGamePackage
        if (activeApp != null) {
            layoutActiveBadge.visibility = View.VISIBLE
            val label = try {
                requireContext().packageManager.getApplicationLabel(
                    requireContext().packageManager.getApplicationInfo(activeApp, 0)
                ).toString()
            } catch (e: Exception) { activeApp }
            tvActiveGame.text = "Game active: $label"
        } else {
            layoutActiveBadge.visibility = View.GONE
        }
    }

    private fun startActivePkgMonitor() {
        activePkgJob = lifecycleScope.launch {
            var lastActive: String? = null
            while (isActive) {
                val currentActive = GameModeService.activeGamePackage
                if (currentActive != lastActive) {
                    lastActive = currentActive
                    updateActiveBadge()
                }
                delay(1000)
            }
        }
    }

    private fun stopActivePkgMonitor() {
        activePkgJob?.cancel()
        activePkgJob = null
    }

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = requireContext().getSystemService(android.app.AppOpsManager::class.java)
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            requireContext().packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }
}
