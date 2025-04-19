package com.tesseract.spectraz

import android.content.Context

class CommandVerifierModel(context: Context) : AIModelBase(context) {

    override fun initialize(apiKey: String, modelIDOverride: String) {
        val useModelID = if (modelIDOverride.isEmpty()) "gemini-2.0-flash" else modelIDOverride

        // Optional: Uncomment and adapt path handling if you want to use context files
        // contextFiles = listOf("path/to/CommandVerifier.md")

        importContext = true

        super.initialize(apiKey, useModelID)
    }
}
