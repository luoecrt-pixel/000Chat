package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun openAiContainsLatestThreeModels() {
        assertEquals(
            listOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna"),
            ModelCatalog.source("openai").models.map { it.id }
        )
    }

    @Test
    fun openRouterAnthropicContainsLatestThreeModels() {
        assertEquals(
            listOf(
                "anthropic/claude-opus-5.5",
                "anthropic/claude-sonnet-5.5",
                "anthropic/claude-fable-5.1"
            ),
            ModelCatalog.source("openrouter").models.map { it.id }
        )
    }

    @Test
    fun unknownModelIsShownById() {
        assertEquals("custom/model", ModelCatalog.displayName("openai", "custom/model"))
        assertNull(ModelCatalog.model("openai", "custom/model"))
    }

    @Test
    fun matchIsTriState() {
        assertEquals(
            ModelCatalog.Match.EXACT,
            ModelCatalog.match("~gpt-6.1-sol", "gpt-6.1-sol")
        )
        assertEquals(
            ModelCatalog.Match.MISMATCH,
            ModelCatalog.match("gpt-6-astra", "gpt-6.1-sol")
        )
        assertEquals(
            ModelCatalog.Match.UNKNOWN,
            ModelCatalog.match("gpt-6-astra", null)
        )
    }
}
