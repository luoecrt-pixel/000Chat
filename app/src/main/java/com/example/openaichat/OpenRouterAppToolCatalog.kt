package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

object OpenRouterAppToolCatalog {
    fun tools(): JSONArray = JSONArray().put(
        JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "create_local_text_file")
                .put("description", "Create a real UTF-8 text/code file inside the app. The user can save or share it from Local Files.")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("filename", JSONObject().put("type", "string"))
                        .put("content", JSONObject().put("type", "string")))
                    .put("required", JSONArray().put("filename").put("content"))
                    .put("additionalProperties", false)))
    )
}
