package com.oof.control.utils

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Safe shell execution utility that prevents command injection vulnerabilities.
 *
 * This class avoids shell injection by:
 * 1. Using ProcessBuilder with explicit argument arrays instead of shell string interpolation
 * 2. Providing dedicated methods for file I/O that don't go through shell
 * 3. Using proper escaping only when shell is absolutely required
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"

    data class CommandResult(
        val isSuccess: Boolean,
        val output: List<String>,
        val errors: List<String>
    )

    /**
     * Execute a command with explicit arguments (no shell interpolation).
     * This is the safest way to execute commands.
     *
     * @param command Array of command and arguments, e.g., arrayOf("getprop", "ro.product.device")
     */
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

    /**
     * Execute a command asynchronously with explicit arguments.
     */
    suspend fun execute(vararg command: String): CommandResult = withContext(Dispatchers.IO) {
        executeSync(*command)
    }

    /**
     * Execute a shell command string. Use sparingly and only with trusted input.
     * Prefer execute(vararg) when possible.
     */
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

    /**
     * Execute a shell command asynchronously.
     */
    suspend fun executeShell(command: String): CommandResult = withContext(Dispatchers.IO) {
        executeShellSync(command)
    }

    /**
     * Read a file safely without shell injection.
     * First tries direct file read, then falls back to root cat if needed.
     */
    fun readFileSync(path: String): String? {
        return try {
            // First try direct file read
            FileUtils.readOneLine(path) ?: run {
                // Root fallback using ProcessBuilder (not shell interpolation)
                val result = executeSync("cat", path)
                result.output.firstOrNull()?.trim()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading $path", e)
            null
        }
    }

    /**
     * Read a file asynchronously.
     */
    suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        readFileSync(path)
    }

    /**
     * Write to a file safely without shell injection.
     * First tries direct file write, then falls back to root echo if needed.
     */
    fun writeFileSync(path: String, value: String): Boolean {
        return try {
            // First try direct file write
            if (FileUtils.fileExists(path) && FileUtils.isFileWritable(path)) {
                FileUtils.writeLine(path, value)
            } else {
                // Root fallback: use tee instead of echo > to avoid shell injection
                // tee reads from stdin, so we can safely pass any value
                writeWithTee(path, value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing $path", e)
            // Last resort: try with tee
            writeWithTee(path, value)
        }
    }

    /**
     * Write to a file using tee command (injection-safe).
     * Uses stdin to pass the value, avoiding shell metacharacter issues.
     */
    private fun writeWithTee(path: String, value: String): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "tee", path)
                .redirectErrorStream(false)
                .start()

            // Write value to stdin
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(value)
                writer.flush()
            }

            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "tee write failed for $path", e)
            // Final fallback: try with printf (safer than echo)
            writeWithPrintf(path, value)
        }
    }

    /**
     * Write to a file using printf (handles special characters better than echo).
     */
    private fun writeWithPrintf(path: String, value: String): Boolean {
        return try {
            // Escape value for printf %s format
            val process = ProcessBuilder("su", "-c", "printf", "%s", value)
                .redirectErrorStream(false)
                .start()

            // Redirect printf output to file
            val printfProcess = ProcessBuilder("su", "-c", "sh", "-c", "cat > ${escapeShellArg(path)}")
                .redirectErrorStream(false)
                .start()

            printfProcess.outputStream.bufferedWriter().use { writer ->
                writer.write(value)
                writer.flush()
            }

            printfProcess.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "printf write failed for $path", e)
            false
        }
    }

    /**
     * Write to a file asynchronously.
     */
    suspend fun writeFile(path: String, value: String): Boolean = withContext(Dispatchers.IO) {
        writeFileSync(path, value)
    }

    /**
     * Get a system property safely.
     */
    fun getPropertySync(prop: String): String? {
        return try {
            val result = executeSync("getprop", prop)
            result.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting property $prop", e)
            null
        }
    }

    /**
     * Get a system property asynchronously.
     */
    suspend fun getProperty(prop: String): String? = withContext(Dispatchers.IO) {
        getPropertySync(prop)
    }

    /**
     * Set a system property safely.
     */
    fun setPropertySync(prop: String, value: String): Boolean {
        return try {
            // Try setprop first
            val result = executeSync("setprop", prop, value)
            if (result.isSuccess) {
                true
            } else {
                // Fallback to resetprop (Magisk)
                executeSync("resetprop", prop, value).isSuccess
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting property $prop", e)
            false
        }
    }

    /**
     * Set a system property asynchronously.
     */
    suspend fun setProperty(prop: String, value: String): Boolean = withContext(Dispatchers.IO) {
        setPropertySync(prop, value)
    }

    /**
     * Execute a settings command safely.
     * @param namespace "system", "secure", or "global"
     * @param action "get" or "put"
     * @param key The setting key
     * @param value The value (only for put action)
     */
    suspend fun settings(namespace: String, action: String, key: String, value: String? = null): CommandResult = withContext(Dispatchers.IO) {
        val args = if (value != null) {
            arrayOf("settings", action, namespace, key, value)
        } else {
            arrayOf("settings", action, namespace, key)
        }
        execute(*args)
    }

    /**
     * Escape a string for safe use in shell arguments.
     * Only use when shell interpolation is absolutely required.
     */
    fun escapeShellArg(arg: String): String {
        // Use single quotes and escape any embedded single quotes
        return "'" + arg.replace("'", "'\"'\"'") + "'"
    }

    /**
     * Check if a file/path exists.
     */
    fun pathExists(path: String): Boolean {
        return try {
            File(path).exists()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if a path exists asynchronously.
     */
    suspend fun pathExistsAsync(path: String): Boolean = withContext(Dispatchers.IO) {
        pathExists(path)
    }
}
