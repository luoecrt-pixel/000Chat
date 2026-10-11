package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class OpenRouterClaudeRouteTest {
    private val model = "anthropic/claude-sonnet-5.5"
    private fun stream(event: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: $event\n\ndata: [DONE]\n\n")

    @Test fun routeAndStructuredOutputStayMergedAcrossToolRoundsEvenWhenSettingChanges() {
        for (route in OpenRouterClaudeRoute.entries) for (structured in listOf(false, true)) {
            val server = MockWebServer(); server.start()
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                server.enqueue(stream("""{"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"read1","type":"function","function":{"name":"file_info","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""))
                server.enqueue(stream("""{"choices":[{"delta":{"content":"{\"answer\":\"ok\"}"},"finish_reason":"stop"}]}"""))
                val setting = AtomicReference(route)
                val captured = setting.get()
                val format = if (structured) OpenRouterStructuredOutputPolicy.format("json_schema",
                    """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""") else null
                val result = client.complete("mock", model,
                    JSONArray().put(JSONObject().put("role", "user").put("content", "Read file and return JSON")),
                    tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "max",
                    verbosity = null, parallelToolCalls = true, responseFormat = format, claudeRoute = captured,
                    executeTool = { _, _ ->
                        setting.set(if (route == OpenRouterClaudeRoute.AUTO) OpenRouterClaudeRoute.ANTHROPIC else OpenRouterClaudeRoute.AUTO)
                        "{\"ok\":true}"
                    })
                assertEquals("stop", result.finishReason)
                for (round in 0..1) {
                    val body = JSONObject(server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8())
                    val provider = body.optJSONObject("provider")
                    if (route == OpenRouterClaudeRoute.ANTHROPIC) {
                        assertEquals("anthropic", provider!!.getJSONArray("order").getString(0))
                        assertFalse(provider.getBoolean("allow_fallbacks"))
                    } else {
                        assertFalse(provider?.has("order") ?: false)
                        assertFalse(provider?.has("allow_fallbacks") ?: false)
                    }
                    assertEquals(structured, provider?.optBoolean("require_parameters", false) ?: false)
                    assertEquals(structured, body.has("response_format"))
                    assertFalse(body.has("max_tokens"))
                    assertEquals("max", body.getJSONObject("reasoning").getString("effort"))
                    if (round == 1) assertEquals("tool", body.getJSONArray("messages").getJSONObject(2).getString("role"))
                }
            } finally { client.close(); server.shutdown() }
        }
    }

    @Test fun imageAndEnvelopeBoundariesDependOnRouteAndNeverAlterOriginal() {
        OpenRouterAttachmentPreflight.validateImageBytes(model, "photo.jpg", 3_750_000, OpenRouterClaudeRoute.AUTO)
        assertThrows(IOException::class.java) { OpenRouterAttachmentPreflight.validateImageBytes(model, "photo.jpg", 3_750_001, OpenRouterClaudeRoute.AUTO) }
        OpenRouterAttachmentPreflight.validateImageBytes(model, "photo.jpg", 7_500_000, OpenRouterClaudeRoute.ANTHROPIC)
        assertThrows(IOException::class.java) { OpenRouterAttachmentPreflight.validateImageBytes(model, "photo.jpg", 7_500_001, OpenRouterClaudeRoute.ANTHROPIC) }
        for (route in OpenRouterClaudeRoute.entries) {
            OpenRouterAttachmentPreflight.validateRequestBytes(model, route.requestByteLimit, route)
            assertThrows(IOException::class.java) { OpenRouterAttachmentPreflight.validateRequestBytes(model, route.requestByteLimit + 1, route) }
        }
        val original = File.createTempFile("route-original", ".jpg")
        try {
            original.outputStream().use { out -> repeat(64) { out.write(ByteArray(65536) { 0x55 }) } }
            val before = java.security.MessageDigest.getInstance("SHA-256").digest(original.readBytes())
            val error = assertThrows(IOException::class.java) { OpenRouterAttachmentPreflight.validateImageBytes(model, original.name, original.length(), OpenRouterClaudeRoute.AUTO) }
            assertTrue(error.message!!.contains("прямой Anthropic"))
            assertArrayEquals(before, java.security.MessageDigest.getInstance("SHA-256").digest(original.readBytes()))
            OpenRouterAttachmentPreflight.validateImageBytes(model, original.name, original.length(), OpenRouterClaudeRoute.ANTHROPIC)
        } finally { original.delete() }
    }

    @Test fun routeSwitchChangesOnlyClaudeBindingAndAutoPreservesLegacyBinding() {
        val auto = OpenRouterProtocolCodec.binding(model, null, "mock")
        val legacy = StreamingJsonRequestBody.sha256(JSONObject().put("model", model).put("tools", JSONArray())
            .put("credential_hash", StreamingJsonRequestBody.sha256("mock")))
        assertEquals(legacy, auto)
        assertEquals(auto, OpenRouterProtocolCodec.binding(model, null, "mock", claudeRoute = OpenRouterClaudeRoute.AUTO))
        assertNotEquals(auto, OpenRouterProtocolCodec.binding(model, null, "mock", claudeRoute = OpenRouterClaudeRoute.ANTHROPIC))
        assertEquals(OpenRouterProtocolCodec.binding("openai/gpt-6-astra", null, "mock"),
            OpenRouterProtocolCodec.binding("openai/gpt-6-astra", null, "mock", claudeRoute = OpenRouterClaudeRoute.ANTHROPIC))
        assertNull(OpenRouterClaudeRoute.ANTHROPIC.provider("openai/gpt-6-astra", false))
        assertTrue(OpenRouterClaudeRoute.ANTHROPIC.provider("openai/gpt-6-astra", true)!!.getBoolean("require_parameters"))
    }

    @Test fun oversizedToolResultIsRejectedBeforeSecondHttpRequest() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":null,"tool_calls":[{"index":0,"id":"large1","type":"function","function":{"name":"file_read_range","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""))
            val failure = assertThrows(IOException::class.java) {
                client.complete("mock", model, JSONArray().put(JSONObject().put("role", "user").put("content", "Read")),
                    tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = "max", verbosity = null,
                    parallelToolCalls = true, claudeRoute = OpenRouterClaudeRoute.AUTO,
                    executeTool = { _, _ -> "x".repeat(20_000_000) })
            }
            assertTrue(failure.message!!.contains("20000000"))
            assertEquals(1, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }
}
