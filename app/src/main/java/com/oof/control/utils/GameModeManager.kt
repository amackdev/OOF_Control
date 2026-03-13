package com.oof.control.utils

import android.util.Log

/**
 * Applies and restores game mode touch parameters.
 *
 * Modified to construct the desired configuration block and write it to a daemon-watched
 * text file rather than calling direct JNI ioctls.
 */
object GameModeManager {

    private const val TAG = "GameModeManager"
    private const val TOUCH_ID = 0  // primary panel

    @Volatile private var savedParams: IntArray? = null
    @Volatile private var gameModeActive = false

    /**
     * Enable game mode for the given profile.
     * We batch all settings into a single write request for the daemon.
     */
    fun enable(profile: GameModeProfile): Boolean {
        if (gameModeActive) return true
        Log.i(TAG, "Enabling game mode: $profile")

        saveCurrentParams()
        writeProfile(profile)

        gameModeActive = true
        Log.i(TAG, "Game mode ENABLED")
        return true
    }

    /**
     * Apply updated parameters while game mode is already active.
     * Unlike enable(), this always writes the config file — used for live
     * seekbar/slider changes in the UI without toggling game mode off/on.
     */
    fun applyProfile(profile: GameModeProfile) {
        if (!gameModeActive) return   // only relevant when game mode is ON
        Log.i(TAG, "Applying live profile update")
        writeProfile(profile)
    }

    /** Shared config writer used by both enable() and applyProfile(). */
    private fun writeProfile(profile: GameModeProfile) {
        val currentState = mutableMapOf<Int, Int>()

        // Push game mode master switch ON
        currentState[TouchConstants.MODE_GAME_MODE] = 1

        // Push all profile parameters
        currentState[TouchConstants.MODE_ACTIVE]          = profile.activeMode
        currentState[TouchConstants.MODE_UP_THRESHOLD]    = profile.upThreshold
        currentState[TouchConstants.MODE_TOLERANCE]       = profile.tolerance
        currentState[TouchConstants.MODE_AIM_SENSITIVITY] = profile.aimSensitivity
        currentState[TouchConstants.MODE_TAP_STABILITY]   = profile.tapStability
        currentState[TouchConstants.MODE_EDGE_FILTER]     = profile.edgeFilter
        currentState[TouchConstants.MODE_REPORT_RATE]     = profile.reportRate

        // Build the grip zone array
        val grip = buildGripArray(profile.edgeFilter)

        // Atomically write config via DaemonConfigWriter
        DaemonConfigWriter.writeConfig(currentState, grip)

        // Signal game mode state to rest of system
        ShellExecutor.setPropertySync("persist.oofcontrol_gamemode", "1")
    }

    /**
     * Disable game mode and restore pre-game-mode params.
     * Sends reset mapping (e.g. MODE_GAME_MODE 0) to daemon.
     */
    fun disable(): Boolean {
        if (!gameModeActive) return true
        Log.i(TAG, "Disabling game mode")

        val currentState = mutableMapOf<Int, Int>()
        currentState[TouchConstants.MODE_GAME_MODE] = 0

        val ok = runCatching {
            DaemonConfigWriter.writeConfig(currentState, null)
            true
        }.getOrDefault(false)

        if (ok) {
            gameModeActive = false
            savedParams = null
            // Signal game mode disabled
            ShellExecutor.setPropertySync("persist.oofcontrol_gamemode", "0")
            Log.i(TAG, "Game mode DISABLED")
        } else {
            Log.e(TAG, "Failed to disable game mode via daemon config write")
            gameModeActive = false
        }
        return ok
    }

    fun isGameModeActive(): Boolean = gameModeActive

    // ─── Grip Zone (DATA_MODE_15) ──────────────────────────────────────────

    /**
     * Build a 96-element grip zone array scaled by edgeLevel (0..3).
     */
    private fun buildGripArray(edgeLevel: Int): IntArray {
        val deadW = intArrayOf(0, 40, 60, 80)[edgeLevel.coerceIn(0, 3)]
        val edgeW  = intArrayOf(0, 80, 100, 120)[edgeLevel.coerceIn(0, 3)]
        val corrW  = intArrayOf(0, 100, 150, 200)[edgeLevel.coerceIn(0, 3)]

        val arr = IntArray(96)
        // Deadzone block [0..31]: 4 rects × 8 params (x,y,w,h,...)
        for (i in 0..31) arr[i] = if (i % 4 == 2) deadW else 0
        // Edgezone block [32..63]
        for (i in 32..63) arr[i] = if (i % 4 == 2) edgeW else 0
        // Cornerzone block [64..95]
        for (i in 64..95) arr[i] = if (i % 4 == 2 || i % 4 == 3) corrW else 0
        return arr
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private fun saveCurrentParams() {
        val saved = IntArray(10)
        for (mode in 0..9) {
            saved[mode] = MiuiTouchFeature.getModeValue(TOUCH_ID, mode).coerceAtLeast(0)
        }
        savedParams = saved
        Log.d(TAG, "Saved current params: \${saved.take(10)}")
    }
}
