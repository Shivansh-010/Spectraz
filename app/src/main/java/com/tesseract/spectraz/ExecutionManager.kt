package com.tesseract.spectraz

import android.content.Context
import android.util.Log
import java.io.File
import com.tesseract.spectraz.RootUtils

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

    /**
     * Callback that is invoked with the final output from the model pipeline.
     * For example, you might execute the command or update the UI.
     */
    var onFinalCommand: ((String) -> Unit)? = null

    // Internal LiveData objects for each model's response.
    // (Assumes each AIModelBase-derived class defines onResponseReceived as MutableLiveData<String>.)
    // The chaining is set up in the init block below.
    init {

        Log.d("BOOTCHECK", "=============================EXECUTION MANAGER INIT=============================")

        // When QueryStepper returns output, log and pass to Tagger.
        queryStepper.onResponseReceived.observeForever { response ->
            onModelResponse("QueryStepper", response)
            tagger.sendMessage(response)
        }

        // When Tagger returns output, log and pass to CommandGenerator.
        tagger.onResponseReceived.observeForever { response ->
            onModelResponse("Tagger", response)
            commandGenerator.sendMessage(response)
        }

        // When CommandGenerator returns output, log and pass to CommandConsolidator.
        commandGenerator.onResponseReceived.observeForever { response ->
            onModelResponse("CommandGenerator", response)
            commandConsolidator.sendMessage(response)
        }

        // When CommandConsolidator returns output, log and pass to CommandVerifier.
        commandConsolidator.onResponseReceived.observeForever { response ->
            onModelResponse("CommandConsolidator", response)
            commandVerifier.sendMessage(response)
        }

        // When CommandVerifier returns output, log and deliver final result.
        commandVerifier.onResponseReceived.observeForever { response ->
            onModelResponse("CommandVerifier", response)
            onFinalCommand?.invoke(response)
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
}
