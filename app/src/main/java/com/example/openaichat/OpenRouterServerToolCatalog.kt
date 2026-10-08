package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

object OpenRouterServerToolCatalog {
    fun tools(webSearch: Boolean, webFetch: Boolean): JSONArray {
        val result = JSONArray()
        if (webSearch) result.put(JSONObject().put("type", "openrouter:web_search"))
        if (webFetch) result.put(JSONObject().put("type", "openrouter:web_fetch"))
        return result
    }
}
