package com.oof.control.game

import org.json.JSONObject

/**
 * Per-app touch profile applied automatically when that app is foregrounded.
 */
data class GameProfile(
    val packageName: String,
    val label: String,
    val sensitivity: Int   = 2,   // 0-4
    val edgeFilter: Int    = 2,   // 0-4
    val tapStability: Int  = 2,   // 0-4
    val upThreshold: Int   = 2,   // 0-4
    val highReportRate: Boolean = false,
    val enabled: Boolean   = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("packageName",    packageName)
        put("label",          label)
        put("sensitivity",    sensitivity)
        put("edgeFilter",     edgeFilter)
        put("tapStability",   tapStability)
        put("upThreshold",    upThreshold)
        put("highReportRate", highReportRate)
        put("enabled",        enabled)
    }

    companion object {
        fun fromJson(obj: JSONObject) = GameProfile(
            packageName    = obj.getString("packageName"),
            label          = obj.optString("label", obj.getString("packageName")),
            sensitivity    = obj.optInt("sensitivity",    2),
            edgeFilter     = obj.optInt("edgeFilter",     2),
            tapStability   = obj.optInt("tapStability",   2),
            upThreshold    = obj.optInt("upThreshold",    2),
            highReportRate = obj.optBoolean("highReportRate", false),
            enabled        = obj.optBoolean("enabled",    true)
        )
    }
}
