package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

data class GenerationInstructions(
    val systemPrompt: String?,
    val developerInstructions: String?,
    val presentationInstructions: String? = null
) {
    private fun clean(value: String?): String? = value?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Responses Create accepts a string (not an array) in "instructions". Role-bearing
     * messages belong in "input": this preserves system vs developer priority without
     * flattening either into a single instruction string. Apply on every request,
     * including previous_response_id and locally replayed typed input.
     *
     * Preserve every existing input item by reference (including file, image, tool
     * and reasoning items); never stringify the array or turn it into plain text.
     */
    fun applyToOpenAiRequest(body: JSONObject) {
        require(!body.has("instructions")) { "Unexpected top-level Responses instructions" }
        val input = body.optJSONArray("input")
            ?: throw IllegalArgumentException("Responses input must be an array")
        val system = listOfNotNull(clean(systemPrompt), clean(presentationInstructions))
            .joinToString("\n\n").takeIf { it.isNotBlank() }
        val developer = clean(developerInstructions)
        if (system == null && developer == null) return

        val withRoles = JSONArray()
        system?.let { withRoles.put(JSONObject().put("role", "system").put("content", it)) }
        developer?.let { withRoles.put(JSONObject().put("role", "developer").put("content", it)) }
        for (index in 0 until input.length()) withRoles.put(input.get(index))
        body.put("input", withRoles)
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
