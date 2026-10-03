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

class OpenRouterChatClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    httpClientOverride: OkHttpClient? = null
) {
    private companion object {
        // Global safety ceilings for one OpenRouter task; per-request max_tokens and maxRounds remain separate.
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
        .readTimeout(300, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
        .build()

    fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        sessionId: String? = null,
        tools: JSONArray?,
        maxTokens: Int,
        maxRounds: Int = 24,
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
                throw budgetException("Лимит времени OpenRouter complete() (20 минут) исчерпан.")
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
                .put("max_tokens", maxTokens.coerceIn(256, 128000))
                .put("stream", false)
                .put("parallel_tool_calls", parallelToolCalls)
                .put("usage", JSONObject().put("include", true))
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
                throw budgetException("Лимит времени OpenRouter complete() (20 минут) исчерпан.")
            }
            call.timeout().timeout(minOf(600_000L, remainingMillis.coerceAtLeast(1L)), TimeUnit.MILLISECONDS)
            call.execute().use { httpResponse ->
                ensureActive()
                val raw = httpResponse.body?.string().orEmpty()
                if (!httpResponse.isSuccessful) {
                    throw IOException("OpenRouter HTTP ${httpResponse.code}: ${raw.take(1200)}")
                }
                val json = JSONObject(raw)
                json.optJSONObject("usage")?.let { usage ->
                    accumulateUsage(usage)
                    usage.optLong("total_tokens", 0L).takeIf { it > 0L }?.let { accumulatedTotalTokens += it }
                }
                val choice = json.optJSONArray("choices")?.optJSONObject(0)
                    ?: throw IOException("OpenRouter response has no choice")
                val message = choice.optJSONObject("message")
                    ?: throw IOException("OpenRouter response has no message")
                return json to JSONObject(message.toString())
            }
        }

        try {
            repeat(maxRounds) {
                ensureWithinBudget()
                val (json, message) = requestMessage("auto")
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

            ensureWithinBudget()
            history.put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Лимит вызовов инструментов исчерпан. Подведи итог по уже полученным данным.")
            )
            val (json, finalMessage) = requestMessage("none")
            return OpenRouterCompletionResult(
                text = finalMessage.optString("content").trim(),
                usage = if (accumulatedUsage.length() > 0) JSONObject(accumulatedUsage.toString()) else null,
                assistantMessage = JSONObject(finalMessage.toString()),
                toolNames = toolNames.distinct()
            )
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
