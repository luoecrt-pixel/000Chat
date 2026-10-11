package com.example.openaichat

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    val responseModel: String?,
    val assistantMessage: JSONObject,
    val toolNames: List<String>,
    val finishReason: String? = null,
    val protocol: JSONObject? = null
)

class OpenRouterCompletionException(
    message: String,
    val partialText: String,
    cause: Throwable? = null
) : IOException(message, cause)

class OpenRouterUpstreamException(
    message: String,
    val stage: String,
    val roundIndex: Int,
    val httpStatus: Int?,
    val errorType: String?,
    val providerName: String?,
    val providerCode: String?,
    val safeMetadata: String?
) : IOException(message)

private data class OpenRouterStreamToolCall(
    var id: String = "",
    var type: String = "function",
    var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

class OpenRouterChatClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    httpClientOverride: OkHttpClient? = null,
    internal val reconcileGenerations: Boolean = true
) {
    internal val metadataBaseUrl get() = (baseUrl.trimEnd('/') + "/").toHttpUrl()
    private companion object {
        // Safety ceiling for one OpenRouter task. Do not add smaller per-response
        // time/iteration caps here: this ceiling is the emergency stop.
        const val COMPLETE_TIME_LIMIT_MS = 5 * 60 * 60 * 1000L
        const val MAX_ACCUMULATED_TOTAL_TOKENS = 5_000_000L
    }

    private fun upstreamError(
        error: JSONObject, stage: String, roundIndex: Int, status: Int? = null
    ): OpenRouterUpstreamException {
        val metadata = error.optJSONObject("metadata")
        fun scalar(o: JSONObject?, key: String): String? = when (val value = o?.opt(key)) {
            is String -> value.takeIf { it.isNotBlank() }; is Number -> value.toString(); else -> null
        }
        val provider = scalar(metadata, "provider_name") ?: scalar(error, "provider_name")
        val raw = metadata?.opt("raw")
        val providerError = (if (raw is JSONObject) raw else if (raw is String && raw.length <= 32_000)
            try { JSONObject(raw) } catch (_: Exception) { null } else null)?.optJSONObject("error")
        val providerCode = scalar(metadata, "provider_code") ?: scalar(error, "provider_code") ?: scalar(providerError, "code")
        val code = scalar(error, "code")
        val errorType = scalar(error, "type") ?: code
        val message = JsonProtocol.nonBlank(error, "message")?.take(480) ?: "Unknown upstream error"
        val detail = JsonProtocol.nonBlank(providerError ?: JSONObject(), "message")?.take(300)
        // Never record a whole provider payload. The diagnostic logger redacts secrets.
        val safeMeta = metadata?.let {
            JSONObject().apply {
                listOf("provider_name", "provider_code").forEach { key ->
                    scalar(it, key)?.let { value -> put(key, value.take(180)) }
                }
                scalar(providerError, "type")?.let { put("upstream_type", it.take(100)) }
                scalar(providerError, "param")?.let { put("upstream_param", it.take(100)) }
                detail?.let { put("upstream_message", it) }
            }.toString().take(420)
        }
        return OpenRouterUpstreamException(
            "OpenRouter " + stage + " error: " + message + (detail?.let { " · $it" } ?: ""),
            stage, roundIndex, status ?: code?.toIntOrNull()?.takeIf { it in 400..599 }, errorType, provider, providerCode, safeMeta
        )
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
        shouldContinue: () -> Boolean = { true },
        onRoundObserved: (roundIndex: Int, generationId: String?, usage: JSONObject?) -> Unit = { _, _, _ -> },
        onTextDelta: (String) -> Unit = {},
        onPayloadObserved: (roundIndex: Int, jsonBytes: Int) -> Unit = { _, _ -> },
        cacheMode: String = "1h",
        onProtocolWarning: (String) -> Unit = {},
        onProtocolCheckpoint: (JSONObject) -> Unit = {},
        pdfEngine: String? = null,
        responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO,
        onFileIoObserved: (roundIndex: Int, stats: JSONObject) -> Unit = { _, _ -> }
    ): OpenRouterCompletionResult {
        // Resolve once before HTTP and all tool rounds; Activity already resolves auto.
        val requestEffort = OpenRouterReasoningPolicy.requestEffort(model, reasoningEffort)
        val history = JsonProtocol.arrayCopy(messages)
        val inputCount = history.length()
        val binding = OpenRouterProtocolCodec.binding(model, tools, apiKey, responseFormat, claudeRoute)
        val sourceInputHash = OpenRouterProtocolCodec.fingerprint(history)
        if (OpenRouterProtocolCodec.checkBinding(history, binding)) {
            onProtocolWarning("Изменён подписанный контекст Claude. История сообщений сохранена; прежние служебные блоки мышления нельзя повторно использовать с этим запросом.")
        }
        val inputHash = StreamingJsonRequestBody.sha256(history)
        val toolNames = mutableListOf<String>()
        val accumulatedText = mutableListOf<String>()
        val accumulatedUsage = JSONObject()
        // Cost is complete only if every generation reported its cost.
        var everyRoundCostKnown = true
        var responseModel: String? = null
        var accumulatedTotalTokens = 0L
        var nextRoundIndex = 0
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(COMPLETE_TIME_LIMIT_MS)

        fun addUsageLong(target: JSONObject, key: String, value: Long) {
            if (value <= 0L) return
            target.put(key, target.optLong(key, 0L) + value)
        }

        fun accumulateUsage(source: JSONObject?) {
            if (source == null) return
            addUsageLong(accumulatedUsage, "prompt_tokens", source.optLong("prompt_tokens", 0L))
            addUsageLong(accumulatedUsage, "completion_tokens", source.optLong("completion_tokens", 0L))
            addUsageLong(accumulatedUsage, "total_tokens", source.optLong("total_tokens", 0L))
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
                throw budgetException("Аварийный бюджет одного ответа OpenRouter (5 млн usage-токенов) исчерпан. Он суммирует вход каждого раунда, включая повторную историю и кэш, и выход с мышлением; история чата сохранена.")
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
            responseFormat?.let {
                body.put("response_format", JsonProtocol.objectCopy(it))
            }
            claudeRoute.provider(model, responseFormat != null)?.let { body.put("provider", it) }
            pdfEngine?.let { engine ->
                require(engine in setOf("native", "auto", "cloudflare-ai", "mistral-ocr"))
                if (engine != "auto") body.put("plugins", JSONArray().put(JSONObject()
                    .put("id", "file-parser").put("pdf", JSONObject().put("engine", engine))))
            }
            tools?.takeIf { it.length() > 0 }?.let {
                body.put("tools", it)
                body.put("tool_choice", toolChoice)
            }
            sessionId?.trim()?.takeIf { it.isNotBlank() }?.let {
                body.put("session_id", it)
            }
            OpenRouterCachePolicy.configureRequest(body, model, cacheMode)
            requestEffort?.let {
                body.put("reasoning", JSONObject().put("effort", it))
            }
            verbosity?.takeIf {
                it.isNotBlank() && OpenRouterReasoningPolicy.supportsResponseVerbosity(model)
            }?.let { body.put("verbosity", it) }

            val roundIndex = nextRoundIndex++
            onRoundObserved(roundIndex, null, null)
            val wireBody = StreamingJsonRequestBody(body)
            OpenRouterAttachmentPreflight.validateRequestBytes(model, wireBody.contentLength(), claudeRoute)
            onPayloadObserved(roundIndex, wireBody.contentLength().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Accept", "text/event-stream")
                .addHeader("HTTP-Referer", "com.example.openaichat")
                .addHeader("X-Title", "OpenAIApiChat")
                .post(wireBody)
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
            var sawDone = false
            var finishReason: String? = null
            var roundResponseModel: String? = null
            var roundReportedCost: Double? = null
            var roundUsage: JSONObject? = null
            var generationId: String? = null
            val contentParts = StringBuilder()
            var lastTextSnapshot = 0L
            var emittedTextLength = 0
            val reasoningDetails = ReasoningDetailAccumulator()
            val toolCallsByIndex = sortedMapOf<Int, OpenRouterStreamToolCall>()

            fun emitTextSnapshot(force: Boolean = false) {
                if (contentParts.length == emittedTextLength) return
                val now = System.nanoTime()
                if (!force && lastTextSnapshot != 0L && now - lastTextSnapshot < 75_000_000L) return
                lastTextSnapshot = now; emittedTextLength = contentParts.length
                onTextDelta((accumulatedText + contentParts.toString()).filter { it.isNotEmpty() }.joinToString("\n\n"))
            }

            fun consume(data: String) {
                if (data == "[DONE]") {
                    sawDone = true
                    return
                }
                val json = try {
                    JSONObject(data)
                } catch (e: Exception) {
                    throw IOException("Invalid OpenRouter SSE event", e)
                }
                json.optJSONObject("error")?.let { error ->
                    throw upstreamError(error, "SSE", roundIndex)
                }
                val candidateId = JsonProtocol.nonBlank(json, "id")?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
                if (candidateId != null && generationId == null) {
                    generationId = candidateId
                    onRoundObserved(roundIndex, candidateId, null)
                }
                JsonProtocol.nonBlank(json, "model")?.let { roundResponseModel = it }
                json.optJSONObject("usage")?.let { usage ->
                    // Repeated final usage frames are snapshots, not billable deltas.
                    roundUsage = JSONObject(usage.toString())
                    // Cost may arrive after finish_reason, just before [DONE].
                    // Explicit zero is known; missing/invalid values are not.
                    if (usage.has("cost")) {
                        roundReportedCost = OpenRouterUsageSnapshot.from(usage).reportedCostUsd
                    }
                    onRoundObserved(roundIndex, generationId, roundUsage)
                }
                val choices = json.optJSONArray("choices")
                if (choices == null || choices.length() == 0) return
                val choice = choices.optJSONObject(0) ?: return
                JsonProtocol.nonBlank(choice, "finish_reason")?.let { finishReason = it }
                val delta = choice.optJSONObject("delta") ?: return

                JsonProtocol.nonBlank(delta, "role")?.let { role = it }
                JsonProtocol.text(delta, "content")?.takeIf { it.isNotEmpty() }?.let { chunk ->
                    contentParts.append(chunk)
                    emitTextSnapshot()
                }

                delta.optJSONArray("reasoning_details")?.let(reasoningDetails::accept)

                delta.optJSONArray("tool_calls")?.let { calls ->
                    for (i in 0 until calls.length()) {
                        val call = calls.optJSONObject(i) ?: continue
                        val index = call.optInt("index", i)
                        val target = toolCallsByIndex.getOrPut(index) { OpenRouterStreamToolCall() }
                        JsonProtocol.nonBlank(call, "id")?.let { target.id = it }
                        JsonProtocol.nonBlank(call, "type")?.let { target.type = it }
                        call.optJSONObject("function")?.let { function ->
                            JsonProtocol.nonBlank(function, "name")?.let { target.name += it }
                            JsonProtocol.text(function, "arguments")?.takeIf { it.isNotEmpty() }?.let { target.arguments.append(it) }
                        }
                    }
                }
            }

            try {
                call.execute().use { httpResponse ->
                    ensureActive()
                    if (!httpResponse.isSuccessful) {
                        val raw = httpResponse.body?.string().orEmpty()
                        val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                        if (error != null) throw upstreamError(error, "HTTP", roundIndex, httpResponse.code)
                        throw IOException("OpenRouter HTTP ${httpResponse.code}: ${raw.take(320)}")
                    }
                    val bodyResponse = httpResponse.body ?: throw IOException("OpenRouter response has no body")
                    val parser = SseEventParser()
                    bodyResponse.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            ensureWithinBudget()
                            parser.acceptLine(line)?.let { event -> consume(event.data) }
                            if (sawDone) break
                        }
                    }
                    if (!sawDone) parser.finish()?.let { event -> consume(event.data) }
                    if (!sawDone) throw IOException("OpenRouter SSE ended before [DONE]")
                }
            } catch (e: Exception) {
                emitTextSnapshot(force = true)
                if (e is CancellationException) throw e
                if (e is OpenRouterCompletionException) {
                    val partial = buildList {
                        addAll(accumulatedText)
                        if (contentParts.isNotEmpty()) add(contentParts.toString())
                    }.joinToString("\n\n").trim()
                    throw OpenRouterCompletionException(
                        message = e.message ?: e.javaClass.simpleName,
                        partialText = partial,
                        cause = e.cause ?: e
                    )
                }
                if (contentParts.isNotEmpty()) {
                    accumulatedText += contentParts.toString()
                }
                throw e
            } finally {
                // Optional diagnostics must not replace the result, cancellation or upstream error.
                try {
                    val stats = FileBackedIoTelemetry.snapshot(body)
                    onFileIoObserved(roundIndex, JSONObject().put("files", stats.files)
                        .put("source_read_passes", stats.readPasses).put("source_read_bytes", stats.readBytes)
                        .put("file_work_ms", stats.workNanos / 1_000_000L))
                } catch (_: Exception) {
                    // Diagnostics are best-effort. Never swallow VM/heap Errors.
                }
            }

            emitTextSnapshot(force = true)
            ensureActive()
            accumulateUsage(roundUsage)
            roundUsage?.let { u ->
                val total = u.optLong("total_tokens", 0L).takeIf { it > 0L }
                    ?: (u.optLong("prompt_tokens", 0L).coerceAtLeast(0L) + u.optLong("completion_tokens", 0L).coerceAtLeast(0L))
                accumulatedTotalTokens += total
            }
            val completedRoundCost = roundReportedCost
            if (completedRoundCost == null) {
                everyRoundCostKnown = false
            } else {
                accumulatedUsage.put("cost", accumulatedUsage.optDouble("cost", 0.0) + completedRoundCost)
            }
            val message = JSONObject()
                .put("role", role)
                .put("content", contentParts.toString())
            val completeDetails = reasoningDetails.array()
            if (completeDetails.length() > 0) {
                message.put("reasoning_details", completeDetails)
            }
            if (toolCallsByIndex.isNotEmpty()) {
                val toolCalls = JSONArray()
                toolCallsByIndex.forEach { (_, call) ->
                    if (finishReason in setOf("tool_calls", "stop") && (call.id.isBlank() || call.name.isBlank())) {
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
            return JSONObject()
                .put("stream", true)
                .put("finish_reason", finishReason ?: JSONObject.NULL)
                .put("model", roundResponseModel ?: JSONObject.NULL) to message
        }
        try {
            while (true) {
                ensureWithinBudget()
                val (responseJson, message) = requestMessage("auto")
                JsonProtocol.nonBlank(responseJson, "model")?.let { responseModel = it }
                val finishReason = JsonProtocol.nonBlank(responseJson, "finish_reason")
                val calls = message.optJSONArray("tool_calls")
                // A complete SSE [DONE] frame may omit finish_reason even after a
                // fully formed tool call. The per-tool id/name/JSON checks below
                // still reject incomplete or malformed tool execution requests.
                if (calls == null || calls.length() == 0 ||
                    finishReason != null && finishReason !in setOf("tool_calls", "stop")) {
                    val tail = JSONArray()
                    for (i in inputCount until history.length()) tail.put(JsonProtocol.copy(history.get(i)))
                    val replayMessage = JsonProtocol.objectCopy(message)
                    if (finishReason != "stop") {
                        replayMessage.remove("tool_calls"); replayMessage.remove("reasoning_details"); replayMessage.remove("reasoning")
                    }
                    tail.put(replayMessage)
                    return OpenRouterCompletionResult(
                        text = (accumulatedText + JsonProtocol.string(message, "content"))
                            .filter { it.isNotBlank() }.joinToString("\n\n").trim(),
                        usage = JSONObject(accumulatedUsage.toString()).apply {
                            // Never present a tool-loop subtotal as a complete charge.
                            if (!everyRoundCostKnown) remove("cost")
                        }.takeIf { it.length() > 0 },
                        responseModel = responseModel,
                        assistantMessage = JSONObject(message.toString()),
                        toolNames = toolNames.distinct(),
                        finishReason = finishReason,
                        protocol = JSONObject().put("version", 1).put("input_sha256", sourceInputHash)
                            .put("wire_input_sha256", inputHash)
                            .put("binding_sha256", binding).put("tail", tail).apply {
                                if (finishReason != "stop") put("interrupted_assistant", JsonProtocol.objectCopy(message))
                            }
                    )
                }
                JsonProtocol.string(message, "content").trim().takeIf { it.isNotBlank() }?.let { accumulatedText += it }
                history.put(JSONObject(message.toString()))
                for (i in 0 until calls.length()) {
                    ensureWithinBudget()
                    val callObject = calls.optJSONObject(i) ?: continue
                    val function = callObject.optJSONObject("function")
                        ?: throw IOException("Malformed tool call")
                    val name = function.optString("name").trim()
                    val id = callObject.optString("id").trim()
                    if (name.isBlank() || id.isBlank()) throw IOException("Malformed tool call id/name")
                    if (name.startsWith("openrouter:")) {
                        throw IOException("Unexpected OpenRouter server-side tool call: $name")
                    }
                    val arguments = try {
                        JSONObject(JsonProtocol.string(function, "arguments", "{}"))
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
                val checkpointTail = JSONArray()
                for (i in inputCount until history.length()) checkpointTail.put(JsonProtocol.copy(history.get(i)))
                onProtocolCheckpoint(JSONObject().put("version", 1).put("input_sha256", sourceInputHash)
                    .put("wire_input_sha256", inputHash).put("binding_sha256", binding).put("tail", checkpointTail))
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
