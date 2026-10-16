package com.example.openaichat

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
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
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAiLocalFileToolBridgeTest {
    private lateinit var context: Context
    private lateinit var db: ChatDatabase
    private lateinit var file: File
    private lateinit var attachment: OpenRouterLocalAttachment
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
        db = ChatDatabase(context)
        file = File(context.filesDir, "bridge-source.txt").apply { writeText("needle — complete local document 😀") }
        attachment = OpenRouterAttachmentStore.classifyLocalFile(file, file.name, "text/plain")
        server = MockWebServer().apply { start() }
        http = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        // Only the credential slot matters here; this private JVM fixture never contacts Android Keystore or a provider.
        val credential = "mock-only"
        val fingerprint = OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("", "", null), credential, "gpt-6-astra")
        db.upsertCurrentChat("chat", "Tools", null, null, listOf(JSONObject().put("role", "user")
            .put("provider", "openai").put("request_id", "request").put("text", "Search")
            .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint)
            .put("attachments", JSONArray().put(attachment.toJson()))))
        db.setActiveResponse("chat", "resp_first", "request", "", "in_progress", System.currentTimeMillis(), "high", "high", false, 6, 1, true)
        OpenAiLocalFileToolBridge.configure(db, "chat", "request", JSONObject().put("model", "gpt-6-astra")
            .put("store", true).put("background", true).put("include", JSONArray().put("reasoning.encrypted_content")), listOf(attachment))
    }
    @After fun after() { db.close(); server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); file.delete() }

    private fun call(id: String, name: String = "search_attached_file", callId: String = "call_1") = JSONObject()
        .put("id", id).put("status", "completed").put("usage", JSONObject().put("input_tokens", 2).put("output_tokens", 1))
        .put("output", JSONArray().put(JSONObject().put("type", "reasoning").put("id", "rs_$id").put("encrypted_content", "signed-$id"))
            .put(JSONObject().put("type", "function_call").put("call_id", callId).put("name", name)
                .put("arguments", JSONObject().put("file_id", attachment.id).put("query", "needle").toString())))
    private fun finalResponse() = JSONObject().put("id", "resp_final").put("status", "completed")
        .put("usage", JSONObject().put("input_tokens", 3).put("output_tokens", 4))
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Found needle")))))

    @Test fun schemasAreFlatAndAllowOnlyTheLocalReadCatalog() {
        val schemas = OpenAiLocalFileToolBridge.schemas()
        assertEquals(4, schemas.length())
        for (i in 0 until schemas.length()) {
            val schema = schemas.getJSONObject(i)
            assertEquals("function", schema.getString("type")); assertFalse(schema.has("function"))
            assertFalse(schema.getBoolean("strict")); assertEquals("object", schema.getJSONObject("parameters").getString("type"))
        }
    }
    @Test fun listedFilesDescribeOnlyResponsesToolNames() {
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val response = call("resp_first", "list_attached_files")
        response.getJSONArray("output").getJSONObject(1).put("arguments", "{}")
        OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", response, http, credential = { "mock-only" })
        val output = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            .getJSONArray("input").getJSONObject(0).getString("output")
        val descriptor = JSONObject(output).getJSONArray("files").getJSONObject(0)
        assertEquals(attachment.id, descriptor.getString("file_id"))
        assertTrue(descriptor.getString("reading").contains("read_attached_file"))
        assertFalse(descriptor.getString("reading").contains("file_read_range"))
    }

    @Test fun twoToolRoundsPreserveSignedItemsOutputsAndAggregateUsage() {
        server.enqueue(MockResponse().setBody(call("resp_second", callId = "call_2").toString()))
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        for ((parent, id) in listOf("resp_first" to "call_1", "resp_second" to "call_2")) {
            val request = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            val body = JSONObject(request.body.readUtf8())
            assertEquals(parent, body.getString("previous_response_id"))
            val output = body.getJSONArray("input").getJSONObject(0)
            assertEquals("function_call_output", output.getString("type")); assertEquals(id, output.getString("call_id"))
            assertTrue(JSONObject(output.getString("output")).getJSONArray("matches").getJSONObject(0).getString("snippet").contains("needle"))
        }
        assertEquals("resp_final", result.getString("id"))
        assertEquals(7, result.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(7, result.getJSONArray("output").length())
        assertEquals("resp_final", db.getActiveResponse("chat")!!.getString("response_id"))
        assertEquals("Found needle", OpenAiSseStreamReader.terminalOutputText(result))
        val replay = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        assertEquals(result.toString(), replay.toString()); assertEquals(2, server.requestCount)
    }

    @Test fun sevenToolRoundsHaveNoFixedCapAndPersistOnlyCommittedToolTelemetry() {
        for (round in 2..7) server.enqueue(MockResponse().setBody(call("resp_$round", callId = "call_$round").toString()))
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val statuses = mutableListOf<String>()
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
            onStatus = { statuses.add(it) }, credential = { "mock-only" })
        assertEquals("resp_final", result.getString("id"))
        assertEquals(17, result.getJSONObject("usage").getInt("input_tokens"))
        assertEquals(22, result.getJSONArray("output").length())
        assertEquals(7, server.requestCount)
        repeat(7) { assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method) }
        assertEquals(7, statuses.size)
        assertTrue(statuses.last().contains("(7)"))
        assertTrue(statuses.none { it.contains("/6") })
        val telemetry = db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry")
        assertEquals(7, telemetry.getInt("rounds_completed"))
        assertEquals(7, telemetry.getInt("calls_executed"))
        assertEquals(List(7) { "search_attached_file" }, (0 until 7).map { telemetry.getJSONArray("executed_tools").getString(it) })
        db.close(); db = ChatDatabase(context)
        assertEquals(result.toString(), OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
            credential = { "mock-only" }).toString())
        assertEquals(telemetry.toString(), db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry").toString())
        assertEquals(7, server.requestCount)
    }

    @Test fun aBatchWithTwoToolsCommitsTwoCallsAndOneRoundWithoutTheirArguments() {
        val response = call("resp_first")
        response.getJSONArray("output").put(call("resp_first", "read_attached_file", "call_2").getJSONArray("output").getJSONObject(1))
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", response, http, credential = { "mock-only" })
        val telemetry = db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry")
        assertEquals(1, telemetry.getInt("rounds_completed"))
        assertEquals(2, telemetry.getInt("calls_executed"))
        assertEquals("[\"search_attached_file\",\"read_attached_file\"]", telemetry.getJSONArray("executed_tools").toString())
        assertEquals(setOf("rounds_completed", "calls_executed", "executed_tools"), telemetry.keys().asSequence().toSet())
        assertFalse(telemetry.toString().contains(attachment.id))
        assertFalse(telemetry.toString().contains("needle"))
    }

    @Test fun preparedTelemetrySurvivesDatabaseReopenAndIsNotCountedAgain() {
        var lookups = 0
        assertThrows(IOException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
                credential = { if (++lookups == 2) throw IOException("simulated crash after batch commit") else "mock-only" })
        }
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("prepared", saved.getString("phase"))
        assertEquals(1, saved.getJSONObject("telemetry").getInt("calls_executed"))
        assertEquals(0, server.requestCount)
        db.close(); db = ChatDatabase(context); file.delete()
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val statuses = mutableListOf<String>()
        OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
            onStatus = { statuses.add(it) }, credential = { "mock-only" })
        assertEquals(saved.getJSONObject("telemetry").toString(), db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry").toString())
        assertEquals(1, server.requestCount)
        val expected = saved.getJSONArray("next_input")
        val sent = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).getJSONArray("input")
        assertEquals(expected.length(), sent.length())
        for (index in 0 until expected.length()) {
            val original = expected.getJSONObject(index)
            val actual = sent.getJSONObject(index)
            assertEquals(original.keys().asSequence().toSet(), actual.keys().asSequence().toSet())
            // The JSON writer canonicalizes object key order, while the saved
            // tool output string and call pairing must stay byte-for-byte equal.
            for (field in listOf("type", "call_id", "output"))
                assertEquals(original.getString(field), actual.getString(field))
        }
        assertTrue(statuses.single().contains("(1): search_attached_file"))
    }

    @Test fun legacyCheckpointReconstructsTelemetryFromCommittedProtocolOnResume() {
        val output = JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", "original result")
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
            .put("phase", "prepared").put("round", 1).put("parent_id", "resp_first")
            .put("prior_output", call("resp_first").getJSONArray("output").put(output))
            .put("usage", call("resp_first").getJSONObject("usage"))
            .put("next_input", JSONArray().put(output)).apply { remove("telemetry"); remove(OpenAiUsageLedger.FIELD) }
        assertTrue(db.saveOpenAiToolCheckpoint("chat", "request", saved))
        db.close(); db = ChatDatabase(context); file.delete()
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        val telemetry = db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry")
        assertEquals(1, telemetry.getInt("rounds_completed"))
        assertEquals(1, telemetry.getInt("calls_executed"))
        assertEquals("[\"search_attached_file\"]", telemetry.getJSONArray("executed_tools").toString())
        assertEquals(5L, result.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(1, server.requestCount)
    }

    @Test fun stoppedRoundDoesNotPublishUncommittedTelemetry() {
        assertThrows(CancellationException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
                onStatus = { db.clearActiveResponse("chat", expectedRequestId = "request") }, credential = { "mock-only" })
        }
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("idle", saved.getString("phase"))
        assertEquals(0, saved.getJSONObject("telemetry").getInt("rounds_completed"))
        assertEquals(0, saved.getJSONObject("telemetry").getInt("calls_executed"))
        assertEquals(0, saved.getJSONObject("telemetry").getJSONArray("executed_tools").length())
        assertEquals(0, server.requestCount)
    }
    @Test fun preparedRoundSurvivesDatabaseReopenWithoutRepeatingTheRead() {
        val snapshot = db.getOpenAiToolCheckpoint("chat", "request")!!
        snapshot.put("phase", "prepared").put("round", 1).put("parent_id", "resp_first")
            .put("next_input", JSONArray().put(JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", "durable original result")))
        assertTrue(db.saveOpenAiToolCheckpoint("chat", "request", snapshot))
        db.close(); db = ChatDatabase(context); file.delete()
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        assertEquals("resp_final", result.getString("id"))
        assertEquals("durable original result", JSONObject(server.takeRequest()!!.body.readUtf8()).getJSONArray("input").getJSONObject(0).getString("output"))
    }
    @Test fun currentCreateOwnerTracksTheCancelableToolRoundCall() {
        val first = http.newCall(okhttp3.Request.Builder().url(server.url("/")).build())
        val next = http.newCall(okhttp3.Request.Builder().url(server.url("/next")).build())
        OpenAiCreateCallOwners.register("test-owner", "tool-round", first)
        try {
            assertTrue(OpenAiCreateCallOwners.replace("test-owner", "tool-round", first, next))
            OpenAiCreateCallOwners.release("test-owner", "tool-round", first)
            assertSame(next, OpenAiCreateCallOwners.call("test-owner", "tool-round"))
            OpenAiCreateCallOwners.call("test-owner", "tool-round")!!.cancel()
            assertTrue(next.isCanceled()); assertFalse(first.isCanceled())
        } finally { OpenAiCreateCallOwners.release("test-owner", "tool-round", next) }
    }

    @Test fun stopAfterPostCancelsOnlyTheKnownChildWithoutPublishingItsRound() {
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                return if (request.path == "/v1/responses") {
                    active.set(false)
                    MockResponse().setBody(finalResponse().put("status", "in_progress").toString())
                } else MockResponse().setBody("{\"status\":\"cancelled\"}")
            }
        }
        assertThrows(CancellationException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http,
                active = { active.get() }, credential = { "mock-only" })
        }
        val post = server.takeRequest(2, TimeUnit.SECONDS)!!
        val cancel = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/responses", post.path); assertEquals("/v1/responses/resp_final/cancel", cancel.path)
        assertEquals(post.getHeader("Authorization"), cancel.getHeader("Authorization"))
        assertEquals("resp_first", db.getActiveResponse("chat")!!.getString("response_id"))
        assertEquals("posting", db.getOpenAiToolCheckpoint("chat", "request")!!.getString("phase"))
    }

    @Test fun receivedPrefixNeverFabricatesTypedOutputForAPreIdFailure() {
        val failed = OpenAiEffectiveResponse.assemble(null, ResponseEventState())
        assertFalse(failed.has("output"))
        val preserved = OpenAiLocalFileToolBridge.attachReceivedPrefix(db, "chat", "request", failed)
        assertFalse(preserved.has("output"))
        val explicit = JSONObject().put("output", JSONArray())
        assertEquals(0, OpenAiLocalFileToolBridge.attachReceivedPrefix(db, "chat", "request", explicit).getJSONArray("output").length())
    }

    @Test fun failedChildRetainsPriorSignedToolProtocolInChatHistory() {
        val checkpoint = db.getOpenAiToolCheckpoint("chat", "request")!!
        val prior = call("resp_first").getJSONArray("output").put(JSONObject()
            .put("type", "function_call_output").put("call_id", "call_1").put("output", "exact durable result"))
        checkpoint.put("phase", "waiting").put("round", 1).put("parent_id", "resp_first")
            .put("response_id", "resp_child").put("prior_output", prior)
        assertTrue(db.advanceOpenAiToolResponse("chat", "request", "resp_first", "resp_child", checkpoint))
        assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", "request", "resp_first", "failed", "network failure"))
        val history = db.getChat("chat")!!.getJSONArray("messages")
        assertEquals("resp_child", history.getJSONObject(history.length() - 1).getString("response_id"))
        val output = history.getJSONObject(history.length() - 1).getJSONArray("openai_protocol_output")
        assertEquals("signed-resp_first", output.getJSONObject(0).getString("encrypted_content"))
        assertEquals("exact durable result", output.getJSONObject(2).getString("output"))
    }

    @Test fun staleCheckpointAndForeignRoundCannotOverwriteCurrentOwner() {
        val initial = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertTrue(db.compareOpenAiToolCheckpoint("chat", "request", initial.toString(), JsonProtocol.objectCopy(initial).put("phase", "posting")))
        assertFalse(db.compareOpenAiToolCheckpoint("chat", "request", initial.toString(), initial.put("phase", "prepared")))
        assertFalse(db.advanceOpenAiToolResponse("chat", "request", "wrong-parent", "child", initial))
        assertEquals("posting", db.getOpenAiToolCheckpoint("chat", "request")!!.getString("phase"))
        db.deleteChat("chat")
        assertNull(db.getOpenAiToolCheckpoint("chat", "request"))
    }

    @Test fun aTransientChildGetFailureResumesTheKnownChildWithoutRepeatingPost() {
        server.enqueue(MockResponse().setBody(finalResponse().put("status", "queued").toString()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
        assertThrows(ApiHttpException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals("waiting", db.getOpenAiToolCheckpoint("chat", "request")!!.getString("phase"))
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        assertEquals("resp_final", result.getString("id"))
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        repeat(2) { assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)!!.method) }
        val telemetry = db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry")
        assertEquals(1, telemetry.getInt("rounds_completed"))
        assertEquals(1, telemetry.getInt("calls_executed"))
    }

    @Test fun rawUsageAndPaidOutputStayIdempotentAcrossFailedGetRepeatedPrefixAndReopen() {
        val first = call("resp_first").put("usage", JSONObject().put("input_tokens", 100).put("output_tokens", 10))
        val second = call("resp_second", callId = "call_2")
            .put("usage", JSONObject().put("input_tokens", 30).put("output_tokens", 3))
        val queued = JSONObject().put("id", "resp_final").put("status", "queued")
        server.enqueue(MockResponse().setBody(second.toString()))
        server.enqueue(MockResponse().setBody(queued.toString()))
        server.enqueue(MockResponse().setResponseCode(503).setBody("{\"error\":{\"message\":\"busy\"}}"))
        assertThrows(ApiHttpException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", first, http, credential = { "mock-only" })
        }
        val waiting = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("waiting", waiting.getString("phase"))
        assertEquals("resp_final", waiting.getJSONObject("received_response").getString("id"))
        assertEquals(130L, waiting.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(6, waiting.getJSONArray("prior_output").length())

        // The Activity still holds the original raw parent when the child GET fails.
        // Repeated failure preservation cannot append that parent's paid output or usage again.
        val retained = JsonProtocol.objectCopy(first)
        repeat(3) {
            OpenAiLocalFileToolBridge.attachReceivedPrefix(db, "chat", "request", retained)
            assertEquals(130L, retained.getJSONObject("usage").getLong("input_tokens"))
            assertEquals(6, retained.getJSONArray("output").length())
            assertEquals(130L, OpenAiUsageLedger.total(OpenAiUsageLedger.resolve(waiting, retained))!!.getLong("input_tokens"))
        }
        db.close(); db = ChatDatabase(context)
        val terminal = finalResponse().put("usage", JSONObject().put("input_tokens", 20).put("output_tokens", 2))
        server.enqueue(MockResponse().setBody(terminal.toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", first, http, credential = { "mock-only" })
        assertEquals(150L, result.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(15L, result.getJSONObject("usage").getLong("output_tokens"))
        assertEquals(7, result.getJSONArray("output").length())
        val entries = result.getJSONObject(OpenAiUsageLedger.FIELD).getJSONObject("responses")
        assertEquals(20L, entries.getJSONObject("resp_final").getJSONObject("usage").getLong("input_tokens"))
        assertEquals("Found needle", OpenAiSseStreamReader.terminalOutputText(result))
        repeat(2) { OpenAiLocalFileToolBridge.attachReceivedPrefix(db, "chat", "request", result) }
        assertEquals(150L, result.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(7, result.getJSONArray("output").length())
        db.close(); db = ChatDatabase(context)
        val replay = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", first, http, credential = { "mock-only" })
        assertEquals(150L, replay.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(7, replay.getJSONArray("output").length())
        assertEquals(listOf("POST", "POST", "GET", "GET"), List(4) { server.takeRequest(2, TimeUnit.SECONDS)!!.method })
        assertEquals(4, server.requestCount)
    }

    @Test fun reopeningPreparedLaterRoundDoesNotReplaceItsPaidSnapshotWithTheFirstParent() {
        val first = call("resp_first").put("usage", JSONObject().put("input_tokens", 100))
        val second = call("resp_second", callId = "call_2").put("usage", JSONObject().put("input_tokens", 30))
        val failed = JSONObject().put("type", "function_call_output").put("call_id", "call_2").put("output", "saved second result")
        val prior = JsonProtocol.arrayCopy(first.getJSONArray("output"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "call_1").put("output", "saved first result"))
        for (i in 0 until second.getJSONArray("output").length()) prior.put(second.getJSONArray("output").get(i))
        prior.put(failed)
        val ledger = OpenAiUsageLedger.observe(OpenAiUsageLedger.observe(null, "resp_first", first.getJSONObject("usage"), "completed"),
            "resp_second", second.getJSONObject("usage"), "completed")
        val prepared = db.getOpenAiToolCheckpoint("chat", "request")!!
            .put("phase", "prepared").put("round", 2).put("parent_id", "resp_second")
            .put("usage", OpenAiUsageLedger.total(ledger)).put(OpenAiUsageLedger.FIELD, ledger)
            .put("prior_output", prior).put("next_input", JSONArray().put(failed)).put("received_response", second)
        assertTrue(db.advanceOpenAiToolResponse("chat", "request", "resp_first", "resp_second", prepared))
        assertEquals("resp_second", db.getActiveResponse("chat")!!.getString("response_id"))
        db.close(); db = ChatDatabase(context)
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", first, http, credential = { "mock-only" })
        }
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("posting", saved.getString("phase"))
        assertEquals("resp_second", saved.getJSONObject("received_response").getString("id"))
        assertEquals(130L, saved.getJSONObject("usage").getLong("input_tokens"))
        val durable = OpenAiLocalFileToolBridge.durableResponse(saved)!!
        assertEquals(6, durable.getJSONArray("output").length())
        assertEquals(130L, durable.getJSONObject("usage").getLong("input_tokens"))
        val wire = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals("resp_second", wire.getString("previous_response_id"))
        assertEquals("saved second result", wire.getJSONArray("input").getJSONObject(0).getString("output"))
        assertEquals(1, server.requestCount)
    }

    @Test fun terminalChildSnapshotSurvivesReopenBeforeFinalCommitAndNeedsNoGet() {
        val first = call("resp_first").put("usage", JSONObject().put("input_tokens", 100))
        val child = finalResponse().put("usage", JSONObject().put("input_tokens", 20))
        val ledger = OpenAiUsageLedger.observe(OpenAiUsageLedger.observe(null, "resp_first", first.getJSONObject("usage"), "completed"),
            "resp_final", child.getJSONObject("usage"), "completed")
        val output = JsonProtocol.arrayCopy(first.getJSONArray("output")).put(JSONObject()
            .put("type", "function_call_output").put("call_id", "call_1").put("output", "exact durable result"))
        val checkpoint = db.getOpenAiToolCheckpoint("chat", "request")!!
            .put("phase", "waiting").put("round", 1).put("parent_id", "resp_first")
            .put("response_id", "resp_final").put("prior_output", output)
            .put("received_response", child).put(OpenAiUsageLedger.FIELD, ledger)
            .put("usage", OpenAiUsageLedger.total(ledger))
        assertTrue(db.advanceOpenAiToolResponse("chat", "request", "resp_first", "resp_final", checkpoint))
        db.close(); db = ChatDatabase(context)
        // A stale indexed snapshot can still look active even after the bridge saved terminal JSON.
        val stale = JsonProtocol.objectCopy(child).put("status", "in_progress")
            .put("usage", JSONObject().put("input_tokens", 999)).put("output", JSONArray())
        val preserved = OpenAiLocalFileToolBridge.attachReceivedPrefix(db, "chat", "request", stale)
        assertEquals(120L, preserved.getJSONObject("usage").getLong("input_tokens"))
        assertEquals("Found needle", OpenAiSseStreamReader.terminalOutputText(preserved))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", stale, http, credential = { "" })
        assertEquals("completed", result.getString("status"))
        assertEquals("Found needle", OpenAiSseStreamReader.terminalOutputText(result))
        assertEquals(120L, result.getJSONObject("usage").getLong("input_tokens"))
        assertEquals(4, result.getJSONArray("output").length())
        assertEquals("exact durable result", result.getJSONArray("output").getJSONObject(2).getString("output"))
        assertEquals("finished", db.getOpenAiToolCheckpoint("chat", "request")!!.getString("phase"))
        assertEquals(0, server.requestCount)
    }

    @Test fun paidContinuationCannotReuseAnOlderAncestorIdAndReplaceItsUsage() {
        server.enqueue(MockResponse().setBody(call("resp_second", callId = "call_2").toString()))
        server.enqueue(MockResponse().setBody(finalResponse().put("id", "resp_first").toString()))
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("posting", saved.getString("phase"))
        assertEquals("resp_second", saved.getJSONObject("received_response").getString("id"))
        assertEquals(4L, saved.getJSONObject("usage").getLong("input_tokens"))
        db.close(); db = ChatDatabase(context)
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals(2, server.requestCount)
        repeat(2) { assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method) }
    }

    @Test fun waitingChildIsPolledAfterReopenAndFinishedCheckpointNeedsNoNetwork() {
        val checkpoint = db.getOpenAiToolCheckpoint("chat", "request")!!.put("phase", "waiting")
            .put("round", 1).put("response_id", "resp_final")
        assertTrue(db.advanceOpenAiToolResponse("chat", "request", "resp_first", "resp_final", checkpoint))
        db.close(); db = ChatDatabase(context)
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val result = OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        val wire = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", wire.method); assertEquals("/v1/responses/resp_final", wire.path)
        assertEquals(result.toString(), OpenAiLocalFileToolBridge.durableTerminal(db, "chat", "request", null)!!.toString())
        assertEquals(1, server.requestCount)
    }

    @Test fun credentialChangeBlocksContinuationButNotAnAlreadyPaidFinalAnswer() {
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "other-account" })
        }
        assertEquals("resp_final", OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", finalResponse(), http,
            credential = { "" }).getString("id"))
        assertEquals(0, server.requestCount)
    }

    @Test fun cancellationReplayPairsCallsAndPreservesAnExistingCompletedResult() {
        val output = JSONArray().put(JSONObject().put("type", "function_call").put("call_id", "A"))
            .put(JSONObject().put("type", "function_call").put("call_id", "B"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "A").put("output", "exact original"))
        val replay = OpenAiLocalFileToolBridge.completePendingOutputs(output)
        assertEquals(3, output.length()); assertEquals(4, replay.length())
        assertEquals("exact original", replay.getJSONObject(2).getString("output"))
        assertEquals("B", replay.getJSONObject(3).getString("call_id"))
        assertTrue(JSONObject(replay.getJSONObject(3).getString("output")).getBoolean("cancelled"))
        assertEquals(4, OpenAiLocalFileToolBridge.completePendingOutputs(replay).length())
        assertEquals(0, server.requestCount)
    }

    @Test fun uncertainPostAndStoppedOwnerNeverRepeatAPaidRequest() {
        val snapshot = db.getOpenAiToolCheckpoint("chat", "request")!!.put("phase", "posting")
        db.saveOpenAiToolCheckpoint("chat", "request", snapshot)
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        db.clearActiveResponse("chat", expectedRequestId = "request")
        assertThrows(CancellationException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals(0, server.requestCount)
        assertFalse(db.advanceOpenAiToolResponse("chat", "request", "resp_first", "foreign", snapshot))
    }
    @Test fun unknownToolsHaveNoHttpSideEffects() {
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first", "arbitrary_read"), http, credential = { "mock-only" })
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun expiredDeadlineStillStopsToolRoundsBeforeAnyHttp() {
        db.setActiveResponse("chat", "resp_first", "request", "", "in_progress",
            System.currentTimeMillis() - ResponseDeadlinePolicy.MAX_REQUEST_MS - 1, "high", "high", false, 6, 1, true)
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals(0, server.requestCount)
        assertEquals(0, db.getOpenAiToolCheckpoint("chat", "request")!!.getJSONObject("telemetry").getInt("calls_executed"))
    }

    private fun assertContinuationPostIsNotRepeated(response: MockResponse) {
        server.enqueue(response)
        // A replayable body would consume this success in the same execute(), hiding the first failure.
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        assertThrows(IOException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        assertEquals(1, server.requestCount)
        val saved = db.getOpenAiToolCheckpoint("chat", "request")!!
        assertEquals("posting", saved.getString("phase"))
        assertEquals("resp_first", db.getActiveResponse("chat")!!.getString("response_id"))
        db.close(); db = ChatDatabase(context)
        assertThrows(UnrecoverableResponsesStreamException::class.java) {
            OpenAiLocalFileToolBridge.finish(context, db, "chat", "request", call("resp_first"), http, credential = { "mock-only" })
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun continuationPostIsNotRepeatedByOkHttpAfter408() {
        assertContinuationPostIsNotRepeated(MockResponse().setResponseCode(408).setBody("request timeout"))
    }

    @Test fun continuationPostIsNotRepeatedByOkHttpAfter503RetryAfterZero() {
        assertContinuationPostIsNotRepeated(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("busy"))
    }

    @Test fun ambiguousContinuationDisconnectLeavesPostingAndNeverRepeatsPaidPost() {
        assertContinuationPostIsNotRepeated(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
    }

    @Test fun statusCallbackReportsActiveToolNameAndRound() {
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val statuses = mutableListOf<String>()
        OpenAiLocalFileToolBridge.finish(
            context, db, "chat", "request", call("resp_first"), http,
            onStatus = { statuses.add(it) },
            credential = { "mock-only" }
        )
        assertEquals(1, statuses.size)
        assertTrue(statuses.single().contains("(1)"))
        assertFalse(statuses.single().contains("/6"))
        assertTrue(statuses.single().contains("search_attached_file"))
    }
}
