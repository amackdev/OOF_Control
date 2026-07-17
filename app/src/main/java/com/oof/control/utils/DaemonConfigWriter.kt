package com.oof.control.utils

import android.util.Log

/** Writes game mode touch params to /data/oofcontrol/gamemode.txt for the daemon. Format: "<mode_id> <value>" */
object DaemonConfigWriter {
    private const val TAG = "DaemonConfigWriter"
    private const val CONFIG_PATH = "/data/oofcontrol/gamemode.txt"

    private var dirCreated = false

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

        try {
            val dir = java.io.File("/data/oofcontrol")
            if (!dir.exists()) {
                dir.mkdirs()
                dir.setExecutable(true, false)
                dir.setReadable(true, false)
                dir.setWritable(true, false)
            }

            val file = java.io.File(CONFIG_PATH)
            file.writeText(configContent)
            file.setReadable(true, false)
            file.setWritable(true, false)
            Log.i(TAG, "Successfully wrote daemon config natively")
        } catch (e: Exception) {
            Log.e(TAG, "Native write failed, falling back to shell", e)
            if (!dirCreated) {
                ShellExecutor.executeShellSync("mkdir -p /data/oofcontrol && chmod 777 /data/oofcontrol")
                dirCreated = true
            }

            val written = ShellExecutor.writeFileSync(CONFIG_PATH, configContent)
            if (written) {
                ShellExecutor.executeShellSync("chmod 666 $CONFIG_PATH")
                Log.i(TAG, "Successfully wrote daemon config via shell")
            } else {
                Log.e(TAG, "Failed to write daemon config to $CONFIG_PATH")
            }
        }
    }
}
