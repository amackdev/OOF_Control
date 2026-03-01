package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.runBlocking

/**
 * Applies and restores game mode touch parameters.
 *
 * SET_LONG_VALUE usage (CMD_SET_LONG_VALUE = 7 in common_data_t.cmd):
 *   The kernel's xiaomi_touch_set_mode_long_value() handles:
 *     - DATA_MODE_15 → grip/corner zone grid (96 ints), BLOCKED when game mode ON
 *     - DATA_MODE_32 → FOD (sensorhub builds only)
 *
 *   Therefore our grip zone pre-configuration uses SET_LONG_VALUE → DATA_MODE_15
 *   BEFORE enabling game mode (step 1 below). Game mode parameters 0-9 use SET_CUR_VALUE.
 *
 *   All other game params (DATA_MODE_0..9) use SET_CUR_VALUE as required by the kernel's
 *   xiaomi_touch_set_mode_value() dispatch table.
 */
object GameModeManager {

    private const val TAG = "GameModeManager"
    private const val TOUCH_ID = 0  // primary panel

    @Volatile private var savedParams: IntArray? = null
    @Volatile private var gameModeActive = false

    /**
     * Enable game mode for the given profile.
     * Order matters — see kernel xiaomi_touch_cmd_update_work():
     *   1. (Optional) Push grip zone via SET_LONG_VALUE before enabling game mode
     *   2. Enable game mode master switch (DATA_MODE_0 = 1)
     *   3. Push all profile params; kernel batches them through cmd_update_work
     */
    fun enable(profile: GameModeProfile): Boolean {
        if (gameModeActive) return true
        Log.i(TAG, "Enabling game mode: $profile")

        saveCurrentParams()

        // Step 1: Apply grip zone config via SET_LONG_VALUE → DATA_MODE_15
        // This must happen BEFORE game mode is ON (kernel blocks it otherwise).
        // We send a standard landscape-friendly grip array; feel free to tune.
        applyGripZoneLongValue(profile.edgeFilter)

        // Step 2: Enable game mode master switch via SET_CUR_VALUE
        val gameModeOk = setMode(TouchConstants.MODE_GAME_MODE, 1)
        if (!gameModeOk) {
            Log.e(TAG, "Failed to enable game mode master switch")
            return false
        }

        // Step 3: Push all profile parameters — kernel's cmd_update_work will
        // batch-process them and call cmd_update_func + trigger grid rebuild.
        setMode(TouchConstants.MODE_ACTIVE,        profile.activeMode)
        setMode(TouchConstants.MODE_UP_THRESHOLD,  profile.upThreshold)
        setMode(TouchConstants.MODE_TOLERANCE,     profile.tolerance)
        setMode(TouchConstants.MODE_AIM_SENSITIVITY, profile.aimSensitivity)
        setMode(TouchConstants.MODE_TAP_STABILITY, profile.tapStability)
        setMode(TouchConstants.MODE_EDGE_FILTER,   profile.edgeFilter)
        setMode(TouchConstants.MODE_REPORT_RATE,   profile.reportRate)

        gameModeActive = true
        Log.i(TAG, "Game mode ENABLED")
        return true
    }

    /**
     * Disable game mode and restore pre-game-mode params.
     * Sends RESET_MODE for mode 0 (resets all game params back to DTS defaults).
     */
    fun disable(): Boolean {
        if (!gameModeActive) return true
        Log.i(TAG, "Disabling game mode")

        // Reset DATA_MODE_0 to 0 — kernel's reset_mode(mode=0) restores all sub-modes
        val ok = runCatching {
            runBlocking { IoctlBridge.resetMode(TOUCH_ID, TouchConstants.MODE_GAME_MODE) }.ok
        }.getOrDefault(false)

        if (ok) {
            gameModeActive = false
            savedParams = null
            Log.i(TAG, "Game mode DISABLED")
        } else {
            Log.e(TAG, "Failed to disable game mode via resetMode")
            // Fallback: force DATA_MODE_0 = 0
            setMode(TouchConstants.MODE_GAME_MODE, 0)
            gameModeActive = false
        }
        return ok
    }

    fun isGameModeActive(): Boolean = gameModeActive

    // ─── SET_LONG_VALUE for grip zone (DATA_MODE_15) ─────────────────────────
    /**
     * Push grip/corner zone configuration via SET_LONG_VALUE → DATA_MODE_15.
     *
     * The kernel expects GRIP_RECT_NUM(12) * GRIP_PARAMETER_NUM(8) = 96 s32 values:
     *   [0..31]   = deadzone_filter
     *   [32..63]  = edgezone_filter
     *   [64..95]  = cornerzone_filter
     *
     * These values only take effect when game mode is OFF (kernel check).
     * When game mode is ON, the kernel uses its own DTS-backed values.
     * We therefore call this before enabling game mode.
     *
     * @param edgeLevel  0..3, scales how aggressively edges are rejected
     */
    private fun applyGripZoneLongValue(edgeLevel: Int) {
        Log.d(TAG, "applyGripZoneLongValue edgeLevel=$edgeLevel")
        val grip = buildGripArray(edgeLevel)
        val result = runCatching {
            runBlocking { IoctlBridge.setModeLong(TOUCH_ID, TouchConstants.MODE_GRIP_LONG, grip) }
        }.getOrNull()
        if (result?.ok == true) Log.i(TAG, "SET_LONG_VALUE grip zone applied")
        else Log.w(TAG, "SET_LONG_VALUE grip zone failed (may be suppressed by kernel if game mode already ON): ${result?.raw}")
    }

    /**
     * Build a 96-element grip zone array scaled by edgeLevel (0..3).
     * Values are approximate; the kernel will use its DTS data once game mode is ON.
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

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun setMode(mode: Int, value: Int): Boolean = runCatching {
        runBlocking { IoctlBridge.setMode(TOUCH_ID, mode, value) }.ok
    }.getOrDefault(false)

    private fun saveCurrentParams() {
        val saved = IntArray(10)
        for (mode in 0..9) {
            saved[mode] = MiuiTouchFeature.getModeValue(TOUCH_ID, mode).coerceAtLeast(0)
        }
        savedParams = saved
        Log.d(TAG, "Saved current params: ${saved.take(10)}")
    }
}
