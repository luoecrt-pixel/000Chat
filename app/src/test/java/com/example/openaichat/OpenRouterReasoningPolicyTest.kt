package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test

class OpenRouterReasoningPolicyTest {
    @Test fun catalogClaudeModelsPreserveEveryNativeEffort() {
        val models = ModelCatalog.source("openrouter").models
        assertTrue(models.isNotEmpty())
        for (model in models) {
            for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
                assertEquals(effort, OpenRouterReasoningPolicy.requestEffort(model.id, effort))
                assertEquals(effort, OpenRouterReasoningPolicy.requestEffort("~${model.id}", effort))
            }
        }
    }

    @Test fun whitespaceAndCaseDoNotChangeTheEffortTier() {
        assertEquals("max", OpenRouterReasoningPolicy.requestEffort("anthropic/claude-opus-5.5", " MAX "))
        assertEquals("xhigh", OpenRouterReasoningPolicy.requestEffort("anthropic/claude-sonnet-5.5", " XHIGH "))
    }

    @Test fun omittedEffortStaysOmitted() {
        assertNull(OpenRouterReasoningPolicy.requestEffort("anthropic/claude-opus-5.5", null))
        assertNull(OpenRouterReasoningPolicy.requestEffort("anthropic/claude-opus-5.5", " "))
    }

    @Test fun unresolvedAutoAndUnsupportedClaudeEffortsAreRejected() {
        for (effort in listOf("auto", "none", "minimal", "ultra")) {
            assertThrows(IllegalArgumentException::class.java) {
                OpenRouterReasoningPolicy.requestEffort("anthropic/claude-opus-5.5", effort)
            }
        }
    }

    @Test fun nonClaudeGatewayLevelsAreNotRewritten() {
        for (effort in listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")) {
            assertEquals(effort, OpenRouterReasoningPolicy.requestEffort("custom/reasoning-model", effort))
        }
    }

    @Test fun responseVerbosityCannotOverrideClaudeEffort() {
        assertFalse(OpenRouterReasoningPolicy.supportsResponseVerbosity("anthropic/claude-opus-5.5"))
        assertFalse(OpenRouterReasoningPolicy.supportsResponseVerbosity("~anthropic/claude-fable-5.1"))
        assertTrue(OpenRouterReasoningPolicy.supportsResponseVerbosity("openai/gpt-6-astra"))
    }
}
