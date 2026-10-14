package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/**
 * A failed/disconnected Responses stream may have received typed output, but it
 * did not complete successfully. A new explicit Send retains those items and
 * carries only locally received fragments missing from them as *incomplete*
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

    fun asVisibleTranscript(message: JSONObject, representedOutput: JSONArray? = null): JSONObject {
        require(isInterrupted(message)) { "Only interrupted OpenAI assistant turns can be annotated" }
        val status = message.optString("status")
        val representedText = mutableListOf<String>()
        val representedSummaries = mutableListOf<String>()
        var hasTypedMessage = false
        for (index in 0 until (representedOutput?.length() ?: 0)) {
            val item = representedOutput?.optJSONObject(index) ?: continue
            when (JsonProtocol.string(item, "type")) {
                "message" -> {
                    hasTypedMessage = true
                    val content = item.optJSONArray("content") ?: continue
                    for (partIndex in 0 until content.length()) {
                        val part = content.optJSONObject(partIndex) ?: continue
                        when (JsonProtocol.string(part, "type")) {
                            "output_text" -> representedText.add(JsonProtocol.string(part, "text"))
                            "refusal" -> representedText.add(JsonProtocol.string(part, "refusal"))
                        }
                    }
                }
                "reasoning" -> {
                    val summary = item.optJSONArray("summary") ?: continue
                    for (partIndex in 0 until summary.length()) {
                        val part = summary.optJSONObject(partIndex) ?: continue
                        if (JsonProtocol.string(part, "type") == "summary_text") {
                            representedSummaries.add(JsonProtocol.string(part, "text"))
                        }
                    }
                }
            }
        }
        val text = unrepresentedFragments(JsonProtocol.string(message, "text"), representedText)
        val reasoning = unrepresentedFragments(
            JsonProtocol.string(message, "reasoning_summary"), representedSummaries
        ).let { if (representedSummaries.isNotEmpty() && it.isBlank()) "" else it }
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
            if (hasTypedMessage) {
                append("Received typed message items are preserved above, including their original ")
                append("content and annotations. Their presence does not mean the request completed.\n")
            }
            if (!hasTypedMessage || text.isNotEmpty()) {
                append(if (hasTypedMessage) "Additional received assistant fragments not represented in typed output:\n"
                    else "Exact received assistant text:\n")
                append(text)
                append("\n[End of received assistant text]")
            }
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

    /**
     * Match only exact received characters, in output order. A partial last item
     * normally owns a prefix and leaves a streamed tail; multiple message items
     * can also leave an unmatched fragment between them. Never trim that tail or
     * reconstruct missing text from reasoning/tools. Divergent local text remains
     * available rather than being replaced by a canonical typed message.
     */
    private fun unrepresentedFragments(received: String, represented: List<String>): String {
        if (received.isEmpty() || represented.isEmpty()) return received
        val remaining = StringBuilder()
        var cursor = 0
        for (part in represented) {
            if (part.isEmpty() || cursor == received.length) continue
            val index = received.indexOf(part, cursor)
            if (index >= 0) {
                remaining.append(received, cursor, index)
                cursor = index + part.length
            } else {
                var commonPrefix = 0
                while (commonPrefix < part.length && cursor + commonPrefix < received.length &&
                    part[commonPrefix] == received[cursor + commonPrefix]) {
                    commonPrefix++
                }
                cursor += commonPrefix
            }
        }
        remaining.append(received, cursor, received.length)
        return remaining.toString()
    }
}
