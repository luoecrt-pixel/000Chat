package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.TimeUnit

class StreamingJsonRequestBodyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun paidJsonPostDoesNotFollow408Or503RetryAfterZero() {
        for (code in listOf(408, 503)) {
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setBody("unexpected second paid POST"))
                server.start()
                val client = OkHttpClient()
                try {
                    val body = StreamingJsonRequestBody(JSONObject().put("input", "paid"), oneShot = true)
                    client.newCall(Request.Builder().url(server.url("/v1/responses")).post(body).build())
                        .execute().use { response -> assertEquals(code, response.code) }
                    assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
                    assertNull("HTTP $code must not trigger a hidden paid retry", server.takeRequest(150, TimeUnit.MILLISECONDS))
                    assertEquals(1, server.requestCount)
                } finally {
                    client.dispatcher.executorService.shutdown()
                    client.connectionPool.evictAll()
                }
            }
        }
    }

    @Test fun paidJsonPostDoesNotReplayAfterAmbiguousDisconnect() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("unexpected second paid POST"))
            server.start()
            val client = OkHttpClient.Builder().callTimeout(3, TimeUnit.SECONDS).build()
            try {
                val request = Request.Builder().url(server.url("/v1/responses"))
                    .post(StreamingJsonRequestBody(JSONObject().put("input", "paid"), oneShot = true)).build()
                assertThrows(IOException::class.java) { client.newCall(request).execute().close() }
                assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
                assertNull(server.takeRequest(150, TimeUnit.MILLISECONDS))
                assertEquals(1, server.requestCount)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun safeGetRemainsRepeatableAfter503RetryAfterZero() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0"))
            server.enqueue(MockResponse().setBody("completed"))
            server.start()
            val client = OkHttpClient()
            try {
                client.newCall(Request.Builder().url(server.url("/v1/responses/resp_known")).get().build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        assertEquals("completed", response.body!!.string())
                    }
                repeat(2) { assertEquals("GET", server.takeRequest(1, TimeUnit.SECONDS)!!.method) }
                assertEquals(2, server.requestCount)
            } finally {
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }

    @Test fun contentLengthFreezesNestedJsonAndUtf8ByteSequence() {
        val text = "Ключ 😀 \"\\\n\t\u0000\uD800"
        val nested = JSONObject().put("text", text).put("enabled", false)
        val items = JSONArray().put(nested).put(JSONObject.NULL).put(42)
        val value = JSONObject().put("messages", items)
        val body = StreamingJsonRequestBody(value)
        val length = body.contentLength()

        nested.put("text", "changed").put("enabled", true)
        items.put("another item")
        value.put("added", "after measuring")

        val first = Buffer().also { body.writeTo(it) }
        assertEquals(length, first.size)
        val bytes = first.readByteArray()
        val sent = JSONObject(bytes.toString(Charsets.UTF_8))
        assertEquals(1, sent.length())
        assertEquals(3, sent.getJSONArray("messages").length())
        val sentNested = sent.getJSONArray("messages").getJSONObject(0)
        assertEquals(text.dropLast(1) + "?", sentNested.getString("text"))
        assertFalse(sentNested.getBoolean("enabled"))
        assertArrayEquals(bytes, Buffer().also { body.writeTo(it) }.readByteArray())
        assertEquals(length, body.contentLength())
    }

    @Test fun writeBeforeContentLengthRetainsSameSnapshot() {
        val value = JSONObject().put("content", "first 😀")
        val body = StreamingJsonRequestBody(value)
        val bytes = Buffer().also { body.writeTo(it) }.readByteArray()

        value.put("content", "replacement with a different length")

        assertEquals(bytes.size.toLong(), body.contentLength())
        assertEquals("first 😀", JSONObject(bytes.toString(Charsets.UTF_8)).getString("content"))
        assertArrayEquals(bytes, Buffer().also { body.writeTo(it) }.readByteArray())
    }

    @Test fun largeInlineJsonRemainsCompleteAndStableAfterMutation() {
        val text = "a".repeat(256 * 1024) + "\nКонец 😀"
        val value = JSONObject().put("content", text)
        val body = StreamingJsonRequestBody(value)
        val length = body.contentLength()
        assertTrue(length > 256 * 1024L)

        value.put("content", "short")

        val sink = Buffer().also { body.writeTo(it) }
        assertEquals(length, sink.size)
        assertEquals(text, JSONObject(sink.readUtf8()).getString("content"))
    }

    @Test fun nestedFileBackedTextRemainsStreamingAndRejectsChangedSource() {
        val file = temporaryFolder.newFile().apply { writeText("first") }
        val value = JSONObject().put("messages", JSONArray().put(JSONObject().put("text", FileBackedText(file))))
        val body = StreamingJsonRequestBody(value)
        val length = body.contentLength()
        val sink = Buffer().also { body.writeTo(it) }
        assertEquals(length, sink.size)
        assertEquals("first", JSONObject(sink.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("text"))

        file.writeText("other")

        assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
    }

    @Test fun nestedFileBackedDataUrlRemainsStreamingAndRejectsChangedSource() {
        val file = temporaryFolder.newFile().apply { writeText("first") }
        val value = JSONObject().put("messages", JSONArray().put(JSONObject().put("data", FileBackedDataUrl(file, "text/plain"))))
        val body = StreamingJsonRequestBody(value)
        val length = body.contentLength()
        val sink = Buffer().also { body.writeTo(it) }
        assertEquals(length, sink.size)
        assertEquals("data:text/plain;base64,Zmlyc3Q=", JSONObject(sink.readUtf8()).getJSONArray("messages").getJSONObject(0).getString("data"))

        file.writeText("other")

        assertThrows(IOException::class.java) { body.writeTo(Buffer()) }
    }
}
