package com.example.openaichat

import org.json.JSONObject

/**
 * OpenRouter Anthropic caching uses ONE automatic 1-hour breakpoint at request level.
 * Do not attach an implicit five-minute breakpoint to the system message: Claude
 * processes tools, system and messages in order, and mixed TTLs can invalidate it.
 */
object OpenRouterCachePolicy {
    fun systemMessage(content: String): JSONObject =
        JSONObject().put("role", "system").put("content", content)

    fun configureRequest(body: JSONObject, model: String) {
        if (model.trim().removePrefix("~").startsWith("anthropic/")) {
            body.put("cache_control", JSONObject().put("type", "ephemeral").put("ttl", "1h"))
        }
    }
}
