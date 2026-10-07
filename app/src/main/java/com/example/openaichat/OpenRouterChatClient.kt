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
import java.util.concurrent.CancellationException

data class OpenRouterCompletionResult(
    val text: String,
    val usage: JSONObject?,
    val assistantMessage: JSONObject,
    val toolNames: List<String>
)

class OpenRouterCompletionException(
    message: String,
    val partialText: String,
    cause: Throwable? = null
) : IOException(message, cause)

private data class OpenRouterStreamToolCall(
    var id: String = "",
    var type: String = "function",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

class OpenRouterChatClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    httpClientOverride: OkHttpClient? = null
) {
    private companion object {
        // Safety ceiling for one OpenRouter task. Do not add smaller per-response
        // time/iteration caps here: this ceiling is the emergency stop.
        const val COMPLETE_TIME_LIMIT_MS = 5 * 60 * 60 * 1000L
        const val MAX_ACCUMULATED_TOTAL_TOKENS = 5_000_000L
    }

    private fun normalizeReasoningEffort(value: String?): String? =
        value?.trim()?.lowercase()?.takeIf { it.isNotBlank() }?.let {
            when (it) {
                "xhigh", "max" -> "high"
                "auto" -> "medium"
                else -> it
            }
        }

    private val httpClient = httpClientOverride ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .build()

    fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        sessionId: String? = null,
        tools: JSONArray?,
        maxTokens: Int?,
        reasoningEffort: String?,
        verbosity: String?,
        parallelToolCalls: Boolean,
        executeTool: (name: String, arguments: JSONObject) -> String,
        onCallCreated: (Call) -> Unit = {},
        shouldContinue: () -> Boolean = { true }
    ): OpenRouterCompletionResult {
        val history = JSONArray(messages.toString())
        val toolNames = mutableListOf<String>()
        val accumulatedText = mutableListOf<String>()
        val accumulatedUsage = JSONObject()
        var accumulatedTotalTokens = 0L
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPLETE_TIME_LIMIT_MS)

        fun addUsageLong(target: JSONObject, key: String, value: Long) {
            if (value <= 0L) return
            target.put(key, target.optLong(key, 0L) + value)
        }

        fun addUsageDouble(key: String, value: Double) {
            if (!value.isFinite() || value <= 0.0) return
            accumulatedUsage.put(key, accumulatedUsage.optDouble(key, 0.0) + value)
        }

        fun accumulateUsage(source: JSONObject?) {
            if (source == null) return
            addUsageLong(accumulatedUsage, "prompt_tokens", source.optLong("prompt_tokens", 0L))
            addUsageLong(accumulatedUsage, "completion_tokens", source.optLong("completion_tokens", 0L))
            addUsageLong(accumulatedUsage, "total_tokens", source.optLong("total_tokens", 0L))
            addUsageDouble("cost", source.optDouble("cost", 0.0))
            source.optJSONObject("prompt_tokens_details")?.let { details ->
                val target = accumulatedUsage.optJSONObject("prompt_tokens_details") ?: JSONObject().also {
                    accumulatedUsage.put("prompt_tokens_details", it)
                }
                addUsageLong(target, "cached_tokens", details.optLong("cached_tokens", 0L))
                addUsageLong(target, "cache_write_tokens", details.optLong("cache_write_tokens", 0L))
            }
            source.optJSONObject("completion_tokens_details")?.let { details ->
                val target = accumulatedUsage.optJSONObject("completion_tokens_details") ?: JSONObject().also {
                    accumulatedUsage.put("completion_tokens_details", it)
                }
                addUsageLong(target, "reasoning_tokens", details.optLong("reasoning_tokens", 0L))
            }
        }

        fun budgetException(message: String): OpenRouterCompletionException =
            OpenRouterCompletionException(
                message = message,
                partialText = accumulatedText.joinToString("\n\n").trim()
            )

        fun ensureActive() {
            if (!shouldContinue()) {
                throw CancellationException("OpenRouter request is no longer current.")
            }
        }

        fun ensureWithinBudget() {
            ensureActive()
            if (System.nanoTime() >= deadlineNanos) {
                throw budgetException("Лимит времени OpenRouter complete() (5 часов) исчерпан.")
            }
            if (accumulatedTotalTokens >= MAX_ACCUMULATED_TOTAL_TOKENS) {
                throw budgetException("Мягкий лимит накопленных токенов OpenRouter (5 млн) исчерпан.")
            }
        }

        fun requestMessage(toolChoice: String): Pair<JSONObject, JSONObject> {
            ensureWithinBudget()
            val body = JSONObject()
                .put("model", model)
                .put("messages", history)
                .put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
                .put("parallel_tool_calls", parallelToolCalls)
                .put("usage", JSONObject().put("include", true))
            maxTokens?.takeIf { it > 0 }?.let { body.put("max_tokens", it) }
            tools?.takeIf { it.length() > 0 }?.let {
                body.put("tools", it)
                body.put("tool_choice", toolChoice)
            }
            sessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                body.put("session_id", it)
            }
            if (model.trim().removePrefix("~").startsWith("anthropic/")) {
                body.put(
                    "cache_control",
                    JSONObject()
                        .put("type", "ephemeral")
                        .put("ttl", "1h")
                )
            }
            normalizeReasoningEffort(reasoningEffort)?.let {
                body.put("reasoning", JSONObject().put("effort", it))
            }
            verbosity?.takeIf { it.isNotBlank() }?.let { body.put("verbosity", it) }

            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("HTTP-Referer", "com.example.openaichat")
                .addHeader("X-Title", "OpenAIApiChat")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val call = httpClient.newCall(request)
            if (!shouldContinue()) {
                call.cancel()
                throw CancellationException("OpenRouter request is no longer current.")
            }
            onCallCreated(call)
            ensureActive()
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis((deadlineNanos - System.nanoTime()).coerceAtLeast(0L))
            if (remainingMillis <= 0L) {
                call.cancel()
                throw budgetException("Лимит времени OpenRouter complete() (5 часов) исчерпан.")
            }
            call.timeout().timeout(remainingMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)

            var role = "assistant"
            val contentParts = StringBuilder()
            val reasoningDetails = JSONArray()
            val toolCallsByIndex = sortedMapOf<Int, OpenRouterStreamToolCall>()

            fun consume(data: String) {
                if (data == "[DONE]") return
                val json = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    throw IOException("Invalid OpenRouter SSE event", e)
                }
                json.optJSONObject("error")?.let { error ->
                    val message = error.optString("message").ifBlank { error.toString() }
                    throw IOException("OpenRouter streaming error: $message")
                }
                json.optJSONObject("usage")?.let { usage ->
                    accumulateUsage(usage)
                    usage.optLong("total_tokens", 0L).takeIf { it > 0L }?.let { accumulatedTotalTokens += it }
                }
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) return
                val choice = choices.optJSONObject(0) ?: return
                val delta = choice.optJSONObject("delta") ?: return

                delta.optString("role").takeIf { it.isNotBlank() }?.let { role = it }
                delta.optString("content", "").takeIf { it.isNotEmpty() }?.let { contentParts.append(it) }

                delta.optJSONArray("reasoning_details")?.let { details ->
                    for (i in 0 until details.length()) {
                        details.opt(i)?.let { reasoningDetails.put(it) }
                    }
                }

                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val index = call.optInt("index", i)
                        val target = toolCallsByIndex.getOrPut(index) { OpenRouterStreamToolCall() }
                        call.optString("id").takeIf { it.isNotBlank() }?.let { target.id = it }
                        call.optString("type").takeIf { it.isNotBlank() }?.let { target.type = it }
                        call.optJSONObject("function")?.let { function ->
                            function.optString("name").takeIf { it.isNotBlank() }?.let { target.name += it }
                            function.optString("arguments", "").takeIf { it.isNotEmpty() }?.let { target.arguments.append(it) }
                        }
                    }
                }
            }

            call.execute().use { httpResponse ->
                ensureActive()
                if (!httpResponse.isSuccessful) {
                    val raw = httpResponse.body?.string().orEmpty()
                    throw IOException("OpenRouter HTTP ${httpResponse.code}: ${raw.take(1200)}")
                }
                val bodyResponse = httpResponse.body ?: throw IOException("OpenRouter response has no body")
                val parser = SseEventParser()
                bodyResponse.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                    reader.forEachLine { line ->
                        ensureWithinBudget()
                        parser.acceptLine(line)?.let { event -> consume(event.data) }
                    }
                }
                parser.finish()?.let { event -> consume(event.data) }
            }

            ensureActive()
            val message = JSONObject()
                .put("role", role)
                .put("content", contentParts.toString())
            if (reasoningDetails.length() > 0) {
                message.put("reasoning_details", reasoningDetails)
            }
            if (toolCallsByIndex.isNotEmpty()) {
                val toolCalls = JSONArray()
                toolCallsByIndex.forEach { (_, call) ->
                    if (call.id.isBlank() || call.name.isBlank()) {
                        throw IOException("Malformed streamed tool call")
                    }
                    toolCalls.put(
                        JSONObject()
                            .put("id", call.id)
                            .put("type", call.type)
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    .put("arguments", call.arguments.toString())
                            )
                    )
                }
                message.put("tool_calls", toolCalls)
            }
            return JSONObject().put("stream", true) to message
        }
        try {
            while (true) {
                ensureWithinBudget()
                val (_, message) = requestMessage("auto")
                val calls = message.optJSONArray("tool_calls")
                if (calls == null || calls.length() == 0) {
                    return OpenRouterCompletionResult(
                        text = message.optString("content").trim(),
                        usage = if (accumulatedUsage.length() > 0) JSONObject(accumulatedUsage.toString()) else null,
                        assistantMessage = JSONObject(message.toString()),
                        toolNames = toolNames.distinct()
                    )
                }
                message.optString("content").trim().takeIf { it.isNotBlank() }?.let { accumulatedText += it }
                history.put(JSONObject(message.toString()))
                for (i in 0 until calls.length()) {
                    ensureWithinBudget()
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
                    ensureActive()
                    val toolResult = executeTool(name, arguments)
                    ensureActive()
                    ensureWithinBudget()
                    history.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", id)
                            .put("content", toolResult)
                    )
                }
            }
        } catch (e: Exception) {
            if (e is OpenRouterCompletionException) throw e
            val partialText = accumulatedText.joinToString("\n\n").trim()
            if (partialText.isNotBlank()) {
                throw OpenRouterCompletionException(
                    message = e.message ?: e.javaClass.simpleName,
                    partialText = partialText,
                    cause = e
                )
            }
            throw e
        }
    }

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}
