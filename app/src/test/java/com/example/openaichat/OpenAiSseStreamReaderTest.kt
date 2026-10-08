package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader

class OpenAiSseStreamReaderTest {
    private fun frames(vararg events: String) =
        events.joinToString("") { "data: $it\n\n" }

    private fun parse(source: String): Pair<OpenAiSseReadResult, ResponseEventState> {
        var state = ResponseEventState()
        val result = OpenAiSseStreamReader.read(BufferedReader(StringReader(source))) {
            state = ResponseEventReducer.reduce(state, it).state
            true
        }
        return result to state
    }

    @Test fun createdAndPartialThenEofIsDisconnectedNotCompleted() {
        val (result, state) = parse(frames(
            """{"type":"response.created","response":{"id":"resp_partial"}}""",
            """{"type":"response.output_text.delta","delta":"partial"}"""
        ))
        assertEquals(OpenAiStreamEnd.DISCONNECTED, result.end)
        assertEquals("partial", state.text)
        assertEquals("resp_partial", state.responseId)
        assertEquals(null, state.terminalState)
    }

    @Test fun doneWithoutTerminalIsDisconnected() {
        val (result, state) = parse(frames(
            """{"type":"response.created","response":{"id":"resp_1"}}""", "[DONE]"
        ))
        assertEquals(OpenAiStreamEnd.DISCONNECTED, result.end)
        assertEquals(null, state.terminalState)
    }

    @Test fun semanticSseErrorAfterPartialIsFailureNotSwallowed() {
        val (result, state) = parse(frames(
            """{"type":"response.output_text.delta","delta":"kept"}""",
            """{"type":"error","error":{"message":"upstream denied"}}"""
        ))
        assertEquals(OpenAiStreamEnd.FAILED, result.end)
        assertEquals("upstream denied", result.errorMessage)
        assertEquals(ResponseTerminalState.FAILED, state.terminalState)
        assertEquals("kept", state.text)
    }

    @Test fun distinctProviderTerminalsPreserveUsageAndPartialText() {
        for ((eventType, expected) in listOf(
            "response.completed" to OpenAiStreamEnd.COMPLETED,
            "response.incomplete" to OpenAiStreamEnd.INCOMPLETE,
            "response.failed" to OpenAiStreamEnd.FAILED,
            "response.cancelled" to OpenAiStreamEnd.CANCELLED
        )) {
            val (result, state) = parse(frames(
                """{"type":"response.output_text.delta","delta":"partial"}""",
                """{"type":"$eventType","response":{"id":"resp","usage":{"output_tokens":7}}}"""
            ))
            assertEquals(eventType, expected, result.end)
            assertEquals("partial", state.text)
            assertEquals(7L, state.usage?.getLong("output_tokens"))
        }
    }

    @Test fun terminalOutputWithoutDeltasIsNotLost() {
        val (result, state) = parse(frames(
            """{"type":"response.completed","response":{"id":"resp","output":[{"type":"message","content":[{"type":"output_text","text":"terminal text"}]}]}}"""
        ))
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertTrue(state.text.isEmpty())
        assertEquals("terminal text", OpenAiSseStreamReader.terminalOutputText(state.terminalResponse))
    }

    @Test fun terminalReturnsWithoutReadingAnotherLineFromOpenStream() {
        val lines = listOf(
            """data: {"type":"response.completed","response":{"id":"resp"}}""",
            ""
        )
        val reader = object : BufferedReader(StringReader("")) {
            var reads = 0
            override fun readLine(): String? {
                if (reads >= lines.size) error("Reader must not wait for EOF after terminal")
                return lines[reads++]
            }
        }
        val result = OpenAiSseStreamReader.read(reader) { true }
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertEquals(2, reader.reads)
    }

    @Test fun malformedJsonIsNotSilentlyIgnored() {
        try {
            parse(frames("""{"type":"""))
            throw AssertionError("Expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Malformed"))
        }
    }

    @Test fun mockWebServerEofAndDoneDoNotBecomeSuccess() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody(frames(
                """{"type":"response.created","response":{"id":"resp"}}""",
                """{"type":"response.output_text.delta","delta":"saved"}"""
            )))
            server.enqueue(MockResponse().setBody(frames("[DONE]")))
            val client = OkHttpClient()
            repeat(2) { index ->
                val response = client.newCall(Request.Builder().url(server.url("/v1/responses")).build()).execute()
                response.use {
                    val result = OpenAiSseStreamReader.read(
                        it.body!!.byteStream().bufferedReader(Charsets.UTF_8)
                    ) { true }
                    assertEquals("case $index", OpenAiStreamEnd.DISCONNECTED, result.end)
                }
            }
            assertEquals(2, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun ignoredDuplicateTerminalDoesNotEndTheStream() {
        val cursor = ResponseStreamSequenceCursor()
        val accepted = mutableListOf<String>()
        val result = OpenAiSseStreamReader.read(BufferedReader(StringReader(frames(
            """{"type":"response.created","sequence_number":1,"response":{"id":"resp"}}""",
            """{"type":"response.completed","sequence_number":1,"response":{"id":"wrong"}}""",
            """{"type":"response.completed","sequence_number":2,"response":{"id":"resp"}}"""
        )))) { event ->
            val sequence = event.optLong("sequence_number", -1)
            val observed = cursor.observe(sequence)
            if (observed == ResponseStreamSequenceObservation.DUPLICATE) {
                false
            } else {
                accepted += event.optString("type")
                true
            }
        }
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertEquals(listOf("response.created", "response.completed"), accepted)
        assertEquals(2L, cursor.lastSequenceNumber)
    }

    @Test fun repeatedTerminalAndTrailingEventsAreNotConsumed() {
        var callbacks = 0
        val (result, _) = parse(frames(
            """{"type":"response.completed","response":{"id":"first"}}""",
            """{"type":"response.failed","response":{"id":"second"}}"""
        ))
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        OpenAiSseStreamReader.read(BufferedReader(StringReader(frames(
            """{"type":"response.completed","response":{"id":"first"}}""",
            """{"type":"response.failed","response":{"id":"second"}}"""
        )))) { callbacks++; true }
        assertEquals(1, callbacks)
    }
}
