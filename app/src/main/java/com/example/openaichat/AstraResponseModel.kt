package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Structured subset of a Responses API result used by Astra's UI and persistence layer. */
data class AstraResponseModel(
    val id: String?,
    val requestId: String?,
    val model: String? = null,
    val text: String,
    val annotations: JSONArray = JSONArray(),
    val tools: List<String> = emptyList(),
    val usage: JSONObject? = null,
    val status: String = "completed",
    val error: String? = null,
    /** Readable reasoning summary returned by the API; this is a summary, not private chain-of-thought. */
    val reasoningSummary: String? = null,
    /** Base64 results from image_generation_call; consumed immediately and never persisted. */
    val generatedImageResults: List<String> = emptyList()
) {
    val fileAnnotations: List<JSONObject>
        get() = annotationsToList("container_file_citation") + annotationsToList("file_citation") + annotationsToList("local_file")

    val urlAnnotations: List<JSONObject>
        get() = annotationsToList("url_citation")

    fun toChatJson(): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("text", text)
        .put("response_id", id ?: JSONObject.NULL)
        .put("request_id", requestId ?: JSONObject.NULL)
        .put("response_model", model ?: JSONObject.NULL)
        .put("annotations", JSONArray(annotations.toString()))
        .put("tools", JSONArray(tools))
        .put("status", status)
        .put("incomplete", status == "incomplete")
        .also {
            if (!error.isNullOrBlank()) it.put("error", error)
            if (!reasoningSummary.isNullOrBlank()) it.put("reasoning_summary", reasoningSummary)
        }

    private fun annotationsToList(type: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (i in 0 until annotations.length()) {
            val a = annotations.optJSONObject(i) ?: continue
            if (a.optString("type") == type) out.add(JSONObject(a.toString()))
        }
        return out
    }

    companion object {
        fun fromResponse(
            response: JSONObject?,
            fallbackText: String,
            requestId: String? = null,
            forcedStatus: String? = null,
            forcedError: String? = null
        ): AstraResponseModel {
            val r = response ?: JSONObject()
            val output = r.optJSONArray("output") ?: JSONArray()
            val tools = mutableListOf<String>()
            val annotations = JSONArray()
            val generatedImages = mutableListOf<String>()
            val reasoningSummaryParts = mutableListOf<String>()
            for (i in 0 until output.length()) {
                val item = output.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "reasoning" -> {
                        val summary = item.optJSONArray("summary")
                        if(summary!=null){
                            for(j in 0 until summary.length()){
                                val s=summary.optJSONObject(j) ?: continue
                                if(s.optString("type")=="summary_text"){
                                    JsonProtocol.string(s, "text").trim().takeIf{it.isNotBlank()}?.let(reasoningSummaryParts::add)
                                }
                            }
                        }
                    }
                    "web_search_call" -> tools.add("🌐 Web Search")
                    "file_search_call" -> tools.add("📚 File Search")
                    "code_interpreter_call" -> tools.add("🐍 Code Interpreter")
                    "image_generation_call" -> {
                        tools.add("🎨 Image Generation")
                        JsonProtocol.nonBlank(item, "result")?.let(generatedImages::add)
                    }
                    "computer_call" -> tools.add("🖥 Computer Use")
                }
                if (item.optString("type") != "message") continue
                val content = item.optJSONArray("content") ?: continue
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    if (c.optString("type") != "output_text") continue
                    val anns = c.optJSONArray("annotations") ?: continue
                    for (k in 0 until anns.length()) {
                        val a = anns.optJSONObject(k) ?: continue
                        when (a.optString("type")) {
                            "container_file_citation", "file_citation", "url_citation" -> annotations.put(JSONObject(a.toString()))
                        }
                    }
                }
            }
            r.optJSONArray("app_local_artifacts")?.let { artifacts ->
                for (index in 0 until artifacts.length()) {
                    val artifact = artifacts.optJSONObject(index) ?: continue
                    val path = JsonProtocol.nonBlank(artifact, "path") ?: continue
                    annotations.put(JSONObject().put("type", "local_file").put("state", "READY")
                        .put("filename", artifact.optString("filename")).put("local_path", path)
                        .put("mime_type", artifact.optString("mime_type")).put("size_bytes", artifact.optLong("size_bytes"))
                        .apply { listOf("patch_diff", "patch_diff_truncated", "diff_path", "patch_diff_path", "source_path", "source_file_id", "source_sha256").forEach { key ->
                            artifact.opt(key)?.let { put(key, JsonProtocol.copy(it)) }
                        } })
                }
            }
            val status = forcedStatus ?: JsonProtocol.nonBlank(r, "status") ?: "completed"
            val error = forcedError
                ?: r.optJSONObject("error")?.let { JsonProtocol.nonBlank(it, "message") }
                ?: r.optJSONObject("incomplete_details")?.let { JsonProtocol.nonBlank(it, "reason") }
            return AstraResponseModel(
                id = JsonProtocol.nonBlank(r, "id"),
                requestId = requestId,
                model = JsonProtocol.nonBlank(r, "model"),
                text = fallbackText,
                annotations = annotations,
                tools = tools.distinct(),
                usage = r.optJSONObject("usage"),
                status = status,
                error = error,
                reasoningSummary = reasoningSummaryParts.joinToString("\n\n").takeIf{it.isNotBlank()},
                generatedImageResults = generatedImages
            )
        }

        fun fromChatJson(obj: JSONObject): AstraResponseModel {
            return AstraResponseModel(
                id = obj.optString("response_id").takeIf { it.isNotBlank() && it != "null" },
                requestId = obj.optString("request_id").takeIf { it.isNotBlank() && it != "null" },
                model = obj.optString("response_model").takeIf { it.isNotBlank() && it != "null" },
                text = JsonProtocol.string(obj, "text"),
                annotations = JSONArray(obj.optJSONArray("annotations")?.toString() ?: "[]"),
                tools = buildList {
                    val arr = obj.optJSONArray("tools")
                    if (arr != null) for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let(::add)
                },
                status = obj.optString("status").ifBlank { if (obj.optBoolean("incomplete")) "incomplete" else "completed" },
                error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" },
                reasoningSummary = obj.optString("reasoning_summary").takeIf { it.isNotBlank() && it != "null" }
            )
        }
    }
}
