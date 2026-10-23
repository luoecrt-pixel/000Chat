package com.example.openaichat

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import okhttp3.Call
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class OpenRouterRuntimeRequest(
    val chatId: String, val requestId: String, val model: String, val messages: JSONArray,
    val attachments: List<OpenRouterLocalAttachment>, val effort: String, val verbosity: String,
    val maxTokens: Int?, val github: Boolean, val githubRepo: String, val githubBranches: Set<String>,
    val webSearch: Boolean, val webFetch: Boolean, val parallel: Boolean, val cacheMode: String,
    val background: Boolean, val startedAt: Long, val taskClass: String, val effortPref: String,
    val autoSelected: Boolean, val promptChars: Int,
    val apiKey: String, val metadataClient: OkHttpClient, val pdfEngine: String,
    val responseFormat: JSONObject? = null,
    val claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO,
    val provider: String = "openrouter"
) {
    override fun toString() = "OpenRouterRuntimeRequest(provider=$provider, chatId=$chatId, requestId=$requestId, model=$model, background=$background)"
}

/** Application-owned requests. Lifecycle visibility is not request ownership. */
object OpenRouterResponseRuntime {
    private class Work(val context: Context, val request: OpenRouterRuntimeRequest, val client: ChatCompletionTransport) {
        val launched = AtomicBoolean(false)
        val cancelled = AtomicBoolean(false)
        val stopping = AtomicBoolean(false)
        val stopLock = Any()
        var stopPrepared = false
        var stopReservesTerminal = false
        var stopWarning: String? = null
        val runtimeAttachments = java.util.concurrent.CopyOnWriteArrayList<OpenRouterLocalAttachment>()
        @Volatile var call: Call? = null
        @Volatile var cancelReason = "Соединение прервано"
        @Volatile var partialReasoning = ""
    }
    private val work = ConcurrentHashMap<String, Work>()
    private val executor = Executors.newCachedThreadPool()
    fun contains(requestId: String) = work.containsKey(requestId)
    fun uses(client: ChatCompletionTransport) = work.values.any { it.client === client }
    fun count() = work.size
    fun activeReasoning(requestId: String) = work[requestId]?.partialReasoning.orEmpty()
    fun retainClient(client: ChatCompletionTransport) = ChatCompletionClientOwners.retain(client)
    fun releaseClient(client: ChatCompletionTransport) = ChatCompletionClientOwners.release(client)
    fun flushCheckpoint(requestId: String) {
        val item = work[requestId] ?: return
        if (item.request.provider != "deepseek") return
        ChatDatabase(item.context).use { db ->
            // Stop may already have atomically archived and consumed the owner.
            // A second cancellation cannot write back into that closed request.
            if (!db.isActiveRequest(item.request.chatId, requestId)) return
            try {
                item.client.flushCheckpoint(requestId)
            } catch (lostOwner: java.util.concurrent.CancellationException) {
                if (db.isActiveRequest(item.request.chatId, requestId)) throw lostOwner
            }
        }
    }
    /** Freeze stream/tool admission and persist its final cursor before SQLite Stop consumes ownership. */
    fun prepareStop(requestId: String, reserveTerminal: Boolean = true): String? {
        val item = work[requestId] ?: return null
        return synchronized(item.stopLock) {
            item.stopReservesTerminal = item.stopReservesTerminal || reserveTerminal
            if (item.stopPrepared) return@synchronized item.stopWarning
            item.stopping.set(true)
            try {
                if (item.request.provider == "deepseek") ChatDatabase(item.context).use { db ->
                    if (db.isActiveRequest(item.request.chatId, requestId)) {
                        item.client.freezeAndFlushCheckpoint(requestId)
                    }
                }
            } catch (failure: Exception) {
                // Stop can still archive the last acknowledged SQLite prefix. Do not
                // claim that the newer in-memory suffix was saved or retry any effect.
                item.stopWarning = "Не удалось сохранить последний фрагмент: " +
                    (failure.message ?: failure.javaClass.simpleName).take(240) +
                    ". В архиве сохранён последний подтверждённый checkpoint; автоматического повтора нет."
            } finally {
                item.stopPrepared = true
                item.call?.cancel()
                OpenRouterUiBridge.cancelReasoning(item.request.chatId, requestId)
            }
            item.stopWarning
        }
    }
    fun localFilePaths(): Set<String> = work.values.flatMap { w ->
        (w.request.attachments + w.runtimeAttachments).flatMap { listOfNotNull(it.path, it.textPath, it.wirePath) }
    }.toSet()
    fun chatFor(requestId: String) = work[requestId]?.request?.chatId
    fun cancelForChat(chatId: String, reason: String = "Остановлено пользователем") {
        if (chatId.isBlank()) return
        work.values.filter { it.request.chatId == chatId }.forEach { cancel(it.request.requestId, reason) }
    }
    fun start(context: Context, request: OpenRouterRuntimeRequest, client: ChatCompletionTransport) {
        require(request.provider in setOf("openrouter", "deepseek")) { "Unsupported runtime provider: ${request.provider}" }
        require(request.provider == client.provider) { "Provider/transport mismatch: ${request.provider}/${client.provider}" }
        val item = Work(context.applicationContext, request, client)
        retainClient(client)
        if (work.putIfAbsent(request.requestId, item) != null) {
            releaseClient(client)
            error("Request is already running")
        }
        OpenRouterUiBridge.beginReasoning(request.chatId, request.requestId)
        try {
            if (request.background) ContextCompat.startForegroundService(item.context,
                Intent(item.context, OpenRouterResponseService::class.java).putExtra(OpenRouterResponseService.EXTRA_REQUEST_ID, request.requestId))
            else launch(request.requestId)
        } catch (e: Exception) {
            if (!OpenRouterUiBridge.viewing(request.chatId)) {
                item.cancelReason = "Не удалось запустить фоновое выполнение: ${e.javaClass.simpleName}"
                item.cancelled.set(true)
            }
            launch(request.requestId)
        }
    }
    fun launch(requestId: String) {
        val item = work[requestId] ?: return
        if (item.launched.compareAndSet(false, true)) executor.execute { run(item) }
    }
    fun cancel(requestId: String, reason: String = "Соединение прервано") {
        work[requestId]?.let { synchronized(it.stopLock) {
            // Flush while the old request still owns its durable SQLite checkpoint.
            // Explicit Stop reserves its terminal transaction; a disconnect still finalizes disconnected.
            try {
                if (!it.cancelled.get()) prepareStop(requestId, reserveTerminal = false)
            } finally {
                it.cancelReason = reason
                it.cancelled.set(true)
                it.call?.cancel()
                OpenRouterUiBridge.cancelReasoning(it.request.chatId, requestId)
            }
        } }
    }
    fun cancelAll(reason: String) { work.keys.toList().forEach { cancel(it, reason) } }
    fun cancelBackground(reason: String) { work.values.filter { it.request.background }.forEach { cancel(it.request.requestId, reason) } }
    private fun annotations(artifacts: List<JSONObject>) = JSONArray().apply {
        artifacts.forEach { a -> put(JSONObject().put("type", "local_file").put("state", "READY")
            .put("filename", JsonProtocol.string(a, "filename")).put("local_path", JsonProtocol.string(a, "path"))
            .put("mime_type", a.optString("mime_type")).put("size_bytes", a.optLong("size_bytes")).apply {
                listOf("sha256", "patch_diff", "patch_diff_truncated", "diff_path", "patch_diff_path", "source_path", "source_file_id", "source_sha256").forEach { key ->
                    a.opt(key)?.let { put(key, JsonProtocol.copy(it)) }
                }
            }) }
    }
    private fun run(item: Work) {
        val r = item.request
        val provider = r.provider
        val isDeepSeek = provider == "deepseek"
        val protocolField = if (isDeepSeek) DeepSeekProtocolCodec.FIELD else "openrouter_protocol"
        val db = ChatDatabase(item.context)
        val diagnostics = AstraDiagnostics(item.context)
        val secure = SecureStore(item.context)
        val artifacts = mutableListOf<JSONObject>()
        val trace = JSONArray()
        var partial = ""
        var protocolNotice: String? = null
        var lastCheckpoint: Long? = null
        var textCheckpointSequence = 0L
        var fatalFailure = false
        var terminalCommitted = false
        var github: GitHubToolExecutor? = null
        var latestFileIoJson: JSONObject? = null
        fun active() = !item.cancelled.get() && !item.stopping.get() && !Thread.currentThread().isInterrupted && db.isActiveRequest(r.chatId, r.requestId)
        fun completeUsage(usage: JSONObject?): Boolean = !isDeepSeek ||
            (usage != null && usage.optBoolean("usage_complete", false) &&
                usage.has("prompt_tokens") && !usage.isNull("prompt_tokens") &&
                usage.has("completion_tokens") && !usage.isNull("completion_tokens"))
        fun terminal(message: JSONObject, status: String, usage: JSONObject?, responseModel: String?, error: String? = null,
            fileIo: JSONObject? = null, roundsCompleted: Int = 0): Boolean {
            if (artifacts.isNotEmpty()) message.put("annotations", annotations(artifacts))
            if (trace.length() > 0) message.put("tool_trace", trace)
            val usageComplete = completeUsage(usage)
            // Preserve the existing sparse OpenRouter policy, including a known cost
            // without one of the token counters. DeepSeek requires a complete ledger.
            val accountingUsage = usage.takeIf { usageComplete }
            val totals = OpenRouterUsageSnapshot.from(accountingUsage)
            val reportedCost = if (isDeepSeek) null else totals.reportedCostUsd
            val executedTools = (0 until trace.length()).mapNotNull { trace.optJSONObject(it)?.let { entry -> JsonProtocol.nonBlank(entry, "name") } }.distinct()
            val toolTelemetry = if (roundsCompleted > 0 || trace.length() > 0 || !usageComplete) JSONObject()
                .put("rounds_completed", roundsCompleted).put("calls_executed", trace.length())
                .put("executed_tools", JSONArray(executedTools)).apply {
                    if (!usageComplete) {
                        put("usage_incomplete", true)
                        usage?.optInt("rounds_with_usage", -1)?.takeIf { it >= 0 }?.let { put("rounds_with_usage", it) }
                        usage?.optInt("rounds_total", -1)?.takeIf { it >= 0 }?.let { put("rounds_total", it) }
                        usage?.optJSONObject("observed_partial_usage")?.let { put("observed_partial_usage", JsonProtocol.objectCopy(it)) }
                    }
                } else null
            val compactTelemetry = FileBackedIoTelemetry.mergeCompactTelemetry(toolTelemetry = toolTelemetry,
                fileIoJson = fileIo ?: latestFileIoJson, costDetails = totals.costDetails)
            compactTelemetry?.let { message.put("telemetry_summary", it) }
            val committed = synchronized(item.stopLock) {
                !item.stopReservesTerminal && db.finalizeCurrentChatIfActiveRequest(r.chatId, "", null, null, listOf(message), r.requestId)
            }
            if (!committed) return false
            terminalCommitted = true
            try {
                db.recordRequestAccounting(chatId = r.chatId, localRequestId = r.requestId, status = status, usage = accountingUsage, error = error,
                    reportedCostUsd = reportedCost, telemetrySummary = compactTelemetry)
                db.recordRequestEvent(chatId = r.chatId, eventType = "request_$status", requestId = r.requestId,
                    provider = provider, requestedModel = r.model, responseModel = responseModel,
                    modelMatch = ModelCatalog.match(r.model, responseModel), status = status,
                    inputTokens = totals.inputTokens, cachedTokens = totals.cachedTokens, cacheWriteTokens = totals.cacheWriteTokens,
                    outputTokens = totals.outputTokens, reasoningTokens = totals.reasoningTokens,
                    estimatedCostUsd = if (isDeepSeek) null else totals.costUsd, reportedCostUsd = reportedCost,
                    latencyMs = SystemClock.elapsedRealtime() - r.startedAt,
                    toolSummary = executedTools.joinToString(","), error = error, telemetrySummary = compactTelemetry,
                    usageComplete = usageComplete)
                ResponseNotifications.finished(item.context, r.chatId, r.requestId, status == "completed")
            } finally {
                OpenRouterUiBridge.finishReasoning(r.chatId, r.requestId, item.partialReasoning)
            }
            return true
        }
        fun base(text: String) = JSONObject().put("role", "assistant").put("provider", provider)
            .put("requested_model", r.model).put("request_id", r.requestId).put("text", text).apply {
                item.partialReasoning.takeIf { it.isNotBlank() }?.let { put("reasoning_summary", it) }
            }
        val runtimeLease = PreparedAttachmentFiles.lease(item.context)
        val runtimeLeaseScope = runtimeLease.capture()
        try {
            if (!active()) throw java.io.IOException(item.cancelReason)
            if (!isDeepSeek && OpenRouterClaudeRoute.isClaude(r.model)) diagnostics.log("openrouter_route", mapOf(
                "request_id" to r.requestId, "model" to r.model, "route" to r.claudeRoute.preference,
                "image_base64_limit" to r.claudeRoute.imageBase64Limit, "request_byte_limit" to r.claudeRoute.requestByteLimit))
            val verifiedAttachments = r.attachments.map { attachment ->
                if (!active()) throw java.io.IOException("Подготовка файлов остановлена")
                if (attachment.kind in setOf("text", "document", "table"))
                    LocalArtifactAttachmentResolver.resolveForTools(item.context, attachment) else attachment
            }
            verifiedAttachments.filter { it !in item.runtimeAttachments }.forEach(item.runtimeAttachments::add)
            val fileTools = LocalFileTools(verifiedAttachments, ::active)
            val existingCanonicalPaths = verifiedAttachments.mapNotNull { runCatching { File(it.path).canonicalPath }.getOrNull() }.toMutableSet()
            db.listLocalFiles(r.chatId).forEach { a ->
                if (!active()) throw java.io.IOException("Подготовка файлов остановлена")
                val rawPath = JsonProtocol.nonBlank(a, "path") ?: return@forEach
                val file = File(rawPath)
                if (!file.isFile) return@forEach
                val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@forEach
                if (!existingCanonicalPaths.add(canonical)) return@forEach
                try {
                    val classified = LocalArtifactAttachmentResolver.resolve(item.context, db, r.chatId, a)
                    item.runtimeAttachments.add(classified)
                    fileTools.register(classified)
                } catch (error: Exception) {
                    if (!active() || error is java.util.concurrent.CancellationException ||
                        a.has("attachment_manifest") || a.has("attachment")) throw error
                }
            }
            val artifactTools = LocalArtifactToolExecutor(item.context)
            var artifactCall = 0
            if (r.github) {
                github = GitHubToolExecutor(secure, { r.githubRepo }, { r.githubBranches },
                    { name, args -> OpenRouterUiBridge.confirm(r.chatId, r.requestId, name, args) },
                    { name, args -> diagnostics.log("github_write", mapOf("tool" to name, "target" to ToolTraceSummary.entry(name, args, "{}").optJSONObject("target"))) },
                    ::active, File(item.context.cacheDir, "github_text_pages"), failOnUncertainWrite = isDeepSeek)
            }
            val tools = if (isDeepSeek) ContextRequestPolicy.deepSeekTools(r.github) else OpenRouterAppToolCatalog.tools().apply {
                if (r.github) GitHubToolCatalog.tools().let { array -> for (i in 0 until array.length()) put(array.get(i)) }
                OpenRouterServerToolCatalog.tools(r.webSearch, r.webFetch).let { array -> for (i in 0 until array.length()) put(array.get(i)) }
            }
            val result = item.client.complete(apiKey = r.apiKey, model = r.model,
                messages = r.messages, sessionId = if (isDeepSeek) r.requestId else r.chatId, tools = tools, maxTokens = r.maxTokens,
                reasoningEffort = r.effort, verbosity = r.verbosity, parallelToolCalls = r.parallel, cacheMode = r.cacheMode,
                pdfEngine = r.pdfEngine.takeIf { !isDeepSeek }?.takeIf { r.attachments.any { a -> a.kind == "pdf" } },
                responseFormat = r.responseFormat, claudeRoute = r.claudeRoute,
                shouldContinue = ::active,
                executeTool = { name, args ->
                    val response = try { when {
                        name == "create_local_text_file" || name == "patch_local_file" -> {
                            if (!active()) throw java.io.IOException("Запрос остановлен")
                            val callId = "local_artifact_${artifactCall++}"
                            val argumentsSha = StreamingJsonRequestBody.sha256(args)
                            val artifactKey = "${r.chatId}\u0000${r.requestId}\u0000$callId"
                            val a = (if (name == "patch_local_file") artifactTools.patchLocalFile(args,
                                r.attachments + item.runtimeAttachments, active = ::active,
                                artifactKey = artifactKey,
                                beforePublish = { planned ->
                                    if (isDeepSeek) {
                                        val intent = JsonProtocol.objectCopy(planned).put("tool_name", name)
                                            .put("arguments_sha256", argumentsSha).put("tool_call_id", callId)
                                        if (!db.saveLocalArtifactIntentIfActive(r.chatId, r.requestId, callId, intent) || !active()) {
                                            throw java.util.concurrent.CancellationException("DeepSeek patch intent owner changed")
                                        }
                                        ArtifactIntentReconciler.enqueue(item.context)
                                    }
                                })
                                else if (isDeepSeek) {
                                    // Commit the exact prospective file identity before publishing any bytes.
                                    // Recovery verifies that identity; it never executes the tool again.
                                    val intent = artifactTools.prepareCreateTextFile(args, artifactKey)
                                        .put("tool_name", name).put("arguments_sha256", argumentsSha).put("tool_call_id", callId)
                                    if (!db.saveLocalArtifactIntentIfActive(r.chatId, r.requestId, callId, intent) || !active()) {
                                        throw java.util.concurrent.CancellationException("DeepSeek create intent owner changed")
                                    }
                                    ArtifactIntentReconciler.enqueue(item.context)
                                    artifactTools.createTextFile(args, artifactKey)
                                } else artifactTools.createTextFile(args))
                                .put("tool_name", name).put("arguments_sha256", argumentsSha).put("tool_call_id", callId)
                            // Keep confirmed effects reachable even when persisting the
                            // protocol or the active-owner registration subsequently fails.
                            if (isDeepSeek) artifacts.add(a)
                            val registered = db.registerLocalArtifactToolResultIfActive(r.chatId, r.requestId, callId, a)
                            if (isDeepSeek && registered == null) {
                                // The operation has already succeeded. Stop may have consumed
                                // request ownership, but cannot retroactively cancel the file.
                                db.registerLocalArtifact(r.chatId, r.requestId, a)
                                throw java.io.IOException("Файл создан; запрос остановлен до записи результата в протокол. Повтор операции запрещён.")
                            }
                            if (!isDeepSeek && (registered == null || JsonProtocol.string(registered, "path") != JsonProtocol.string(a, "path"))) {
                                val durable = db.getLocalArtifactToolResult(r.chatId, r.requestId, callId)
                                if (durable?.let { JsonProtocol.string(it, "path") } != JsonProtocol.string(a, "path")) {
                                    File(a.getString("path")).delete()
                                    JsonProtocol.nonBlank(a, "patch_diff_path")?.let { File(it).delete() }
                                }
                            }
                            if (registered == null) throw java.io.IOException("Запрос остановлен")
                            if (!isDeepSeek) artifacts.add(registered)
                            val f = OpenRouterLocalAttachment.fromJson(registered.getJSONObject("attachment"))
                            item.runtimeAttachments.add(f)
                            fileTools.register(f)
                            LocalArtifactToolExecutor.toolResult(registered).toString()
                        }
                        name in LocalFileTools.names -> fileTools.execute(name, args)
                        r.github && github != null -> github!!.execute(name, args)
                        else -> JSONObject().put("ok", false).put("error", "Инструмент отключён").toString()
                    } } catch (e: java.io.IOException) {
                        if (e is GitHubWriteOutcomeUnknownException || !active()) throw e
                        JSONObject().put("ok", false).put("error", e.message).toString()
                    }
                    trace.put(ToolTraceSummary.entry(name, args, response)); response
                },
                onCallCreated = { call -> item.call = call; if (!active()) call.cancel() },
                onRoundObserved = { round, id, usage ->
                    if (isDeepSeek) db.observeDeepSeekRound(r.chatId, r.requestId, round, usage)
                    else db.observeOpenRouterRound(r.chatId, r.requestId, round, id, usage)
                },
                onReasoningDelta = { reasoning ->
                    item.partialReasoning = reasoning
                    if (active()) OpenRouterUiBridge.reasoning(r.chatId, r.requestId, reasoning)
                },
                onTextDelta = { text ->
                    partial = text
                    // OpenRouter has no server SSE sequence. This cursor belongs to
                    // the single runtime writer, never to an OpenAI response stream.
                    val sequence = textCheckpointSequence++
                    val now = SystemClock.elapsedRealtime()
                    val previousCheckpoint = lastCheckpoint
                    if ((previousCheckpoint == null || now - previousCheckpoint >= 500L) && active()) {
                        if (db.checkpointOpenRouterResponse(r.chatId, r.requestId, text, sequence)) {
                            lastCheckpoint = now
                            OpenRouterUiBridge.changed(r.chatId, r.requestId)
                        }
                    }
                },
                onPayloadObserved = { round, size -> diagnostics.log("${provider}_http_payload", mapOf("chat_id" to r.chatId, "request_id" to r.requestId, "round" to round, "model" to r.model, "json_bytes" to size)) },
                onFileIoObserved = { round, stats ->
                    latestFileIoJson = JsonProtocol.objectCopy(stats)
                    diagnostics.log("${provider}_file_io", mapOf("chat_id" to r.chatId,
                        "request_id" to r.requestId, "round" to round, "files" to stats.optInt("files"),
                        "source_read_passes" to stats.optLong("source_read_passes"), "source_read_bytes" to stats.optLong("source_read_bytes"),
                        "file_work_ms" to stats.optLong("file_work_ms"), "round_read_passes" to stats.optLong("round_read_passes"),
                        "round_read_bytes" to stats.optLong("round_read_bytes"), "round_work_ms" to stats.optLong("round_work_ms")))
                },
                onProtocolCheckpoint = { checkpoint ->
                    if (isDeepSeek) {
                        if (!db.saveDeepSeekCheckpoint(r.chatId, r.requestId, checkpoint)) {
                            throw java.util.concurrent.CancellationException("DeepSeek checkpoint owner changed")
                        }
                    } else db.saveOpenRouterCheckpoint(r.chatId, r.requestId, checkpoint)
                },
                onProtocolWarning = { warning ->
                    protocolNotice = warning
                    diagnostics.log("${provider}_protocol_reset", mapOf("request_id" to r.requestId, "reason" to warning))
                    OpenRouterUiBridge.warning(r.chatId, r.requestId, warning)
                })
            val completed = OpenRouterFinishPolicy.completed(result.finishReason) &&
                (!isDeepSeek || (result.generationComplete == true && result.replayable == true && completeUsage(result.usage)))
            val status = if (completed) "completed" else "incomplete"
            val explanation = if (completed) "" else result.protocolWarning?.takeIf { it.isNotBlank() }
                ?: OpenRouterFinishPolicy.description(result.finishReason)
            val message = base(result.text).put("status", status).put("finish_reason", result.finishReason ?: JSONObject.NULL)
                .put("response_model", result.responseModel ?: JSONObject.NULL)
                .put("tools", JSONArray(result.toolNames))
            result.generationComplete?.let { message.put("generation_complete", it) }
            result.usageComplete?.let { message.put("usage_complete", it) }
            result.replayable?.let { message.put("protocol_replayable", it) }
            JsonProtocol.nonBlank(result.assistantMessage, "reasoning_summary")?.let { message.put("reasoning_summary", it) }
            if (explanation.isNotBlank()) message.put("error", explanation) // UI metadata, never model content.
            result.protocol?.let { protocol -> protocolNotice?.let { protocol.put("context_notice", it) }; message.put(protocolField, protocol) }
            result.usage?.let { message.put("usage", it) }
            if (terminal(message, status, result.usage, result.responseModel, explanation.takeIf { it.isNotBlank() },
                    result.fileIoTelemetry, result.roundsCompleted)) {
                val recordMetric: (OpenRouterUsageSnapshot) -> Unit = { totals ->
                    db.recordEffortMetric(r.requestId, r.chatId, r.taskClass, r.effortPref, r.effort, r.autoSelected,
                        r.promptChars, r.attachments.size, SystemClock.elapsedRealtime() - r.startedAt,
                        totals.inputTokens, totals.cachedTokens, totals.cacheWriteTokens, totals.outputTokens,
                        totals.reasoningTokens, completed, result.finishReason.takeUnless { completed }, if (isDeepSeek) null else totals.reportedCostUsd,
                        costKind = if (isDeepSeek) "unknown" else totals.costKind)
                }
                if (isDeepSeek) {
                    // A flagged unknown metric counts the attempt without adding storage
                    // sentinels to confirmed token totals (including after app restart).
                    recordMetric(OpenRouterUsageSnapshot.from(result.usage.takeIf { completeUsage(it) }))
                } else OpenRouterCompletionTelemetry.record(result.usage, recordMetric, { })
            }
        } catch (e: Exception) {
            val upstream = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<OpenRouterUpstreamException>().firstOrNull()
            diagnostics.log("${provider}_failed", mapOf("chat_id" to r.chatId, "request_id" to r.requestId,
                "stage" to upstream?.stage, "round" to upstream?.roundIndex, "http_status" to upstream?.httpStatus,
                "error_type" to (upstream?.errorType ?: e.javaClass.simpleName), "provider_name" to upstream?.providerName,
                "provider_code" to upstream?.providerCode, "error_message" to (upstream?.message ?: e.message)?.take(600),
                "safe_metadata" to upstream?.safeMetadata))
            val completionError = e as? OpenRouterCompletionException
            val saved = completionError?.partialText?.takeIf { it.isNotBlank() } ?: partial
            val failedUsage = completionError?.accumulatedUsage
            val error = if (item.cancelled.get()) item.cancelReason else (e.message ?: e.javaClass.simpleName).take(800)
            val status = if (item.cancelled.get()) "disconnected" else "failed"
            val failed = base(saved)
                .put("status", status).put("error", error)
            failedUsage?.let { failed.put("usage", JSONObject(it.toString())) }
            if (isDeepSeek) db.getDeepSeekCheckpoint(r.chatId, r.requestId)?.let {
                val archive = DeepSeekProtocolCodec.withVisibleTail(it, failed)
                failed.put(protocolField, archive)
                DeepSeekProtocolCodec.extractReasoningSummary(archive).takeIf { summary -> summary.isNotBlank() }
                    ?.let { summary -> failed.put("reasoning_summary", summary) }
            } else db.getOpenRouterCheckpoint(r.chatId, r.requestId)?.let {
                failed.put(protocolField, OpenRouterProtocolCodec.withVisibleTail(it, failed))
            }
            terminal(failed, status, failedUsage, null, error, completionError?.fileIoTelemetry,
                completionError?.roundsCompleted ?: 0)
        } catch (fatal: Error) {
            fatalFailure = true
            item.call?.cancel()
            // Best effort only: OOM may also prevent SQLite allocating. Never swallow VM errors.
            // Avoid copying tool/protocol arrays or formatting the throwable in this path.
            try {
                db.finalizeCurrentChatIfActiveRequest(r.chatId, "", null, null,
                    listOf(base(partial).put("status", "failed").put("error", fatal.javaClass.simpleName)), r.requestId)
            } catch (_: Throwable) {
                try { db.claimActiveRequestFailure(r.chatId, r.requestId) } catch (_: Throwable) { }
            }
            try { OpenRouterUiBridge.changed(r.chatId, r.requestId) } catch (_: Throwable) { }
            throw fatal
        } finally {
            try { runtimeLeaseScope.close() } catch (_: Exception) {}
            item.call = null
            if (!terminalCommitted) OpenRouterUiBridge.cancelReasoning(r.chatId, r.requestId)
            try { github?.close() } catch (_: Exception) {}
            try {
                if (isDeepSeek) db.sealDeepSeekRounds(r.chatId, r.requestId)
                else db.sealOpenRouterRounds(r.chatId, r.requestId)
                val orClient = item.client as? OpenRouterChatClient
                if (!isDeepSeek && !fatalFailure && orClient != null && orClient.reconcileGenerations) {
                    var anyReconciled = false
                    OpenRouterGenerationReconciler(r.metadataClient, orClient.metadataBaseUrl)
                        .reconcile(db.listOpenRouterRounds(r.chatId, r.requestId), r.apiKey) { id, metadata ->
                            if (db.reconcileOpenRouterGeneration(r.chatId, r.requestId, id, metadata)) {
                                anyReconciled = true
                            }
                        }
                    if (anyReconciled) OpenRouterUiBridge.changed(r.chatId, r.requestId)
                }
            } catch (_: Exception) { /* Unknown cost stays unknown. */ }
            work.remove(r.requestId, item)
            if (!fatalFailure) try {
                val owners = PreparedAttachmentFiles.Owners(
                    db.listAllLocalFilePaths().toSet() +
                        LocalAttachmentReferences.allComposerPaths(item.context.getSharedPreferences("app", Context.MODE_PRIVATE).all),
                    localFilePaths() + db.listActiveOpenAiToolAttachmentPaths()
                )
                runtimeLease.release(owners)
                PreparedAttachmentFiles.cleanupPending(item.context) { owners }
            } catch (_: Exception) { runtimeLease.defer() }
            else runtimeLease.defer()
            try {
                releaseClient(item.client)
                if (work.values.none { it.request.background }) item.context.stopService(Intent(item.context, OpenRouterResponseService::class.java))
            } finally { db.close() }
        }
    }
}
