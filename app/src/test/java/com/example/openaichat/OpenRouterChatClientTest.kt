package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class OpenRouterChatClientTest {
    @Test
    fun toolLoopStopsBeforeStartingNextRoundAfterOwnershipLoss() {
        val server = ServerSocket(0)
        val requestCount = AtomicInteger(0)
        val serverDone = CountDownLatch(1)
        val serverThread = Thread {
            try {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketException) {
                        break
                    }
                    socket.use { clientSocket ->
                        BufferedReader(InputStreamReader(clientSocket.getInputStream(), StandardCharsets.US_ASCII)).use { reader ->
                            while (reader.readLine().orEmpty().isNotEmpty()) Unit
                        }
                        requestCount.incrementAndGet()
                        val body = if (requestCount.get() == 1) {
                            """{"choices":[{"message":{"content":"","tool_calls":[{"id":"call-1","type":"function","function":{"name":"test_tool","arguments":"{}"}}]}}],"usage":{"total_tokens":1}}"""
                        } else {
                            """{"choices":[{"message":{"content":"unexpected second round","tool_calls":[]}}]}"""
                        }
                        val bytes = body.toByteArray(StandardCharsets.UTF_8)
                        val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                            bytes.size + "\r\nConnection: close\r\n\r\n"
                        clientSocket.getOutputStream().use { out ->
                            out.write(response.toByteArray(StandardCharsets.US_ASCII))
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            } finally {
                serverDone.countDown()
            }
        }.also { it.start() }

        val toolStarted = CountDownLatch(1)
        val releaseTool = CountDownLatch(1)
        val shouldContinue = AtomicBoolean(true)
        val failure = AtomicReference<Throwable?>(null)
        val client = OpenRouterChatClient("http://127.0.0.1:" + server.localPort)

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
                            releaseTool.await(2, TimeUnit.SECONDS)
                            "tool-result"
                        },
                        shouldContinue = { shouldContinue.get() }
                    )
                } catch (t: Throwable) {
                    failure.set(t)
                }
            }
            worker.start()

            assertTrue("first tool call never started", toolStarted.await(2, TimeUnit.SECONDS))
            shouldContinue.set(false)
            releaseTool.countDown()
            worker.join(3000L)

            assertFalse("OpenRouter completion thread did not finish", worker.isAlive)
            assertTrue("expected ownership cancellation", failure.get() is CancellationException)
            assertEquals("old tool loop started a second HTTP round", 1, requestCount.get())
        } finally {
            client.close()
            server.close()
            serverDone.await(2, TimeUnit.SECONDS)
        }
    }
}