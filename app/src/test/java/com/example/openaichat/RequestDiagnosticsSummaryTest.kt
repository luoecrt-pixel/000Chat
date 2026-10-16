package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestDiagnosticsSummaryTest {
    @Test fun formatsExactObservedCountersAndSeparatesUnknownFromConfirmedZero() {
        val telemetry = JSONObject().put("rounds_completed", 3).put("calls_executed", 4)
            .put("executed_tools", JSONArray().put("read_attached_file").put("search_attached_file"))
            .put("files", 2).put("source_read_passes", 7).put("source_read_bytes", 4097).put("file_work_ms", 12)
            .put("cost_details", JSONObject().put("server_tool_cost", 0.0025).put("service_tier", "priority"))
        val unknown = event().put("telemetry_summary", telemetry.toString())
        val zero = event().put("cost_kind", "provider_reported").put("cost_usd", "0.0")
        val text = RequestDiagnosticsSummary.format(listOf(unknown, zero))
        assertTrue(text.contains("status=completed"))
        assertTrue(text.contains("раундов=3, вызовов=4 (read_attached_file, search_attached_file)"))
        assertTrue(text.contains("files=2, passes=7, bytes=4097, work_ms=12"))
        assertTrue(text.contains("Расход неизвестен"))
        assertTrue(text.contains("Расход $0.0000"))
        assertTrue(text.contains("server_tool_cost"))
        assertTrue(text.contains("priority"))
        assertFalse(text.contains("эконом"))
        assertFalse(text.contains("estimated_cost"))
    }

    @Test fun projectionNeverDisplaysRawArgumentsFilesLedgerOrMalformedModelMetadata() {
        val apiKey = "sk-proj-SyntheticDiagnosticSecret123456"
        val rawContent = "PRIVATE_FILE_CONTENT"
        val path = "/data/user/0/private/document.txt"
        val telemetry = JSONObject().put("rounds_completed", 2).put("calls_executed", 2)
            .put("executed_tools", JSONArray().put("read_attached_file").put(apiKey).put(path).put("tool\n$rawContent"))
            .put("arguments", JSONObject().put("content", rawContent).put("path", path))
            .put("openai_round_usages", JSONObject().put(apiKey, JSONObject().put("content", rawContent)))
            .put("cost_details", JSONObject().put("upstream_inference_cost", 0.01).put("service_tier", apiKey)
                .put("arguments", rawContent).put("path", path)
                .put("server_tool_use", JSONObject().put(path, 1).put("web_search_requests", 2))
                .put("iterations", JSONArray().put(JSONObject().put("model", apiKey).put("type", rawContent)
                    .put("path", path).put("input_tokens", 250))))
        val source = event().put("requested_model", "$apiKey $path $rawContent").put("error", rawContent)
            .put("tool_summary", path).put("telemetry_summary", telemetry)
        val text = RequestDiagnosticsSummary.format(listOf(source))
        assertFalse(text.contains(apiKey))
        assertFalse(text.contains(rawContent))
        assertFalse(text.contains(path))
        assertFalse(text.contains("openai_round_usages"))
        assertFalse(text.contains("arguments"))
        assertTrue(text.contains("[REDACTED]"))
        assertTrue(text.contains("[некорректное имя]"))
        assertTrue(text.contains("web_search_requests"))
        assertTrue(text.contains("250"))
    }

    @Test fun viewIsBoundedAndDoesNotMutateStoredTelemetry() {
        val names = JSONArray().apply { repeat(40) { put("tool_$it") } }
        val iterations = JSONArray().apply { repeat(40) {
            put(JSONObject().put("input_tokens", Long.MAX_VALUE).put("output_tokens", Long.MAX_VALUE)
                .put("total_tokens", Long.MAX_VALUE).put("prompt_tokens", Long.MAX_VALUE)
                .put("completion_tokens", Long.MAX_VALUE).put("cost", 123456789.123456)
                .put("server_tool_cost", 123456789.123456).put("upstream_inference_cost", 123456789.123456))
        } }
        val telemetry = JSONObject().put("rounds_completed", 40).put("calls_executed", 40)
            .put("executed_tools", names).put("cost_details", JSONObject().put("iterations", iterations))
        val before = telemetry.toString()
        val text = RequestDiagnosticsSummary.format(List(12) { event().put("telemetry_summary", telemetry) })
        assertEquals(10, Regex("● Запрос ").findAll(text).count())
        assertTrue(text.contains("tool_31"))
        assertFalse(text.contains("tool_32"))
        assertTrue(text.contains("список сокращён"))
        assertTrue(text.contains("показ сокращён; данные сохранены"))
        assertTrue(text.contains("только последние 10 запросов"))
        assertEquals(before, telemetry.toString())
        assertTrue(text.length < 60_000)
    }

    @Test fun missingAndMalformedTelemetryDoNotInventZeroCountersOrCost() {
        assertEquals("", RequestDiagnosticsSummary.format(emptyList()))
        val missing = RequestDiagnosticsSummary.format(listOf(event().put("telemetry_summary", "invalid JSON")))
        assertFalse(missing.contains("Файловый I/O"))
        assertFalse(missing.contains("Инструменты"))
        val malformed = event().put("status", "sk-test-SecretStatus1234567")
            .put("telemetry_summary", JSONObject().put("files", -10).put("source_read_bytes", "not a number"))
        val text = RequestDiagnosticsSummary.format(listOf(malformed))
        assertTrue(text.contains("status=unknown"))
        assertTrue(text.contains("files=неизвестно"))
        assertTrue(text.contains("bytes=неизвестно"))
        assertFalse(text.contains("SecretStatus"))
        assertFalse(text.contains("Расход $0.0000"))
    }

    private fun event(): JSONObject = JSONObject().put("status", "completed").put("provider", "openai")
        .put("cost_kind", "unknown")
}
