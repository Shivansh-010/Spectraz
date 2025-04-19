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
        try {
            val fullCommand = if (asRoot) arrayOf("su", "-c", command) else arrayOf("sh", "-c", command)
            val process = Runtime.getRuntime().exec(fullCommand)

            val output = process.inputStream.bufferedReader().readText().trim()
            val error = process.errorStream.bufferedReader().readText().trim()
            process.waitFor()

            if (output.isNotEmpty()) {
                addToHistory(command, output, isError = false)
            }

            if (error.isNotEmpty()) {
                addToHistory(command, error, isError = true)
            }

        } catch (e: Exception) {
            addToHistory(command, "Exception: ${e.message}", isError = true)
        }
    }

    private fun addToHistory(command: String, result: String, isError: Boolean) {
        val entry = TerminalEntry(command, result, isError)
        history.add(entry)
        _liveHistory.postValue(history.toList())
    }

    fun clearHistory() {
        history.clear()
        _liveHistory.postValue(history.toList())
    }
}
