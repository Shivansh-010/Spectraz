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

// Enum to define the execution environment
enum class ExecutionEnvironment {
    ANDROID, DEBIAN
}

class TerminalWrapper {

    private val history = mutableListOf<TerminalEntry>()
    private val _liveHistory = MutableLiveData<List<TerminalEntry>>()
    val liveHistory: LiveData<List<TerminalEntry>> = _liveHistory

    // State for the current execution environment
    private var currentEnvironment: ExecutionEnvironment = ExecutionEnvironment.DEBIAN // Default to Debian
        private set

    // Flag indicating if boot has been initiated (doesn't guarantee success)
    var isDebianBootInitiated: Boolean = false
        private set

    // Path to the chroot environment
    private val debianChrootDir = "/data/local/debian"

    // --- Initialization ---
    init {
        Log.d("TerminalWrapper", "Initializing TerminalWrapper...")
        // Boot Debian immediately upon creation
        bootDebianChroot()
        // Attempt to change directory within Debian after starting boot
        // Note: This command is queued and runs after boot starts,
        // assuming /home exists early in the boot process.
        Log.d("TerminalWrapper", "Queueing initial 'cd /home' for Debian.")
        runCommand("cd /home") // This will run in Debian by default
    }

    // --- Public Methods ---

    /**
     * Sets the target environment for subsequent runCommand calls.
     */
    fun setEnvironment(environment: ExecutionEnvironment) {
        if (currentEnvironment != environment) {
            Log.d("TerminalWrapper", "Switching environment to ${environment.name}")
            currentEnvironment = environment
            // Add a visual separator in history
            addToHistory("--- Switched to ${environment.name} environment ---", "", false)
        }
    }

    /**
     * Gets the currently selected execution environment.
     */
    fun getCurrentEnvironment(): ExecutionEnvironment = currentEnvironment

