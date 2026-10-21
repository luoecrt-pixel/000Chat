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
    @Test fun legacyFinalEofProvidesUtf16DenominatorAndMissingEvidenceRemainsUnknown() {
        val finalPage = read(0, 20, null).put("eof", true)
        val full = LocalEvidenceVerifier.coverage(trace(finalPage)).single()
        assertEquals(20L, full.totalChars); assertEquals(100, full.percent)
        assertFalse(full.partial); assertFalse(full.unknown)
        val tail = LocalEvidenceVerifier.coverage(trace(read(10, 20, null).put("eof", true))).single()
        assertEquals(50, tail.percent); assertTrue(tail.partial)
        val unknown = LocalEvidenceVerifier.coverage(trace(read(0, 20, null).apply { remove("eof") })).single()
        assertNull(unknown.percent); assertFalse(unknown.partial); assertTrue(unknown.unknown)
        val conflicting = LocalEvidenceVerifier.coverage(trace(read(0, 20, 100).put("eof", true))).single()
        assertNull(conflicting.percent); assertFalse(conflicting.partial); assertTrue(conflicting.unknown)
    }

    @Test fun duplicateOutputsAndBlankCallIdsNeverBecomeCoverageEvidence() {
        fun call(id: String) = JSONObject().put("type", "function_call").put("call_id", id)
            .put("name", "read_attached_file").put("arguments", "{\"file_id\":\"f\"}")
        fun output(id: String) = JSONObject().put("type", "function_call_output").put("call_id", id)
            .put("output", read(0, 20).toString())
        val duplicated = JSONArray().put(call("a")).put(output("a")).put(output("a"))
        assertTrue(LocalEvidenceVerifier.coverage(JSONObject().put("openai_protocol_output", duplicated)).isEmpty())
        val blank = JSONArray().put(call("")).put(output(""))
        assertTrue(LocalEvidenceVerifier.coverage(JSONObject().put("openai_protocol_output", blank)).isEmpty())
        val failed = read(0, 20).put("ok", false)
        assertTrue(LocalEvidenceVerifier.coverage(trace(failed)).isEmpty())
    }

    @Test fun defaultXlsxStatisticsResolveOneSheetAndCannotProveWholeWorkbookCoverage() {
        val extracted = Files.createTempFile("evidence-workbook", ".utf8").toFile()
        try {
            extracted.writeText(listOf(
                JSONObject().put("sheet", "First").put("record", "sheet"),
                JSONObject().put("sheet", "First").put("row", 1).put("cells", JSONArray().put(JSONObject().put("ref", "A1").put("value", "3"))),
                JSONObject().put("sheet", "Second").put("record", "sheet"),
                JSONObject().put("sheet", "Second").put("row", 1).put("cells", JSONArray().put(JSONObject().put("ref", "A1").put("value", "9")))
            ).joinToString("\n"))
            val source = OpenRouterLocalAttachment("f", "workbook.xlsx", "table", extracted.path,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", extracted.length(), "", textPath = extracted.path)
            val args = JSONObject().put("file_id", "f").put("column", 1)
            val result = JSONObject(LocalFileTools(listOf(source)).execute("file_table_stats", args))
            assertTrue(result.getBoolean("ok")); assertEquals("First", result.getString("sheet"))
            val entry = ToolTraceSummary.entry("file_table_stats", args, result.toString())
            assertEquals("First", entry.getJSONObject("evidence").getString("sheet"))
            assertEquals("all_rows", entry.getJSONObject("evidence").getString("row_scope"))
            val partial = trace(read(0, 20)); partial.getJSONArray("tool_trace").put(entry)
            assertTrue(LocalEvidenceVerifier.coverage(partial).single().partial)
            // A CSV full-row scan has no sheet scope and can suppress the partial-read warning.
            result.put("sheet", JSONObject.NULL)
            partial.getJSONArray("tool_trace").put(ToolTraceSummary.entry("file_table_stats", args, result.toString()))
            assertFalse(LocalEvidenceVerifier.coverage(partial).single().partial)
        } finally { extracted.delete() }
    }

    @Test fun matchingPrefixWithInvalidTextLaterIsUnknownInsteadOfConfirmed() {
        val file = Files.createTempFile("evidence-invalid-tail", ".txt").toFile()
        try {
            val prefix = "точная цитата" + "x".repeat(20000)
            val source = OpenRouterLocalAttachment("f", "legacy.txt", "text", file.path, "text/plain", 0, "", textEncoding = "UTF-8")
            file.writeBytes(prefix.toByteArray(Charsets.UTF_8) + byteArrayOf(0xc3.toByte()))
            val malformed = LocalEvidenceVerifier.quotes("точная цитата", listOf(source))
            assertTrue(malformed.matches.isEmpty()); assertTrue(malformed.incomplete)
            file.writeText(prefix + "\u0000", Charsets.UTF_8)
            val binary = LocalEvidenceVerifier.quotes("точная цитата", listOf(source))
            assertTrue(binary.matches.isEmpty()); assertTrue(binary.incomplete)
            file.writeText(prefix + "\n", Charsets.UTF_8)
            val complete = LocalEvidenceVerifier.quotes("точная цитата", listOf(source))
            assertEquals(0L, complete.matches.single().offset); assertFalse(complete.incomplete)
        } finally { file.delete() }
    }

    @Test fun quoteAndNavigationRejectUnpinnedOrModifiedDerivedTextWhileOriginalStaysValid() {
        val original = Files.createTempFile("evidence-source", ".bin").toFile()
        val derived = Files.createTempFile("evidence-text", ".utf8").toFile()
        try {
            original.writeBytes(byteArrayOf(1, 2, 3, 4))
            derived.writeText("trusted citation", Charsets.UTF_8)
            val attachment = OpenRouterLocalAttachment("f", "document.docx", "document", original.path,
                "application/octet-stream", original.length(), OpenRouterAttachmentStore.sha256(original),
                textPath = derived.path, textEncoding = "UTF-8", textSha256 = OpenRouterAttachmentStore.sha256(derived))
            assertEquals(0L, LocalEvidenceVerifier.quotes("trusted", listOf(attachment)).matches.single().offset)
            assertEquals(derived, LocalEvidenceVerifier.verifiedTextFile(attachment))
            assertTrue(LocalEvidenceVerifier.quotes("trusted", listOf(attachment.copy(textSha256 = null))).incomplete)
            val modified = derived.lastModified()
            derived.writeText("changed citation", Charsets.UTF_8); derived.setLastModified(modified)
            val result = LocalEvidenceVerifier.quotes("changed", listOf(attachment))
            assertTrue(result.incomplete); assertTrue(result.matches.isEmpty())
            assertThrows(java.io.IOException::class.java) { LocalEvidenceVerifier.verifiedTextFile(attachment) }
            assertEquals(attachment.sha256, OpenRouterAttachmentStore.sha256(original))
        } finally { original.delete(); derived.delete() }
    }

}
