package com.example.openaichat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenRouterChatClientTest {

    @Test
    fun aggregatesUsageAcrossToolLoopAndRequestsUsageDetails() {
        val requestCount = AtomicInteger(0)
        val firstRequestBody = AtomicReference<String?>(null)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                if (requestNumber == 1) firstRequestBody.set(chain.request().body?.let { body ->
                    okio.Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8()
                })
                val body = if (requestNumber == 1) {
                    """{"choices":[{"message":{"content":"working","tool_calls":[{"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]}}],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":20},"completion_tokens_details":{"reasoning_tokens":5},"cost":0.01}}"""
                } else {
                    """{"choices":[{"message":{"content":"done","tool_calls":[]}}],"usage":{"prompt_tokens":150,"completion_tokens":30,"total_tokens":180,"prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":10},"cost":0.02}}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val result = client.complete(
                apiKey = "test",
                model = "anthropic/claude-opus-5.5",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                sessionId = "chat-123",
                tools = JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
                ),
                maxTokens = 128,
                maxRounds = 24,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, _ -> "tool-result" }
            )

            val firstBody = JSONObject(firstRequestBody.get())
            assertEquals("chat-123", firstBody.getString("session_id"))
            assertTrue(firstBody.getJSONObject("usage").getBoolean("include"))
            assertEquals("1h", firstBody.getJSONObject("cache_control").getString("ttl"))

            val usage = result.usage ?: error("usage missing")
            assertEquals(250L, usage.getLong("prompt_tokens"))
            assertEquals(50L, usage.getLong("completion_tokens"))
            assertEquals(300L, usage.getLong("total_tokens"))
            assertEquals(180L, usage.getJSONObject("prompt_tokens_details").getLong("cached_tokens"))
            assertEquals(20L, usage.getJSONObject("prompt_tokens_details").getLong("cache_write_tokens"))
            assertEquals(15L, usage.getJSONObject("completion_tokens_details").getLong("reasoning_tokens"))
            assertEquals(0.03, usage.getDouble("cost"), 0.000001)
            assertEquals("done", result.text)
            assertEquals(2, requestCount.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun toolLoopStopsBeforeStartingNextRoundAfterOwnershipLoss() {
        val requestCount = AtomicInteger(0)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                val body = if (requestNumber == 1) {
                    """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]}}],"usage":{"total_tokens":1}}"""
                } else {
                    """{"choices":[{"message":{"content":"unexpected second round","tool_calls":[]}}]}"""
                }
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

        val toolStarted = CountDownLatch(1)
        val releaseTool = CountDownLatch(1)
        val shouldContinue = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val client = OpenRouterChatClient("http://test.invalid", transport)

        try {
            val worker = Thread {
                try {
                    client.complete(
                        apiKey = "test",
                        model = "test-model",
                        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                        tools = JSONArray().put(
                            JSONObject()
                                .put("type", "function")
                                .put("function", JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
                        ),
                        maxTokens = 128,
                        maxRounds = 24,
                        reasoningEffort = null,
                        verbosity = null,
                        parallelToolCalls = false,
                        executeTool = { _, _ ->
                            toolStarted.countDown()
                            releaseTool.await(5, TimeUnit.SECONDS)
                            "tool-result"
                        },
                        shouldContinue = { shouldContinue.get() }
                    )
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            worker.start()

            assertTrue("first tool call never started", toolStarted.await(10, TimeUnit.SECONDS))
            shouldContinue.set(false)
            releaseTool.countDown()
            worker.join(10_000L)

            assertFalse("OpenRouter completion thread did not finish", worker.isAlive)
            assertTrue("expected ownership cancellation", failure.get() is CancellationException)
            assertEquals("old tool loop started a second HTTP round", 1, requestCount.get())
        } finally {
            client.close()
        }
    }
}
