package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

data class GenerationInstructions(
    val systemPrompt: String?,
    val developerInstructions: String?,
    val presentationInstructions: String? = null
) {
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    // A lone legacy developer message keeps its original string transport shape.
    // When a system prompt is configured, use typed Responses instructions so
    // that system and developer instructions retain their distinct API roles.
    fun openAi(): Any? {
        val system = listOfNotNull(clean(systemPrompt), clean(presentationInstructions)).joinToString("\n\n").takeIf { it.isNotBlank() }
        val developer = clean(developerInstructions)
        if (system == null) return developer
        return JSONArray().put(JSONObject().put("role", "system").put("content", system)).apply {
            developer?.let {
                put(JSONObject().put("role", "developer").put("content", it))
            }
        }
    }

    fun openRouter(
        githubContext: String?,
        attachmentContext: String?,
        localFileToolContext: String?
    ): String? =
        listOfNotNull(
            clean(githubContext),
            clean(systemPrompt),
            clean(developerInstructions),
            clean(attachmentContext),
            clean(localFileToolContext),
            clean(presentationInstructions)
        ).joinToString("\n\n").takeIf { it.isNotEmpty() }
}
