package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterRuntimeTelemetryTest {
    private lateinit var context: Context
    private val chat = "runtime-telemetry"
    private val requestId = "request-telemetry"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("response_notifications", false).commit()
    }
    @After fun teardown() {
        OpenRouterResponseRuntime.cancel(requestId)
        awaitFinished()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    private fun started() = ChatDatabase(context).use { db ->
        db.ensureChat(chat)
        db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
            "high", "high", false, 0, 0, false)
        assertTrue(db.startRequestAccounting(chat, requestId, "openrouter", "test-model", "openrouter_api_key"))
    }
    private fun runtimeRequest() = OpenRouterRuntimeRequest(chatId = chat, requestId = requestId,
        model = "test-model", messages = JSONArray().put(JSONObject().put("role", "user").put("content", "list")),
        attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null, github = false,
        githubRepo = "", githubBranches = emptySet(), webSearch = false, webFetch = false, parallel = false,
        cacheMode = "off", background = false, startedAt = SystemClock.elapsedRealtime(), taskClass = "general",
        effortPref = "high", autoSelected = false, promptChars = 4, apiKey = "mock-key",
        metadataClient = OkHttpClient(), pdfEngine = "auto")
    private fun sse(vararg frames: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { "data: $it\n\n" })
    private fun tool() = """{"id":"gen-tool","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-list","type":"function","function":{"name":"file_list","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":100,"completion_tokens":10,"cost":0.01,"cost_details":{"server_tool_cost":0.002},"service_tier":"standard"}}"""
    private fun final() = """{"id":"gen-final","model":"test-model","choices":[{"delta":{"content":"done"},"finish_reason":"stop"}],"usage":{"prompt_tokens":150,"completion_tokens":20,"cost":0,"server_tool_use":{"web_search_requests":1}}}"""
    private fun awaitFinished() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (OpenRouterResponseRuntime.contains(requestId) && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("Runtime did not finish", OpenRouterResponseRuntime.contains(requestId))
    }

    @Test fun actualRuntimeCompactsCheckpointOnlyAfterPreservingSignedTailUsageAndTelemetry() {
        started()
        MockWebServer().use { server ->
            server.enqueue(sse(tool(), "[DONE]"))
            server.enqueue(sse(final(), "[DONE]"))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
            awaitFinished()
            ChatDatabase(context).use { db ->
                assertNull(db.getOpenRouterCheckpoint(chat, requestId))
                val assistant = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals("done", assistant.getString("text"))
                assertEquals(3, assistant.getJSONObject("openrouter_protocol").getJSONArray("tail").length())
                assertEquals(250L, assistant.getJSONObject("usage").getLong("prompt_tokens"))
                val telemetry = assistant.getJSONObject("telemetry_summary")
                assertEquals(2, telemetry.getInt("rounds_completed"))
                assertEquals(1, telemetry.getInt("calls_executed"))
                assertEquals("file_list", telemetry.getJSONArray("executed_tools").getString(0))
                assertEquals(0.002, telemetry.getJSONObject("cost_details").getJSONObject("cost_details").getDouble("server_tool_cost"), 0.0)
                val account = db.listRequestAccounting(chat).single()
                assertEquals(250L, account.getLong("input_tokens"))
                assertEquals(0.01, account.getString("cost_usd").toDouble(), 1e-12)
                val event = db.listRequestEvents(chat).single()
                assertTrue(RequestEventFormatter.summary(event).contains("tools 1/2r"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun staleRuntimeCannotDeleteSavedProtocolCheckpointOrFinishReplacementRequest() {
        started()
        MockWebServer().use { server ->
            val requestCount = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (requestCount.getAndIncrement() == 0) return sse(tool(), "[DONE]")
                    ChatDatabase(context).use { db ->
                        assertNotNull(db.getOpenRouterCheckpoint(chat, requestId))
                        // A new foreground owner takes over while the old HTTP response is in flight.
                        db.writableDatabase.execSQL("UPDATE chats SET active_request_id=? WHERE id=?", arrayOf("replacement-request", chat))
                    }
                    return sse(final(), "[DONE]")
                }
            }
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
            awaitFinished()
            ChatDatabase(context).use { db ->
                assertNotNull("Finally must retain checkpoint when final commit loses CAS", db.getOpenRouterCheckpoint(chat, requestId))
                assertTrue(db.isActiveRequest(chat, "replacement-request"))
                assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
            }
        }
    }
}
