package com.tesseract.spectraz

import android.util.Log

object RootUtils {
    fun readFileWithRoot(filePath: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $filePath"))
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val error = process.errorStream.bufferedReader().use { it.readText() }

            process.waitFor()

            if (error.isNotEmpty()) {
                Log.e("RootUtils", "Error reading file: $error")
            }

            output
        } catch (e: Exception) {
            Log.e("RootUtils", "Exception reading file: ${e.message}")
            ""
        }
    }

    fun writeFileWithRoot(filePath: String, content: String): Boolean {
        return try {
            val command = "echo '${content.replace("'", "'\\''")}' > $filePath"
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val error = process.errorStream.bufferedReader().use { it.readText() }

            process.waitFor()

            if (error.isNotEmpty()) {
                Log.e("RootUtils", "Error writing file: $error")
                false
            } else true
        } catch (e: Exception) {
            Log.e("RootUtils", "Exception writing file: ${e.message}")
            false
        }
    }
}
