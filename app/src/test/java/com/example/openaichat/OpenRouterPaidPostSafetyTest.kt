package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Exercise OkHttp follow-ups inside the real completion client, not a request-body flag alone. */
class OpenRouterPaidPostSafetyTest {
    private fun stream(event: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody("data: $event\n\ndata: [DONE]\n\n")

    private fun complete(client: OpenRouterChatClient, execute: (String, JSONObject) -> String = { _, _ ->
        throw AssertionError("Unexpected local tool")
    }) = client.complete("synthetic-only", "test-model",
        JSONArray().put(JSONObject().put("role", "user").put("content", "Test")),
        tools = OpenRouterAppToolCatalog.tools(), maxTokens = null, reasoningEffort = null,
        verbosity = null, parallelToolCalls = false, executeTool = execute)

    private fun assertPostIsNotRepeated(first: MockResponse) {
        val server = MockWebServer()
        server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(first)
            // A replayable POST would consume this success within the same execute(), hiding the failure.
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Unwanted paid retry"},"finish_reason":"stop"}]}"""))
            assertThrows(IOException::class.java) { complete(client) }
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
            assertNull("A failed completion must not silently start another inference",
                server.takeRequest(250, TimeUnit.MILLISECONDS))
            assertEquals(1, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }

    @Test fun completionPostIsNotRepeatedAfter408() {
        assertPostIsNotRepeated(MockResponse().setResponseCode(408).setBody("request timeout"))
    }

    @Test fun completionPostIsNotRepeatedAfter503RetryAfterZero() {
        assertPostIsNotRepeated(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
    }

    @Test fun ambiguousDisconnectAfterRequestDoesNotRepeatInference() {
        assertPostIsNotRepeated(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    }

    @Test fun bodyPreservingRedirectCannotReplayInferencePost() {
        for (code in listOf(307, 308)) {
            val server = MockWebServer()
            server.start()
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                server.enqueue(MockResponse().setResponseCode(code)
                    .setHeader("Location", server.url("/redirected/chat/completions")))
                server.enqueue(stream("""{"choices":[{"delta":{"content":"Unwanted redirected inference"},"finish_reason":"stop"}]}"""))
                assertThrows(IOException::class.java) { complete(client) }
                val original = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("POST", original.method)
                assertEquals("/api/v1/chat/completions", original.path)
                assertNull("HTTP $code must not replay the paid request at its Location",
                    server.takeRequest(250, TimeUnit.MILLISECONDS))
                assertEquals(1, server.requestCount)
            } finally {
                client.close()
                server.shutdown()
            }
        }
    }

    @Test fun successfulToolRoundStillStartsOneExplicitContinuationWithOriginalResult() {
        val server = MockWebServer()
        server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_read","type":"function","function":{"name":"file_info","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":2,"cost":0.01}}"""))
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":3,"cost":0}}"""))
            var executions = 0
            val result = complete(client) { _, _ -> executions++; "original tool result" }
            assertEquals("Done", result.text)
            assertEquals(1, executions)
            assertEquals(30L, result.usage!!.getLong("prompt_tokens"))
            assertEquals(0.01, result.usage.getDouble("cost"), 0.0)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val second = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val messages = second.getJSONArray("messages")
            assertEquals("original tool result", messages.getJSONObject(messages.length() - 1).getString("content"))
            assertEquals(2, server.requestCount)
        } finally {
            client.close()
            server.shutdown()
        }
    }
}
