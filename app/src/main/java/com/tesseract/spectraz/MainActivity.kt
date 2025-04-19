package com.tesseract.spectraz

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

import android.Manifest
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

    private val PERMISSION_REQUEST_READ_EXTERNAL_STORAGE = 1

    private lateinit var outputView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView

    private lateinit var executionManager: ExecutionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
        }

        Log.d("BOOTCHECK", "MainActivity onCreate")

        initExecutionManager()

        outputView = findViewById(R.id.outputView)
        inputField = findViewById(R.id.inputField)
        sendButton = findViewById(R.id.sendButton)
        scrollView = findViewById(R.id.scrollView)

        if (TerminalNative.startShell()) {
            readShellOutput()
        } else {
            outputView.append("Failed to start shell\n")
        }

        sendButton.setOnClickListener {
            val command = inputField.text.toString()
            if (command.isNotEmpty()) {
                TerminalNative.sendToShell(command)
                inputField.setText("")
                readShellOutput()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

    /*      if (requestCode == PERMISSION_REQUEST_READ_EXTERNAL_STORAGE) {
            if ((grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)) {
                initExecutionManager()
            } else {
                Toast.makeText(this, "Storage permission is required", Toast.LENGTH_SHORT).show()
            }
        }*/
    }

    private fun readShellOutput() {
        Thread {
            val output = TerminalNative.readFromShell()
            runOnUiThread {
                outputView.append(output + "\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }

    private fun initExecutionManager() {
        executionManager = ExecutionManager(this)

        executionManager.onFinalCommand = { finalCmd ->
            // Execute final command or update the UI with it
            Log.d("MainActivity", "Final Command: $finalCmd")
        }

        executionManager.configureModelsFromFile(
            "/storage/emulated/0/Documents/Obsidian_Live/_KnowledgeBase/DataFiles/ModelConfigAndroid.md"
        )

        // For testing, send a command on create.
        executionManager.processQuery("use ImageMagick to resize image.png to 200x200 and then move it to /storage/emulated/0/Pictures/")
    }
}
