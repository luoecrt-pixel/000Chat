package com.example.openaichat

import org.json.JSONObject

/**
 * A failed/disconnected Responses stream has no trustworthy terminal output array.
 * A new explicit Send may carry the exact locally received fragment as *incomplete*
 * transcript context. It must not masquerade as an API-completed message or imply
 * that inaccessible encrypted reasoning or tool payloads have been reconstructed.
 *
 * This only serializes already-persisted data; it makes no provider call, retries
 * nothing, and does not mutate the original failed assistant chat record.
 */
object OpenAiInterruptedTurnReplay {
    private val statuses = setOf("failed", "disconnected", "incomplete", "cancelled")

    fun isInterrupted(message: JSONObject): Boolean =
        message.optString("provider") == "openai" &&
            message.optString("role") == "assistant" &&
            message.optString("status") in statuses

    fun asVisibleTranscript(message: JSONObject): JSONObject {
        require(isInterrupted(message)) { "Only interrupted OpenAI assistant turns can be annotated" }
        val status = message.optString("status")
        val text = JsonProtocol.string(message, "text")
        val reasoning = JsonProtocol.string(message, "reasoning_summary")
        val tools = message.optJSONArray("tools")
        val toolLabels = (0 until (tools?.length() ?: 0))
            .mapNotNull { i -> tools?.optString(i)?.takeIf { it.isNotBlank() } }
        val description = buildString {
            append("[Interrupted OpenAI assistant response: ").append(status).append("]\n")
            append("The previous request did NOT complete successfully. The following is a ")
            append("locally preserved, incomplete transcript, not a completed Responses output. ")
            append("Any internal reasoning or tool payloads that were not actually received ")
            append("cannot be reconstructed; do not assume such missing data exists. ")
            append("Do not retry or regenerate the interrupted request.\n")
            append("Exact received assistant text:\n")
            append(text)
            append("\n[End of received assistant text]")
            if (reasoning.isNotEmpty()) {
                append("\nReceived reasoning summary (not private reasoning):\n")
                append(reasoning)
                append("\n[End of received reasoning summary]")
            }
            if (toolLabels.isNotEmpty()) {
                append("\nObserved tool labels only (not tool result payloads): ")
                append(toolLabels.joinToString(", "))
            }
        }
        return JSONObject().put("role", "assistant").put("content", description)
    }
}
