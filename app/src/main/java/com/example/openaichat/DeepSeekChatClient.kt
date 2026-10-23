package com.example.openaichat

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private data class DeepSeekStreamToolCall(
    var id: String = "", var type: String? = null, var name: String = "",
    val arguments: StringBuilder = StringBuilder()
)

/** Direct DeepSeek inference. A failed/interrupted request never starts another paid POST. */
class DeepSeekChatClient(
    private val baseUrl: String = "https://api.deepseek.com",
    httpClientOverride: OkHttpClient? = null,
    internal val sseLimits: BoundedResponseDecoder.SseLimits = BoundedResponseDecoder.SseLimits(
        maxLineChars = 2 * 1024 * 1024, maxEventChars = 2 * 1024 * 1024,
        maxStreamChars = 16L * 1024 * 1024, maxBodyBytes = 32L * 1024 * 1024),
    internal val maxAccumulatedChars: Int = 4 * 1024 * 1024,
    internal val maxInlineToolResultBytes: Long = 512L * 1024,
    internal val maxCheckpointBytes: Long = 8L * 1024 * 1024
) : ChatCompletionTransport {
    override val provider: String = "deepseek"
    private val flushers = ConcurrentHashMap<String, (Boolean) -> Unit>()
    private val httpClient = httpClientOverride ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS).build()

    init { require(maxAccumulatedChars > 0 && maxInlineToolResultBytes > 0 && maxCheckpointBytes > 0) }

    override fun flushCheckpoint(sessionId: String) { flushers[sessionId]?.invoke(false) }
    override fun freezeAndFlushCheckpoint(sessionId: String) { flushers[sessionId]?.invoke(true) }

    override fun complete(
        apiKey: String, model: String, messages: JSONArray, sessionId: String?, tools: JSONArray?,
        maxTokens: Int?, reasoningEffort: String?, verbosity: String?, parallelToolCalls: Boolean,
        executeTool: (name: String, arguments: JSONObject) -> String,
        onCallCreated: (Call) -> Unit, shouldContinue: () -> Boolean,
        onRoundObserved: (roundIndex: Int, generationId: String?, usage: JSONObject?) -> Unit,
        onTextDelta: (String) -> Unit, onPayloadObserved: (roundIndex: Int, jsonBytes: Int) -> Unit,
        cacheMode: String, onProtocolWarning: (String) -> Unit, onProtocolCheckpoint: (JSONObject) -> Unit,
        pdfEngine: String?, responseFormat: JSONObject?, claudeRoute: OpenRouterClaudeRoute,
        onFileIoObserved: (roundIndex: Int, stats: JSONObject) -> Unit,
        onReasoningDelta: (String) -> Unit
    ): OpenRouterCompletionResult {
        require(apiKey.isNotBlank()) { "DeepSeek API key is required" }
        DeepSeekContextSnapshot.validateMaxTokens(maxTokens)
        val format = DeepSeekContextSnapshot.validateResponseFormat(responseFormat)
        val thinking = DeepSeekContextSnapshot.resolveThinking(reasoningEffort)
        val cleanModel = model.trim().removePrefix("~")
        require(cleanModel.isNotBlank()) { "DeepSeek model is required" }
        val history = JsonProtocol.arrayCopy(messages)
        val binding = DeepSeekProtocolCodec.binding(cleanModel, tools, apiKey, format)
        if (DeepSeekProtocolCodec.checkBinding(history, binding)) onProtocolWarning(
            "Изменились модель, инструменты, ключ, формат или история. Сохранён видимый текст; прежний протокол DeepSeek не используется для exact replay.")
        val inputCount = history.length()
        val inputHash = DeepSeekProtocolCodec.fingerprint(history)
        val allowedTools = mutableSetOf<String>()
        tools?.let { catalog ->
            for (i in 0 until catalog.length()) {
                val tool = catalog.optJSONObject(i) ?: throw IOException("Некорректный каталог инструментов DeepSeek")
                if (JsonProtocol.text(tool, "type") != "function") throw IOException("DeepSeek принимает только локальные function tools")
                val name = tool.optJSONObject("function")?.let { JsonProtocol.nonBlank(it, "name") }
                    ?: throw IOException("Инструмент DeepSeek не имеет имени")
                if (!allowedTools.add(name)) throw IOException("Повторное имя инструмента DeepSeek: $name")
            }
        }
        val lock = Any()
        val frozen = AtomicBoolean(false)
        var frozenCheckpoint: JSONObject? = null
        val textRounds = mutableListOf<String>()
        val reasoningRounds = mutableListOf<String>()
        val roundUsage = mutableListOf<JSONObject?>()
        val executions = JSONArray()
        val toolNames = mutableListOf<String>()
        var responseModel: String? = null
        var uncommitted: (() -> JSONObject?)? = null
        var roundsCompleted = 0
        var accumulatedChars = 0L
        var lastCheckpointNanos = 0L
        var fileIo: FileBackedIoTelemetry.Snapshot? = null
        val deadline = System.nanoTime() + TimeUnit.HOURS.toNanos(5)

        fun strictCount(source: JSONObject?, key: String): Long? = when (val value = source?.opt(key)) {
            is Int -> value.toLong().takeIf { it >= 0 }
            is Long -> value.takeIf { it >= 0 }
            else -> null
        }
        fun normalizedUsage(raw: JSONObject): JSONObject = JSONObject().apply {
            for (key in listOf("prompt_tokens", "completion_tokens", "total_tokens")) strictCount(raw, key)?.let { put(key, it) }
            val cached = strictCount(raw.optJSONObject("prompt_tokens_details"), "cached_tokens")
                ?: strictCount(raw, "prompt_cache_hit_tokens")
            cached?.let { put("prompt_tokens_details", JSONObject().put("cached_tokens", it)) }
            strictCount(raw.optJSONObject("completion_tokens_details"), "reasoning_tokens")?.let {
                put("completion_tokens_details", JSONObject().put("reasoning_tokens", it))
            }
        }
        fun completeUsage(usage: JSONObject?) = strictCount(usage, "prompt_tokens") != null && strictCount(usage, "completion_tokens") != null
        fun usageSnapshot(): JSONObject? {
            if (roundUsage.isEmpty()) return null
            val complete = roundUsage.all(::completeUsage)
            val observed = OpenRouterUsageAccumulator.aggregate(roundUsage)?.apply { remove("cost") }
            return (if (complete) observed ?: JSONObject() else JSONObject()).apply {
                put("usage_complete", complete)
                put("rounds_with_usage", roundUsage.count(::completeUsage)); put("rounds_total", roundUsage.size)
                if (!complete && observed != null) put("observed_partial_usage", observed)
                if (!complete) put("observed_rounds", JSONArray().apply {
                    roundUsage.forEachIndexed { index, usage ->
                        put(JSONObject().put("round_index", index).put("usage_complete", completeUsage(usage)).apply {
                            usage?.let { put("usage", JsonProtocol.objectCopy(it)) }
                        })
                    }
                })
            }
        }
        fun ensureActive() {
            if (frozen.get() || !shouldContinue() || Thread.currentThread().isInterrupted) throw CancellationException("DeepSeek request is no longer current")
        }
        fun ensureBudget() {
            ensureActive()
            if (System.nanoTime() >= deadline) throw IOException("Лимит времени DeepSeek (5 часов) исчерпан")
            val total = roundUsage.fold(0L) { sum, usage ->
                val tokens = strictCount(usage, "total_tokens") ?: ((strictCount(usage, "prompt_tokens") ?: 0L) +
                    (strictCount(usage, "completion_tokens") ?: 0L)).coerceAtLeast(0L)
                if (tokens > Long.MAX_VALUE - sum) Long.MAX_VALUE else sum + tokens
            }
            if (total >= 5_000_000L) throw IOException("Аварийный бюджет одного ответа DeepSeek (5 млн reported usage-токенов) исчерпан; история сохранена")
        }
        fun addChars(count: Int) {
            if (accumulatedChars + count > maxAccumulatedChars) throw ResponsesSseLimitException(
                "Накопленный ответ DeepSeek превысил безопасный лимит памяти ($maxAccumulatedChars символов)")
            accumulatedChars += count
        }
        fun candidateCheckpoint(pending: JSONObject? = uncommitted?.invoke(), extra: JSONObject? = null): JSONObject {
            // This is a shallow view. Measure before copying JSON trees or serializing text.
            val tail = JSONArray()
            for (i in inputCount until history.length()) tail.put(history.get(i))
            extra?.let(tail::put)
            return JSONObject().put("version", 1).put("replayable", false).put("thinking_enabled", thinking.enabled)
                .put("input_sha256", inputHash).put("wire_input_sha256", inputHash).put("binding_sha256", binding)
                .put("tail", tail).put("tool_execution", executions).apply {
                    pending?.let { put("uncommitted_assistant", it) }
                    usageSnapshot()?.let { put("usage", it) }
                }
        }
        fun checkCheckpoint(checkpoint: JSONObject) {
            if (StreamingJsonRequestBody.countBytes(checkpoint) > maxCheckpointBytes) throw IOException(
                "Checkpoint DeepSeek превышает безопасный лимит $maxCheckpointBytes байт; сохранён последний подтверждённый префикс, инструменты автоматически не повторяются")
        }
        fun publish(force: Boolean = true) = synchronized(lock) {
            if (frozen.get()) return@synchronized
            val now = System.nanoTime()
            if (!force && lastCheckpointNanos != 0L && now - lastCheckpointNanos < TimeUnit.SECONDS.toNanos(2)) return@synchronized
            val checkpoint = candidateCheckpoint()
            checkCheckpoint(checkpoint)
            onProtocolCheckpoint(JsonProtocol.objectCopy(checkpoint))
            lastCheckpointNanos = now
        }
        val flusher: (Boolean) -> Unit = { freeze ->
            synchronized(lock) {
                if (freeze) {
                    // The same lock covers delta mutation and snapshot creation: no later frame
                    // can slip between this durable prefix and Stop's ownership transaction.
                    frozen.set(true)
                    val snapshot = frozenCheckpoint ?: candidateCheckpoint().also {
                        checkCheckpoint(it)
                    }.let(JsonProtocol::objectCopy).also { frozenCheckpoint = it }
                    // Repeated Stop/cancel calls reuse the exact frozen source, even if an
                    // already started external operation reports an outcome afterwards.
                    onProtocolCheckpoint(JsonProtocol.objectCopy(snapshot))
                } else publish()
            }
        }
        if (sessionId != null) check(flushers.putIfAbsent(sessionId, flusher) == null) { "DeepSeek session is already active" }

        fun requestRound(): Pair<JSONObject, String?> {
            ensureBudget()
            val body = JSONObject().put("model", cleanModel).put("messages", history).put("stream", true)
                .put("stream_options", JSONObject().put("include_usage", true))
                .put("thinking", JSONObject().put("type", if (thinking.enabled) "enabled" else "disabled"))
            thinking.effort?.let { body.put("reasoning_effort", it) }
            maxTokens?.let { body.put("max_tokens", it) }
            format?.let { body.put("response_format", it) }
            tools?.takeIf { it.length() > 0 }?.let { body.put("tools", it) }
            val wireBody = StreamingJsonRequestBody(body, oneShot = true)
            DeepSeekContextSnapshot.validateRequestBytes(wireBody.contentLength())
            val index = synchronized(lock) { roundUsage.add(null); roundUsage.lastIndex }
            onRoundObserved(index, null, null)
            onPayloadObserved(index, wireBody.contentLength().coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            ensureActive()
            val call = httpClient.newCall(Request.Builder().url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey").header("Content-Type", "application/json")
                .header("Accept", "text/event-stream").post(wireBody).build())
            onCallCreated(call)
            if (!shouldContinue()) { call.cancel(); ensureActive() }
            val remaining = TimeUnit.NANOSECONDS.toMillis((deadline - System.nanoTime()).coerceAtLeast(0L))
            if (remaining <= 0L) { call.cancel(); throw IOException("Лимит времени DeepSeek (5 часов) исчерпан") }
            call.timeout().timeout(remaining.coerceAtLeast(1), TimeUnit.MILLISECONDS)
            val content = StringBuilder()
            val reasoning = StringBuilder()
            val calls = sortedMapOf<Int, DeepSeekStreamToolCall>()
            var sawReasoning = false
            var finish: String? = null
            var done = false
            var lastText = 0L
            var lastReasoning = 0L
            var emittedText = 0
            var emittedReasoning = 0
            val prefixText = if (textRounds.isEmpty()) "" else textRounds.joinToString("\n\n") + "\n\n"
            val prefixReasoning = if (reasoningRounds.isEmpty()) "" else reasoningRounds.joinToString("\n\n") + "\n\n"
            fun assistant(): JSONObject = JSONObject().put("role", "assistant").put("content", content.toString()).apply {
                if (sawReasoning) put("reasoning_content", reasoning.toString())
                if (calls.isNotEmpty()) put("tool_calls", JSONArray().apply {
                    calls.forEach { (_, call) -> put(JSONObject().put("id", call.id).put("type", call.type ?: JSONObject.NULL)
                        .put("function", JSONObject().put("name", call.name).put("arguments", call.arguments.toString()))) }
                })
            }
            fun emit(force: Boolean = false) {
                val now = System.nanoTime()
                if (content.length != emittedText && (force || lastText == 0L || now - lastText >= 75_000_000L)) {
                    emittedText = content.length; lastText = now; onTextDelta(prefixText + content.toString())
                }
                if (reasoning.length != emittedReasoning && (force || lastReasoning == 0L || now - lastReasoning >= 120_000_000L)) {
                    emittedReasoning = reasoning.length; lastReasoning = now; onReasoningDelta(prefixReasoning + reasoning.toString())
                }
            }
            synchronized(lock) { uncommitted = { assistant().takeIf { content.isNotEmpty() || sawReasoning || calls.isNotEmpty() } } }
            fun consume(data: String) = synchronized(lock) {
                ensureActive()
                if (data == "[DONE]") { done = true; return@synchronized }
                // The decoder bounds raw frame allocation; accumulated builders have a separate budget.
                val json = try { JSONObject(data) } catch (e: Exception) { throw IOException("Invalid DeepSeek SSE event", e) }
                json.optJSONObject("error")?.let { throw OpenRouterErrorClassifier.classify(it, "SSE", index) }
                JsonProtocol.nonBlank(json, "model")?.let { responseModel = it }
                json.optJSONObject("usage")?.let { raw ->
                    val normalized = normalizedUsage(raw)
                    roundUsage[index] = OpenRouterUsageAccumulator.mergeSnapshot(roundUsage[index], normalized)
                    onRoundObserved(index, null, roundUsage[index])
                }
                val choices = json.optJSONArray("choices") ?: return@synchronized
                if (choices.length() == 0) return@synchronized
                if (choices.length() != 1) throw IOException("DeepSeek returned multiple choices for a single response")
                val choice = choices.optJSONObject(0) ?: throw IOException("Invalid DeepSeek choice")
                JsonProtocol.nonBlank(choice, "finish_reason")?.let {
                    if (finish != null && finish != it) throw IOException("Conflicting DeepSeek finish_reason")
                    finish = it
                }
                val delta = choice.optJSONObject("delta") ?: return@synchronized
                JsonProtocol.nonBlank(delta, "role")?.let { if (it != "assistant") throw IOException("Invalid DeepSeek delta role") }
                JsonProtocol.text(delta, "reasoning_content")?.let { chunk -> sawReasoning = true; addChars(chunk.length); reasoning.append(chunk) }
                JsonProtocol.text(delta, "content")?.let { chunk -> addChars(chunk.length); content.append(chunk) }
                delta.optJSONArray("tool_calls")?.let { incoming ->
                    for (i in 0 until incoming.length()) {
                        val call = incoming.optJSONObject(i) ?: throw IOException("Invalid streamed tool call")
                        val rawIndex = call.opt("index")
                        val callIndex = when (rawIndex) {
                            is Int -> rawIndex.takeIf { it >= 0 }
                            is Long -> rawIndex.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt()
                            else -> null
                        } ?: throw IOException("DeepSeek tool_calls.index must be a non-negative JSON integer")
                        val target = calls[callIndex] ?: DeepSeekStreamToolCall().also { addChars(64); calls[callIndex] = it }
                        if (call.has("id")) {
                            val id = JsonProtocol.nonBlank(call, "id") ?: throw IOException("Invalid DeepSeek tool call id")
                            if (target.id.isNotEmpty() && target.id != id) throw IOException("Conflicting tool call id at index $callIndex")
                            if (target.id.isEmpty()) { addChars(id.length); target.id = id }
                        }
                        if (call.has("type")) {
                            val type = JsonProtocol.text(call, "type")
                            if (type != "function") throw IOException("Unsupported DeepSeek tool call type")
                            target.type = type
                        }
                        call.optJSONObject("function")?.let { function ->
                            JsonProtocol.text(function, "name")?.let { chunk -> addChars(chunk.length); target.name += chunk }
                            JsonProtocol.text(function, "arguments")?.let { chunk ->
                                if (target.arguments.length.toLong() + chunk.length > 256L * 1024) throw IOException("DeepSeek tool arguments exceed 256 Ki characters")
                                addChars(chunk.length); target.arguments.append(chunk)
                            }
                        }
                    }
                }
                emit()
                // Tool-only streams also advance the durable checkpoint. The first fragment is immediate.
                if (content.isNotEmpty() || sawReasoning || calls.isNotEmpty()) publish(force = false)
            }
            try {
                call.execute().use { response ->
                    ensureActive()
                    if (!response.isSuccessful) {
                        val raw = BoundedResponseDecoder.readErrorBody(response.body)
                        val error = runCatching { JSONObject(raw).optJSONObject("error") }.getOrNull()
                            ?: JSONObject().put("message", "HTTP ${response.code}: ${raw.take(320)}")
                        throw OpenRouterErrorClassifier.classify(error, "HTTP", index, response.code, response.header("Retry-After"))
                    }
                    val responseBody = response.body ?: throw IOException("DeepSeek response has no body")
                    BoundedResponseDecoder.openSse(responseBody.byteStream(), sseLimits).use { reader ->
                        BoundedResponseDecoder.readSse(reader, sseLimits, onLine = { ensureBudget() }) { event -> consume(event.data); done }
                    }
                    if (!done) throw IOException("DeepSeek SSE ended before [DONE]")
                }
                synchronized(lock) {
                    ensureActive()
                    emit(force = true)
                    publish()
                    roundsCompleted++
                    if (reasoning.isNotEmpty()) reasoningRounds += reasoning.toString()
                    return assistant() to finish
                }
            } catch (failure: Exception) {
                synchronized(lock) {
                    emit(force = true)
                    try { publish() } catch (checkpointFailure: Exception) { failure.addSuppressed(checkpointFailure) }
                    if (content.isNotEmpty()) textRounds += content.toString()
                    if (reasoning.isNotEmpty()) reasoningRounds += reasoning.toString()
                }
                throw failure
            } finally {
                try {
                    val stats = FileBackedIoTelemetry.snapshot(body)
                    val delta = stats.deltaFrom(fileIo); fileIo = stats
                    onFileIoObserved(index, stats.toJson().put("round_read_passes", delta.readPasses)
                        .put("round_read_bytes", delta.readBytes).put("round_work_ms", delta.workMs))
                } catch (_: Exception) { /* File diagnostics cannot replace protocol failure. */ }
            }
        }

        try {
            while (true) {
                val (message, finish) = requestRound()
                ensureBudget()
                val calls = message.optJSONArray("tool_calls")
                val hasCalls = calls != null && calls.length() > 0
                val currentUsageComplete = synchronized(lock) { completeUsage(roundUsage.lastOrNull()) }
                val hasReasoning = !thinking.enabled || JsonProtocol.text(message, "reasoning_content") != null
                if (!hasCalls || finish != "tool_calls" || !currentUsageComplete || !hasReasoning) {
                    val generationComplete = finish == "stop" && !hasCalls
                    val usage = synchronized(lock) { usageSnapshot() }
                    val usageComplete = usage?.optBoolean("usage_complete", false) == true
                    val effectiveFinish = when {
                        hasCalls && finish == "stop" -> "incomplete_tool_calls"
                        finish in setOf("stop", "tool_calls") && !usageComplete -> "missing_usage"
                        finish in setOf("stop", "tool_calls") && !hasReasoning -> "missing_reasoning_content"
                        else -> finish
                    }
                    val finalContent = (textRounds + JsonProtocol.string(message, "content")).filter { it.isNotEmpty() }.joinToString("\n\n")
                    if (format?.optString("type") == "json_object" && generationComplete &&
                        !strictObject(JsonProtocol.string(message, "content"))) throw IOException("DeepSeek вернул невалидный JSON-объект в режиме json_object")
                    val protocol = synchronized(lock) {
                        // Keep the in-flight final response available to a concurrent Stop.
                        // Only this result view substitutes its assistant into the raw tail.
                        val candidate = candidateCheckpoint(pending = null, extra = message)
                        checkCheckpoint(candidate)
                        JsonProtocol.objectCopy(candidate).put("generation_complete", generationComplete)
                            .put("usage_complete", usageComplete)
                    }
                    val replayable = generationComplete && usageComplete && hasReasoning &&
                        DeepSeekProtocolCodec.hasConfirmedToolChain(protocol.getJSONArray("tail"))
                    protocol.put("replayable", replayable)
                    // Preserve every original field even when replay is prohibited.
                    if (!replayable && hasCalls) protocol.put("interrupted_assistant", JsonProtocol.objectCopy(message))
                    val warning = when (effectiveFinish) {
                        "missing_usage" -> "Текст получен, но полный расход DeepSeek неизвестен; exact replay запрещён."
                        "missing_reasoning_content" -> "DeepSeek не передал обязательный reasoning_content при включённом thinking; exact replay запрещён."
                        "incomplete_tool_calls" -> "DeepSeek завершил ответ с неподтверждёнными вызовами инструментов; они не повторяются автоматически."
                        else -> null
                    }
                    warning?.let(onProtocolWarning)
                    return OpenRouterCompletionResult(finalContent, usage, responseModel,
                        JsonProtocol.objectCopy(message).apply {
                            reasoningRounds.takeIf { it.isNotEmpty() }?.let { put("reasoning_summary", it.joinToString("\n\n")) }
                        }, toolNames.distinct(), effectiveFinish, protocol,
                        fileIo?.takeIf { it.hasSourceWork }?.toJson(), roundsCompleted,
                        generationComplete, usageComplete, replayable, warning)
                }
                val validated = mutableListOf<Triple<String, String, JSONObject>>()
                val ids = mutableSetOf<String>()
                for (i in 0 until calls!!.length()) {
                    val call = calls.getJSONObject(i)
                    if (JsonProtocol.text(call, "type") != "function") throw IOException("Missing/unsupported DeepSeek tool call type")
                    val id = JsonProtocol.nonBlank(call, "id") ?: throw IOException("Missing DeepSeek tool call id")
                    val fn = call.optJSONObject("function") ?: throw IOException("Missing DeepSeek tool function")
                    val name = JsonProtocol.nonBlank(fn, "name") ?: throw IOException("Missing DeepSeek tool name")
                    if (!ids.add(id)) throw IOException("Duplicate DeepSeek tool call id")
                    if (name !in allowedTools) throw IOException("DeepSeek tool is outside enabled catalog: $name")
                    val raw = JsonProtocol.text(fn, "arguments") ?: throw IOException("Missing DeepSeek tool arguments")
                    if (!strictObject(raw)) throw IOException("Invalid JSON object arguments for $name")
                    validated += Triple(id, name, JSONObject(raw))
                }
                synchronized(lock) {
                    ensureActive()
                    uncommitted = null
                    checkCheckpoint(candidateCheckpoint(extra = message))
                    JsonProtocol.string(message, "content").takeIf { it.isNotEmpty() }?.let(textRounds::add)
                    history.put(JsonProtocol.objectCopy(message))
                    validated.forEach { (id, _, _) -> executions.put(JSONObject().put("call_id", id).put("state", "not_started")) }
                    publish()
                }
                for ((id, name, args) in validated) {
                    ensureBudget()
                    val execution = synchronized(lock) {
                        ensureActive()
                        (0 until executions.length()).map { executions.getJSONObject(it) }.last { it.optString("call_id") == id }
                            .apply { put("state", "in_flight") }.also { publish() }
                    }
                    toolNames += name
                    val result = try { executeTool(name, args) } catch (failure: Exception) {
                        synchronized(lock) {
                            if (!frozen.get()) {
                                execution.put("state", "outcome_unknown")
                                try { publish() } catch (cp: Exception) { failure.addSuppressed(cp) }
                            }
                        }
                        throw failure
                    }
                    synchronized(lock) {
                        // Stop may have frozen an in-flight operation. Its outcome remains unknown
                        // in that immutable snapshot; runtime stores any late confirmed artifact separately.
                        ensureActive()
                        // The operation has happened. Never label it cancelled or delete its artifacts.
                        execution.put("state", "completed")
                        val bytes = StreamingJsonRequestBody.countBytes(result)
                        if (bytes > maxInlineToolResultBytes) {
                            execution.put("state", "completed_result_not_inline").put("result_json_bytes", bytes)
                                .put("result_sha256", StreamingJsonRequestBody.sha256(result))
                            publish()
                            throw IOException("Результат инструмента превышает inline-лимит $maxInlineToolResultBytes байт. Операция выполнена; файлы сохранены, автоматического повтора нет.")
                        }
                        val tool = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", result)
                        try { checkCheckpoint(candidateCheckpoint(extra = tool)) } catch (limit: IOException) {
                            execution.put("state", "completed_result_not_inline").put("result_json_bytes", bytes)
                                .put("result_sha256", StreamingJsonRequestBody.sha256(result))
                            try { publish() } catch (cp: Exception) { limit.addSuppressed(cp) }
                            throw limit
                        }
                        history.put(tool)
                        publish()
                    }
                    ensureBudget()
                }
            }
        } catch (failure: Exception) {
            val usage = synchronized(lock) { usageSnapshot() }
            if (failure is CancellationException) throw failure
            throw OpenRouterCompletionException(failure.message ?: failure.javaClass.simpleName,
                textRounds.joinToString("\n\n"), failure, usage,
                fileIo?.takeIf { it.hasSourceWork }?.toJson(), roundsCompleted)
        } finally {
            if (sessionId != null) flushers.remove(sessionId, flusher)
        }
    }

    private fun strictObject(raw: String) = raw.trimStart().startsWith("{") && StrictJsonValue.isObjectOrArray(raw)

    override fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        httpClient.cache?.close()
    }
}
