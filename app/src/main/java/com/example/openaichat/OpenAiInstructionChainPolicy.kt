package com.example.openaichat

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Responses previous_response_id inherits input messages from the server-side chain.
 * Roles injected into the first request must NOT be inserted again on every turn:
 * doing so duplicates system/developer rules and leaves outdated rules after edits.
 *
 * Persist only hashes, never the credential or instruction text. The versioned
 * context binds both the credential and model as well as privileged instructions.
 * Legacy instruction-only hashes intentionally require full local typed replay.
 */
object OpenAiInstructionChainPolicy {
    const val FINGERPRINT_FIELD = "openai_instructions_sha256"
    private val boundFingerprint = Regex("v2:[0-9a-f]{64}:[0-9a-f]{64}")

    // OpenAI identifiers are case-sensitive. Normalize surrounding whitespace
    // only, as it is not part of the model name sent over the wire.
    fun normalizedModel(model: String): String = model.trim()

    fun credentialHash(apiKey: String): String = MessageDigest.getInstance("SHA-256")
        .digest(apiKey.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun fingerprint(instructions: GenerationInstructions, apiKey: String, model: String): String {
        if (apiKey.isBlank() || normalizedModel(model).isBlank()) return ""
        val credentialHash = credentialHash(apiKey)
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            "openai-chain-v2",
            credentialHash,
            normalizedModel(model),
            instructions.systemPrompt,
            instructions.developerInstructions,
            instructions.presentationInstructions
        ).forEach { value ->
            val bytes = value.orEmpty().trim().toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        return "v2:$credentialHash:" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Missing/legacy bindings must never send a response ID to another account. */
    fun matchesCredential(fingerprint: String?, apiKey: String): Boolean =
        !apiKey.isBlank() && fingerprint != null && boundFingerprint.matches(fingerprint) &&
            MessageDigest.isEqual(fingerprint.substring(3, 67).toByteArray(Charsets.US_ASCII),
                credentialHash(apiKey).toByteArray(Charsets.US_ASCII))

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
        if (regenerating || previousResponseId.isNullOrBlank() || !boundFingerprint.matches(fingerprint)) return false
        val previous = history.lastOrNull() ?: return false
        return previous.optString("role") == "assistant" &&
            previous.optString("provider") == "openai" &&
            previous.optString("status") == "completed" &&
            previous.optString("response_id") == previousResponseId &&
            previous.optString(FINGERPRINT_FIELD) == fingerprint
    }
}
