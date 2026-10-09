package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test

class ModelRequestPolicyTest {
    @Test fun knownOpenAiModelsSerializeModesIndependentlyOfEffort() {
        for (model in listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna")) {
            assertTrue(ModelRequestPolicy.supportsReasoningMode("openai", model))
            val pro = ModelRequestPolicy.openAiReasoning(model, "low", "pro", "auto")
            assertEquals("pro", pro.getString("mode"))
            assertEquals("low", pro.getString("effort"))
            assertEquals("auto", pro.getString("summary"))
            val standard = ModelRequestPolicy.openAiReasoning(model, "high", "standard", "null")
            assertEquals("standard", standard.getString("mode"))
            assertEquals("high", standard.getString("effort"))
            assertFalse(standard.has("summary"))
        }
    }

    @Test fun unknownAndOpenRouterModelsDoNotClaimModeSupport() {
        assertNull(ModelRequestPolicy.supportedMode("openai", "custom/model", "pro"))
        assertNull(ModelRequestPolicy.supportedMode("openrouter", "anthropic/claude-opus-5.5", "pro"))
        assertNull(ModelRequestPolicy.supportedMode("openai", "gpt-6-astra", "unsupported"))
        val reasoning = ModelRequestPolicy.openAiReasoning("custom/model", "medium", "pro", "concise")
        assertFalse(reasoning.has("mode"))
        assertEquals("medium", reasoning.getString("effort"))
        assertEquals("concise", reasoning.getString("summary"))
    }

    @Test fun manualEffortBadgeMatchesTheSelectedLevel() {
        for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
            assertEquals("⚡ $effort", ModelRequestPolicy.effortBadge(effort))
        }
    }

    @Test fun autoBadgeShowsOnlyAResolvedRequestLevel() {
        assertEquals("⚡ auto", ModelRequestPolicy.effortBadge("auto"))
        assertEquals("⚡ auto", ModelRequestPolicy.effortBadge("auto", ""))
        assertEquals("⚡ auto → low", ModelRequestPolicy.effortBadge("auto", "low"))
        assertEquals("⚡ auto → xhigh", ModelRequestPolicy.effortBadge("auto", "xhigh"))
        assertEquals("⚡ auto → max", ModelRequestPolicy.effortBadge("auto", "max"))
        assertEquals("⚡ high", ModelRequestPolicy.effortBadge("high", "medium"))
    }
}