    /**
     * Runs a command in the currently selected environment (Android or Debian).
     *
     * @param command The command string to execute.
     * @param asRoot For ANDROID environment only: If true, executes the command using "su -c".
     *               This parameter is ignored for the DEBIAN environment, as chroot itself requires root.
     */
    fun runCommand(command: String, asRoot: Boolean = false) {
        Thread {
            val commandToLog = "> $command"
            val environmentForCommand = currentEnvironment // Capture current env for this thread
            var effectiveCommand = command // The command string that might be modified
            val executionCommandArray: Array<String> // The final array for Runtime.exec

            try {
                // Add the user's intended command to history first
                addToHistory(commandToLog, "", false)
                Log.d("TerminalWrapper", "Executing in ${environmentForCommand.name}: $command")

                // Prepare the actual command based on the environment
                when (environmentForCommand) {
                    ExecutionEnvironment.DEBIAN -> {
                        // Ensure Debian boot has at least been started
                        if (!isDebianBootInitiated) {
                            val bootMsg = "Debian environment not booted. Cannot run command."
                            Log.e("TerminalWrapper", bootMsg)
                            addToHistory(commandToLog, bootMsg, true)
                            addToHistory("___", "", false) // Separator
                            return@Thread // Exit thread
                        }
                        // Escape single quotes within the command for shell safety inside '-c'
                        val escapedCommand = command.replace("'", "'\\''")
                        // Construct the chroot command, always executed via Android 'su'
                        effectiveCommand = "chroot $debianChrootDir /bin/bash --login -c '$escapedCommand'"
                        executionCommandArray = arrayOf("su", "-c", effectiveCommand)
                        Log.v("TerminalWrapper", "Effective Debian command: ${executionCommandArray.joinToString(" ")}")
                    }
                    ExecutionEnvironment.ANDROID -> {
                        // Use standard Android shell or su
                        executionCommandArray = if (asRoot) {
                            arrayOf("su", "-c", command)
                        } else {
                            arrayOf("sh", "-c", command)
                        }
                        Log.v("TerminalWrapper", "Effective Android command: ${executionCommandArray.joinToString(" ")}")
                    }
                }

                // Execute the prepared command
                val process = Runtime.getRuntime().exec(executionCommandArray)

                val stdout = StringBuilder()
                val stderr = StringBuilder()

                // Concurrently read stdout and stderr (same logic as before)
                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stdout.appendLine(it) }
                        }
                    } catch (e: Exception) {
                        Log.e("TerminalWrapper", "Error reading stdout: ${e.message}")
                        stderr.appendLine("Error reading stdout: ${e.message}") // Add to stderr
                    }
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { stderr.appendLine(it) }
                        }
                    } catch (e: Exception) {
                        Log.e("TerminalWrapper", "Error reading stderr: ${e.message}")
                        // Avoid infinite loop if error reading error stream
                    }
                }

                stdoutThread.start()
                stderrThread.start()
                stdoutThread.join()
                stderrThread.join()

                val exitCode = process.waitFor()
                Log.d("TerminalWrapper", "Command finished with exit code: $exitCode")

                // Add results to history
                val outputString = stdout.toString().trim()
                val errorString = stderr.toString().trim()

                if (outputString.isNotEmpty()) {
                    // Use "Output:" prefix for clarity, keep original command logged above
                    addToHistory("Output:", outputString, false)
                }
                if (errorString.isNotEmpty()) {
                    // Use "Error:" prefix, mark as error
                    addToHistory("Error:", errorString, true)
                }
                if (outputString.isEmpty() && errorString.isEmpty()) {
                    // Indicate if there was no output at all
                    addToHistory("(No output)", "", false)
                }

                // Add a separator
                addToHistory("___", "", false)

            } catch (e: Exception) {
                Log.e("TerminalWrapper", "Exception running command '$command': ${e.message}", e)
                // Add exception message to history, linked to the original command
                addToHistory(commandToLog, "Exception: ${e.message}", true)
                addToHistory("___", "", false) // Separator
            }
        }.start()
    }

    /**
     * Initiates the Debian boot process. Should only be called once.
     */
    private fun bootDebianChroot() {
        if (isDebianBootInitiated) {
            Log.w("TerminalWrapper", "Debian boot already initiated.")
            return
        }
        Log.d("TerminalWrapper", "Initiating Debian boot sequence...")
        // Define the boot command relative to the chroot dir
        // 'runCommand' will handle wrapping this with 'su -c' because asRoot = true
        val bootScriptCommand = "cd $debianChrootDir && ./boot-debian.sh"

        // Run the boot script using the ANDROID 'su' environment.
        // Temporarily switch environment just for this call if needed,
        // or rely on the caller knowing the context. For simplicity,
        // let's just call runCommand directly with asRoot = true.
        // Note: This uses the *Android* environment execution path within runCommand.
        executeAndroidCommand(bootScriptCommand, asRoot = true) // Use a helper to bypass env toggle

        // Mark that boot has been started.
        // IMPORTANT: This does NOT guarantee the boot script succeeded, only that it was launched.
        isDebianBootInitiated = true
        Log.d("TerminalWrapper", "Debian boot command launched.")
        addToHistory("--- Debian boot initiated ---", "", false)
    }

    /**
     * Helper to explicitly run an Android command, bypassing the environment toggle.
     * Used internally for tasks like booting Debian.
     */
    private fun executeAndroidCommand(command: String, asRoot: Boolean) {
        val previousEnvironment = currentEnvironment
        setEnvironment(ExecutionEnvironment.ANDROID) // Temporarily switch
        runCommand(command, asRoot)
        setEnvironment(previousEnvironment) // Switch back
    }


    /**
     * Clears the command history.
     */
    fun clearHistory() {
        history.clear()
        _liveHistory.postValue(history.toList())
        Log.d("TerminalWrapper", "Command history cleared.")
    }

    // --- Private Helpers ---

    /**
     * Adds an entry to the history and updates LiveData. Runs on the caller's thread.
     * Should be called from the background thread within runCommand.
     */
    private fun addToHistory(cmdOrPrefix: String, result: String, isError: Boolean) {
        synchronized(history) { // Synchronize access to history list
            val entry = TerminalEntry(cmdOrPrefix, result, isError)
            history.add(entry)
            // Post the update to LiveData (safe to call from background thread)
            _liveHistory.postValue(history.toList()) // Post a new immutable list
        }
    }
}