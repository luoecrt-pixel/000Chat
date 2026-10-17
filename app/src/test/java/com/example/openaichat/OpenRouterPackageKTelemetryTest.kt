package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/** Exercises real serialized requests and SSE snapshots, rather than constructing aggregate totals. */
class OpenRouterPackageKTelemetryTest {
    private fun enqueue(server: MockWebServer, vararg frames: String) {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(frames.joinToString("") { "data: $it\n\n" }))
    }

    private fun toolFrame(index: Int) = """{"id":"gen-$index","choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-$index","type":"function","function":{"name":"test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
    private fun finishFrame() = """{"id":"gen-final","model":"test-model","choices":[{"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}"""
    private fun tools() = JSONArray().put(JSONObject().put("type", "function").put("function",
        JSONObject().put("name", "test_tool").put("parameters", JSONObject().put("type", "object"))))

    private fun complete(client: OpenRouterChatClient, messages: JSONArray = JSONArray()
        .put(JSONObject().put("role", "user").put("content", "hi")),
        onFileIo: (Int, JSONObject) -> Unit = { _, _ -> }): OpenRouterCompletionResult = client.complete(
        apiKey = "mock-key", model = "test-model", messages = messages, tools = tools(),
        maxTokens = null, reasoningEffort = null, verbosity = null, parallelToolCalls = false,
        executeTool = { name, _ -> assertEquals("test_tool", name); "{\"ok\":true}" },
        onFileIoObserved = onFileIo)

    @Test fun twoRoundsPreserveBreakdownAndSparseRepeatedSnapshotsWithoutIncreasingTotalCharge() {
        MockWebServer().use { server ->
            enqueue(server, toolFrame(0),
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":5},"cost":0.0125,"cost_details":{"upstream_inference_cost":0.0100,"server_tool_cost":0.0025},"upstream_inference_cost":0.0100,"server_tool_cost":0.0025,"service_tier":"priority","server_tool_use":{"web_search_requests":1},"iterations":[{"type":"message","model":"test-model","input_tokens":100,"output_tokens":10,"cost":0.0125}]}}""",
                // These are snapshots of this round, not three additional generations.
                """{"choices":[],"usage":{"prompt_tokens":100,"cost":0.0125}}""",
                """{"choices":[],"usage":{"completion_tokens":10}}""", "[DONE]")
            enqueue(server, finishFrame(),
                """{"choices":[],"usage":{"prompt_tokens":150,"completion_tokens":20,"cost":0,"cost_details":{"upstream_inference_cost":0,"server_tool_cost":0},"service_tier":"standard","server_tool_use":{"web_search_requests":2,"code_interpreter_requests":0},"iterations":[{"type":"message","input_tokens":150,"output_tokens":20,"cost":0}]}}""", "[DONE]")
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                val result = complete(client)
                val usage = result.usage!!
                assertEquals(250L, usage.getLong("prompt_tokens"))
                assertEquals(30L, usage.getLong("completion_tokens"))
                assertEquals(80L, usage.getJSONObject("prompt_tokens_details").getLong("cached_tokens"))
                assertEquals(5L, usage.getJSONObject("prompt_tokens_details").getLong("cache_write_tokens"))
                assertEquals(0.0125, usage.getDouble("cost"), 1e-12)
                assertEquals(2, result.roundsCompleted)
                val snapshot = OpenRouterUsageSnapshot.from(usage)
                assertEquals("provider_reported", snapshot.costKind)
                val metadata = snapshot.costDetails!!
                assertEquals(0.0100, metadata.getJSONObject("cost_details").getDouble("upstream_inference_cost"), 1e-12)
                assertEquals(0.0025, metadata.getDouble("server_tool_cost"), 1e-12)
                assertEquals(3L, metadata.getJSONObject("server_tool_use").getLong("web_search_requests"))
                assertEquals(0L, metadata.getJSONObject("server_tool_use").getLong("code_interpreter_requests"))
                assertEquals("standard", metadata.getString("service_tier"))
                assertEquals(2, metadata.getJSONArray("iterations").length())
                val compact = FileBackedIoTelemetry.mergeCompactTelemetry(costDetails = metadata)!!
                assertEquals(2, compact.getJSONObject("cost_details").getJSONArray("iterations").length())
                assertEquals(2, server.requestCount)
                repeat(2) {
                    val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                    assertEquals("/api/v1/chat/completions", request.path)
                    assertFalse(JSONObject(request.body.readUtf8()).has("max_tokens"))
                }
            } finally { client.close() }
        }
    }

    @Test fun unknownRoundCostStaysUnknownEvenWhenOtherRoundReportsZeroAndBreakdownAmounts() {
        MockWebServer().use { server ->
            enqueue(server, toolFrame(0),
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10,"cost":0,"server_tool_cost":0.5}}""", "[DONE]")
            enqueue(server, finishFrame(),
                """{"choices":[],"usage":{"prompt_tokens":150,"completion_tokens":20,"cost_details":{"upstream_inference_cost":0.2}}}""", "[DONE]")
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                val snapshot = OpenRouterUsageSnapshot.from(complete(client).usage)
                assertEquals(250L, snapshot.inputTokens)
                assertNull(snapshot.reportedCostUsd)
                assertNull(snapshot.costUsd)
                assertEquals("unknown", snapshot.costKind)
                assertEquals(0.5, snapshot.costDetails!!.getDouble("server_tool_cost"), 0.0)
                assertEquals("Расход неизвестен", RequestCostFormatter.text(JSONObject().put("cost_kind", snapshot.costKind)))
            } finally { client.close() }
        }
    }

    @Test fun laterNullOrInvalidCostSnapshotsRetainPreviouslyConfirmedRoundCostAndNewTokens() {
        MockWebServer().use { server ->
            enqueue(server, finishFrame(),
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10,"cost":0.0125}}""",
                """{"choices":[],"usage":{"prompt_tokens":120,"cost":null}}""",
                """{"choices":[],"usage":{"completion_tokens":20,"cost":"NaN"}}""",
                """{"choices":[],"usage":{"prompt_tokens":150,"cost":-1}}""", "[DONE]")
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                val result = complete(client)
                val usage = result.usage!!
                assertEquals(150L, usage.getLong("prompt_tokens"))
                assertEquals(20L, usage.getLong("completion_tokens"))
                assertEquals(0.0125, usage.getDouble("cost"), 1e-12)
                val snapshot = OpenRouterUsageSnapshot.from(usage)
                assertEquals("provider_reported", snapshot.costKind)
                assertEquals(0.0125, snapshot.reportedCostUsd!!, 1e-12)
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun threeToolRoundsReportCumulativeIoAndDeltasWhoseSumEqualsFinalObservation() {
        val file = Files.createTempFile("openrouter-io", ".txt").toFile().apply { writeText("Текст 😀\n0123456789") }
        try {
            val backed = FileBackedText(file)
            val original = JSONArray().put(JSONObject().put("role", "user").put("content", backed))
            MockWebServer().use { server ->
                repeat(2) { round -> enqueue(server, toolFrame(round), "[DONE]") }
                enqueue(server, finishFrame(), "[DONE]")
                val client = OpenRouterChatClient(server.url("/api/v1").toString())
                val observations = mutableListOf<JSONObject>()
                try {
                    val result = complete(client, original) { index, stats ->
                        assertEquals(observations.size, index)
                        observations += JsonProtocol.objectCopy(stats)
                    }
                    assertEquals(3, result.roundsCompleted)
                    assertEquals(3, observations.size)
                    assertEquals(1, result.fileIoTelemetry!!.getInt("files"))
                    val cumulative = observations.map { it.getLong("source_read_passes") }
                    // Construction + canonical history fingerprint + one network write each round.
                    assertEquals(listOf(3L, 4L, 5L), cumulative)
                    assertEquals(listOf(3L, 1L, 1L), observations.map { it.getLong("round_read_passes") })
                    assertEquals(cumulative.last(), observations.sumOf { it.getLong("round_read_passes") })
                    assertEquals(file.length() * 5L, result.fileIoTelemetry!!.getLong("source_read_bytes"))
                    assertEquals(file.length() * 5L, observations.sumOf { it.getLong("round_read_bytes") })
                    repeat(3) {
                        val sent = JSONObject(server.takeRequest().body.readUtf8())
                        assertEquals(file.readText(), sent.getJSONArray("messages").getJSONObject(0).getString("content"))
                    }
                } finally { client.close() }
            }
        } finally { file.delete() }
    }

    @Test fun initialTypedHttpErrorRemainsDirectEvenWhenAttachmentSerializationDidIo() {
        val file = Files.createTempFile("openrouter-http-error", ".txt").toFile().apply { writeText("attachment") }
        try {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(400).setBody(
                    """{"error":{"message":"Unsupported input","type":"validation_error","metadata":{"provider_name":"Anthropic"}}}"""))
                val client = OpenRouterChatClient(server.url("/api/v1").toString())
                try {
                    val error = assertThrows(OpenRouterUpstreamException::class.java) {
                        complete(client, JSONArray().put(JSONObject().put("role", "user").put("content", FileBackedText(file))))
                    }
                    assertEquals("HTTP", error.stage)
                    assertEquals(400, error.httpStatus)
                    assertEquals("validation_error", error.errorType)
                    assertEquals("Anthropic", error.providerName)
                } finally { client.close() }
            }
        } finally { file.delete() }
    }

    @Test fun failingLaterSsePreservesEarlierAndCurrentPaidUsageAndTypedCause() {
        MockWebServer().use { server ->
            enqueue(server, toolFrame(0),
                """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":10,"cost":0.1}}""", "[DONE]")
            enqueue(server,
                """{"id":"gen-partial","choices":[{"delta":{"content":"partial"}}],"usage":{"prompt_tokens":50,"completion_tokens":3,"server_tool_cost":0.01}}""",
                """{"error":{"message":"provider unavailable","type":"server_error","metadata":{"provider_name":"Anthropic"}}}""")
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                val error = assertThrows(OpenRouterCompletionException::class.java) { complete(client) }
                assertEquals("partial", error.partialText)
                assertEquals(150L, error.accumulatedUsage!!.getLong("prompt_tokens"))
                assertEquals(13L, error.accumulatedUsage!!.getLong("completion_tokens"))
                assertFalse(error.accumulatedUsage!!.has("cost"))
                assertEquals(0.01, error.accumulatedUsage!!.getDouble("server_tool_cost"), 0.0)
                assertEquals(1, error.roundsCompleted)
                assertTrue(error.cause is OpenRouterUpstreamException)
                assertEquals("SSE", (error.cause as OpenRouterUpstreamException).stage)
            } finally { client.close() }
        }
    }
}
