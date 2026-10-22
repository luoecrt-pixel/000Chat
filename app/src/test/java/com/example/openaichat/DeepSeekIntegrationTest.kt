package com.example.openaichat

import okhttp3.Call
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekIntegrationTest {
    private fun frame(delta: JSONObject = JSONObject(), finish: String? = null, usage: JSONObject? = null) =
        JSONObject().put("model", "deepseek-test").put("choices", JSONArray().put(JSONObject().put("delta", delta).apply {
            finish?.let { put("finish_reason", it) }
        })).apply { usage?.let { put("usage", it) } }.toString()
    private fun usage(prompt: Any = 7, completion: Any = 3) = JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion)
    private fun enqueue(server: MockWebServer, vararg events: String, done: Boolean = true) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody((events.toList() + if (done) listOf("[DONE]") else emptyList()).joinToString("") { "data: $it\n\n" }))
    }
    private fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function",
        JSONObject().put("name", "test_tool").put("parameters", JSONObject().put("type", "object"))))
    private fun call(index: Any?, id: String? = null, arguments: String = "{}", first: Boolean = true): JSONObject =
        JSONObject().apply {
            if (index != null) put("index", index)
            if (first) { put("id", id ?: "A"); put("type", "function") }
            put("function", JSONObject().put("arguments", arguments).apply { if (first) put("name", "test_tool") })
        }
    private fun complete(client: DeepSeekChatClient, effort: String = "high", tools: JSONArray? = null,
        execute: (String, JSONObject) -> String = { _, _ -> "ok" }, checkpoint: (JSONObject) -> Unit = {},
        reasoning: (String) -> Unit = {}, active: () -> Boolean = { true }, created: (Call) -> Unit = {},
        sessionId: String = "test-request"): OpenRouterCompletionResult = client.complete(
        apiKey = "inert-deepseek-test-key", model = "deepseek-test", messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
        sessionId = sessionId, tools = tools, maxTokens = null, reasoningEffort = effort, verbosity = null,
        parallelToolCalls = false, executeTool = execute, onProtocolCheckpoint = checkpoint,
        onReasoningDelta = reasoning, shouldContinue = active, onCallCreated = created)

    @Test fun fullStopHasSeparateCompletionEvidenceAndOnlyDirectEndpointReceivesKey() {
        MockWebServer().use { server ->
            server.start()
            enqueue(server, frame(JSONObject().put("reasoning_content", "reason").put("content", "answer"), "stop", usage()))
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val seen = mutableListOf<String>()
                val result = complete(client, reasoning = seen::add)
                assertEquals("answer", result.text)
                assertEquals("stop", result.finishReason)
                assertEquals(true, result.generationComplete); assertEquals(true, result.usageComplete); assertEquals(true, result.replayable)
                assertTrue(DeepSeekProtocolCodec.hasCompleteReasoning(result.protocol))
                assertEquals("reason", seen.last())
                assertEquals("reason", result.assistantMessage.getString("reasoning_summary"))
                val request = server.takeRequest()
                assertEquals("/chat/completions", request.path)
                assertEquals("Bearer inert-deepseek-test-key", request.getHeader("Authorization"))
                val body = JSONObject(request.body.readUtf8())
                assertFalse(body.has("provider")); assertFalse(body.has("plugins")); assertFalse(body.has("max_tokens"))
                assertEquals("enabled", body.getJSONObject("thinking").getString("type"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun stopWithoutUsagePreservesTextAndReasoningButCannotClaimCompletionOrFreeTokens() {
        MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "raw thought").put("content", "paid visible text"), "stop"))
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val result = complete(client)
                assertEquals("paid visible text", result.text); assertEquals("missing_usage", result.finishReason)
                assertEquals(true, result.generationComplete); assertEquals(false, result.usageComplete); assertEquals(false, result.replayable)
                assertFalse(result.usage!!.has("prompt_tokens")); assertFalse(result.usage!!.has("completion_tokens"))
                assertEquals("raw thought", result.protocol!!.getJSONArray("tail").getJSONObject(0).getString("reasoning_content"))
                assertNotNull(result.protocolWarning)
            } finally { client.close() }
        }
    }

    @Test fun missingThinkingPreventsReplayButDisabledThinkingNeedsNoReasoningField() {
        for (effort in listOf("high", "none")) MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("content", "answer"), "stop", usage()))
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val result = complete(client, effort)
                assertEquals("answer", result.text)
                assertEquals(effort == "none", result.replayable)
                assertEquals(if (effort == "none") "stop" else "missing_reasoning_content", result.finishReason)
                assertEquals(true, result.usageComplete)
            } finally { client.close() }
        }
    }

    @Test fun strictToolIndexRejectsCoercionsBeforeAnyExecution() {
        for (index in listOf("0", 1.5, -1, JSONObject.NULL, null, 2147483648L)) MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "r").put("tool_calls", JSONArray().put(call(index))), "tool_calls", usage()))
            val client = DeepSeekChatClient(server.url("/").toString())
            var executions = 0
            try {
                try { complete(client, tools = tools(), execute = { _, _ -> executions++; "result" }); fail("index=$index accepted") }
                catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("index")) }
                assertEquals(0, executions); assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun twoInterleavedCallsKeepIndicesArgumentsAndFirstFragmentMetadata() {
        MockWebServer().use { server ->
            server.start()
            enqueue(server,
                frame(JSONObject().put("reasoning_content", "tools").put("tool_calls", JSONArray().put(call(1, "B", "{\"x\":" )).put(call(0, "A", "{\"x\":")))),
                frame(JSONObject().put("tool_calls", JSONArray().put(call(1, arguments = "2}", first = false)).put(call(0, arguments = "1}", first = false))), "tool_calls", usage()))
            enqueue(server, frame(JSONObject().put("reasoning_content", "final").put("content", "done"), "stop", usage()))
            val seen = mutableListOf<Int>()
            val checkpoints = mutableListOf<JSONObject>()
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val result = complete(client, tools = tools(), execute = { _, args -> seen += args.getInt("x"); "result" }, checkpoint = checkpoints::add)
                assertEquals(listOf(1, 2), seen); assertEquals(true, result.replayable)
                val sent = server.takeRequest(); assertEquals("/chat/completions", sent.path)
                val next = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
                val calls = next.getJSONObject(1).getJSONArray("tool_calls")
                assertEquals("A", calls.getJSONObject(0).getString("id")); assertEquals("B", calls.getJSONObject(1).getString("id"))
                assertEquals("{\"x\":1}", calls.getJSONObject(0).getJSONObject("function").getString("arguments"))
                assertTrue(checkpoints.any { it.optJSONObject("uncommitted_assistant")?.optJSONArray("tool_calls") != null })
            } finally { client.close() }
        }
    }

    @Test fun twoRoundsWithIncompleteSecondUsageKeepDiagnosticsOutsideConfirmedTotals() {
        MockWebServer().use { server ->
            server.start()
            enqueue(server, frame(JSONObject().put("reasoning_content", "tools").put("tool_calls", JSONArray().put(call(0))), "tool_calls", usage(10, 4)))
            enqueue(server, frame(JSONObject().put("reasoning_content", "final").put("content", "done"), "stop", JSONObject().put("prompt_tokens", 12)))
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val result = complete(client, tools = tools())
                assertEquals("missing_usage", result.finishReason)
                val actual = result.usage!!
                assertFalse(actual.getBoolean("usage_complete")); assertFalse(actual.has("prompt_tokens")); assertFalse(actual.has("completion_tokens"))
                val observed = actual.getJSONObject("observed_partial_usage")
                assertEquals(22, observed.getInt("prompt_tokens")); assertEquals(4, observed.getInt("completion_tokens"))
                assertEquals(12, actual.getJSONArray("observed_rounds").getJSONObject(1).getJSONObject("usage").getInt("prompt_tokens"))
            } finally { client.close() }
        }
    }

    @Test fun tokenCountersNeverAcceptNumericStringsOrFractionsAsCompleteUsage() {
        for (bad in listOf<Any>("7", 7.5, -7, JSONObject.NULL)) MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "r").put("content", "answer"), "stop", usage(bad, 3)))
            val client = DeepSeekChatClient(server.url("/").toString())
            try {
                val result = complete(client)
                assertEquals(false, result.usageComplete); assertFalse(result.usage!!.has("prompt_tokens"))
                assertEquals(3, result.usage!!.getJSONObject("observed_partial_usage").getInt("completion_tokens"))
            } finally { client.close() }
        }
    }

    @Test fun interruptedArchiveDoesNotRewriteOriginalCallsOrInventToolOutcomes() {
        val assistant = JSONObject().put("role", "assistant").put("content", "before").put("reasoning_content", "r")
            .put("tool_calls", JSONArray().put(call(0, "A", "{\"text\":\"first\"}").apply { remove("index") })
                .put(call(1, "B", "{\"text\":\"second\"}").apply { remove("index") }))
        val tail = JSONArray().put(assistant).put(JSONObject().put("role", "tool").put("tool_call_id", "A").put("content", "created"))
        val cp = JSONObject().put("version", 1).put("tail", tail).put("replayable", true)
        val original = StreamingJsonRequestBody.sha256(tail)
        val archived = DeepSeekProtocolCodec.withVisibleTail(cp, JSONObject().put("text", "visible"))
        assertEquals(original, StreamingJsonRequestBody.sha256(archived.getJSONArray("tail")))
        assertEquals(original, StreamingJsonRequestBody.sha256(cp.getJSONArray("tail")))
        assertEquals(2, archived.getJSONArray("tail").length())
        assertFalse(archived.getBoolean("replayable"))
        assertEquals("B", archived.getJSONArray("unconfirmed_tool_calls").getJSONObject(0).getString("id"))
        assertEquals("{\"text\":\"second\"}", archived.getJSONArray("tail").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(1).getJSONObject("function").getString("arguments"))
    }

    @Test fun stopFlushesLatestToolOnlyPrefixBeforeCancellation() {
        MockWebServer().use { server ->
            server.start()
            val event = "data: " + frame(JSONObject().put("tool_calls", JSONArray().put(call(0, arguments = "{\"path\":\"part")))) + "\n\n"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(event + " ".repeat(10_000))
                .throttleBody(event.toByteArray().size.toLong(), 1, TimeUnit.DAYS))
            val client = DeepSeekChatClient(server.url("/").toString())
            val active = AtomicBoolean(true)
            val ready = CountDownLatch(1)
            val current = AtomicReference<Call>()
            val saved = AtomicReference<JSONObject>()
            val pool = Executors.newSingleThreadExecutor()
            try {
                val task = pool.submit<OpenRouterCompletionResult> { complete(client, tools = tools(),
                    checkpoint = { saved.set(it); ready.countDown() }, active = active::get, created = current::set) }
                assertTrue("first tool-only fragment was not checkpointed", ready.await(10, TimeUnit.SECONDS))
                client.flushCheckpoint("test-request")
                val durable = JsonProtocol.objectCopy(saved.get())
                active.set(false); current.get().cancel()
                try { task.get(10, TimeUnit.SECONDS); fail("cancelled stream completed") } catch (_: java.util.concurrent.ExecutionException) { }
                val partial = durable.getJSONObject("uncommitted_assistant").getJSONArray("tool_calls").getJSONObject(0)
                assertEquals("{\"path\":\"part", partial.getJSONObject("function").getString("arguments"))
                assertEquals(1, server.requestCount)
                assertFalse(DeepSeekProtocolCodec.withVisibleTail(durable, JSONObject()).getBoolean("replayable"))
            } finally { active.set(false); current.get()?.cancel(); pool.shutdownNow(); client.close() }
        }
    }

    @Test fun oversizedToolResultStopsOnceAndKeepsCompletedArtifactAndOriginalCall() {
        MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "tools").put("tool_calls", JSONArray().put(call(0))), "tool_calls", usage()))
            val client = DeepSeekChatClient(server.url("/").toString(), maxInlineToolResultBytes = 64)
            val artifact = File.createTempFile("deepseek-confirmed-", ".txt")
            val saved = AtomicReference<JSONObject>()
            var executed = 0
            try {
                try { complete(client, tools = tools(), checkpoint = saved::set, execute = { _, _ ->
                    executed++; artifact.writeText("confirmed result"); "x".repeat(1024 * 1024)
                }); fail("oversized inline tool result accepted") } catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("inline")) }
                assertEquals(1, executed); assertEquals(1, server.requestCount); assertEquals("confirmed result", artifact.readText())
                val cp = saved.get()
                assertEquals("completed_result_not_inline", cp.getJSONArray("tool_execution").getJSONObject(0).getString("state"))
                assertEquals("A", cp.getJSONArray("tail").getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
                assertEquals(1, cp.getJSONArray("tail").length()); assertTrue(cp.toString().length < 4096)
            } finally { client.close(); artifact.delete() }
        }
    }

    @Test fun failedSideEffectOutcomeIsUnknownAndNeverSynthesizedAsCancelled() {
        MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "r").put("tool_calls", JSONArray().put(call(0))), "tool_calls", usage()))
            val client = DeepSeekChatClient(server.url("/").toString())
            val saved = AtomicReference<JSONObject>()
            var executions = 0
            try {
                try { complete(client, tools = tools(), checkpoint = saved::set, execute = { _, _ -> executions++; throw IOException("result unavailable after write") }); fail("tool failure ignored") }
                catch (_: IOException) { }
                val cp = saved.get()
                assertEquals("outcome_unknown", cp.getJSONArray("tool_execution").getJSONObject(0).getString("state"))
                assertEquals(1, cp.getJSONArray("tail").length()); assertEquals(1, executions); assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun checkpointBudgetIsEnforcedBeforeToolExecution() {
        MockWebServer().use { server ->
            server.start(); enqueue(server, frame(JSONObject().put("reasoning_content", "r").put("tool_calls", JSONArray().put(call(0))), "tool_calls", usage()))
            val client = DeepSeekChatClient(server.url("/").toString(), maxCheckpointBytes = 100)
            var executions = 0
            try {
                try { complete(client, tools = tools(), execute = { _, _ -> executions++; "ok" }); fail("oversized checkpoint accepted") }
                catch (expected: IOException) { assertTrue(expected.message.orEmpty().contains("Checkpoint")) }
                assertEquals(0, executions); assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }
}
