package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCachePolicyTest {
    @Test
    fun anthropicSystemAndConversationUseOneAutomaticOneHourCacheMarker() {
        val messages = JSONArray()
            .put(OpenRouterCachePolicy.systemMessage("Repo tools and system context"))
            .put(JSONObject().put("role", "user").put("content", "First prompt"))
            .put(JSONObject().put("role", "assistant").put("content", "First answer"))
            .put(JSONObject().put("role", "user").put("content", "Continue"))
        val body = JSONObject().put("messages", messages).put(
            "tools", JSONArray().put(JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", "get_file")))
        )
        OpenRouterCachePolicy.configureRequest(body, "~anthropic/claude-opus-5.5")
        assertEquals("1h", body.getJSONObject("cache_control").getString("ttl"))
        assertEquals("ephemeral", body.getJSONObject("cache_control").getString("type"))
        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.get("content") is String)
        assertFalse(system.has("cache_control"))
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            assertFalse(message.has("cache_control"))
        }
    }

    @Test
    fun nonAnthropicModelDoesNotGetClaudeCacheControl() {
        val body = JSONObject()
        OpenRouterCachePolicy.configureRequest(body, "openai/gpt-6")
        assertFalse(body.has("cache_control"))
    }

    @Test
    fun systemTextIsNotSplitIntoImplicitFiveMinuteCacheBlocks() {
        val system = OpenRouterCachePolicy.systemMessage("Prompt")
        assertEquals("Prompt", system.getString("content"))
        assertFalse(system.has("cache_control"))
    }
}
