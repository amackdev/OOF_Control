package com.oof.control.utils

import android.util.Log

/**
 * DaemonConfigWriter handles asynchronous writing of Xiaomi Touch game mode params
 * to a standardized file location that an external daemon watches.
 *
 * Current format is a simplified space-separated KV matching the IOCTL modes:
 * <mode_id> <value>
 */
object DaemonConfigWriter {
    private const val TAG = "DaemonConfigWriter"
    private const val CONFIG_PATH = "/data/oofcontrol/gamemode.txt"

    /**
     * Batch writes the current gamemode state and values to the configuration file.
     * We use Coroutines / ShellExecutor.executeShellSync to echo the config atomically.
     */
    fun writeConfig(state: Map<Int, Int>, gripArray: IntArray?) {
        val sb = StringBuilder()

        // Write standard modes: each line is "<mode_id> <value>"
        for ((mode, value) in state) {
            sb.append("$mode $value\n")
        }

        // Write grip array if provided (MODE_GRIP_LONG = 15)
        // Format: "15 v0,v1,...,v95"
        if (gripArray != null && gripArray.size == 96) {
            sb.append("${TouchConstants.MODE_GRIP_LONG} ${gripArray.joinToString(",")}\n")
        }

        val configContent = sb.toString()
        Log.i(TAG, "Writing touch config to daemon:\n$configContent")

        // Ensure the directory exists
        ShellExecutor.executeShellSync("mkdir -p /data/oofcontrol && chmod 777 /data/oofcontrol")

        // Write the file atomically using ShellExecutor's safe write path
        val written = ShellExecutor.writeFileSync(CONFIG_PATH, configContent)
        if (written) {
            // Make it world-readable so the daemon can pick it up
            ShellExecutor.executeShellSync("chmod 666 $CONFIG_PATH")
            Log.i(TAG, "Successfully wrote daemon config")
        } else {
            Log.e(TAG, "Failed to write daemon config to $CONFIG_PATH")
        }
    }
}
