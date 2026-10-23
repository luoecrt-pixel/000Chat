package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit

/** Production transport -> runtime -> SQLite, including re-opened unknown accounting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekRuntimeIntegrationTest {
    private lateinit var context: Context
    private val chat = "deepseek-runtime"
    private val requestId = "deepseek-runtime-request"
    private val metadata = OkHttpClient()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean("response_notifications", false).commit()
    }

    @After fun teardown() {
        OpenRouterResponseRuntime.cancel(requestId)
        await { !OpenRouterResponseRuntime.contains(requestId) }
        metadata.dispatcher.executorService.shutdownNow()
        metadata.connectionPool.evictAll()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun request(provider: String = "deepseek") = OpenRouterRuntimeRequest(
        chatId = chat, requestId = requestId, model = "deepseek-chat",
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Answer")),
        attachments = emptyList(), effort = "none", verbosity = "", maxTokens = null,
        github = false, githubRepo = "", githubBranches = emptySet(), webSearch = true,
        webFetch = true, parallel = false, cacheMode = "off", background = false,
        startedAt = SystemClock.elapsedRealtime(), taskClass = "general", effortPref = "none",
        autoSelected = false, promptChars = 6, apiKey = "inert-$provider-key",
        metadataClient = metadata, pdfEngine = "auto", provider = provider)

    private fun started(provider: String = "deepseek") = ChatDatabase(context).use { db ->
        db.ensureChat(chat)
        db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
            "none", "none", false, 6, 0, false)
        assertTrue(db.startRequestAccounting(chat, requestId, provider, "deepseek-chat", "${provider}_api_key"))
    }

    private fun sse(vararg frames: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(frames.joinToString("") { "data: $it\n\n" })
    private fun finish(usage: String? = null): String =
        """{"model":"deepseek-chat","choices":[{"delta":{"content":"visible answer"},"finish_reason":"stop"}]${usage?.let { ",\"usage\":$it" }.orEmpty()}}"""
    private fun usage() = """{"prompt_tokens":13,"completion_tokens":7,"total_tokens":20}"""
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("Runtime state did not settle", predicate())
    }

    @Test fun mismatchedProviderAndTransportRejectBeforePostOrAuthorization() {
        MockWebServer().use { server ->
            val openRouter = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            val deepSeek = DeepSeekChatClient(server.url("/").toString())
            for ((provider, client) in listOf("deepseek" to openRouter, "openrouter" to deepSeek)) {
                try {
                    OpenRouterResponseRuntime.start(context, request(provider), client)
                    fail("Provider mismatch must be rejected synchronously")
                } catch (expected: IllegalArgumentException) {
                    assertTrue(expected.message!!.contains("mismatch"))
                }
                assertFalse(OpenRouterResponseRuntime.contains(requestId))
                assertEquals(0, server.requestCount)
                assertNull(server.takeRequest(50, TimeUnit.MILLISECONDS))
            }
            openRouter.close()
            deepSeek.close()
        }
    }

    @Test fun correctDeepSeekPairUsesOnlyItsEndpointAndLedgerAndExcludesServerTools() {
        started()
        MockWebServer().use { server ->
            server.enqueue(sse(finish(usage()), "[DONE]"))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()))
            await { !OpenRouterResponseRuntime.contains(requestId) }
            val wire = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("/chat/completions", wire.path)
            assertEquals("Bearer inert-deepseek-key", wire.getHeader("Authorization"))
            val body = JSONObject(wire.body.readUtf8())
            val tools = body.getJSONArray("tools")
            assertFalse(tools.toString().contains("openrouter:"))
            assertFalse(body.has("plugins"))
            assertEquals(1, server.requestCount)
            ChatDatabase(context).use { db ->
                val assistant = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals("completed", assistant.getString("status"))
                assertEquals("deepseek", assistant.getString("provider"))
                assertTrue(assistant.getJSONObject("deepseek_protocol").getBoolean("replayable"))
                assertFalse(assistant.has("openrouter_protocol"))
                assertTrue(db.listOpenRouterRounds(chat, requestId).isEmpty())
                assertNull(db.getDeepSeekCheckpoint(chat, requestId))
                val accounting = db.listRequestAccounting(chat).single()
                assertEquals(13L, accounting.getLong("input_tokens"))
                assertEquals(7L, accounting.getLong("output_tokens"))
                val event = db.listRequestEvents(chat).single()
                assertTrue(event.getBoolean("usage_complete"))
                assertEquals(13L, event.getLong("input_tokens"))
            }
        }
    }

    @Test fun stopWithoutUsageKeepsVisibleTextButUnknownTokensSurviveDatabaseReopen() {
        started()
        MockWebServer().use { server ->
            server.enqueue(sse(finish(), "[DONE]"))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()))
            await { !OpenRouterResponseRuntime.contains(requestId) }
        }
        ChatDatabase(context).use { db ->
            val assistant = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
            assertEquals("visible answer", assistant.getString("text"))
            assertEquals("incomplete", assistant.getString("status"))
            assertTrue(assistant.getJSONObject("deepseek_protocol").getBoolean("generation_complete"))
            assertFalse(assistant.getJSONObject("deepseek_protocol").getBoolean("replayable"))
            assertTrue(assistant.getString("error").isNotBlank())
        }
        ChatDatabase(context).use { db ->
            val event = db.listRequestEvents(chat).single()
            assertFalse(event.getBoolean("usage_complete"))
            assertTrue(event.isNull("input_tokens"))
            assertTrue(event.isNull("output_tokens"))
            val accounting = db.listRequestAccounting(chat).single()
            assertTrue(accounting.isNull("input_tokens"))
            assertTrue(accounting.isNull("output_tokens"))
        }
    }

    @Test fun openRouterSparseUsageRetainsReportedCostDespiteMissingCompletionTokens() {
        started("openrouter")
        MockWebServer().use { server ->
            server.enqueue(sse(finish("""{"prompt_tokens":11,"cost":0.031}"""), "[DONE]"))
            OpenRouterResponseRuntime.start(context, request("openrouter"),
                OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false))
            await { !OpenRouterResponseRuntime.contains(requestId) }
            ChatDatabase(context).use { db ->
                val accounting = db.listRequestAccounting(chat).single()
                assertEquals(0.031, accounting.getDouble("cost_usd"), 1e-12)
                assertEquals(11L, accounting.getLong("input_tokens"))
                val event = db.listRequestEvents(chat).single()
                assertEquals(0.031, event.getDouble("cost_usd"), 1e-12)
                assertEquals("completed", event.getString("status"))
            }
        }
    }

    @Test fun secondRoundMissingUsageCannotTurnKnownPartialTokensIntoCompleteTotals() {
        started()
        MockWebServer().use { server ->
            val tool = """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-list","type":"function","function":{"name":"file_list","arguments":"{}"}}]},"finish_reason":"tool_calls"}],"usage":${usage()}}"""
            server.enqueue(sse(tool, "[DONE]"))
            server.enqueue(sse(finish(), "[DONE]"))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()))
            await { !OpenRouterResponseRuntime.contains(requestId) }
            assertEquals(2, server.requestCount)
        }
        ChatDatabase(context).use { db ->
            val assistant = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
            assertEquals("visible answer", assistant.getString("text"))
            val telemetry = assistant.getJSONObject("telemetry_summary")
            assertTrue(telemetry.getBoolean("usage_incomplete"))
            assertEquals(13L, telemetry.getJSONObject("observed_partial_usage").getLong("prompt_tokens"))
            val event = db.listRequestEvents(chat).single()
            assertFalse(event.getBoolean("usage_complete"))
            assertTrue(event.isNull("input_tokens"))
            assertTrue(event.isNull("output_tokens"))
            assertTrue(db.listRequestAccounting(chat).single().isNull("input_tokens"))
        }
    }
    @Test fun streamedArgumentsOnlyAreFlushedBeforeStopAndArchivedWithoutInventingToolResults() {
        started()
        MockWebServer().use { server ->
            val prefix = "{\"prefix\":"
            val callFragment = JSONObject().put("index", 0).put("id", "unconfirmed-call")
                .put("type", "function").put("function", JSONObject().put("name", "create_local_text_file").put("arguments", prefix))
            val delta = JSONObject().put("tool_calls", JSONArray().put(callFragment))
            val first = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)))
            // One full frame is delivered, then the socket remains in a later throttle window.
            val body = ("data: $first".padEnd(131070, ' ') + "\n\n") +
                ("data: {}".padEnd(131070, ' ') + "\n\n") + "data: [DONE]\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
                .throttleBody(131072, 2, TimeUnit.SECONDS))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()))
            await { ChatDatabase(context).use { it.getDeepSeekCheckpoint(chat, requestId) != null } || !OpenRouterResponseRuntime.contains(requestId) }
            assertTrue(ChatDatabase(context).use { it.getChat(chat).toString() }, OpenRouterResponseRuntime.contains(requestId))
            assertNull(OpenRouterResponseRuntime.prepareStop(requestId))
            ChatDatabase(context).use { db -> assertTrue(db.stopActiveRequestOnce(chat, requestId)) }
            OpenRouterResponseRuntime.cancel(requestId, "Остановлено пользователем")
            await { !OpenRouterResponseRuntime.contains(requestId) }
            assertEquals(1, server.requestCount)
            ChatDatabase(context).use { db ->
                assertNull(db.getDeepSeekCheckpoint(chat, requestId))
                val archive = db.getRequestRecoveryArchive(chat, requestId)!!
                val protocol = archive.getJSONObject("deepseek_protocol")
                assertFalse(protocol.getBoolean("replayable"))
                val pending = protocol.optJSONObject("uncommitted_assistant")
                    ?: protocol.getJSONObject("interrupted_assistant")
                val call = pending.getJSONArray("tool_calls").getJSONObject(0)
                assertEquals("unconfirmed-call", call.getString("id"))
                assertEquals(prefix, call.getJSONObject("function").getString("arguments"))
                val tail = protocol.getJSONArray("tail")
                assertFalse((0 until tail.length()).any { tail.getJSONObject(it).optString("role") == "tool" })
                assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
            }
        }
    }

    @Test fun telemetryKeepsPartialObservationsAndUnknownFlagAcrossCompactionAndMerge() {
        val initial = FileBackedIoTelemetry.mergeCompactTelemetry(toolTelemetry = JSONObject()
            .put("usage_incomplete", true).put("rounds_with_usage", 1).put("rounds_total", 2)
            .put("observed_partial_usage", JSONObject().put("prompt_tokens", 13)))!!
        val merged = JSONObject(FileBackedIoTelemetry.mergeTelemetryJson(initial.toString(),
            JSONObject().put("rounds_completed", 2).put("usage_incomplete", false))!!)
        assertTrue(merged.getBoolean("usage_incomplete"))
        assertEquals(2, merged.getInt("rounds_total"))
        assertEquals(13L, merged.getJSONObject("observed_partial_usage").getLong("prompt_tokens"))
    }

    @Test fun stopAfterConfirmedFileEffectPreservesFileOriginalCallAndCompletedExecution() {
        started()
        MockWebServer().use { server ->
            val arguments = JSONObject().put("filename", "confirmed.txt").put("content", "confirmed user bytes")
            val callFragment = JSONObject().put("index", 0).put("id", "create-confirmed")
                .put("type", "function").put("function", JSONObject().put("name", "create_local_text_file")
                    .put("arguments", arguments.toString()))
            val choice = JSONObject().put("delta", JSONObject().put("tool_calls", JSONArray().put(callFragment)))
                .put("finish_reason", "tool_calls")
            val tool = JSONObject().put("choices", JSONArray().put(choice)).put("usage", JSONObject(usage()))
            server.enqueue(sse(tool.toString(), "[DONE]"))
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()))
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val second = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(second.body.readUtf8().contains("create-confirmed"))
            assertNull(OpenRouterResponseRuntime.prepareStop(requestId))
            ChatDatabase(context).use { db -> assertTrue(db.stopActiveRequestOnce(chat, requestId)) }
            OpenRouterResponseRuntime.cancel(requestId, "Остановлено пользователем")
            await { !OpenRouterResponseRuntime.contains(requestId) }
            assertEquals(2, server.requestCount)
            ChatDatabase(context).use { db ->
                val files = db.listLocalFiles(chat)
                assertEquals(1, files.size)
                assertEquals("confirmed user bytes", java.io.File(files.single().getString("path")).readText())
                val archive = db.getRequestRecoveryArchive(chat, requestId)!!
                assertEquals(1, archive.getJSONArray("confirmed_artifacts").length())
                val protocol = archive.getJSONObject("deepseek_protocol")
                assertFalse(protocol.getBoolean("replayable"))
                val tail = protocol.getJSONArray("tail")
                val original = tail.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0)
                assertEquals("create-confirmed", original.getString("id"))
                assertEquals(arguments.toString(), original.getJSONObject("function").getString("arguments"))
                assertEquals("tool", tail.getJSONObject(1).getString("role"))
                assertEquals("completed", protocol.getJSONArray("tool_execution").getJSONObject(0).getString("state"))
                assertFalse(protocol.toString().contains("cancelled"))
            }
        }
    }

}
