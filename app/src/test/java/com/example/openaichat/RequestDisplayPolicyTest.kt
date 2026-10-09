package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RequestDisplayPolicyTest {
    @Test fun costDistinguishesUnknownZeroTinyAndInvalid() {
        assertEquals("Расход неизвестен", RequestCostFormatter.text(null))
        assertEquals("Расход неизвестен", RequestCostFormatter.text(
            JSONObject().put("cost_kind", "estimated").put("cost_usd", "1")))
        assertEquals("Расход неизвестен", RequestCostFormatter.text(
            JSONObject().put("cost_kind", "provider_reported")))
        assertEquals("Расход $0.0000", RequestCostFormatter.text(
            JSONObject().put("cost_kind", "provider_reported").put("cost_usd", "0")))
        assertEquals("Расход < $0.0001", RequestCostFormatter.reportedText("0.00000001"))
        assertEquals("Расход неизвестен", RequestCostFormatter.reportedText("-1"))
        assertEquals("Расход неизвестен", RequestCostFormatter.reportedText("NaN"))
    }

    @Test fun stoppedEventUsesOnlyMatchingLedgerAndRemainsStopped() {
        val e = JSONObject().put("event_type", "request_stopped").put("request_id", "local_now")
            .put("status", "stopped").put("cost_kind", "unknown")
        val old = JSONObject().put("local_request_id", "old").put("local_status", "stopped")
            .put("cost_kind", "provider_reported").put("cost_usd", "1")
        assertEquals("Расход неизвестен", RequestCostFormatter.text(RequestStopEventPolicy.enrich(e, old)))
        val current = JSONObject().put("local_request_id", "local_now").put("local_status", "stopped")
            .put("cost_kind", "provider_reported").put("cost_usd", "0.00000001")
        val result = RequestStopEventPolicy.enrich(e, current)
        assertEquals("stopped", result.getString("status"))
        assertEquals("Расход < $0.0001", RequestCostFormatter.text(result))
        assertEquals("unknown", e.getString("cost_kind"))
    }

    @Test fun modelLabelUsesResponseThenRequestedThenLegacyFallback() {
        val m = JSONObject().put("provider", "openrouter")
            .put("requested_model", "anthropic/claude-sonnet-5.5")
            .put("response_model", "anthropic/claude-opus-5.5")
        assertEquals("Astra · Claude Opus 5.5", AssistantModelLabel.text(m))
        m.remove("response_model")
        assertEquals("Astra · запрошена Claude Sonnet 5.5", AssistantModelLabel.text(m))
        assertEquals("Astra · модель неизвестна", AssistantModelLabel.text(JSONObject()))
    }
}
