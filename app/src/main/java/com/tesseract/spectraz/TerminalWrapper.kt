package com.tesseract.spectraz

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import java.io.IOException // Import IOException

data class TerminalEntry(
    val command: String,
    val output: String,
    val isError: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ExecutionEnvironment {
    ANDROID, DEBIAN
}

class TerminalWrapper {

    private val history = mutableListOf<TerminalEntry>()
    private val _liveHistory = MutableLiveData<List<TerminalEntry>>()
    val liveHistory: LiveData<List<TerminalEntry>> = _liveHistory

    private var currentEnvironment: ExecutionEnvironment = ExecutionEnvironment.DEBIAN
        private set

    var isDebianBootInitiated: Boolean = false
        private set

    private val debianChrootDir = "/data/local/debian"
    // --- State for Persistent Debian Directory ---
    private var currentDebianDirectory: String = "/" // Start at root initially
    private val debianDirLock = ReentrantLock() // Lock for synchronizing access/updates

    // --- Initialization ---
    init {
        Log.d("TerminalWrapper", "Initializing TerminalWrapper...")
        // Boot Debian first
        bootDebianChroot() // This runs asynchronously

        // **Crucially, set the initial directory *after* boot is likely started**
        // We'll attempt the 'cd /home' and update our tracked state.
        // Run this *synchronously relative to other commands* but it still uses a bg thread.
        // Best practice might involve a callback or delayed execution,
        // but this is simpler for now.
        runCommand("cd /home") // Attempt initial cd
    }

    // --- Public Methods ---

    fun setEnvironment(environment: ExecutionEnvironment) {
        if (currentEnvironment != environment) {
            Log.d("TerminalWrapper", "Switching environment to ${environment.name}")
            currentEnvironment = environment
            addToHistory("--- Switched to ${environment.name} environment ---", "", false)
        }
    }

    fun getCurrentEnvironment(): ExecutionEnvironment = currentEnvironment

    /**
     * Runs a command, handling environment switching, persistent directories for Debian,
     * and the special 'clear' command.
     */
    fun runCommand(command: String, asRoot: Boolean = false) {
        Thread {
            // --- Check for special 'clear' command FIRST ---
            val trimmedCommand = command.trim()
            if (trimmedCommand.equals("clear", ignoreCase = true)) {
                // User typed 'clear', call our history clearing function
                clearHistory()
                Log.d("TerminalWrapper", "History cleared via 'clear' command.")
                // Don't add 'clear' to history or try to execute it further.
                // Just exit this thread for this specific command.
                return@Thread
            }
            // --- End special 'clear' command check ---


            // --- If not 'clear', proceed with normal execution ---
            val commandToLog = "> $trimmedCommand" // Use trimmed command for logging consistency
            val environmentForCommand = currentEnvironment
            var effectiveCommand: String
            val executionCommandArray: Array<String>

            var commandDebianDir = "/"
            debianDirLock.withLock {
                commandDebianDir = currentDebianDirectory
            }

            // Use startsWith check on the *trimmed* command
            val isCdCommand = trimmedCommand.startsWith("cd ")

            try {
                // Log the user's intended command (already trimmed)
                addToHistory(commandToLog, "", false)
                Log.d("TerminalWrapper", "Executing in ${environmentForCommand.name}: $trimmedCommand")

                // Prepare the actual command based on the environment
                when (environmentForCommand) {
                    ExecutionEnvironment.DEBIAN -> {
                        if (!isDebianBootInitiated) {
                            val bootMsg = "Debian environment not booted. Cannot run command."
                            Log.e("TerminalWrapper", bootMsg)
                            addToHistory(commandToLog, bootMsg, true)
                            addToHistory("___", "", false)
                            return@Thread
                        }

                        // Escape single quotes for shell safety (use trimmed command)
                        val escapedCommand = trimmedCommand.replace("'", "'\\''")
                        val escapedDebianDir = commandDebianDir.replace("'", "'\\''")

                        if (isCdCommand) {
                            effectiveCommand = "cd '$escapedDebianDir' && $escapedCommand && pwd"
                        } else {
                            effectiveCommand = "cd '$escapedDebianDir' && $escapedCommand"
                        }

                        executionCommandArray = arrayOf("su", "-c", "chroot $debianChrootDir /bin/bash --login -c '$effectiveCommand'")
                        Log.v("TerminalWrapper", "Effective Debian command: ${executionCommandArray.joinToString(" ")}")
                    }
                    ExecutionEnvironment.ANDROID -> {
                        // Use trimmed command for execution
                        effectiveCommand = trimmedCommand
                        executionCommandArray = if (asRoot) {
                            arrayOf("su", "-c", effectiveCommand)
                        } else {
                            arrayOf("sh", "-c", effectiveCommand)
                        }
                        Log.v("TerminalWrapper", "Effective Android command: ${executionCommandArray.joinToString(" ")}")
                    }
                }

                // --- Execute and Read Output ---
                val process = Runtime.getRuntime().exec(executionCommandArray)
                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val stdoutThread = Thread {
                    try {
                        process.inputStream.bufferedReader().useLines { lines ->
                            lines.forEach { stdout.appendLine(it) }
                        }
                    } catch (e: IOException) {
                        Log.e("TerminalWrapper", "IOException reading stdout: ${e.message}")
                        stderr.appendLine("IOException reading stdout: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("TerminalWrapper", "Error reading stdout: ${e.message}")
                        stderr.appendLine("Error reading stdout: ${e.message}")
                    }
                }
                val stderrThread = Thread {
                    try {
                        process.errorStream.bufferedReader().useLines { lines ->
                            lines.forEach { stderr.appendLine(it) }
                        }
                    } catch (e: IOException) {
                        Log.e("TerminalWrapper", "IOException reading stderr: ${e.message}")
                    } catch (e: Exception) {
                        Log.e("TerminalWrapper", "Error reading stderr: ${e.message}")
                    }
                }

                stdoutThread.start()
                stderrThread.start()
                stdoutThread.join()
                stderrThread.join()

                val exitCode = process.waitFor()
                Log.d("TerminalWrapper", "Command finished with exit code: $exitCode")
                // --- End Execute and Read Output ---


                // --- Process Results and Update State ---
                val outputString = stdout.toString().trim()
                val errorString = stderr.toString().trim()

                // Update Debian directory if it was a successful 'cd' command
                if (environmentForCommand == ExecutionEnvironment.DEBIAN && isCdCommand && exitCode == 0) {
                    val newDir = outputString.lines().lastOrNull()?.trim()
                    if (!newDir.isNullOrBlank()) {
                        debianDirLock.withLock {
                            currentDebianDirectory = newDir
                            Log.i("TerminalWrapper", "Debian directory updated to: $currentDebianDirectory")
                        }
                        if (errorString.isNotEmpty()) {
                            addToHistory("Error:", errorString, true)
                        } else {
                            addToHistory("(Directory changed)", "", false)
                        }
                    } else {
                        Log.w("TerminalWrapper", "Successful 'cd' command but 'pwd' returned empty.")
                        addToHistory("Error:", "cd succeeded but failed to determine new directory.", true)
                        if (errorString.isNotEmpty()) addToHistory("stderr:", errorString, true)
                    }
                } else {
                    // Handle regular command output or failed 'cd'
                    if (outputString.isNotEmpty()) {
                        addToHistory("Output:", outputString, false)
                    }
                    if (errorString.isNotEmpty()) {
                        addToHistory("Error:", errorString, true)
                    }
                    // Check trimmedCommand here as well
                    if (outputString.isEmpty() && errorString.isEmpty() && !(isCdCommand && exitCode == 0)) {
                        addToHistory("(No output)", "", false)
                    }
                    // Check isCdCommand (which already used trimmed command)
                    if (isCdCommand && exitCode != 0) {
                        addToHistory("(cd command failed, directory not changed)", "", true)
                    }
                }

                addToHistory("___", "", false) // Separator

            } catch (e: Exception) {
                // Use trimmedCommand in error message
                Log.e("TerminalWrapper", "Exception running command '$trimmedCommand': ${e.message}", e)
                addToHistory(commandToLog, "Exception: ${e.message}", true)
                addToHistory("___", "", false)
            }
        }.start()
    }

    private fun bootDebianChroot() {
        if (isDebianBootInitiated) {
            Log.w("TerminalWrapper", "Debian boot already initiated.")
            return
        }
        Log.d("TerminalWrapper", "Initiating Debian boot sequence...")
        val bootScriptCommand = "cd $debianChrootDir && ./boot-debian.sh"

        // Use a temporary switch to Android env for the boot command itself
        val previousEnvironment = currentEnvironment
        val executeBoot = {
            setEnvironment(ExecutionEnvironment.ANDROID)
            runCommand(bootScriptCommand, asRoot = true) // Use the modified runCommand
            setEnvironment(previousEnvironment)
        }
        // Execute the boot logic in a separate thread to avoid blocking init
        Thread(executeBoot).start()


        isDebianBootInitiated = true // Mark boot *attempt* as started
        Log.d("TerminalWrapper", "Debian boot command launch initiated.")
        addToHistory("--- Debian boot initiated ---", "", false) // Log initiation
    }

    fun clearHistory() {
        synchronized(history) {
            history.clear()
            _liveHistory.postValue(history.toList())
        }
        Log.d("TerminalWrapper", "Command history cleared.")
    }

    private fun addToHistory(cmdOrPrefix: String, result: String, isError: Boolean) {
        synchronized(history) { // Synchronize access to history list
            val entry = TerminalEntry(cmdOrPrefix, result, isError)
            history.add(entry)
            // Post the update to LiveData (safe to call from background thread)
            _liveHistory.postValue(history.toList()) // Post a new immutable list
        }
    }
}