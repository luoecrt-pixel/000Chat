package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterAccountingTransportTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun tearDown() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun enqueue(server: MockWebServer, vararg frames: String) {
        val body = frames.joinToString(separator = "") { "data: $it\n\n" }
        server.enqueue(MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "text/event-stream")
            .setBody(body))
    }

    private fun complete(server: MockWebServer, tools: JSONArray? = null): OpenRouterCompletionResult {
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        return try {
            client.complete(
                apiKey = "unit-test-key",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "test")),
                tools = tools,
                maxTokens = 32,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { name, _ ->
                    assertEquals("test_tool", name)
                    "tool-result"
                }
            )
        } finally {
            client.close()
        }
    }

    private fun persistedEvent(usage: JSONObject?): JSONObject {
        ChatDatabase(context).use { db ->
            db.ensureChat("cost-chat", "Costs")
            OpenRouterCompletionTelemetry.record(
                usage = usage,
                recordMetric = {},
                recordEvent = { snapshot ->
                    db.recordRequestEvent(
                        chatId = "cost-chat",
                        eventType = "request_completed",
                        requestId = "req",
                        provider = "openrouter",
                        requestedModel = "test-model",
                        responseModel = "test-model",
                        modelMatch = ModelCatalog.Match.EXACT,
                        status = "completed",
                        inputTokens = snapshot.inputTokens,
                        outputTokens = snapshot.outputTokens,
                        reportedCostUsd = snapshot.reportedCostUsd
                    )
                }
            )
        }
        return ChatDatabase(context).use { it.listRequestEvents("cost-chat").single() }
    }

    @Test fun confirmedZeroSurvivesSseCompletionAndDatabaseReopen() {
        val server = MockWebServer()
        server.start()
        try {
            enqueue(server,
                """{"choices":[{"delta":{"role":"assistant","content":"done"}}]}""",
                """{"choices":[],"usage":{"prompt_tokens":13,"completion_tokens":4,"cost":0}}""",
                "[DONE]"
            )
            val result = complete(server)
            assertEquals(0.0, result.usage!!.getDouble("cost"), 0.0)
            val event = persistedEvent(result.usage)
            assertEquals("provider_reported", event.getString("cost_kind"))
            assertEquals(0.0, event.getString("cost_usd").toDouble(), 0.0)
            assertEquals(13, event.getInt("input_tokens"))
            assertTrue(RequestEventFormatter.summary(event).contains("Расход $0.0000"))
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun firstToolRoundCostDoesNotImpersonateTotalWhenSecondOmitsCost() {
        val server = MockWebServer()
        server.start()
        try {
            enqueue(server,
                """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]}}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3,"cost":0.018}}""",
                "[DONE]"
            )
            enqueue(server,
                """{"choices":[{"delta":{"role":"assistant","content":"finished"}}]}""",
                """{"choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2}}""",
                "[DONE]"
            )
            val result = complete(server, JSONArray().put(
                JSONObject().put("type", "function").put("function",
                    JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
            ))
            assertEquals("finished", result.text)
            assertEquals(2, server.requestCount)
            assertEquals(17L, result.usage!!.getLong("prompt_tokens"))
            assertFalse(result.usage!!.has("cost"))
            val event = persistedEvent(result.usage)
            assertEquals("unknown", event.getString("cost_kind"))
            assertFalse(event.has("cost_usd"))
            assertTrue(RequestEventFormatter.summary(event).contains("Расход неизвестен"))
        } finally {
            server.shutdown()
        }
    }
}
