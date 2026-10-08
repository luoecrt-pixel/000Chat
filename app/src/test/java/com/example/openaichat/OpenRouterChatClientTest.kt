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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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
        val firstArguments = AtomicReference<JSONObject?>(null)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                if (requestNumber == 1) {
                    firstRequestBody.set(chain.request().body?.let { body ->
                        okio.Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8()
                    })
                }
                val body = if (requestNumber == 1) {
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"working","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{\"x\":\""}}]},"finish_reason":null}]}""",
                        """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"value\"}"}}]},"finish_reason":"tool_calls"}]}""",
                        """{"choices":[],"usage":{"prompt_tokens":100,"completion_tokens":20,"total_tokens":120,"prompt_tokens_details":{"cached_tokens":80,"cache_write_tokens":20},"completion_tokens_details":{"reasoning_tokens":5},"cost":0.01}}""",
                        "[DONE]"
                    )
                } else {
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                        """{"choices":[],"usage":{"prompt_tokens":150,"completion_tokens":30,"total_tokens":180,"prompt_tokens_details":{"cached_tokens":100},"completion_tokens_details":{"reasoning_tokens":10},"cost":0.02}}""",
                        "[DONE]"
                    )
                }
                sseResponse(chain, body)
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
                maxTokens = 200_000,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, arguments ->
                    firstArguments.set(arguments)
                    "tool-result"
                }
            )

            val firstBody = JSONObject(firstRequestBody.get())
            assertEquals("chat-123", firstBody.getString("session_id"))
            assertEquals(200_000, firstBody.getInt("max_tokens"))
            assertTrue(firstBody.getBoolean("stream"))
            assertTrue(firstBody.getJSONObject("stream_options").getBoolean("include_usage"))
            assertTrue(firstBody.getJSONObject("usage").getBoolean("include"))
            assertEquals("1h", firstBody.getJSONObject("cache_control").getString("ttl"))

            val arguments = firstArguments.get() ?: error("tool arguments missing")
            assertEquals("value", arguments.getString("x"))

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
    fun serverToolDefinitionsAreForwardedWithoutLocalExecution() {
        val requestBody = AtomicReference<String?>(null)
        val localExecutions = AtomicInteger(0)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestBody.set(chain.request().body?.let { body ->
                    okio.Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8()
                })
                sseResponse(
                    chain,
                    sse(
                        """{"id":"gen-server-tool","model":"test-model","choices":[{"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                        """{"choices":[],"usage":{"total_tokens":1}}""",
                        "[DONE]"
                    )
                )
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            client.complete(
                apiKey = "test",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "search the web")),
                tools = JSONArray()
                    .put(JSONObject().put("type", "openrouter:web_search"))
                    .put(JSONObject().put("type", "openrouter:web_fetch")),
                maxTokens = 128,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, _ ->
                    localExecutions.incrementAndGet()
                    error("server tools must not reach the local tool executor")
                }
            )

            val body = JSONObject(requestBody.get() ?: error("request body missing"))
            val tools = body.getJSONArray("tools")
            assertEquals(2, tools.length())
            assertEquals("openrouter:web_search", tools.getJSONObject(0).getString("type"))
            assertEquals("openrouter:web_fetch", tools.getJSONObject(1).getString("type"))
            assertEquals("auto", body.getString("tool_choice"))
            assertEquals(0, localExecutions.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun serverToolCallCannotFallThroughToLocalExecutor() {
        val localExecutions = AtomicInteger(0)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-server","type":"function","function":{"name":"openrouter:web_search","arguments":"{\"query\":\"test\"}"}}]},"finish_reason":"tool_calls"}]}""",
                        """{"choices":[],"usage":{"total_tokens":1}}""",
                        "[DONE]"
                    )
                )
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val failure = try {
                client.complete(
                    apiKey = "test",
                    model = "test-model",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "search")),
                    tools = JSONArray().put(JSONObject().put("type", "openrouter:web_search")),
                    maxTokens = 128,
                    reasoningEffort = null,
                    verbosity = null,
                    parallelToolCalls = false,
                    executeTool = { _, _ ->
                        localExecutions.incrementAndGet()
                        error("server tool must never reach local executor")
                    }
                )
                null
            } catch (e: IOException) {
                e
            }

            assertNotNull(failure)
            assertTrue(failure?.message?.contains("Unexpected OpenRouter server-side tool call") == true)
            assertEquals(0, localExecutions.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun serverAndLocalToolsCanShareOneRequest() {
        val requestCount = AtomicInteger(0)
        val localExecutions = AtomicInteger(0)
        val requestBody = AtomicReference<String?>(null)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                if (requestNumber == 1) {
                    requestBody.set(chain.request().body?.let { body ->
                        okio.Buffer().also { buffer -> body.writeTo(buffer) }.readUtf8()
                    })
                    sseResponse(
                        chain,
                        sse(
                            """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"local-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                            """{"choices":[],"usage":{"total_tokens":1}}""",
                            "[DONE]"
                        )
                    )
                } else {
                    sseResponse(
                        chain,
                        sse(
                            """{"choices":[{"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                            """{"choices":[],"usage":{"total_tokens":1}}""",
                            "[DONE]"
                        )
                    )
                }
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val result = client.complete(
                apiKey = "test",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "use tools")),
                tools = JSONArray()
                    .put(JSONObject().put("type", "openrouter:web_search"))
                    .put(
                        JSONObject()
                            .put("type", "function")
                            .put("function", JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
                    ),
                maxTokens = 128,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { name, _ ->
                    assertEquals("test_tool", name)
                    localExecutions.incrementAndGet()
                    "local-result"
                }
            )

            val body = JSONObject(requestBody.get() ?: error("request body missing"))
            val tools = body.getJSONArray("tools")
            assertEquals(2, tools.length())
            assertEquals("openrouter:web_search", tools.getJSONObject(0).getString("type"))
            assertEquals("function", tools.getJSONObject(1).getString("type"))
            assertEquals("done", result.text)
            assertEquals(2, requestCount.get())
            assertEquals(1, localExecutions.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun streamingTextAccumulatesMultipleSseDeltas() {
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse(
                        """{"id":"gen-1","model":"test-model","choices":[{"delta":{"role":"assistant","content":"Hello ","reasoning_details":[{"type":"reasoning.text","text":"summary"}]},"finish_reason":null}]}""",
                        """{"choices":[{"delta":{"content":"world"},"finish_reason":null}]}""",
                        """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                        """{"choices":[],"usage":{"total_tokens":3}}""",
                        "[DONE]"
                    )
                )
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val result = client.complete(
                apiKey = "test",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                tools = null,
                maxTokens = 128,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, _ -> error("tool should not run") }
            )
            assertEquals("Hello world", result.text)
            assertEquals("test-model", result.responseModel)
            assertEquals(3L, result.usage?.getLong("total_tokens"))
            assertEquals("assistant", result.assistantMessage.getString("role"))
            assertEquals(1, result.assistantMessage.getJSONArray("reasoning_details").length())
        } finally {
            client.close()
        }
    }

    @Test
    fun streamingErrorPreservesPartialText() {
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"partial answer"},"finish_reason":null}]}""",
                        """{"error":{"message":"upstream failed"}}"""
                    )
                )
            }
            .build()

        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val failure = try {
                client.complete(
                    apiKey = "test",
                    model = "test-model",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                    tools = null,
                    maxTokens = 128,
                    reasoningEffort = null,
                    verbosity = null,
                    parallelToolCalls = false,
                    executeTool = { _, _ -> error("tool should not run") }
                )
                null
            } catch (e: OpenRouterCompletionException) {
                e
            }
            assertNotNull(failure)
            assertEquals("partial answer", failure?.partialText)
            assertTrue(failure?.message?.contains("upstream failed") == true)
        } finally {
            client.close()
        }
    }

    @Test
    fun unexpectedEofAfterStopReasonIsNotACompletedResponse() {
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"answer cut short"},"finish_reason":"stop"}]}"""
                    )
                )
            }
            .build()
        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val failure = try {
                client.complete(
                    apiKey = "test",
                    model = "test-model",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                    tools = null,
                    maxTokens = null,
                    reasoningEffort = null,
                    verbosity = null,
                    parallelToolCalls = false,
                    executeTool = { _, _ -> error("tool should not run") }
                )
                null
            } catch (e: OpenRouterCompletionException) {
                e
            }
            assertNotNull(failure)
            assertEquals("answer cut short", failure?.partialText)
            assertTrue(failure?.message?.contains("[DONE]") == true)
        } finally {
            client.close()
        }
    }

    @Test
    fun unexpectedEofWithToolCallMustNotExecuteLocalTool() {
        val localExecutions = AtomicInteger(0)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}"""
                    )
                )
            }
            .build()
        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val failure = try {
                client.complete(
                    apiKey = "test",
                    model = "test-model",
                    messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                    tools = JSONArray().put(
                        JSONObject().put("type", "function")
                            .put("function", JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
                    ),
                    maxTokens = null,
                    reasoningEffort = null,
                    verbosity = null,
                    parallelToolCalls = false,
                    executeTool = { _, _ ->
                        localExecutions.incrementAndGet()
                        "not allowed"
                    }
                )
                null
            } catch (e: IOException) {
                e
            }
            assertNotNull(failure)
            assertTrue(failure?.message?.contains("[DONE]") == true)
            assertEquals(0, localExecutions.get())
        } finally {
            client.close()
        }
    }

    @Test
    fun finalDoneMarkerWithoutTrailingBlankLineIsAccepted() {
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                sseResponse(
                    chain,
                    sse("""{"choices":[{"delta":{"content":"complete"},"finish_reason":"stop"}]}""") +
                        "data: [DONE]"
                )
            }
            .build()
        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val result = client.complete(
                apiKey = "test",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                tools = null,
                maxTokens = null,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, _ -> error("tool should not run") }
            )
            assertEquals("complete", result.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun toolLoopDoesNotStopAtTwentyFourRounds() {
        val requestCount = AtomicInteger(0)
        val transport = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val requestNumber = requestCount.incrementAndGet()
                val body = if (requestNumber <= 25) {
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-$requestNumber","type":"function","function":{"name":"test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                        """{"choices":[],"usage":{"total_tokens":1}}""",
                        "[DONE]"
                    )
                } else {
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"done"},"finish_reason":"stop"}]}""",
                        """{"choices":[],"usage":{"total_tokens":1}}""",
                        "[DONE]"
                    )
                }
                sseResponse(chain, body)
            }
            .build()

        val toolCalls = AtomicInteger(0)
        val client = OpenRouterChatClient("http://test.invalid", transport)
        try {
            val result = client.complete(
                apiKey = "test",
                model = "test-model",
                messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
                tools = JSONArray().put(
                    JSONObject()
                        .put("type", "function")
                        .put("function", JSONObject().put("name", "test_tool").put("parameters", JSONObject()))
                ),
                maxTokens = 128,
                reasoningEffort = null,
                verbosity = null,
                parallelToolCalls = false,
                executeTool = { _, _ ->
                    toolCalls.incrementAndGet()
                    "tool-result"
                }
            )

            assertEquals("done", result.text)
            assertEquals(26, requestCount.get())
            assertEquals(25, toolCalls.get())
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
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
                        """{"choices":[],"usage":{"total_tokens":1}}""",
                        "[DONE]"
                    )
                } else {
                    sse(
                        """{"choices":[{"delta":{"role":"assistant","content":"unexpected second round"},"finish_reason":"stop"}]}""",
                        "[DONE]"
                    )
                }
                sseResponse(chain, body)
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

    private fun sse(vararg data: String): String =
        data.joinToString("\n\ndata: ", prefix = "data: ", postfix = "\n\n")

    private fun sseResponse(chain: okhttp3.Interceptor.Chain, body: String): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("text/event-stream".toMediaType()))
            .build()
}
