package com.tesseract.spectraz

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.text.InputType
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import com.tesseract.spectraz.TerminalNative

class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TextView
    private lateinit var jsonView : TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var sendAiButton: Button

    private lateinit var executionManager: ExecutionManager
    private lateinit var terminalWrapper: TerminalWrapper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)


        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        Log.d("BOOTCHECK", "MainActivity onCreate")

        // Initialize TerminalWrapper for command execution
        terminalWrapper = TerminalWrapper()

        terminalView = findViewById(R.id.terminalView)
        jsonView = findViewById(R.id.jsonView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView)
        sendAiButton = findViewById(R.id.sendAiButton)

        // After Initializing jsonView
        initExecutionManager()

        // Observe terminal output changes
        terminalWrapper.liveHistory.observe(this, { history ->
            history.forEach { entry ->
                terminalView.append("${entry.command}\n${entry.output}\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        })

        sendButton.setOnClickListener {
            val command = inputField.text.toString()
            if (command.isNotEmpty()) {
                executeCommandInTerminal(command)
                inputField.setText("")
                inputField.requestFocus()
            }
        }

        sendAiButton.setOnClickListener {
            val userInput = inputField.text.toString()
            if (userInput.isNotEmpty()) {
                // Send the input to the AI for processing
                executionManager.processQuery(userInput)

                inputField.setText("") // Clear the input field after sending
                inputField.requestFocus() // Request focus back on the input field
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    fun showUserInputDialog(
        context: Context,
        question: String,
        onSubmit: (String) -> Unit
    ) {
        val inputEditText = EditText(context).apply {
            hint = "Enter your response..."
            inputType = InputType.TYPE_CLASS_TEXT
            setPadding(40, 20, 40, 20)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("CommandGen requested for Your Input")
            .setMessage(question)
            .setView(inputEditText)
            .setPositiveButton("Submit") { _, _ ->
                val userInput = inputEditText.text.toString().trim()
                if (userInput.isNotEmpty()) {
                    onSubmit(userInput)
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()
    }

    private fun initExecutionManager() {
        executionManager = ExecutionManager(this)

        executionManager.setJsonView(jsonView)

        executionManager.onFinalCommand = { finalCmd ->
            try {
                // Parse the final command JSON
                val jsonObject = JSONObject(finalCmd)

                // Check if "consolidated_commands" exists in the response
                val consolidatedCommandsArray = jsonObject.optJSONArray("consolidated_commands")

                if (consolidatedCommandsArray != null && consolidatedCommandsArray.length() > 0) {
                    // Case 1: Consolidated commands present
                    for (i in 0 until consolidatedCommandsArray.length()) {
                        val consolidatedCommand = consolidatedCommandsArray.getJSONObject(i)
                        val command = consolidatedCommand.optString("commands", "")

                        if (command.isNotEmpty()) {
                            // Execute the consolidated command in the terminal
                            executeCommandInTerminal(command)
                        }
                    }
                } else {
                    // Case 2: Only step commands present
                    val stepsArray = jsonObject.optJSONArray("steps")

                    if (stepsArray != null) {
                        for (i in 0 until stepsArray.length()) {
                            val step = stepsArray.getJSONObject(i)
                            val stepCommand = step.optString("command", "")

                            if (stepCommand.isNotEmpty()) {
                                // Execute the step command in the terminal
                                executeCommandInTerminal(stepCommand)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Error parsing final command JSON: ${e.message}")
            }
        }

        executionManager.configureModelsFromFile(
            "/storage/emulated/0/Documents/Obsidian_Live/_KnowledgeBase/DataFiles/ModelConfigAndroid.md"
        )

        executionManager.onAskUserRequest = { question ->
            showUserInputDialog(this, question) { userInput ->
                executionManager.submitUserInputToCommandGenerator(userInput)
            }
        }
    }

    private fun executeCommandInTerminal(command: String) {
        Log.d("MainActivity", "Executing command: $command")

        // Special case: if the command is "bootdebian", run the full chroot boot script
        if (command.trim().equals("bootdebian", ignoreCase = true)) {
            terminalWrapper.bootDebianChroot()
            Log.d("MainActivity", "Debian Boot Initiated")
        } else {
            terminalWrapper.runCommand(command, true)
        }
    }

}
