package com.oof.control.utils

import android.util.Log
import java.io.*

object FileUtils {

    private const val TAG = "FileUtils"

    /**
     * Reads the first line of text from the given file.
     * Reference BufferedReader.readLine() for clarification on what a line is.
     *
     * @return the read line contents, or null on failure
     */
    @JvmStatic
    fun readOneLine(fileName: String): String? {
        return try {
            BufferedReader(FileReader(fileName), 512).use { reader ->
                reader.readLine()
            }
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "No such file $fileName for reading", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "Could not read from file $fileName", e)
            null
        }
    }

    @JvmStatic
    fun readLineInt(fileName: String): Int {
        val line = readOneLine(fileName)
        if (line == null) {
            Log.e(TAG, "readLineInt: line is null for file $fileName")
            return 0
        }

        return try {
            line.replace("0x", "").toInt()
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Could not convert string to int from file $fileName", e)
            0
        }
    }

    @JvmStatic
    fun writeOneLine(fileName: String, value: String): Boolean {
        return try {
            BufferedWriter(FileWriter(fileName)).use { writer ->
                writer.write(value)
            }
            true
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "No such file $fileName for writing", e)
            false
        } catch (e: IOException) {
            Log.e(TAG, "Could not write to file $fileName", e)
            false
        }
    }

    /**
     * Writes the given value into the given file.
     *
     * @return true on success, false on failure
     */
    @JvmStatic
    fun writeLine(fileName: String, value: String): Boolean {
        return writeOneLine(fileName, value)
    }

    @JvmStatic
    fun writeLine(fileName: String, value: Int) {
        writeLine(fileName, value.toString())
    }

    /**
     * Checks whether the given file exists.
     */
    @JvmStatic
    fun fileExists(fileName: String): Boolean {
        return File(fileName).exists()
    }

    /**
     * Checks whether the given file is readable.
     */
    @JvmStatic
    fun isFileReadable(fileName: String): Boolean {
        val file = File(fileName)
        return file.exists() && file.canRead()
    }

    /**
     * Checks whether the given file is writable.
     */
    @JvmStatic
    fun isFileWritable(fileName: String): Boolean {
        val file = File(fileName)
        return file.exists() && file.canWrite()
    }

    /**
     * Deletes an existing file.
     */
    @JvmStatic
    fun delete(fileName: String): Boolean {
        return try {
            File(fileName).delete()
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException trying to delete $fileName", e)
            false
        }
    }

    /**
     * Renames an existing file.
     */
    @JvmStatic
    fun rename(srcPath: String, dstPath: String): Boolean {
        return try {
            File(srcPath).renameTo(File(dstPath))
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException trying to rename $srcPath to $dstPath", e)
            false
        } catch (e: NullPointerException) {
            Log.e(TAG, "NullPointerException trying to rename $srcPath to $dstPath", e)
            false
        }
    }
}
