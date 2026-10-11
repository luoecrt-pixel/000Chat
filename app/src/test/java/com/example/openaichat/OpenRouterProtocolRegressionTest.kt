package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.util.concurrent.TimeUnit

class OpenRouterProtocolRegressionTest {
    @Test fun structuredOutputIsExplicitAndEnforcedInActualRequest() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            assertNull(OpenRouterStructuredOutputPolicy.format("text", ""))
            assertThrows(IllegalArgumentException::class.java) { OpenRouterStructuredOutputPolicy.format("json_schema", "") }
            val format = OpenRouterStructuredOutputPolicy.format("json_schema", """{"type":"object","properties":{"answer":{"type":"string"}},"required":["answer"],"additionalProperties":false}""")!!
            server.enqueue(stream("""{"choices":[{"delta":{"content":"{\"answer\":\"yes\"}"},"finish_reason":"stop"}]}"""))
            client.complete("mock", "anthropic/claude-sonnet-5.5", input("Return JSON"), tools = null,
                maxTokens = null, reasoningEffort = "max", verbosity = null, parallelToolCalls = true,
                executeTool = { _, _ -> throw AssertionError("Unexpected tool") }, responseFormat = format)
            val request = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertEquals("json_schema", request.getJSONObject("response_format").getString("type"))
            assertTrue(request.getJSONObject("response_format").getJSONObject("json_schema").getBoolean("strict"))
            assertTrue(request.getJSONObject("provider").getBoolean("require_parameters"))
            assertFalse(request.has("max_tokens")); assertEquals("max", request.getJSONObject("reasoning").getString("effort"))
        } finally { client.close(); server.shutdown() }
    }
    private fun stream(vararg events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events.joinToString("") { "data: $it\n\n" } + "data: [DONE]\n\n")
    private fun input(text: String = "Create file") = JSONArray().put(JSONObject().put("role", "user").put("content", text))
    private fun complete(c: OpenRouterChatClient, messages: JSONArray = input(),
                         observeIo: (Int, JSONObject) -> Unit = { _, _ -> },
                         execute: (String, JSONObject) -> String = { _, _ -> "{}" }) =
        c.complete("mock", "anthropic/claude-sonnet-5.5", messages, tools = OpenRouterAppToolCatalog.tools(),
            maxTokens = null, reasoningEffort = "max", verbosity = "high", parallelToolCalls = true,
            executeTool = execute, onFileIoObserved = observeIo)

    @Test fun failedIoObserverDoesNotTurnSuccessfulResponseIntoFailure() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}"""))
            var observed = 0
            val result = complete(client, observeIo = { _, _ -> observed++; throw IllegalStateException("observer failed") })
            assertEquals(1, observed); assertEquals("Done", result.text); assertEquals("stop", result.finishReason)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun fatalIoObserverErrorIsNotSwallowed() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Done"},"finish_reason":"stop"}]}"""))
            val error = assertThrows(OutOfMemoryError::class.java) {
                complete(client, observeIo = { _, _ -> throw OutOfMemoryError("synthetic observer OOM") })
            }
            assertEquals("synthetic observer OOM", error.message)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun failedIoObserverDoesNotMaskUpstreamErrorOrPartialText() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Partial"}}]}""",
                """{"error":{"code":502,"message":"provider failed","metadata":{"provider_name":"Anthropic"}}}"""))
            var observed = 0
            val failure = assertThrows(OpenRouterCompletionException::class.java) {
                complete(client, observeIo = { _, _ -> observed++; throw IllegalStateException("observer failed") })
            }
            assertEquals(1, observed); assertEquals("Partial", failure.partialText)
            val upstream = failure.cause as OpenRouterUpstreamException
            assertEquals("SSE", upstream.stage); assertEquals(502, upstream.httpStatus)
            assertEquals("Anthropic", upstream.providerName)
            assertTrue(failure.message!!.contains("provider failed")); assertFalse(failure.message!!.contains("observer failed"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun nullDeltasDoNotBecomeTextOrToolNameFragments() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            server.enqueue(stream(
                """{"choices":[{"delta":{"role":"assistant","content":null,"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"create_local_text_file","arguments":""}}]}}]}""",
                """{"choices":[{"delta":{"role":null,"content":null,"tool_calls":[{"index":0,"id":null,"type":null,"function":{"name":null,"arguments":null}}]}}]}""",
                JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "tool_calls").put("delta", JSONObject().put("tool_calls", JSONArray()
                    .put(JSONObject().put("index", 0).put("function", JSONObject().put("arguments", "{\"filename\":\"666.txt\",\"content\":\"hello\"}"))))))).toString()))
            server.enqueue(stream("""{"choices":[{"delta":{"content":null}},{"delta":{"content":"ignored second choice"}}]}""",
                """{"choices":[{"delta":{"content":"File ready"},"finish_reason":"stop"}]}"""))
            var calls = 0
            val result = complete(client) { name, args ->
                calls++; assertEquals("create_local_text_file", name); assertEquals("hello", args.getString("content")); "{\"ok\":true}"
            }
            assertEquals(1, calls); assertEquals("File ready", result.text); assertEquals("stop", result.finishReason)
            val request = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(request.has("max_tokens")); assertFalse(request.has("verbosity"))
            assertEquals("max", request.getJSONObject("reasoning").getString("effort"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun lengthWithAndWithoutVisibleTextRemainsIncompleteAndDoesNotExecuteCutCalls() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            for (text in listOf("Partial", "")) {
                server.enqueue(stream(JSONObject().put("choices", JSONArray().put(JSONObject().put("finish_reason", "length")
                    .put("delta", JSONObject().put("content", text).put("tool_calls", JSONArray().put(JSONObject().put("index", 0)
                        .put("id", "cut").put("function", JSONObject().put("name", "create_local_text_file").put("arguments", "{"))))))).toString()))
                val result = complete(client) { _, _ -> throw AssertionError("A cut call must not execute") }
                assertEquals(text, result.text); assertFalse(OpenRouterFinishPolicy.completed(result.finishReason))
                assertEquals("length", result.finishReason)
                assertFalse(result.protocol!!.getJSONArray("tail").getJSONObject(0).has("tool_calls"))
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun missingFinishReasonAndMissingCutToolIdDoNotTriggerSideEffects() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Partial","tool_calls":[{"index":0,"function":{"name":"create_local_text_file","arguments":"{"}}]}}]}"""))
            val result = complete(client) { _, _ -> throw AssertionError("An unconfirmed finish must not execute tools") }
            assertEquals("Partial", result.text); assertNull(result.finishReason)
            assertFalse(OpenRouterFinishPolicy.completed(result.finishReason))
            assertFalse(result.protocol!!.getJSONArray("tail").getJSONObject(0).has("tool_calls"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun completedToolProtocolSurvivesSerializedHistoryAndNextUserTurn() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString())
        try {
            server.enqueue(stream("""{"choices":[{"delta":{"content":null,"reasoning_details":[{"index":0,"type":"reasoning.encrypted","data":"opaque-","signature":"sig-"}],"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"create_local_text_file","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""))
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Done","reasoning_details":[{"index":1,"type":"reasoning.encrypted","data":"final-data"}]},"finish_reason":"stop"}]}"""))
            val first = complete(client) { _, _ -> "{\"ok\":true}" }
            val stored = JSONObject(JSONObject().put("role", "assistant").put("text", first.text).put("openrouter_protocol", first.protocol).toString())
            val next = input(); assertTrue(OpenRouterProtocolCodec.append(next, stored)); next.put(JSONObject().put("role", "user").put("content", "Continue"))
            server.enqueue(stream("""{"choices":[{"delta":{"content":"Next"},"finish_reason":"stop"}]}"""))
            complete(client, next)
            server.takeRequest(); server.takeRequest()
            val sent = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            assertEquals("opaque-", sent.getJSONObject(1).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertEquals("sig-", sent.getJSONObject(1).getJSONArray("reasoning_details").getJSONObject(0).getString("signature"))
            assertEquals("tool", sent.getJSONObject(2).getString("role"))
            assertEquals("final-data", sent.getJSONObject(3).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
            assertFalse(sent.toString().contains("_astra_"))
        } finally { client.close(); server.shutdown() }
    }

    @Test fun changedPrefixResetsOldBlocksButKeepsNewBlocksSignedAfterThatChange() {
        val original = input("old")
        val expected = OpenRouterProtocolCodec.binding("model", JSONArray(), "key")
        fun protocol(prefix: JSONArray, text: String) = JSONObject().put("version", 1).put("input_sha256", OpenRouterProtocolCodec.fingerprint(prefix))
            .put("binding_sha256", expected).put("tail", JSONArray().put(JSONObject().put("role", "assistant").put("content", text)
                .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", text)))))
        val old = JSONObject().put("openrouter_protocol", protocol(original, "old-thinking"))
        val changed = input("edited"); OpenRouterProtocolCodec.append(changed, old)
        changed.put(JSONObject().put("role", "user").put("content", "new"))
        val fresh = JSONObject().put("openrouter_protocol", protocol(changed, "new-thinking"))
        OpenRouterProtocolCodec.append(changed, fresh)
        assertTrue(OpenRouterProtocolCodec.checkBinding(changed, expected))
        assertFalse(changed.getJSONObject(1).has("reasoning_details"))
        assertEquals("new-thinking", changed.getJSONObject(3).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
    }

    @Test fun rangesCoverLargeFileAndUnicodeBoundaryWithoutSilentCutoff() {
        val text = "x".repeat(39999) + "😀" + "y".repeat(100000)
        var offset = 0L; val restored = StringBuilder()
        while (true) {
            val page = BoundedTextRead.range(StringReader(text), offset, 40000)
            restored.append(page.getString("content")); offset = page.getLong("next_offset")
            if (page.getBoolean("eof")) break
        }
        assertEquals(text, restored.toString())
        assertThrows(java.io.IOException::class.java) { BoundedTextRead.range(StringReader("a\u0000b"), 0, 10) }
    }

    @Test fun reasoningFragmentsWithOmittedTypeKeepSignatureAndOrder() {
        val merger = ReasoningDetailAccumulator()
        merger.accept(JSONArray("""[{"index":0,"type":"reasoning.encrypted","data":"a","signature":"s"}]"""))
        merger.accept(JSONArray("""[{"index":0,"data":"b","signature":"ig"},{"index":1,"type":"reasoning.text","text":"next"}]"""))
        val result = merger.array()
        assertEquals(2, result.length()); assertEquals("ab", result.getJSONObject(0).getString("data"))
        assertEquals("sig", result.getJSONObject(0).getString("signature")); assertEquals("next", result.getJSONObject(1).getString("text"))
    }
}
