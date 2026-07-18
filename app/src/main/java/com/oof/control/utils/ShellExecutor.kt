package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Shell runner. Uses ProcessBuilder (no shell interpolation) where possible; falls back to tee/sh. App runs as priv-app, no su needed. */
object ShellExecutor {

    private const val TAG = "ShellExecutor"

    data class CommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
        val errors: List<String>
    )


    fun executeSync(vararg command: String): CommandResult {
        return try {
            val process = ProcessBuilder(*command)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readLines()
            val errors = process.errorStream.bufferedReader().readLines()
            val exitCode = process.waitFor()

            CommandResult(exitCode == 0, output, errors)
        } catch (e: Exception) {
            Log.e(TAG, "Command failed: ${command.joinToString(" ")}", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }


    suspend fun execute(vararg command: String): CommandResult = withContext(Dispatchers.IO) {
        executeSync(*command)
    }

    /** Use sparingly — prefer execute(vararg) when possible. */
    fun executeShellSync(command: String): CommandResult {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readLines()
            val errors = process.errorStream.bufferedReader().readLines()
            val exitCode = process.waitFor()

            CommandResult(exitCode == 0, output, errors)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            CommandResult(false, emptyList(), listOf(e.message ?: "Unknown error"))
        }
    }


    suspend fun executeShell(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellSync(command)
    }

    // Tries direct read first, falls back to root cat
    fun readFileSync(path: String): String? {
        return try {
            FileUtils.readOneLine(path) ?: run {
                val result = executeSync("cat", path)
                result.output.firstOrNull()?.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $path", e)
            null
        }
    }


    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        readFileSync(path)
    }

    // Tries direct write first, falls back to tee
    fun writeFileSync(path: String, value: String): Boolean {
        return try {
            if (FileUtils.fileExists(path) && FileUtils.isFileWritable(path)) {
                FileUtils.writeLine(path, value)
            } else {
                writeWithTee(path, value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing $path", e)
            writeWithTee(path, value)
        }
    }

    // Writes via stdin to tee — avoids shell metacharacter issues
    private fun writeWithTee(path: String, value: String): Boolean {
        return try {
            val process = ProcessBuilder("tee", path)
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(value)
                writer.flush()
            }

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "tee write failed for $path", e)
            // Final fallback: try direct shell write
            writeWithShell(path, value)
        }
    }

    // Last resort: cat from stdin
    private fun writeWithShell(path: String, value: String): Boolean {
        return try {
            val process = ProcessBuilder("sh", "-c", "cat > ${escapeShellArg(path)}")
                .redirectErrorStream(false)
                .start()

            process.outputStream.bufferedWriter().use { writer ->
                writer.write(value)
                writer.flush()
            }

            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "shell write failed for $path", e)
            false
        }
    }


    suspend fun writeFile(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
        writeFileSync(path, value)
    }


    fun getPropertySync(prop: String): String? {
        return try {
            val result = executeSync("getprop", prop)
            result.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting property $prop", e)
            null
        }
    }


    suspend fun getProperty(prop: String): String? = withContext(Dispatchers.IO) {
        getPropertySync(prop)
    }


    fun setPropertySync(prop: String, value: String): Boolean {
        return try {
            // Try setprop first
            val result = executeSync("setprop", prop, value)
            if (result.isSuccess) {
                true
            } else {
                // resetprop fallback for Magisk
                executeSync("resetprop", prop, value).isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting property $prop", e)
            false
        }
    }


    suspend fun setProperty(prop: String, value: String): Boolean = withContext(Dispatchers.IO) {
        setPropertySync(prop, value)
    }


    suspend fun settings(namespace: String, action: String, key: String, value: String? = null): CommandResult = withContext(Dispatchers.IO) {
        val args = if (value != null) {
            arrayOf("settings", action, namespace, key, value)
        } else {
            arrayOf("settings", action, namespace, key)
        }
        execute(*args)
    }

    fun escapeShellArg(arg: String): String =
        "'" + arg.replace("'", "'\"'\"'") + "'"


    fun pathExists(path: String): Boolean {
        return try {
            if (File(path).exists()) return true
            // Fallback: check with ls in root shell for sysfs nodes with strict SELinux rules
            val result = executeSync("ls", path)
            result.isSuccess
        } catch (e: Exception) {
            false
        }
    }


    suspend fun pathExistsAsync(path: String): Boolean = withContext(Dispatchers.IO) {
        pathExists(path)
    }
}
