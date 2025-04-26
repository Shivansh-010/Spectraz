package com.tesseract.spectraz

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.widget.TextView
import java.io.File
import com.tesseract.spectraz.RootUtils
import com.tesseract.spectraz.RootUtils.readFileWithRoot
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * ExecutionManager routes queries through the model pipeline.
 * It also provides a method to configure each model from a config file.
 */
class ExecutionManager(private val context: Context, private val activity: MainActivity) {

    // Model instances; they are constructed with the same context.
    val queryStepper = QueryStepperModel(context)
    val tagger = TaggerModel(context)
    val commandGenerator = CommandGeneratorModel(context)
    val commandConsolidator = CommandConsolidatorModel(context)
    val commandVerifier = CommandVerifierModel(context)
    val orchestratorModel = OrchestratorModel(context)

    private lateinit var jsonView: TextView

    // Global variable to store the formatted response string
    var lastModelResponse: String? = null

    // for verification failure
    var verificationFailureReason: String? = null

    var onAskUserRequest: ((String) -> Unit)? = null

    /**
     * Callback that is invoked with the final output from the model pipeline.
     * For example, you might execute the command or update the UI.
     */
    var onFinalCommand: ((String) -> Unit)? = null
    init {

        Log.d("ExecutionManager", "EXECUTION MANAGER INIT")

        // Step 1: QueryStepper to Tagger
        queryStepper.onResponseReceived.observeForever { response ->
            activity.setStagesUpTo(0, activity.getStageColor(0))
            lastModelResponse = "QueryStepper\n$response"
            jsonView.text = lastModelResponse
            onModelResponse("QueryStepper", response)

            // Use cleanAndExtractJson() instead of isValidJson()
            val cleanJson = cleanAndExtractJson(response)
            if (cleanJson != null) {
                tagger.sendMessage(cleanJson)
            } else {
                Log.w("AIModelPipeline", "Skipping Tagger. Not valid JSON.")
            }
        }

        // this will also work when tagname is TaG and filename is "tAg"
        // Step 2: Tagger to CommandGenerator with Context Injection
        tagger.onResponseReceived.observeForever { response ->
            activity.setStagesUpTo(1, activity.getStageColor(1))
            onModelResponse("Tagger", response)
            lastModelResponse = "Tagger\n$response"
            jsonView.text = lastModelResponse

            try {
                // clean up any fences
                val cleanedJson = response
                    .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
                    .replace("```", "")
                    .trim()

                val jsonObject = JSONObject(cleanedJson)
                val stepsArray = jsonObject.getJSONArray("steps")

                for (i in 0 until stepsArray.length()) {
                    val step = stepsArray.getJSONObject(i)

                    // 1) collect tags (single or multiple)
                    val tagList = mutableListOf<String>()
                    // single‑tag legacy support
                    step.optString("tag")?.takeIf { it.isNotBlank() }?.let { tagList.add(it) }
                    // multi‑tag support
                    step.optJSONArray("tags")?.let { arr ->
                        for (j in 0 until arr.length()) {
                            arr.optString(j)?.takeIf { it.isNotBlank() }?.let { tagList.add(it) }
                        }
                    }

                    // 2) read all docs (ensuring lowercase consistency)
                    val docsJson = JSONArray()
                    tagList.forEach { tagName ->
                        // Normalize the tag name to lowercase.
                        val normalizedTagName = tagName.lowercase()
                        // Build the expected file name in lowercase.
                        val expectedFileName = "$normalizedTagName.md"
                        val docPath =
                            "/storage/emulated/0/Documents/Obsidian_Live/_KnowledgeBase/DataFiles/Documentation/$expectedFileName"

                        // Create a File instance so we can verify the file's name.
                        val docFile = File(docPath)
                        if (docFile.exists()) {
                            // Compare the lowercased file name with the expected file name.
                            if (docFile.name.lowercase() == expectedFileName) {
                                val docContent = readFileWithRoot(docPath)
                                docsJson.put(docContent)
                            } else {
                                Log.e("DocConsistency", "Mismatch: tag [$normalizedTagName] vs file name [${docFile.name.lowercase()}]")
                            }
                        } else {
                            Log.e("DocConsistency", "File not found: $docPath")
                        }
                    }

                    // 3) remove old fields & inject docs array
                    step.remove("tag")
                    step.remove("tags")
                    step.put("documentation", docsJson)
                }

                // send enriched JSON forward
                commandGenerator.sendMessage(jsonObject.toString())

            } catch (e: Exception) {
                Log.e("ExecutionManager", "Error processing Tagger response: ${e.message}")
            }
        }

        // Step 3: CommandGenerator to CommandConsolidator
        commandGenerator.onResponseReceived.observeForever { response ->
            activity.setStagesUpTo(2, activity.getStageColor(2))
            onModelResponse("CommandGenerator", response)

            jsonView.setText(lastModelResponse)

            lastModelResponse = "commandGenerator\n$response"

            try {
                // Clean markdown fences if needed
                val cleanedResponse = response.replace("```json", "").replace("```", "").trim()

                // Check for ask_user line
                val askUserRegex = Regex("""ask_user:\s*(.+)""", RegexOption.IGNORE_CASE)
                val match = askUserRegex.find(cleanedResponse)
                if (match != null) {
                    val userQuery = match.groupValues[1].trim()
                    Log.d("ExecutionManager", "Model requested user input: $userQuery")

                    // Send user query to UI or handler
                    onAskUserRequest?.invoke(userQuery)
                    return@observeForever
                }

                // Parse JSON and strip documentation
                val jsonObject = JSONObject(cleanedResponse)
                val stepsArray = jsonObject.getJSONArray("steps")

                for (i in 0 until stepsArray.length()) {
                    val step = stepsArray.getJSONObject(i)
                    step.remove("documentation")
                }

                // Send cleaned JSON to consolidator
                commandConsolidator.sendMessage(jsonObject.toString())

            } catch (e: Exception) {
                Log.e("ExecutionManager", "Error handling CommandGenerator response: ${e.message}")
            }
        }

        // Step 4: CommandConsolidator to CommandVerifier
        commandConsolidator.onResponseReceived.observeForever { response ->
            activity.setStagesUpTo(3, activity.getStageColor(3))
            onModelResponse("CommandConsolidator", response)
            lastModelResponse = "commandConsolidator\n$response"
            jsonView.setText(lastModelResponse)
            commandVerifier.sendMessage(response)
        }

        // Step 5: Final command output
        commandVerifier.onResponseReceived.observeForever { response ->
            onModelResponse("CommandVerifier", response)
            lastModelResponse = "commandVerifier\n$response"
            jsonView.setText(lastModelResponse)
            // Clean markdown fences if present
            val cleanedJson = response.replace("```json", "").replace("```", "").trim()

            try {
                val jsonObject = JSONObject(cleanedJson)
                val result = jsonObject.optString("verification_result", "")

                if (result == "success") {
                    activity.setStagesUpTo(4, activity.getStageColor(4))
                    onFinalCommand?.invoke(cleanedJson)
                } else {
                    activity.setStagesUpTo(4, Color.RED) // Indicate error in verification
                    val reason = jsonObject.optString("reason", "Unknown verification failure.")
                    Log.e("ExecutionManager", "Verification failed: $reason")

                    // Store the reason
                    verificationFailureReason = reason

                    // Send a correction prompt to the Command Generator
                    val retryMessage = """
                        The command you generated has failed verification due to the following reason:
                        "$verificationFailureReason"
                    
                        Please fix the issue and regenerate the correct JSON output for the terminal command steps.
                    
                        If you require additional input from the user to resolve the issue, include a line starting with:
                        ask_user: your question here
                    """.trimIndent()


                    commandGenerator.sendMessage(retryMessage)
                }


            } catch (e: Exception) {
                Log.e("ExecutionManager", "Error parsing verifier response: ${e.message}")
            }
        }

        orchestratorModel.onResponseReceived.observeForever { response ->
            
        }
    }

