package com.example.openaichat

import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class StreamingJsonRequestBodyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

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
