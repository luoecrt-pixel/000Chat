package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** The same captured built-in tool declarations for Send and context inspection. */
object ContextToolSchemas {
    fun openAi(
        web: Boolean,
        fileSearch: Boolean,
        vectorStore: String,
        codeInterpreter: Boolean,
        containerMemory: String,
        containerFileIds: List<String>,
        imageGeneration: Boolean
    ): JSONArray = JSONArray().apply {
        if (web) put(JSONObject().put("type", "web_search"))
        if (fileSearch && vectorStore.isNotBlank()) {
            put(JSONObject().put("type", "file_search")
                .put("vector_store_ids", JSONArray().put(vectorStore)).put("max_num_results", 20))
        }
        if (codeInterpreter) {
            val container = JSONObject().put("type", "auto").put("memory_limit", containerMemory)
            val ids = containerFileIds.filter { it.isNotBlank() }.distinct()
            if (ids.isNotEmpty()) container.put("file_ids", JSONArray().apply { ids.forEach { put(it) } })
            put(JSONObject().put("type", "code_interpreter").put("container", container))
        }
        if (imageGeneration) put(JSONObject().put("type", "image_generation"))
    }
}
