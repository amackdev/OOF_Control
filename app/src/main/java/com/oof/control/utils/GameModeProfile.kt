package com.oof.control.utils

import android.content.Context
import org.json.JSONObject

/**
 * Represents a complete touch game-mode parameter set.
 *
 * All values are sent via SET_CUR_VALUE to the kernel (DATA_MODE_0..9).
 * Grip zone overrides use SET_LONG_VALUE → DATA_MODE_15 (only when game mode OFF,
 * then we re-enable game mode; kernel blocks DATA_MODE_15 while game mode is active).
 *
 * Kernel mode mapping (xiaomi_touch_type_common.h / xiaomi_touch_mode.c):
 *   DATA_MODE_0 = Game Mode master switch     (0=off, 1=on)
 *   DATA_MODE_1 = Active Mode / scan rate     (0..3)
 *   DATA_MODE_2 = Up Threshold / hysteresis   (0..4)
 *   DATA_MODE_3 = Tolerance                   (0..4)
 *   DATA_MODE_4 = Aim Sensitivity             (0..4)
 *   DATA_MODE_5 = Tap Stability               (0..4)
 *   DATA_MODE_7 = Edge Filter level           (0..3, also triggers grip grid rebuild)
 *   DATA_MODE_8 = Panel Orientation           (0=portrait, 1=land-right, 3=land-left)
 *   DATA_MODE_9 = Report Rate boost           (0=normal, 1=high)
 */
data class GameModeProfile(
    val activeMode: Int = 1,        // 0-3: scan rate  (1 = enabled)
    val upThreshold: Int = 0,       // 0-4: lift sensitivity  (0 = most sensitive)
    val tolerance: Int = 0,         // 0-4: movement jitter   (0 = tightest)
    val aimSensitivity: Int = 2,    // 0-4: aim tracking precision
    val tapStability: Int = 2,      // 0-4: tap jitter reduction
    val edgeFilter: Int = 2,        // 0-3: edge rejection (2 = game-tuned)
    val reportRate: Int = 1         // 0/1: 0=normal, 1=high rate boost
) {
    companion object {
        val DEFAULT = GameModeProfile()

        fun fromJson(json: String): GameModeProfile? = try {
            val o = JSONObject(json)
            GameModeProfile(
                activeMode    = o.optInt("activeMode", 1),
                upThreshold   = o.optInt("upThreshold", 0),
                tolerance     = o.optInt("tolerance", 0),
                aimSensitivity = o.optInt("aimSensitivity", 2),
                tapStability  = o.optInt("tapStability", 2),
                edgeFilter    = o.optInt("edgeFilter", 2),
                reportRate    = o.optInt("reportRate", 1)
            )
        } catch (e: Exception) { null }
    }

    fun toJson(): String = JSONObject().apply {
        put("activeMode",     activeMode)
        put("upThreshold",    upThreshold)
        put("tolerance",      tolerance)
        put("aimSensitivity", aimSensitivity)
        put("tapStability",   tapStability)
        put("edgeFilter",     edgeFilter)
        put("reportRate",     reportRate)
    }.toString()
}

/**
 * A game app entry selected by the user.
 */
data class GameAppEntry(
    val packageName: String,
    val label: String,
    val profileJson: String = GameModeProfile.DEFAULT.toJson()
) {
    val profile: GameModeProfile get() = GameModeProfile.fromJson(profileJson) ?: GameModeProfile.DEFAULT

    companion object {
        fun fromJson(json: String): GameAppEntry? = try {
            val o = JSONObject(json)
            GameAppEntry(
                packageName = o.getString("pkg"),
                label       = o.getString("label"),
                profileJson = o.optString("profile", GameModeProfile.DEFAULT.toJson())
            )
        } catch (e: Exception) { null }
    }

    fun toJson(): String = JSONObject().apply {
        put("pkg",     packageName)
        put("label",   label)
        put("profile", profileJson)
    }.toString()
}