    fun cleanAndExtractJson(input: String): String? {
        // Remove Markdown fences
        val noFences = input
            .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
            .replace("```", "")
            .trimStart()

        // Find where JSON actually begins
        val start = noFences.indexOfFirst { it == '{' || it == '[' }
        if (start == -1) return null

        val candidate = noFences.substring(start).trim()
        return try {
            when {
                candidate.startsWith("{") -> {
                    JSONObject(candidate)    // throws if invalid
                    candidate
                }
                candidate.startsWith("[") -> {
                    JSONArray(candidate)
                    candidate
                }
                else -> null
            }
        } catch (e: JSONException) {
            null
        }
    }

    // called from execution manager to set to UI textbox
    fun setJsonView(view: TextView) {
        this.jsonView = view
    }

    /**
     * Called each time a model produces output.
     *
     * @param modelName The name of the model (e.g., "QueryStepper").
     * @param response The response produced by the model.
     */
    private fun onModelResponse(modelName: String, response: String) {
        Log.d("ExecutionManager", "$modelName produced response: $response")
        // Additional processing can be done here if needed.
    }

    fun configureModelsFromFile(configPath: String) {
        val configText = RootUtils.readFileWithRoot(configPath)
        if (configText.isBlank()) {
            Log.e("ExecutionManager", "Failed to read config file (empty or error): $configPath")
            return
        }

        val modelConfigs = mutableMapOf<String, MutableMap<String, String>>()
        var currentModel: String? = null

        // Split into lines and parse
        configText.lines().forEach { line ->
            val trimmedLine = line.trim()
            if (trimmedLine.startsWith("## ")) {
                // Section header: model name
                currentModel = trimmedLine.substring(3).trim()
                modelConfigs[currentModel!!] = mutableMapOf()
                Log.d("ExecutionManager", "Parsing model config: $currentModel")
            } else if (trimmedLine.contains(":") && currentModel != null) {
                val parts = trimmedLine.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim()
                    modelConfigs[currentModel!!]!![key] = value
                    Log.d("ExecutionManager", "  $key = $value")
                }
            }
        }

