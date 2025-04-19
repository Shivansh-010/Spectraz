package com.tesseract.spectraz

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var outputView: TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

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

    private fun readShellOutput() {
        Thread {
            val output = TerminalNative.readFromShell()
            runOnUiThread {
                outputView.append(output + "\n")
                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        }.start()
    }
}
