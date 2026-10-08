package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterCompletionTelemetryTest {
    @Test
    fun completionEventIsRecordedWhenProviderOmitsUsage() {
        var metricCalls = 0
        val recordedEvents = mutableListOf<OpenRouterUsageSnapshot>()
        OpenRouterCompletionTelemetry.record(
            usage = null,
            recordMetric = { metricCalls++ },
            recordEvent = { recordedEvents += it }
        )
        assertEquals(0, metricCalls)
        assertEquals(1, recordedEvents.size)
        assertEquals(0L, recordedEvents.single().inputTokens)
        assertEquals(0L, recordedEvents.single().outputTokens)
        assertEquals(0.0, recordedEvents.single().costUsd, 0.0)
    }

    @Test
    fun providerUsageRecordsMetricAndCompletionEventWithIdenticalTotals() {
        val usage = JSONObject(
            """{"prompt_tokens":120,"completion_tokens":55,"cost":0.014,"prompt_tokens_details":{"cached_tokens":90,"cache_write_tokens":12},"completion_tokens_details":{"reasoning_tokens":21}}"""
        )
        val metrics = mutableListOf<OpenRouterUsageSnapshot>()
        val events = mutableListOf<OpenRouterUsageSnapshot>()
        OpenRouterCompletionTelemetry.record(
            usage = usage,
            recordMetric = { metrics += it },
            recordEvent = { events += it }
        )
        assertEquals(1, metrics.size)
        assertEquals(listOf(metrics.single()), events)
        assertEquals(120L, events.single().inputTokens)
        assertEquals(90L, events.single().cachedTokens)
        assertEquals(12L, events.single().cacheWriteTokens)
        assertEquals(55L, events.single().outputTokens)
        assertEquals(21L, events.single().reasoningTokens)
        assertEquals(0.014, events.single().costUsd, 0.00000001)
    }

    @Test
    fun emptyUsageStillRecordsBothEventsAndInvalidCostIsNeverPersisted() {
        val calls = mutableListOf<String>()
        OpenRouterCompletionTelemetry.record(
            usage = JSONObject().put("cost", Double.NaN).put("prompt_tokens", -99),
            recordMetric = {
                calls += "metric"
                assertEquals(0.0, it.costUsd, 0.0)
                assertEquals(0L, it.inputTokens)
            },
            recordEvent = {
                calls += "event"
                assertEquals(0.0, it.costUsd, 0.0)
            }
        )
        assertEquals(listOf("metric", "event"), calls)
    }
}
