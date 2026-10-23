package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class DeepSeekConfigurationTest {
    @After fun cleanup() { SecureStore.failWritesForTesting = false }

    @Test fun providerHasOwnCredentialSlotAndFailureCannotReuseOpenRouterKey() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("secure", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val secure = SecureStore(context) { raw -> ByteArray(12) { 1 } + raw }
        assertTrue(secure.setOpenRouterApiKey("synthetic-openrouter"))
        assertTrue(secure.setDeepSeekApiKey("synthetic-deepseek"))
        assertNotEquals(prefs.getString("openrouter_key", null), prefs.getString("deepseek_key", null))
        val originalOpenRouter = prefs.getString("openrouter_key", null)
        SecureStore.failWritesForTesting = true
        assertFalse(secure.setDeepSeekApiKey("synthetic-replacement"))
        assertFalse(prefs.contains("deepseek_key"))
        assertEquals(originalOpenRouter, prefs.getString("openrouter_key", null))
        assertEquals("", secure.getDeepSeekApiKey())
    }

    @Test fun providerCatalogAndToolsUseDirectProviderWithoutServerSearchFetch() {
        assertEquals("deepseek", ModelCatalog.source("deepseek").id)
        assertEquals("deepseek-flash", ContextRequestPolicy.model("deepseek", null))
        val localNames = ContextStatusPolicy.toolNames(ContextRequestPolicy.deepSeekTools(false)).toSet()
        assertTrue("create_local_text_file" in localNames)
        assertTrue("patch_local_file" in localNames)
        assertFalse(localNames.any { it == "web_search" || it == "web_fetch" })
        assertEquals(OpenRouterAppToolCatalog.tools().toString(), ContextRequestPolicy.deepSeekTools(false).toString())
        val withGithub = ContextRequestPolicy.deepSeekTools(true)
        assertEquals(OpenRouterAppToolCatalog.tools().length() + GitHubToolCatalog.tools().length(), withGithub.length())
        val binding = DeepSeekProtocolCodec.binding("deepseek-flash", withGithub, "synthetic-key", JSONObject().put("type", "text"))
        assertNotEquals(binding, DeepSeekProtocolCodec.binding("deepseek-flash", ContextRequestPolicy.deepSeekTools(false), "synthetic-key", JSONObject().put("type", "text")))
    }

    @Test fun jsonObjectInstructionsAndBindingUseIdenticalValidatedFormat() {
        val format = DeepSeekContextSnapshot.validateResponseFormat(JSONObject().put("type", "json_object"))!!
        val tools = ContextRequestPolicy.deepSeekTools(false)
        val binding = DeepSeekProtocolCodec.binding("deepseek-flash", tools, "synthetic-key", format)
        val system = DeepSeekContextSnapshot.systemContent(GenerationInstructions(null, null, null), false, "", "", true)
        val messages = DeepSeekContextSnapshot.build(listOf(JSONObject().put("role", "user").put("text", "json please")), system,
            listOf(emptyList()), binding)
        assertTrue(messages.getJSONObject(0).getString("content").contains("JSON"))
        assertEquals("user", messages.getJSONObject(messages.length() - 1).getString("role"))
        assertEquals(binding, DeepSeekProtocolCodec.binding("deepseek-flash", tools, "synthetic-key", format))
    }

    @Test fun contextInspectorLabelsDeepSeekWithoutOpenRouterRouteOrOpenAiChain() {
        val snapshot = ContextStatusPolicy.build("deepseek", "deepseek-flash", emptyList(), "resp_foreign", "foreign",
            false, null, OpenRouterClaudeRoute.AUTO, "1h", emptyList(), emptyList(), null, null, 100)
        assertEquals("DeepSeek · прямой API", snapshot.route)
        assertEquals("полная история", snapshot.mode)
        assertFalse(snapshot.details().contains("OpenAI Responses"))
    }
}
