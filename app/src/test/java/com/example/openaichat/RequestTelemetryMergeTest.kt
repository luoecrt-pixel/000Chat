package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestTelemetryMergeTest {
    @Test fun absentTelemetryRetainsExistingCountersAndResponseLedgerMergesById() {
        val parent = OpenAiUsageLedger.observe(null, "resp-parent", JSONObject().put("input_tokens", 100), "completed")
        val child = OpenAiUsageLedger.observe(null, "resp-child", JSONObject().put("input_tokens", 20), "completed")
        val existing = JSONObject().put("rounds_completed", 2).put("calls_executed", 3)
            .put("executed_tools", JSONArray().put("read_attached_file"))
            .put("files", 1).put("source_read_passes", 4).put(OpenAiUsageLedger.FIELD, parent)
        val unchanged = JSONObject(FileBackedIoTelemetry.mergeTelemetryJson(existing.toString(), null)!!)
        assertEquals(3, unchanged.getInt("calls_executed"))
        val incoming = JSONObject().put(OpenAiUsageLedger.FIELD, child)
            .put("cost_details", JSONObject().put("service_tier", "standard"))
        val merged = JSONObject(FileBackedIoTelemetry.mergeTelemetryJson(existing.toString(), incoming)!!)
        assertEquals(4, merged.getInt("source_read_passes"))
        assertEquals(120L, OpenAiUsageLedger.total(merged.getJSONObject(OpenAiUsageLedger.FIELD))!!.getLong("input_tokens"))
        val repeated = JSONObject(FileBackedIoTelemetry.mergeTelemetryJson(merged.toString(), incoming)!!)
        assertEquals(120L, OpenAiUsageLedger.total(repeated.getJSONObject(OpenAiUsageLedger.FIELD))!!.getLong("input_tokens"))
        assertEquals("standard", repeated.getJSONObject("cost_details").getString("service_tier"))
    }

    @Test fun delayedOlderSummaryCannotRegressCumulativeCountersOrEraseExecutedToolNames() {
        val recent = JSONObject().put("rounds_completed", 5).put("calls_executed", 8)
            .put("files", 2).put("source_read_passes", 10).put("source_read_bytes", 12000).put("file_work_ms", 40)
            .put("executed_tools", JSONArray().put("read_attached_file").put("search_attached_file"))
        val old = JSONObject().put("rounds_completed", 1).put("calls_executed", 2)
            .put("files", 1).put("source_read_passes", 3).put("source_read_bytes", 4000).put("file_work_ms", 12)
            .put("executed_tools", JSONArray().put("read_attached_file"))
        val merged = JSONObject(FileBackedIoTelemetry.mergeTelemetryJson(recent.toString(), old)!!)
        listOf("rounds_completed", "calls_executed", "files", "source_read_passes", "source_read_bytes", "file_work_ms")
            .forEach { assertEquals(recent.getLong(it), merged.getLong(it)) }
        assertEquals(listOf("read_attached_file", "search_attached_file"),
            merged.getJSONArray("executed_tools").let { values -> (0 until values.length()).map { values.getString(it) } })
    }

    @Test fun completedEventUsesMatchingAuthoritativeLedgerEvenWhenEarlierTokensWereNonzero() {
        val event = JSONObject().put("event_type", "request_completed").put("request_id", "request-one")
            .put("input_tokens", 100).put("output_tokens", 10).put("cost_kind", "unknown")
            .put("telemetry_summary", JSONObject().put("files", 1).put("source_read_passes", 2))
        val ledger = JSONObject().put("local_request_id", "request-one").put("local_status", "completed")
            .put("input_tokens", 250).put("output_tokens", 30).put("cached_tokens", 90)
            .put("cost_kind", "provider_reported").put("cost_usd", "0.0125")
            .put("telemetry_summary", JSONObject().put("rounds_completed", 2).put("calls_executed", 1)
                .put("executed_tools", JSONArray().put("file_list")))
        val enriched = RequestStopEventPolicy.enrich(event, ledger)
        assertEquals(250L, enriched.getLong("input_tokens"))
        assertEquals(30L, enriched.getLong("output_tokens"))
        assertEquals(90L, enriched.getLong("cached_tokens"))
        assertEquals(1, enriched.getJSONObject("telemetry_summary").getInt("files"))
        assertEquals(1, enriched.getJSONObject("telemetry_summary").getInt("calls_executed"))
        assertTrue(RequestEventFormatter.summary(enriched).contains("Расход $0.0125"))
        assertTrue(RequestEventFormatter.summary(enriched).contains("tools 1/2r"))
        assertTrue(RequestEventFormatter.details(enriched).contains("file_io_read_passes=2"))
        // Enrichment returns a copy and must never attach another request's accounting.
        assertEquals(100L, event.getLong("input_tokens"))
        ledger.put("local_request_id", "request-two")
        assertSame(event, RequestStopEventPolicy.enrich(event, ledger))
    }

    @Test fun knownZeroDiffersFromUnknownAndReconciledUnknownCannotRetainStaleConfirmedPrice() {
        val missing = OpenRouterUsageSnapshot.from(JSONObject().put("prompt_tokens", 12))
        assertNull(missing.costUsd)
        assertNull(missing.reportedCostUsd)
        assertEquals("unknown", missing.costKind)
        val zero = OpenRouterUsageSnapshot.from(JSONObject().put("cost", 0))
        assertEquals(0.0, zero.reportedCostUsd!!, 0.0)
        assertEquals("provider_reported", zero.costKind)
        val event = JSONObject().put("event_type", "request_completed").put("request_id", "request")
            .put("input_tokens", 100).put("cost_kind", "provider_reported").put("cost_usd", "0.1")
        val account = JSONObject().put("local_request_id", "request").put("local_status", "completed")
            .put("input_tokens", 250).put("cost_kind", "unknown")
        val enriched = RequestStopEventPolicy.enrich(event, account)
        assertEquals(250L, enriched.getLong("input_tokens"))
        assertEquals("unknown", enriched.getString("cost_kind"))
        assertFalse(enriched.has("cost_usd"))
        assertTrue(RequestEventFormatter.summary(enriched).contains("Расход неизвестен"))
    }
}
