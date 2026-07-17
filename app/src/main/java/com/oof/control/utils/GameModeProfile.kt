package com.oof.control.utils

import android.content.Context
import org.json.JSONObject

/** Touch game-mode parameter set sent to the kernel via daemon config file. */
data class GameModeProfile(
    val activeMode: Int = 1,        // 0-3: scan rate  (1 = enabled)
    val upThreshold: Int = 0,       // 0-4: lift sensitivity  (0 = most sensitive)
    val tolerance: Int = 0,         // 0-4: movement jitter   (0 = tightest)
    val aimSensitivity: Int = 20,   // aim tracking precision (0-40)
    val tapStability: Int = 20,     // tap jitter reduction (0-40)
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
                aimSensitivity = o.optInt("aimSensitivity", 20),
                tapStability  = o.optInt("tapStability", 20),
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
