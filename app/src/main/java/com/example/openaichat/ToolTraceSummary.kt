package com.example.openaichat

import org.json.JSONObject

object ToolTraceSummary {
    fun entry(name: String, arguments: JSONObject, result: String): JSONObject {
        val target = JSONObject()
        listOf("owner", "repo", "path", "branch", "ref", "filename", "file_id", "issue_number", "pull_number").forEach { key ->
            arguments.opt(key)?.takeUnless { it == JSONObject.NULL }?.let { target.put(key, it.toString().take(180)) }
        }
        val parsed = runCatching { JSONObject(result) }.getOrNull()
        return JSONObject().put("name", name).put("target", target)
            .put("ok", parsed?.optBoolean("ok", true) ?: true)
            .put("status", if (parsed?.optBoolean("ok", true) == false) "ошибка" else "готово")
            .put("summary", (JsonProtocol.nonBlank(parsed ?: JSONObject(), "error") ?: "Выполнено").take(300))
    }
}
