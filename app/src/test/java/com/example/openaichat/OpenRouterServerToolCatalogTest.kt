package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Test

class OpenRouterServerToolCatalogTest {
    @Test
    fun bothServerToolsUseOpenRouterToolTypes() {
        val tools = OpenRouterServerToolCatalog.tools(webSearch = true, webFetch = true)
        assertEquals(2, tools.length())
        assertEquals("openrouter:web_search", tools.getJSONObject(0).getString("type"))
        assertEquals(1, tools.getJSONObject(0).length())
        assertEquals("openrouter:web_fetch", tools.getJSONObject(1).getString("type"))
        assertEquals(1, tools.getJSONObject(1).length())
    }

    @Test
    fun disabledServerToolIsNotDeclared() {
        val tools = OpenRouterServerToolCatalog.tools(webSearch = true, webFetch = false)
        assertEquals(1, tools.length())
        assertEquals("openrouter:web_search", tools.getJSONObject(0).getString("type"))
    }

    @Test
    fun noServerToolsMeansNoExtraToolDeclaration() {
        assertEquals(0, OpenRouterServerToolCatalog.tools(webSearch = false, webFetch = false).length())
    }
}
