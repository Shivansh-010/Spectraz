package com.tesseract.spectraz

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.View
import androidx.core.app.ActivityCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import androidx.core.graphics.toColorInt
import android.view.animation.AnimationUtils
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {

    private lateinit var terminalView: TextView
    private lateinit var jsonView : TextView
    private lateinit var inputField: EditText
    private lateinit var sendButton: Button
    private lateinit var scrollView: ScrollView
    private lateinit var sendAiButton: Button
    private lateinit var environmentSwitch: MaterialSwitch

    private lateinit var executionManager: ExecutionManager
    private lateinit var terminalWrapper: TerminalWrapper

    private var RunThroughOrchestrator: Boolean = false
    var TerminalRelay: Boolean = false

    // Cache of active stages and their colors
    val stageColors = mutableMapOf<Int, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setTheme(R.style.Theme_Spectraz)
        // hide top action bar
        supportActionBar?.hide()

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
        environmentSwitch = findViewById<MaterialSwitch>(R.id.EnvSwitch)
        // val isEnvironmentEnabled = environmentSwitch.isChecked

        // After Initializing jsonView
        initExecutionManager()

        // Observe terminal output changes
        terminalWrapper.liveHistory.observe(this, { history ->
            history.forEach { entry ->
                terminalView.append("${entry.command}\n${entry.output}\n")

                // Relay to orchestrator Model
                if(TerminalRelay){
                    executionManager.orchestratorModel.sendMessage("${entry.command}\n${entry.output}\n")
                }

                scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
            }
        })

        sendButton.setOnClickListener {
            val command = inputField.text.toString()
            if (command.isNotEmpty()) {
                if(RunThroughOrchestrator){
                    executionManager.orchestratorModel.sendMessage(command)
                } else {
                    executeCommandInTerminal(command)
                }

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
        val environmentSwitch = findViewById<MaterialSwitch>(R.id.EnvSwitch)

        environmentSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                terminalWrapper.setEnvironment(ExecutionEnvironment.ANDROID);
            } else {
                terminalWrapper.setEnvironment(ExecutionEnvironment.DEBIAN);
            }
        }
    }

    public fun setStagesUpTo(stage: Int, color: Int) {
        val boxes = listOf(
            findViewById<View>(R.id.box0),
            findViewById<View>(R.id.box1),
            findViewById<View>(R.id.box2),
            findViewById<View>(R.id.box3),
            findViewById<View>(R.id.box4),
            findViewById<View>(R.id.box5)
        )

        val glowAnimation = AnimationUtils.loadAnimation(this, R.anim.glow)
        val defaultOffColor = "#222222".toColorInt()
        val glowInsetDp = 4f // Adjust this value to change the size of the inner square (higher value = smaller square)
        val innerCornerRadiusDp = 2f // Optional: Rounded corners for the inner square

        // Convert DP values to pixels
        val glowInsetPx = dpToPx(glowInsetDp, this).toInt()
        val innerCornerRadiusPx = dpToPx(innerCornerRadiusDp, this)

        // --- State Management (same as before) ---
        stageColors[stage] = color
        val keysToRemove = stageColors.keys.filter { it > stage }
        keysToRemove.forEach { stageColors.remove(it) }
        // --- End State Management ---


        // --- Create Drawables ---
        // Drawable for the OFF state (simple solid color)
        val offDrawable = ColorDrawable(defaultOffColor)


        // --- Update UI ---
        boxes.forEachIndexed { index, view ->
            view.clearAnimation() // Clear any previous animation

            if (index <= stage) {
                // This box should be ON (glowing square)
                val boxColor = stageColors[index] ?: defaultOffColor // Should always find a color here now

                // Create the square glow drawable dynamically using LayerDrawable
                val squareGlowDrawable = createSquareGlowDrawable(
                    boxColor,
                    defaultOffColor,
                    glowInsetPx,
                    innerCornerRadiusPx
                )

                view.background = squareGlowDrawable // Set the drawable background

                // Only animate the *most recently activated* stage
                if (index == stage) {
                    view.startAnimation(glowAnimation)
                }
            } else {
                // This box should be OFF
                view.background = offDrawable // Use the simple OFF drawable
            }
        }
    }

    /**
     * Helper function to create a LayerDrawable simulating an inset square glow.
     *
     * @param glowColor The color of the inner square.
     * @param backgroundColor The color of the outer background area.
     * @param insetPx The inset in pixels applied to all sides of the inner square.
     * @param innerCornerRadiusPx Corner radius for the inner glow square.
     * @return A LayerDrawable representing the effect.
     */
    private fun createSquareGlowDrawable(
        glowColor: Int,
        backgroundColor: Int,
        insetPx: Int,
        innerCornerRadiusPx: Float
    ): Drawable {
        // Layer 0: Background
        val backgroundDrawable = ColorDrawable(backgroundColor)

        // Layer 1: Inner Glow Square
        // Using GradientDrawable for easy corner radius, but set as solid color
        val glowSquareDrawable = GradientDrawable()
        glowSquareDrawable.shape = GradientDrawable.RECTANGLE
        glowSquareDrawable.setColor(glowColor)
        glowSquareDrawable.cornerRadius = innerCornerRadiusPx // Apply corner radius

        // Combine layers
        val layers = arrayOf(backgroundDrawable, glowSquareDrawable)
        val layerDrawable = LayerDrawable(layers)

        // Apply inset to the top layer (index 1) to make the square smaller and centered
        layerDrawable.setLayerInset(1, insetPx, insetPx, insetPx, insetPx)

        return layerDrawable
    }

    /**
     * Helper function to convert DP to Pixels.
     */
    private fun dpToPx(dp: Float, context: Context): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
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
        executionManager = ExecutionManager(this, this)

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
        setStagesUpTo(5, getStageColor(5))

        Log.d("MainActivity", "Executing command: $command")

        terminalWrapper.runCommand(command, asRoot = true)
    }

    fun getStageColor(stage: Int): Int {
        return when (stage) {
            0 -> "#CCCCCC".toColorInt() // Query Stepper white
            1 -> "#BC8A15".toColorInt() // Tagger dull yellow
            2 -> "#1FC7E5".toColorInt() // Command Generator cyan
            3 -> "#19A87D".toColorInt() // Consolidator dull green
            4 -> "#9859E6".toColorInt() // Verifier wisteria
            5 -> "#00FF00".toColorInt() // Terminal Output Terminal green
            else -> "#222222".toColorInt()
        }
    }

    fun setStageActive(stage: Int, color: Int) {
        val boxes = listOf(
            findViewById<View>(R.id.box0),
            findViewById<View>(R.id.box1),
            findViewById<View>(R.id.box2),
            findViewById<View>(R.id.box3),
            findViewById<View>(R.id.box4),
            findViewById<View>(R.id.box5)
        )

        val glow = AnimationUtils.loadAnimation(this, R.anim.glow)

        boxes.forEachIndexed { index, view ->
            view.clearAnimation()

            if (index == stage) {
                view.setBackgroundColor(color)
                view.startAnimation(glow)
            } else {
                view.setBackgroundColor("#222222".toColorInt())
            }
        }
    }
}
