package com.tesseract.spectraz

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

    fun runCommand(command: String, asRoot: Boolean = false) {
        Thread {
            try {
                addToHistory("> $command", "", false)

                val fullCommand = if (asRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
                val process = Runtime.getRuntime().exec(fullCommand)

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
        runCommand("cd /data/local/debian && nohup ./boot-debian.sh &", asRoot = true)
    }

    fun clearHistory() {
        history.clear()
        _liveHistory.postValue(history.toList())  // Update the live history
    }
}
