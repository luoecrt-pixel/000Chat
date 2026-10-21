package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalEvidenceVerifierTest {
    private fun read(start: Long, end: Long, total: Long? = 100) = JSONObject().put("file_id", "f")
        .put("offset", start).put("next_offset", end).put("eof", end == total).put("ok", true).apply {
            total?.let { put("total_chars", it) }
        }
    private fun trace(vararg outputs: JSONObject): JSONObject = JSONObject().put("tool_trace", JSONArray().apply {
        outputs.forEach { put(ToolTraceSummary.entry("file_read_range", JSONObject().put("file_id", "f"), it.toString())) }
    })
    @Test fun overlappingReadsCountOnceAndEofAtNonzeroOffsetDoesNotProveFullRead() {
        val partial = LocalEvidenceVerifier.coverage(trace(read(50, 100))).single()
        assertEquals(50, partial.percent); assertTrue(partial.partial)
        val full = LocalEvidenceVerifier.coverage(trace(read(0, 60), read(40, 100))).single()
        assertEquals(100, full.percent); assertFalse(full.partial)
        val legacy = LocalEvidenceVerifier.coverage(trace(read(0, 20, null))).single()
        assertNull(legacy.percent); assertTrue(legacy.partial)
    }
    @Test fun openAiJoinsCallsAndResultsAndIgnoresFailedOrAmbiguousCalls() {
        fun call(id: String) = JSONObject().put("type", "function_call").put("call_id", id)
            .put("name", "read_attached_file").put("arguments", "{\"file_id\":\"f\"}")
        fun result(id: String) = JSONObject().put("type", "function_call_output").put("call_id", id).put("output", read(0, 20).toString())
        val output = JSONArray().put(call("a")).put(result("a"))
        assertEquals(20, LocalEvidenceVerifier.coverage(JSONObject().put("openai_protocol_output", output)).single().percent)
        output.put(call("a"))
        assertTrue(LocalEvidenceVerifier.coverage(JSONObject().put("openai_protocol_output", output)).isEmpty())
    }
    @Test fun exactQuotesCrossBufferBoundaryKeepUnicodeOffsetsAndUnavailableNeverMeansNotFound() {
        val file = Files.createTempFile("evidence", ".txt").toFile()
        try {
            val prefix = "я".repeat(8190)
            file.writeText(prefix + "😀 точная цитата", Charsets.UTF_8)
            val source = OpenRouterLocalAttachment("f", "текст.txt", "text", file.path, "text/plain", file.length(), "", textEncoding = "UTF-8")
            val match = LocalEvidenceVerifier.quotes("😀 точная", listOf(source)).matches.single()
            assertEquals(prefix.length.toLong(), match.offset)
            assertEquals("Не найдено в исходном файле", LocalEvidenceVerifier.quotes("ТОЧНАЯ", listOf(source)).label)
            assertTrue(LocalEvidenceVerifier.quotes("test", listOf(source.copy(path = file.path + "missing"))).incomplete)
            assertFalse(LocalEvidenceVerifier.quotes("точная", listOf(source)).incomplete)
        } finally { file.delete() }
    }
    @Test fun traceEvidenceContainsNoFileContentAndSubsetStatisticsDoNotSuppressWarning() {
        val args = JSONObject().put("file_id", "f").put("end_row", 10)
        val result = JSONObject().put("ok", true).put("file_id", "f").put("complete_requested_range", true).put("content", "SECRET")
        val stat = ToolTraceSummary.entry("file_table_stats", args, result.toString())
        assertFalse(stat.toString().contains("SECRET"))
        val message = trace(read(0, 20)); message.getJSONArray("tool_trace").put(stat)
        assertTrue(LocalEvidenceVerifier.coverage(message).single().partial)
    }
}
