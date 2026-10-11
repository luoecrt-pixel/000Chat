package com.example.openaichat

import org.json.JSONObject

/**
 * OpenRouter Anthropic caching uses ONE automatic breakpoint at request level.
 * The documented five-minute default omits ttl; one hour uses ttl="1h".
 * Do not attach an implicit five-minute breakpoint to the system message: Claude
 * processes tools, system and messages in order, and mixed TTLs can invalidate it.
 */
object OpenRouterCachePolicy {
    fun systemMessage(content: String): JSONObject =
        JSONObject().put("role", "system").put("content", content)

    fun configureRequest(body: JSONObject, model: String, mode: String = "1h") {
        body.remove("cache_control")
        if (model.trim().removePrefix("~").startsWith("anthropic/") && mode != "off") {
            require(mode in setOf("1h", "5m")) { "Unknown cache mode" }
            body.put("cache_control", JSONObject().put("type", "ephemeral").apply {
                if (mode == "1h") put("ttl", "1h")
            })
        }
    }
}
