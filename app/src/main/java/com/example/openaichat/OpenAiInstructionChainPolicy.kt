package com.example.openaichat

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Responses previous_response_id inherits input messages from the server-side chain.
 * Roles injected into the first request must NOT be inserted again on every turn:
 * doing so duplicates system/developer rules and leaves outdated rules after edits.
 *
 * Persist only a digest, not the user's instruction text. Older chat records without
 * the digest are intentionally ineligible: they must use full typed-history replay.
 */
object OpenAiInstructionChainPolicy {
    const val FINGERPRINT_FIELD = "openai_instructions_sha256"

    fun fingerprint(instructions: GenerationInstructions): String {
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            instructions.systemPrompt,
            instructions.developerInstructions,
            instructions.presentationInstructions
        ).forEach { value ->
            val bytes = value.orEmpty().trim().toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Only the immediately preceding completed OpenAI assistant turn can anchor a
     * continuation. A failed/incomplete turn, an intervening user turn, regenerate,
     * provider switch or changed instructions requires replay from local history.
     */
    fun canReuse(
        previousResponseId: String?,
        history: List<JSONObject>,
        fingerprint: String,
        regenerating: Boolean = false
    ): Boolean {
        if (regenerating || previousResponseId.isNullOrBlank()) return false
        val previous = history.lastOrNull() ?: return false
        return previous.optString("role") == "assistant" &&
            previous.optString("provider") == "openai" &&
            previous.optString("status") == "completed" &&
            previous.optString("response_id") == previousResponseId &&
            previous.optString(FINGERPRINT_FIELD) == fingerprint
    }
}