        // Local function to configure a model from settings
        fun configureModel(model: AIModelBase?, settings: Map<String, String>) {
            if (model == null) return

            val APIKey = settings["APIKey"] ?: ""
            val ModelID = settings["ModelID"] ?: ""
            Log.d("ExecutionManager", "Initializing model with APIKey=$APIKey, ModelID=$ModelID")

            // Handle the "Context" field
            if (settings.containsKey("Context")) {
                val contextStr = settings["Context"] ?: ""
                // Expecting a comma-separated list of paths
                val paths = contextStr.split(",").map { it.trim() }
                val adjustedPaths = paths.map { path ->
                    // If the path is absolute (starts with "/"), keep it;
                    // otherwise, prepend a default directory (you can adjust as needed).
                    if (path.startsWith("/")) path
                    else File(context.filesDir, path).absolutePath
                }
                model.contextFiles = adjustedPaths
                adjustedPaths.forEach { Log.d("ExecutionManager", "    Context File: $it") }
            }

            // Set import context based on the key (defaults to true)
            model.importContext = settings["ImportContext"]?.lowercase() != "false"
            Log.d("ExecutionManager", "    ImportContext: ${model.importContext}")

            // Finally, initialize the model with APIKey and ModelID
            model.initialize(APIKey, ModelID)
        }

        // Configure each model if a section exists in the config file.
        modelConfigs["QueryStepper"]?.let { configureModel(queryStepper, it) }
        modelConfigs["Tagger"]?.let { configureModel(tagger, it) }
        modelConfigs["CommandGenerator"]?.let { configureModel(commandGenerator, it) }
        modelConfigs["CommandConsolidator"]?.let { configureModel(commandConsolidator, it) }
        modelConfigs["CommandVerifier"]?.let { configureModel(commandVerifier, it) }
        modelConfigs["Orchestrator"]?.let { configureModel(orchestratorModel, it) }
    }

    // Additional functions to process queries and route through the pipeline can be added here.
    fun processQuery(query: String) {
        // Example: send query to the first model
        queryStepper.sendMessage(query)
        Log.d("AIModel", "processQuery(query: String")
    }

    // if command generator model asks for user response
    fun submitUserInputToCommandGenerator(userInput: String) {
        val formattedInput = """
        The user has provided the following input in response to your previous request:
        "$userInput"
        
        Please continue by generating the correct JSON output.
    """.trimIndent()

        commandGenerator.sendMessage(formattedInput)
    }

}
