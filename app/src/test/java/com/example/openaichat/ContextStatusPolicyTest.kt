package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ContextStatusPolicyTest {
    private val instructions = GenerationInstructions("rules", "developer")
    private val fingerprint = OpenAiInstructionChainPolicy.fingerprint(instructions, "synthetic-key", "gpt-test")
    private val reply get() = JSONObject().put("role", "assistant").put("provider", "openai")
        .put("status", "completed").put("response_id", "resp-one").put("text", "Ответ")
        .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint)

    private fun build(history: List<JSONObject> = listOf(reply), manifests: Boolean = false,
        accounting: JSONObject? = null, provider: String = "openai", binding: String? = null,
        expected: String? = null): ContextStatusSnapshot {
        val actualHistory = if (provider == "openrouter") listOf(JSONObject().put("role", "assistant")
            .put("openrouter_protocol", JSONObject().put("version", 1).put("binding_sha256", binding ?: JSONObject.NULL)
                .put("tail", org.json.JSONArray().put(JSONObject().put("reasoning", "preserve"))))) else history
        val before = actualHistory.map { it.toString() }
        return ContextStatusPolicy.build(provider, if (provider == "openai") "gpt-test" else "anthropic/claude-test",
            actualHistory, "resp-one", fingerprint, manifests, expected, OpenRouterClaudeRoute.ANTHROPIC,
            "1h", listOf("данные.csv"), listOf("file_search"), accounting, 1000L, 4000L).also {
            assertEquals("Inspection must not mutate protocol", before, actualHistory.map { m -> m.toString() })
        }
    }

    @Test fun chainMatchesSendPolicyAndManifestsForceReplay() {
        assertEquals("цепочка", build().mode)
        assertEquals("локальный replay", build(manifests = true).mode)
        assertEquals("локальный replay", build(history = listOf(reply.put("status", "incomplete"))).mode)
        val changed = reply.put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD,
            OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("changed", "developer"), "synthetic-key", "gpt-test"))
        assertTrue(build(history = listOf(changed)).explanation.contains("Изменились"))
    }

    @Test fun absentNullInvalidAndObservedZeroCacheRemainDistinct() {
        assertNull(build().cacheReadTokens)
        assertNull(build(accounting = JSONObject().put("cached_tokens", JSONObject.NULL)).cacheReadTokens)
        assertNull(build(accounting = JSONObject().put("cached_tokens", -1)).cacheReadTokens)
        val known = build(accounting = JSONObject().put("cached_tokens", 0).put("cache_write_tokens", 42))
        assertEquals(0L, known.cacheReadTokens)
        assertEquals(42L, known.cacheWriteTokens)
        assertEquals(3000L, known.lastResponseAgeMillis)
    }

    @Test fun invalidatedUnsupportedAndMissingTailNeverClaimPreservedBinding() {
        for (case in listOf("invalidated", "version", "tail")) {
            val protocol = JSONObject().put("version", 1).put("binding_sha256", "bound")
                .put("tail", org.json.JSONArray().put(JSONObject().put("role", "assistant")))
            val message = JSONObject().put("role", "assistant").put("openrouter_protocol", protocol)
            when (case) {
                "invalidated" -> message.put("protocol_context_state", "invalidated")
                "version" -> protocol.put("version", 2)
                "tail" -> protocol.remove("tail")
            }
            val value = ContextStatusPolicy.build("openrouter", "anthropic/test", listOf(message), null, "", false,
                "bound", OpenRouterClaudeRoute.AUTO, "1h", emptyList(), emptyList(), null, null, 10,
                openRouterPrefixMatches = false, openRouterPrefixReason = "Изменены инструкции")
            assertFalse(case, value.explanation.contains("привязка сохранена"))
            assertFalse(case, value.explanation.contains("инструментов сохранена"))
            assertTrue(value.explanation.contains("Изменены инструкции"))
        }
    }

    @Test fun routerBindingAndUnverifiedPrefixAreSeparateAndSecretsAreAbsent() {
        val matching = build(provider = "openrouter", binding = "bound", expected = "bound")
        assertTrue(matching.explanation.contains("сохранена"))
        assertTrue(matching.explanation.contains("при подготовке"))
        assertTrue(build(provider = "openrouter", binding = "old", expected = "new").explanation.contains("изменилась"))
        val details = matching.details()
        assertFalse(details.contains("synthetic-key"))
        assertFalse(details.contains("preserve"))
        assertTrue(details.contains("не гарантируют"))
    }
}
