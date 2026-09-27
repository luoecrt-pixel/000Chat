package com.example.openaichat

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class OpenRouterCompletionResult(
    val text: String,
    val usage: JSONObject?,
    val assistantMessage: JSONObject,
    val toolNames: List<String>
)

class OpenRouterChatClient {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(240, TimeUnit.SECONDS)
        .build()

    fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        tools: JSONArray?,
        maxTokens: Int,
        reasoningEffort: String?,
        verbosity: String?,
        parallelToolCalls: Boolean,
        executeTool: (name: String, arguments: JSONObject) -> String,
        onCallCreated: (Call) -> Unit = {}
    ): OpenRouterCompletionResult {
        val history = JSONArray(messages.toString())
        val toolNames = mutableListOf<String>()
        repeat(8) {
            val body = JSONObject()
                .put("model", model)
                .put("messages", history)
                .put("max_tokens", maxTokens.coerceIn(256, 128000))
                .put("stream", false)
                .put("parallel_tool_calls", parallelToolCalls)
            tools?.takeIf { it.length() > 0 }?.let {
                body.put("tools", it)
                body.put("tool_choice", "auto")
            }
            reasoningEffort?.takeIf { it.isNotBlank() && it != "auto" }?.let {
                body.put("reasoning", JSONObject().put("effort", it))
            }
            verbosity?.takeIf { it.isNotBlank() }?.let { body.put("verbosity", it) }

            val request = Request.Builder()
                .url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("HTTP-Referer", "com.example.openaichat")
                .addHeader("X-Title", "OpenAIApiChat")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(request)
            onCallCreated(call)
            call.execute().use { httpResponse ->
                val raw = httpResponse.body?.string().orEmpty()
                if (!httpResponse.isSuccessful) {
                    throw IOException("OpenRouter HTTP ${httpResponse.code}: ${raw.take(1200)}")
                }
                val json = JSONObject(raw)
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw IOException("OpenRouter response has no choice")
                val message = choice.optJSONObject("message")
                    ?: throw IOException("OpenRouter response has no message")
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    return OpenRouterCompletionResult(
                        text = message.optString("content").trim(),
                        usage = json.optJSONObject("usage"),
                        assistantMessage = JSONObject(message.toString()),
                        toolNames = toolNames.distinct()
                    )
                }
                history.put(JSONObject(message.toString()))
                for (i in 0 until calls.length()) {
                    val callObject = calls.optJSONObject(i) ?: continue
                    val function = callObject.optJSONObject("function")
                        ?: throw IOException("Malformed tool call")
                    val name = function.optString("name").trim()
                    val id = callObject.optString("id").trim()
                    if (name.isBlank() || id.isBlank()) throw IOException("Malformed tool call id/name")
                    val arguments = try {
                        JSONObject(function.optString("arguments", "{}"))
                    } catch (e: Exception) {
                        throw IOException("Invalid tool arguments for $name", e)
                    }
                    toolNames += name
                    history.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", executeTool(name, arguments))
                    )
                }
            }
        }
        throw IOException("OpenRouter tool loop exceeded 8 rounds")
    }

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}
