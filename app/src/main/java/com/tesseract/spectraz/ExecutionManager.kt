package com.tesseract.spectraz

import android.content.Context
import android.util.Log
import java.io.File
import com.tesseract.spectraz.RootUtils
import com.tesseract.spectraz.RootUtils.readFileWithRoot
import org.json.JSONObject

/**
 * ExecutionManager routes queries through the model pipeline.
 * It also provides a method to configure each model from a config file.
 */
class ExecutionManager(private val context: Context) {

    // Model instances; they are constructed with the same context.
    val queryStepper = QueryStepperModel(context)
    val tagger = TaggerModel(context)
    val commandGenerator = CommandGeneratorModel(context)
    val commandConsolidator = CommandConsolidatorModel(context)
    val commandVerifier = CommandVerifierModel(context)

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
            onModelResponse("QueryStepper", response)

            // Optional: clean markdown fences here if needed
            tagger.sendMessage(response)
        }

        // Step 2: Tagger to CommandGenerator with Context Injection
        tagger.onResponseReceived.observeForever { response ->
            onModelResponse("Tagger", response)

            // Documentation JSON Injection
            try {
                // Clean markdown fences like ```json and ``` if present
                val cleanedJson = response.replace("```json", "").replace("```", "").trim()

                val jsonObject = JSONObject(cleanedJson)
                val stepsArray = jsonObject.getJSONArray("steps")

                for (i in 0 until stepsArray.length()) {
                    val step = stepsArray.getJSONObject(i)
                    val tag = step.optString("tag", "")
                    if (tag.isNotEmpty()) {
                        // Construct file path
                        val docPath = "/storage/emulated/0/Documents/Obsidian_Live/_KnowledgeBase/DataFiles/Documentation/$tag.md"

                        // Read file using your root-enabled file reader
                        val docContent = readFileWithRoot(docPath)

                        // Replace tag with documentation field
                        step.remove("tag")
                        step.put("documentation", docContent)
                    }
                }

                // Pass JSON with tool Documentation to CommandGenerator
                commandGenerator.sendMessage(jsonObject.toString())

            } catch (e: Exception) {
                Log.e("ExecutionManager", "Error processing Tagger response: ${e.message}")
            }
        }

        // Step 3: CommandGenerator to CommandConsolidator
        commandGenerator.onResponseReceived.observeForever { response ->
            onModelResponse("CommandGenerator", response)

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
            onModelResponse("CommandConsolidator", response)
            commandVerifier.sendMessage(response)
        }

        // Step 5: Final command output
        commandVerifier.onResponseReceived.observeForever { response ->
            onModelResponse("CommandVerifier", response)

            // Clean markdown fences if present
            val cleanedJson = response.replace("```json", "").replace("```", "").trim()

            try {
                val jsonObject = JSONObject(cleanedJson)
                val result = jsonObject.optString("verification_result", "")

                if (result == "success") {
                    onFinalCommand?.invoke(cleanedJson)
                } else {
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
