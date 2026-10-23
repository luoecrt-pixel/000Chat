package com.example.openaichat

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stable fingerprint for fields persisted from one chat message.
 * This lets ChatDatabase skip SQL writes for messages that did not change.
 */
object ChatMessageFingerprint {
    private val persistedKeys = listOf(
        "role", "text", "response_id", "request_id",
        "file_ids", "file_names", "file_sizes", "file_paths", "file_mimes", "attachments",
        "image_path", "image_file_id", "image_name", "image_mime", "image_size",
        "annotations", "generated_images", "tools", "status", "feedback"
    ) + MessageMetadataCodec.allKeys.filterNot { it == "deepseek_protocol" }

    fun of(message: JSONObject): String {
        val normalized = JSONArray()
        persistedKeys.forEach { key ->
            if (key in MessageMetadataCodec.allKeys) {
                normalized.put(MessageMetadataCodec.serialized(message, key) ?: JSONObject.NULL)
                return@forEach
            }
            when (key) {
                "role", "text" -> normalized.put(
                    JsonProtocol.string(message, key)
                )
                "feedback" -> normalized.put(
                    if (message.has(key) && !message.isNull(key)) message.optInt(key) else JSONObject.NULL
                )
                "response_id", "request_id", "image_path", "image_file_id", "image_name", "image_mime", "status" ->
                    normalized.put(JsonProtocol.string(message, key).takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                "image_size" ->
                    normalized.put(message.optLong(key, 0L).coerceAtLeast(0L))
                else ->
                    normalized.put(message.optJSONArray(key)?.toString() ?: JSONObject.NULL)
            }
        }
        // Retain existing v1 hashes for messages with no DeepSeek protocol. The optional
        // extension is tagged and appended only when it actually carries persisted data.
        MessageMetadataCodec.serialized(message, "deepseek_protocol")?.let {
            normalized.put("deepseek_protocol").put(it)
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
