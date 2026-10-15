package com.example.openaichat

import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModelCatalogTest {
    private lateinit var prefs: SharedPreferences

    @Before fun before() {
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("model_catalog_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        ModelCatalog.syncFromPreferences(prefs)
    }

    @After fun after() {
        prefs.edit().clear().commit()
        ModelCatalog.syncFromPreferences(prefs)
    }

    @Test
    fun customModelCanBeAddedUpdatedAndRemovedWithPersistedNameAndCompactionThreshold() {
        val id = "custom/research-model"
        val builtInIds = ModelCatalog.source("openai").models.map { it.id }
        assertTrue(ModelCatalog.upsertCustomModel(prefs, "openai", "~$id", "Research model", 200_000))
        ModelCatalog.syncFromPreferences(prefs)
        assertEquals("Research model", ModelCatalog.displayName("openai", id))
        assertEquals(183_616, ModelCatalog.compactionThreshold(id))
        assertTrue(ModelCatalog.model("openai", id)!!.custom)
        assertEquals(builtInIds.size + 1, ModelCatalog.source("openai").models.size)

        assertTrue(ModelCatalog.upsertCustomModel(prefs, "openai", id, "Updated research model", 1_500_000))
        ModelCatalog.syncFromPreferences(prefs)
        assertEquals("Updated research model", ModelCatalog.displayName("openai", "~$id"))
        assertEquals(800_000, ModelCatalog.compactionThreshold(id))
        assertEquals(1_500_000, ModelCatalog.model("openai", id)!!.contextWindow)
        assertEquals(1, ModelCatalog.source("openai").models.count { it.id == id })

        assertTrue(ModelCatalog.removeCustomModel(prefs, "openai", id))
        ModelCatalog.syncFromPreferences(prefs)
        assertEquals(id, ModelCatalog.displayName("openai", id))
        assertNull(ModelCatalog.compactionThreshold(id))
        assertNull(ModelCatalog.model("openai", id))
        assertEquals(builtInIds, ModelCatalog.source("openai").models.map { it.id })
    }

    @Test
    fun customAnthropicModelPreservesNativeEffortWithoutVerbosityOverride() {
        val id = "anthropic/claude-custom"
        assertTrue(ModelCatalog.upsertCustomModel(prefs, "openrouter", id, "Custom Claude"))
        assertEquals("Custom Claude", ModelCatalog.displayName("openrouter", id))
        for (effort in listOf("low", "medium", "high", "xhigh", "max")) {
            assertEquals(effort, OpenRouterReasoningPolicy.requestEffort(id, effort))
        }
        assertThrows(IllegalArgumentException::class.java) {
            OpenRouterReasoningPolicy.requestEffort(id, "minimal")
        }
        assertFalse(OpenRouterReasoningPolicy.supportsResponseVerbosity(id))
    }

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
