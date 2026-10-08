package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class RequestEventFormatterTest {
    @Test
    fun exactSummaryDoesNotUseModelArrow() {
        val event = JSONObject()
            .put("provider", "openai")
            .put("requested_model", "gpt-6.1-sol")
            .put("response_model", "gpt-6.1-sol")
            .put("model_match", "exact")
            .put("input_tokens", 18400)
            .put("output_tokens", 4300)
            .put("reasoning_tokens", 950)
            .put("cached_tokens", 12000)
            .put("estimated_cost_usd", 0.047)
            .put("latency_ms", 8400)

        val summary = RequestEventFormatter.summary(event)

        assertEquals(true, summary.contains("GPT-6.1 Sol"))
        assertEquals(false, summary.contains(" -> "))
        assertEquals(true, summary.contains("18.4K in"))
        assertEquals(true, summary.contains("4.3K out"))
        assertEquals(true, summary.contains("Расход неизвестен"))
    }

    @Test
    fun unknownMatchIsDistinct() {
        val event = JSONObject()
            .put("provider", "openrouter")
            .put("requested_model", "anthropic/claude-opus-5.5")
            .put("model_match", "unknown")

        assertEquals(ModelCatalog.Match.UNKNOWN, RequestEventFormatter.match(event))
        assertEquals(true, RequestEventFormatter.summary(event).contains("response=unknown"))
    }

    @Test
    fun detailsExposeRequestedAndResponseModels() {
        val details = RequestEventFormatter.details(
            JSONObject()
                .put("requested_model", "a")
                .put("response_model", "b")
                .put("model_match", "mismatch")
        )
        assertEquals(true, details.contains("requested_model=a"))
        assertEquals(true, details.contains("response_model=b"))
        assertEquals(true, details.contains("model_match=mismatch"))
    }
    @Test
    fun providerCostMustBeExplicitToDisplayZero() {
        val unknown = JSONObject().put("estimated_cost_usd", 0.0)
        assertEquals(true, RequestEventFormatter.summary(unknown).contains("Расход неизвестен"))
        val actual = JSONObject().put("cost_kind", "provider_reported").put("cost_usd", "0")
        assertEquals(true, RequestEventFormatter.summary(actual).contains("Расход $0.0000"))
        actual.put("cost_usd", "0.00000001")
        assertEquals(true, RequestEventFormatter.summary(actual).contains("Расход < $0.0001"))
        actual.put("cost_usd", "NaN")
        assertEquals(true, RequestEventFormatter.summary(actual).contains("Расход неизвестен"))
    }
}
