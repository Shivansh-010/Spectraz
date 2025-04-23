package com.tesseract.spectraz

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

data class TerminalEntry(
    val command: String,
    val output: String,
    val isError: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

class TerminalWrapper {

    private val history = mutableListOf<TerminalEntry>()
    private val _liveHistory = MutableLiveData<List<TerminalEntry>>()
    val liveHistory: LiveData<List<TerminalEntry>> = _liveHistory

    var isDebianRunning: Boolean = false
        private set

    fun runCommand(command: String, asRoot: Boolean = false) {
        Thread {
            try {
                // Clean up the history: Add the command first
                addToHistory("> $command", "", false)

                // Run the command (with root if needed)
                val fullCommand = if (asRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
                val process = Runtime.getRuntime().exec(fullCommand)

                val stdout = StringBuilder()
                val stderr = StringBuilder()

                // Handle stdout in a separate thread
                val stdoutThread = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { stdout.appendLine(it) }
                    }
                }

                // Handle stderr in a separate thread
                val stderrThread = Thread {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { stderr.appendLine(it) }
                    }
                }

                // Start the threads to read stdout and stderr concurrently
                stdoutThread.start()
                stderrThread.start()

                // Wait for threads to finish
                stdoutThread.join()
                stderrThread.join()

                // Wait for the process to exit
                process.waitFor()

                // Add the results to history
                if (stdout.isNotEmpty()) {
                    addToHistory("exec result:", stdout.toString(), false)
                }

                // Add the stderr if there's any
                if (stderr.isNotEmpty()) {
                    addToHistory("exec error:", stderr.toString(), true)
                }

                // Add a separator to make the output look clean and readable
                addToHistory("___", "", false)

            } catch (e: Exception) {
                // Add exception message to history
                addToHistory("> $command", "Exception: ${e.message}", true)
            }
        }.start()
    }

    fun runCommandWithTermuxEnv(command: String, asRoot: Boolean = false) {
        Thread {
            try {
                // Add Termux directories to environment variables
                val termuxBin = "/data/data/com.termux/files/usr/bin/"
                val termuxLib = "/data/data/com.termux/files/usr/lib/"

                // Create a new process builder
                val processBuilder = ProcessBuilder()

                // Set environment variables to include Termux paths
                val processEnv = processBuilder.environment()
                processEnv["PATH"] = "${processEnv["PATH"]}:$termuxBin"
                processEnv["LD_LIBRARY_PATH"] = "${processEnv["LD_LIBRARY_PATH"]}:$termuxLib"

                // Log the environment variables to check
                Log.d("KotlinApp", "PATH: ${processEnv["PATH"]}")
                Log.d("KotlinApp", "LD_LIBRARY_PATH: ${processEnv["LD_LIBRARY_PATH"]}")

                // Decide whether to run the command as root or not
                val fullCommand = if (asRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)

                // Start the process
                processBuilder.command(*fullCommand)
                val process = processBuilder.start()

                // Handle stdout and stderr streams
                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val stdoutThread = Thread {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { stdout.appendLine(it) }
                    }
                }

                val stderrThread = Thread {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.forEach { stderr.appendLine(it) }
                    }
                }

                stdoutThread.start()
                stderrThread.start()

                stdoutThread.join()
                stderrThread.join()

                process.waitFor()

                // Add output and error to history
                if (stdout.isNotEmpty()) {
                    addToHistory("", stdout.toString().trim(), false)
                }

                if (stderr.isNotEmpty()) {
                    addToHistory("", stderr.toString().trim(), true)
                }

            } catch (e: Exception) {
                addToHistory("> $command", "Exception: ${e.message}", true)
            }
        }.start()
    }

    private fun addToHistory(command: String, result: String, isError: Boolean) {
        val entry = TerminalEntry(command, result, isError)
        history.add(entry)
        _liveHistory.postValue(history.toList())  // Update the live history
    }

    fun bootDebianChroot() {
        // Start the boot-debian.sh script inside the /data/local/debian directory.
        // Running it with su as root ensures the necessary permissions are set up.
        runCommand("cd /data/local/debian && su -c './boot-debian.sh'", asRoot = true)

        // After booting, we mark the Debian environment as running.
        isDebianRunning = true // Mark as Debian is booted
        Log.d("MainActivity", "Debian Boot Initiated and environment set up")
    }
    
    fun runInDebian(command: String) {
        // Wrap the command inside chroot to ensure it runs in the Debian environment.
        val chrootCommand = "chroot /data/local/debian /bin/bash --login -c '$command'"

        // Run the command as root to ensure we have the necessary permissions.
        runCommand(chrootCommand, asRoot = true)

        Log.d("MainActivity", "Running command in Debian: $command")
    }

    fun clearHistory() {
        history.clear()
        _liveHistory.postValue(history.toList())  // Update the live history
    }
}
