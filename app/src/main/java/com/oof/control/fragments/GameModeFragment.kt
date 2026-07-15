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
import com.oof.control.utils.MiuiTouchFeature
import com.oof.control.utils.PrefsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private lateinit var seekTap: SeekBar
    private lateinit var tvTapVal: TextView
    private lateinit var seekTolerance: SeekBar
    private lateinit var tvToleranceVal: TextView
    private lateinit var seekEdge: SeekBar
    private lateinit var tvEdgeVal: TextView
    private lateinit var switchReportRate: MaterialSwitch
    private lateinit var layoutNoGames: LinearLayout
    private lateinit var layoutGameApps: RecyclerView
    private lateinit var gameAppAdapter: GameAppAdapter

    private var isBusy = false
    private val SEEKBAR_CHANGE_DELAY = 80L

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
    }

    // ── Bind views ─────────────────────────────────────────────────────────

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
        seekTap          = v.findViewById(R.id.seek_tap)
        tvTapVal         = v.findViewById(R.id.tv_tap_val)
        seekTolerance    = v.findViewById(R.id.seek_tolerance)
        tvToleranceVal   = v.findViewById(R.id.tv_tolerance_val)
        seekEdge         = v.findViewById(R.id.seek_edge)
        tvEdgeVal        = v.findViewById(R.id.tv_edge_val)
        switchReportRate = v.findViewById(R.id.switch_report_rate)
        layoutNoGames    = v.findViewById(R.id.layout_no_games)
        layoutGameApps   = v.findViewById(R.id.layout_game_apps)

        gameAppAdapter = GameAppAdapter(mutableListOf<GameAppEntry>()) { pkg ->
            prefs.removeGameApp(pkg)
            loadGameApps()
        }
        layoutGameApps.adapter = gameAppAdapter
    }

    // ── Load/Save profile ──────────────────────────────────────────────────

    private fun loadProfile() {
        val profile = GameModeProfile.fromJson(prefs.gameModeProfileJson) ?: GameModeProfile.DEFAULT
        switchGameMode.isChecked  = prefs.gameModeEnabled
        switchAutoGame.isChecked  = prefs.gameServiceEnabled
        seekActiveMode.progress   = profile.activeMode
        tvActiveModeVal.text      = profile.activeMode.toString()
        seekAim.progress          = profile.aimSensitivity
        tvAimVal.text             = profile.aimSensitivity.toString()
        seekTap.progress          = profile.tapStability
        tvTapVal.text             = profile.tapStability.toString()
        seekTolerance.progress    = profile.tolerance
        tvToleranceVal.text       = profile.tolerance.toString()
        seekEdge.progress         = profile.edgeFilter
        tvEdgeVal.text            = profile.edgeFilter.toString()
        switchReportRate.isChecked = profile.reportRate == 1

        updateStatusText(prefs.gameModeEnabled)
    }

    private fun buildProfileFromUI(): GameModeProfile = GameModeProfile(
        activeMode     = seekActiveMode.progress,
        upThreshold    = 0,
        tolerance      = seekTolerance.progress,
        aimSensitivity = seekAim.progress,
        tapStability   = seekTap.progress,
        edgeFilter     = seekEdge.progress,
        reportRate     = if (switchReportRate.isChecked) 1 else 0
    )

    private fun saveProfile() {
        prefs.gameModeProfileJson = buildProfileFromUI().toJson()
    }

    // ── Game Mode on/off ───────────────────────────────────────────────────

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
        if (!prefs.gameModeEnabled || isBusy) return
        // Re-apply live if game mode is currently active — writes gamemode.txt immediately
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                GameModeManager.applyProfile(profile)
            }
        }
    }

    // ── Auto-game service ──────────────────────────────────────────────────

    private fun setAutoGameService(enable: Boolean) {
        if (enable && !hasUsageStatsPermission()) {
            // Uncheck the switch and prompt the user
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

    // ── Game Apps list ─────────────────────────────────────────────────────

    private fun loadGameApps() {
        val apps = prefs.getGameApps()
        layoutNoGames.visibility = if (apps.isEmpty()) View.VISIBLE else View.GONE
        gameAppAdapter.updateApps(apps)
    }

    private fun showAppPickerDialog() {
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { getLauncherApps() }
            val alreadyAdded = prefs.getGameApps().map { it.packageName }.toSet()
            val available = apps.filter { it.applicationInfo.packageName !in alreadyAdded }

            if (available.isEmpty()) {
                showError("All installed apps are already added")
                return@launch
            }

            val labels = available.map { it.label.toString() }.toTypedArray()
            val checkedItems = BooleanArray(available.size) { false }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Select Game Apps")
                .setMultiChoiceItems(labels, checkedItems) { _, which, checked ->
                    checkedItems[which] = checked
                }
                .setPositiveButton("Add") { _, _ ->
                    available.forEachIndexed { i, info ->
                        if (checkedItems[i]) {
                            prefs.addGameApp(GameAppEntry(
                                packageName = info.applicationInfo.packageName,
                                label = labels[i],
                                profileJson = buildProfileFromUI().toJson()
                            ))
                        }
                    }
                    loadGameApps()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    /**
     * Uses LauncherApps API — same method used by launcher & thermal apps (e.g. MIUI Game Turbo).
     * This correctly lists ALL user-installed launchable apps across all profiles,
     * bypassing the MIUI/HyperOS app-hiding bug in getInstalledApplications().
     */
    private fun getLauncherApps(): List<LauncherActivityInfo> {
        val launcherApps = requireContext().getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
        val userManager = requireContext().getSystemService(Context.USER_SERVICE) as UserManager

        return userManager.userProfiles
            .flatMap { profile -> launcherApps.getActivityList(null, profile) }
            .filter { info ->
                // Exclude system apps — keep only user-installed launchable apps
                info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
            }
            .distinctBy { it.applicationInfo.packageName }
            .sortedBy { it.label.toString().lowercase() }
    }

    // ── Listeners ──────────────────────────────────────────────────────────

    private fun setupListeners() {
        switchGameMode.setOnCheckedChangeListener { _, checked ->
            applyGameMode(checked)
        }

        switchAutoGame.setOnCheckedChangeListener { _, checked ->
            setAutoGameService(checked)
        }

        view?.findViewById<View>(R.id.btn_add_game)?.setOnClickListener {
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
        seekTap.setOnSeekBarChangeListener(seekListener(tvTapVal) {})
        seekTolerance.setOnSeekBarChangeListener(seekListener(tvToleranceVal) {})
        seekEdge.setOnSeekBarChangeListener(seekListener(tvEdgeVal) {})

        switchReportRate.setOnCheckedChangeListener { _, _ -> applyParamChange() }
    }

    // ── UI helpers ─────────────────────────────────────────────────────────

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

    private fun showError(msg: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Error")
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    /**
     * Check if PACKAGE_USAGE_STATS (Usage Access) has been granted.
     * Mirrors the check pattern from XiaomiParts Thermal.
     * Uses AppOpsManager.checkOpNoThrow — the proper way to query this protected permission.
     */
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
