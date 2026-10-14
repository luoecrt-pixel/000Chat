package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenRouterBoundedDecoderTest {
    @Test fun actualWirePreservesUnicodeMultilineFramesAndUsage() {
        withServer { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
                ": heartbeat\r\n\r\ndata: {\r\ndata: \"choices\":[{\"delta\":{\"content\":\"привет 😀\"},\"finish_reason\":\"stop\"}]}\r\n\r\n" +
                    "data: {\"usage\":{\"total_tokens\":7}}\n\ndata: [DONE]\n\n"
            ))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), sseLimits = limits())
            try {
                val result = complete(client)
                assertEquals("привет 😀", result.text)
                assertEquals(7, result.usage!!.getInt("total_tokens"))
                assertEquals("stop", result.finishReason)
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun actualWireRejectsLineEventStreamAndBytesWithoutToolExecutionOrRetry() {
        val cases = listOf(
            Triple("data: " + "x".repeat(60) + "\n\n", limits(line = 32), "line limit"),
            Triple("data: " + "x".repeat(15) + "\ndata: " + "y".repeat(15) + "\n\n",
                limits(line = 32, event = 40), "event limit"),
            Triple(": heartbeat\n\n".repeat(8), limits(line = 32, event = 40, stream = 80), "stream limit"),
            Triple("data: " + "x".repeat(60) + "\n\n", limits(bytes = 20), "byte limit")
        )
        for ((source, limit, expected) in cases) withServer { server ->
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(source))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), sseLimits = limit)
            try {
                val failure = assertThrows(ResponsesSseLimitException::class.java) { complete(client) }
                assertTrue(failure.message.orEmpty(), failure.message.orEmpty().contains(expected))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun decoderFailureRetainsEarlierPartialTextAndNeverPretendsSuccess() {
        withServer { server ->
            server.enqueue(MockResponse().setBody(
                "data: {\"choices\":[{\"delta\":{\"content\":\"paid partial\"}}]}\n\n" +
                    "data: " + "x".repeat(300) + "\n\ndata: [DONE]\n\n"
            ))
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), sseLimits = limits(line = 128))
            try {
                val failure = assertThrows(OpenRouterCompletionException::class.java) { complete(client) }
                assertEquals("paid partial", failure.partialText)
                assertTrue(failure.cause is ResponsesSseLimitException)
                assertTrue(failure.message.orEmpty().contains("line limit"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun malformedUtf8DoesNotSilentlyChangeModelText() {
        withServer { server ->
            server.enqueue(MockResponse().setBody(Buffer().writeUtf8("data: ")
                .write(byteArrayOf(0xC3.toByte(), '('.code.toByte())).writeUtf8("\n\n")))
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                assertThrows(java.nio.charset.MalformedInputException::class.java) { complete(client) }
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun oversizedHttpErrorPreservesStatusAndLimitDiagnostic() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("x".repeat(1024 * 1024 + 1)))
            val client = OpenRouterChatClient(server.url("/api/v1").toString())
            try {
                val failure = assertThrows(IOException::class.java) { complete(client) }
                assertTrue(failure.message.orEmpty().contains("HTTP 401"))
                assertTrue(failure.message.orEmpty().contains("byte limit"))
                assertFalse(failure.message.orEmpty().contains("synthetic-key"))
                val request = server.takeRequest(2, TimeUnit.SECONDS) ?: error("Missing actual HTTP request")
                assertEquals("Bearer synthetic-key", request.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }

    private fun complete(client: OpenRouterChatClient) = client.complete(
        apiKey = "synthetic-key", model = "anthropic/claude-sonnet-5.5",
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hello")),
        tools = null, maxTokens = null, reasoningEffort = null, verbosity = null,
        parallelToolCalls = false, executeTool = { _, _ -> error("Decoder failure must never execute tools") }
    )

    private fun limits(line: Int = 512, event: Int = 1024, stream: Long = 4096, bytes: Long = 8192) =
        BoundedResponseDecoder.SseLimits(maxLineChars = line, maxEventChars = event,
            maxStreamChars = stream, maxBodyBytes = bytes)

    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
