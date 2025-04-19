package com.tesseract.spectraz

import android.content.Context
import android.util.Log
import androidx.lifecycle.MutableLiveData
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import com.tesseract.spectraz.RootUtils

data class AIMessage(
    val role: String, // "user" or "model"
    val content: String
)

open class AIModelBase(private val context: Context) {

    private var apiKey: String = ""
    private var modelID: String = ""
    private var endpoint: String = ""

    val onResponseReceived = MutableLiveData<String>()
    val conversationHistory = mutableListOf<AIMessage>()

    var contextFiles: List<String> = emptyList()
    var importContext: Boolean = true

    open fun initialize(inApiKey: String, inModelID: String) {
        apiKey = inApiKey
        modelID = inModelID
        endpoint = "https://generativelanguage.googleapis.com/v1beta/models/$modelID:streamGenerateContent?key=$apiKey"

        if (importContext) {
            for (filePath in contextFiles) {
                try {
                    val text = RootUtils.readFileWithRoot(filePath)
                    conversationHistory.add(AIMessage("user", text))
                    Log.d("AIModel", "Context loaded from $filePath")
                } catch (e: Exception) {
                    Log.w("AIModel", "Failed to load context from $filePath")
                }
            }
        }
    }

    fun sendMessage(userInput: String) {
        Log.d("AIModel", "→ Sending to model [$modelID]: $userInput")
        conversationHistory.add(AIMessage("user", userInput))
        val payload = buildRequestPayload(userInput)
        dispatchHttpRequest(payload)
    }

    fun clearHistory() {
        conversationHistory.clear()
    }

    fun exportHistoryToFile(filePath: String) {
        val output = buildString {
            conversationHistory.forEach {
                append("[${it.role}] ${it.content}\n")
            }
        }
        File(filePath).writeText(output)
    }

    fun loadHistoryFromFile(filePath: String) {
        val lines = File(filePath).readLines()
        conversationHistory.clear()
        lines.forEach { line ->
            when {
                line.startsWith("[user] ") -> conversationHistory.add(AIMessage("user", line.removePrefix("[user] ")))
                line.startsWith("[model] ") -> conversationHistory.add(AIMessage("model", line.removePrefix("[model] ")))
            }
        }
    }

    private fun buildRequestPayload(inputText: String): String {
        val root = JSONObject()
        val contents = JSONArray()

        for (msg in conversationHistory) {
            val message = JSONObject()
            message.put("role", msg.role)
            val part = JSONObject().put("text", msg.content)
            message.put("parts", JSONArray().put(part))
            contents.put(message)
        }

        val userInput = JSONObject().apply {
            put("role", "user")
            val userPart = JSONObject().put("text", inputText)
            put("parts", JSONArray().put(userPart))
        }

        contents.put(userInput)
        root.put("contents", contents)

        val generationConfig = JSONObject()
        generationConfig.put("responseMimeType", "text/plain")
        root.put("generationConfig", generationConfig)

        return root.toString()
    }

    private fun dispatchHttpRequest(jsonPayload: String) {
        val requestBody = RequestBody.create("application/json".toMediaTypeOrNull(), jsonPayload)
        val request = Request.Builder()
            .url(endpoint)
            .post(requestBody)
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("AIModel", "HTTP Request failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                val cleanText = extractCleanResponseText(responseBody)
                conversationHistory.add(AIMessage("model", cleanText))
                Log.d("AIModel", "← Response from model [$modelID]: $cleanText")
                onResponseReceived.postValue(cleanText)
            }
        })
    }

    private fun extractCleanResponseText(jsonString: String?): String {
        if (jsonString == null) return "Error: No response"

        return try {
            val rootArray = JSONArray(jsonString)
            val collectedText = StringBuilder()

            for (i in 0 until rootArray.length()) {
                val obj = rootArray.getJSONObject(i)
                val candidates = obj.optJSONArray("candidates") ?: continue
                for (j in 0 until candidates.length()) {
                    val candidate = candidates.getJSONObject(j)
                    val content = candidate.optJSONObject("content") ?: continue
                    val parts = content.optJSONArray("parts") ?: continue
                    for (k in 0 until parts.length()) {
                        val part = parts.getJSONObject(k)
                        val text = part.optString("text", "")
                        collectedText.append(text)
                    }
                }
            }

            if (collectedText.isEmpty()) "No text found in model response." else collectedText.toString()

        } catch (e: Exception) {
            "Error parsing model response: ${e.message}"
        }
    }
}
