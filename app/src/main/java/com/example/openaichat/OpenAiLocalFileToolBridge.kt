package com.example.openaichat

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException

/** Durable, request-scoped local-file continuation, shared by Activity and Worker. */
object OpenAiLocalFileToolBridge {
    private const val OUTPUT_PREFIX_FIELD = "openai_tool_output_prefix_attached"
    private val terminalStatuses = setOf("completed", "cancelled", "failed", "incomplete")
    private val names = linkedMapOf(
        "list_attached_files" to "file_list", "search_attached_file" to "file_search",
        "read_attached_file" to "file_read_range", "read_attached_table" to "file_table_stats",
        "patch_local_file" to "patch_local_file"
    )

    fun schemas(): JSONArray = JSONArray().apply {
        val source = OpenRouterAppToolCatalog.tools()
        for (i in 0 until source.length()) {
            val function = source.getJSONObject(i).getJSONObject("function")
            val name = names.entries.firstOrNull { it.value == function.optString("name") }?.key ?: continue
            put(JSONObject().put("type", "function").put("name", name).put("strict", false)
                .put("description", function.getString("description"))
                .put("parameters", JsonProtocol.objectCopy(function.getJSONObject("parameters"))))
        }
    }

    fun attachments(
        history: List<JSONObject>,
        localFiles: List<JSONObject> = emptyList(),
        chatId: String? = null,
        context: Context? = null,
        db: ChatDatabase? = null
    ): List<OpenRouterLocalAttachment> {
        val fromMessages = history.flatMap { message ->
            val items = message.optJSONArray("attachments") ?: JSONArray()
            (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(OpenRouterLocalAttachment::fromJson) }
        }
        val existingCanonical = fromMessages.mapNotNull {
            runCatching { java.io.File(it.path).canonicalPath }.getOrNull()
        }.toMutableSet()
        val fromArtifacts = if (chatId.isNullOrBlank() || localFiles.isEmpty()) emptyList() else {
            localFiles.asSequence()
                .filter { JsonProtocol.string(it, "chat_id") == chatId }
                .mapNotNull { a ->
                    val path = JsonProtocol.nonBlank(a, "path") ?: return@mapNotNull null
                    val file = java.io.File(path)
                    if (!file.isFile) return@mapNotNull null
                    val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return@mapNotNull null
                    if (!existingCanonical.add(canonical)) return@mapNotNull null
                    try {
                        LocalArtifactAttachmentResolver.resolve(context, db, chatId, a)
                    } catch (error: Exception) {
                        if (Thread.currentThread().isInterrupted || error is CancellationException ||
                            a.has("attachment_manifest") || a.has("attachment")) throw error
                        null
                    }
                }
                .toList()
        }
        return (fromMessages + fromArtifacts)
            .filter { it.kind in setOf("text", "document", "table") }
            .distinctBy { it.id }
    }

    fun configure(db: ChatDatabase, chatId: String, requestId: String, body: JSONObject,
        files: List<OpenRouterLocalAttachment>) {
        if (files.isEmpty()) return
        val tools = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
        val local = schemas()
        for (i in 0 until local.length()) tools.put(local.get(i))
        val template = JsonProtocol.objectCopy(body).apply { remove("input"); remove("previous_response_id") }
        check(db.saveOpenAiToolCheckpoint(chatId, requestId, JSONObject().put("version", 1)
            .put("template", template).put("attachments", JSONArray().apply { files.forEach { put(it.toJson()) } })
            .put("round", 0).put("phase", "idle").put("prior_output", JSONArray()).put("usage", JSONObject())
            .put("local_artifacts", JSONArray())
            .put(OpenAiUsageLedger.FIELD, OpenAiUsageLedger.empty())
            .put("telemetry", JSONObject().put("rounds_completed", 0).put("calls_executed", 0).put("executed_tools", JSONArray()))))
    }

    fun durableTerminal(db: ChatDatabase, chatId: String, requestId: String, fallback: JSONObject?): JSONObject? {
        val saved = db.getOpenAiToolCheckpoint(chatId, requestId)
        return if (saved?.optString("phase") == "finished") durableResponse(saved) else fallback
    }

    fun finish(context: Context, db: ChatDatabase, chatId: String, requestId: String, initial: JSONObject,
        client: OkHttpClient, active: () -> Boolean = { true }, onCall: (Call) -> Unit = {},
        onStatus: (String) -> Unit = {},
        credential: () -> String = { SecureStore(context).getApiKey() },
        localToolsFactory: (List<OpenRouterLocalAttachment>, () -> Boolean) -> LocalFileTools = { files, running ->
            LocalFileTools(files, running)
        }): JSONObject {
        val checkpoint = db.getOpenAiToolCheckpoint(chatId, requestId)
            ?: return JsonProtocol.objectCopy(initial).apply { remove("app_local_artifacts") }
        if (checkpoint.optInt("version") != 1) throw UnrecoverableResponsesStreamException("Неизвестная версия файлового checkpoint OpenAI")
        val runtimeLease = PreparedAttachmentFiles.lease(context)
        val runtimeLeaseScope = runtimeLease.capture()
        try {
            fun ensureActive() {
                if (!active() || Thread.currentThread().isInterrupted || !db.isActiveRequest(chatId, requestId))
                    throw CancellationException("Запрос остановлен")
            }
            fun ensureCredential(): String {
                val startedAt = db.getActiveResponse(chatId)?.optLong("started_at", 0L) ?: 0L
                if (ResponseDeadlinePolicy.remainingMillis(startedAt, System.currentTimeMillis()) <= 0L)
                    throw UnrecoverableResponsesStreamException("Истёк сквозной лимит ответа (5 часов)")
                val key = credential()
                if (!OpenAiInstructionChainPolicy.matchesCredential(db.getOpenAiRequestFingerprint(chatId, requestId), key))
                    throw UnrecoverableResponsesStreamException("Исходный ключ запроса изменён; продолжение инструментов остановлено")
                return key
            }
            var persisted = checkpoint.toString()
            // v1 checkpoints predate telemetry. Recover only durably saved results, never physical
            // executions: read-only work may repeat; patch results have a separate idempotent commit.
            if (checkpoint.optJSONObject("telemetry") == null) checkpoint.put("telemetry", committedTelemetry(checkpoint))
            fun persist() {
                ensureActive()
                if (!db.compareOpenAiToolCheckpoint(chatId, requestId, persisted, checkpoint)) throw CancellationException("Раунд уже принадлежит другому читателю")
                persisted = checkpoint.toString()
            }
            fun observeResponse(received: JSONObject) {
                // This helper accepts only the checked current initial/GET/POST response. A new
                // POST child is observed while the durable phase is still "posting", before the
                // active response ID and waiting checkpoint advance atomically together.
                val base = OpenAiUsageLedger.fromCheckpoint(checkpoint)
                val ledger = received.optJSONObject(OpenAiUsageLedger.FIELD)?.let { OpenAiUsageLedger.merge(base, it) }
                    ?: OpenAiUsageLedger.observe(base, JsonProtocol.nonBlank(received, "id"), received.optJSONObject("usage"),
                        JsonProtocol.nonBlank(received, "status"))
                val previous = checkpoint.optJSONObject("received_response")
                val snapshot = if (previous != null && previous.optString("id") == received.optString("id") &&
                    previous.optString("status") in terminalStatuses && received.optString("status") !in terminalStatuses)
                    previous else received
                checkpoint.put(OpenAiUsageLedger.FIELD, ledger)
                    .put("usage", OpenAiUsageLedger.total(ledger) ?: JSONObject())
                    .put("received_response", JsonProtocol.objectCopy(snapshot))
            }
            fun http(body: JSONObject?, responseId: String? = null): JSONObject {
                ensureActive(); val key = ensureCredential()
                val transport = ResumableResponseStreamClient.transportForTests
                val original = okhttp3.HttpUrl.Builder().scheme("https").host("api.openai.com")
                    .addPathSegment("v1").addPathSegment("responses").apply { responseId?.let(::addPathSegment) }.build()
                val url = if (transport == null) original else original.newBuilder().host(transport.second.host).port(transport.second.port).build()
                val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
                    .apply { if (body != null) post(StreamingJsonRequestBody(body, oneShot = true)) }.build()
                val call = (transport?.first ?: client).newCall(request)
                db.getActiveResponse(chatId)?.optLong("started_at")?.let {
                    ResponseDeadlinePolicy.boundCall(call, it, System.currentTimeMillis())
                }
                onCall(call)
                ensureActive()
                val received = call.execute().use { response ->
                    if (!response.isSuccessful) {
                        if (body == null) throw ApiHttpException(response.code,
                            response.header("Retry-After")?.toLongOrNull()?.coerceIn(1, 300)?.times(1000),
                            BoundedResponseDecoder.readErrorBody(response.body))
                        throw UnrecoverableResponsesStreamException("Ошибка продолжения OpenAI HTTP ${response.code}; автоматический повтор POST запрещён")
                    }
                    JSONObject(BoundedResponseDecoder.readBody(response.body))
                }
                try { ensureActive() } catch (cancelled: CancellationException) {
                    // Stop/Delete can win after POST reached the provider but before its new ID was committed.
                    // Cancel only that known child, with the original bound credential, never a replacement key.
                    val child = JsonProtocol.nonBlank(received, "id")
                    if (body?.optBoolean("background") == true && child != null) {
                        val cancelUrl = url.newBuilder().addPathSegment(child).addPathSegment("cancel").build()
                        val cleanup = (transport?.first ?: client).newCall(Request.Builder().url(cancelUrl)
                            .header("Authorization", "Bearer $key").post(StreamingJsonRequestBody(JSONObject())).build())
                        cleanup.timeout().timeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        try { cleanup.execute().close() } catch (_: IOException) { /* A stopped owner is never resurrected. */ }
                    }
                    throw cancelled
                }
                return received
            }
            if (checkpoint.optString("phase") in setOf("prepared", "waiting")) {
                ensureActive()
                val pending = checkpoint.optJSONArray("next_input") ?: JSONArray()
                val pendingIds = (0 until pending.length()).mapNotNull { pending.optJSONObject(it)?.optString("call_id") }.toSet()
                val prior = checkpoint.optJSONArray("prior_output") ?: JSONArray()
                val tools = (0 until prior.length()).mapNotNull { prior.optJSONObject(it) }
                    .filter { it.optString("type") == "function_call" && it.optString("call_id") in pendingIds }
                    .map { it.optString("name") }.distinct()
                onStatus("🔧 Локальные файлы (${checkpoint.optInt("round")}): ${tools.joinToString(", ")}")
            }
            val received = checkpoint.optJSONObject("received_response")
            // A process can die after committing the child's paid terminal JSON but before
            // committing its local tool batch/final response. Consume it offline on resume.
            var response = if (received != null && checkpoint.optString("phase") == "waiting" &&
                received.optString("id") == checkpoint.optString("response_id") &&
                received.optString("status") in terminalStatuses) JsonProtocol.objectCopy(received) else initial
            if (checkpoint.optString("phase") == "idle" ||
                (checkpoint.optString("phase") == "waiting" && response.optString("id") == checkpoint.optString("response_id"))) {
                // Save paid snapshots before validating/executing their tools. Failure/Stop can then
                // preserve the raw provider output as well as all previous committed rounds.
                observeResponse(response)
                if (checkpoint.toString() != persisted) persist()
            }
            // Keep the sparse text-page index for the lifetime of this continuation.
            // The allow-list is still revalidated before every batch, and rebuilt
            // derivatives replace this instance rather than retaining stale pages.
            var localTools: LocalFileTools? = null
            var localToolsFiles: List<OpenRouterLocalAttachment>? = null
            val startedAt = db.getActiveResponse(chatId)?.optLong("started_at", 0L) ?: 0L
            fun toolsActive() = active() && db.isActiveRequest(chatId, requestId) &&
                !Thread.currentThread().isInterrupted &&
                ResponseDeadlinePolicy.remainingMillis(startedAt, System.currentTimeMillis()) > 0L
            while (true) {
                ensureActive()
                when (checkpoint.getString("phase")) {
                    "finished" -> return durableResponse(checkpoint)!!
                    "posting" -> throw UnrecoverableResponsesStreamException("Исход продолжения OpenAI неизвестен после прерывания POST; платный запрос не повторён")
                    "prepared" -> {
                        ensureCredential()
                        val next = JsonProtocol.objectCopy(checkpoint.getJSONObject("template"))
                            .put("stream", false).put("previous_response_id", checkpoint.getString("parent_id"))
                            .put("input", checkpoint.getJSONArray("next_input"))
                        checkpoint.put("phase", "posting"); persist()
                        response = http(next)
                        val id = JsonProtocol.nonBlank(response, "id") ?: throw IOException("Нет ID продолжения OpenAI")
                        val ledger = OpenAiUsageLedger.fromCheckpoint(checkpoint)
                        val covered = ledger.optJSONArray("legacy_covered_ids") ?: JSONArray()
                        if (id == checkpoint.getString("parent_id") || ledger.optJSONObject("responses")?.has(id) == true ||
                            (0 until covered.length()).any { JsonProtocol.text(covered, it) == id })
                            throw UnrecoverableResponsesStreamException("OpenAI повторил ID вместо нового раунда")
                        observeResponse(response)
                        checkpoint.put("phase", "waiting").put("response_id", id)
                        if (!db.advanceOpenAiToolResponse(chatId, requestId, checkpoint.getString("parent_id"), id, checkpoint))
                            throw CancellationException("Владелец ответа изменён")
                        persisted = checkpoint.toString()
                    }
                    "waiting" -> {
                        val id = checkpoint.getString("response_id")
                        if (response.optString("id") != id || response.optString("status") in setOf("queued", "in_progress")) response = http(null, id)
                        if (response.optString("id") != id) throw UnrecoverableResponsesStreamException("OpenAI вернул чужой ID дочернего ответа")
                        observeResponse(response)
                        if (checkpoint.toString() != persisted) persist()
                        if (response.optString("status") in setOf("queued", "in_progress")) {
                            Thread.sleep(1_500L); continue
                        }
                        checkpoint.put("phase", "idle")
                    }
                }
                if (checkpoint.optString("phase") == "waiting") continue
                val output = response.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).mapNotNull { output.optJSONObject(it) }.filter { it.optString("type") == "function_call" }
                if (response.optString("status") != "completed" || calls.isEmpty()) {
                    val combined = JsonProtocol.arrayCopy(checkpoint.getJSONArray("prior_output"))
                    for (i in 0 until output.length()) combined.put(JsonProtocol.copy(output.get(i)))
                    response = JsonProtocol.objectCopy(response).apply {
                        if (combined.length() > 0 || has("output")) put("output", combined)
                        put(OUTPUT_PREFIX_FIELD, true)
                    }
                    response.put("app_local_artifacts", JsonProtocol.arrayCopy(checkpoint.optJSONArray("local_artifacts") ?: JSONArray()))
                    OpenAiUsageLedger.attach(response, OpenAiUsageLedger.fromCheckpoint(checkpoint))
                    checkpoint.put("phase", "finished").put("final_response", response); persist()
                    return response
                }
                val ids = HashSet<String>()
                val validated = calls.map { call ->
                    val name = call.optString("name")
                    val id = JsonProtocol.nonBlank(call, "call_id") ?: throw IOException("Нет call_id инструмента")
                    val declared = checkpoint.optJSONObject("template")?.optJSONArray("tools") ?: JSONArray()
                    if (name == "patch_local_file" && (0 until declared.length()).none { declared.optJSONObject(it)?.optString("name") == name })
                        throw UnrecoverableResponsesStreamException("Инструмент записи не включён в исходный запрос")
                    if (name !in names || !ids.add(id)) throw UnrecoverableResponsesStreamException("Необъявленный или повторный вызов инструмента: $name")
                    val raw = JsonProtocol.string(call, "arguments", "{}")
                    if (raw.length > 256 * 1024) throw IOException("Аргументы инструмента слишком велики")
                    val arguments = try { JSONObject(raw) } catch (_: Exception) {
                        throw UnrecoverableResponsesStreamException("Недействительный JSON аргументов инструмента: $name")
                    }
                    Triple(id, names.getValue(name), arguments)
                }
                ensureCredential()
                val rawFiles = attachments(listOf(JSONObject().put("attachments", checkpoint.getJSONArray("attachments"))))
                var rebuiltAny = false
                val files = try {
                    OpenRouterAttachmentPreflight.verifySources(rawFiles)
                    rawFiles.map { item ->
                        val missingText = item.kind in setOf("text", "document", "table") &&
                            (item.textPath.isNullOrBlank() || !java.io.File(item.textPath!!).isFile)
                        if (missingText) {
                            rebuiltAny = true
                            val rebuilt = OpenRouterAttachmentStore.classifyLocalFile(context, java.io.File(item.path), item.name, item.mime)
                            if (item.kind == "text" && !item.encodingGuessed && item.textEncoding != null) {
                                val selected = OpenRouterAttachmentStore.withTextEncoding(rebuilt, item.textEncoding)
                                rebuilt.textPath?.let { java.io.File(it).delete() }
                                selected
                            } else rebuilt
                        } else item
                    }
                } catch (error: IOException) {
                    if (Thread.currentThread().isInterrupted || !active()) throw CancellationException("Подготовка инструмента остановлена")
                    throw UnrecoverableResponsesStreamException("Изменился или недоступен исходник файлового инструмента: ${error.message}")
                }
                if (rebuiltAny) {
                    checkpoint.put("attachments", JSONArray().apply { files.forEach { put(it.toJson()) } })
                    persist()
                }
                val nextRound = checkpoint.getInt("round") + 1
                val toolLabels = calls.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }.distinct().joinToString(", ")
                onStatus("🔧 Локальные файлы ($nextRound): $toolLabels")
                if (localTools == null || rebuiltAny || localToolsFiles != files) {
                    localTools = localToolsFactory(files, ::toolsActive)
                    localToolsFiles = files
                }
                val local = checkNotNull(localTools)
                val results = JSONArray()
                val batchFiles = files.toMutableList()
                val artifactExecutor = LocalArtifactToolExecutor(context)
                for ((id, name, args) in validated) {
                    ensureActive()
                    val result = if (name == "patch_local_file") {
                        try {
                            val argumentsSha = StreamingJsonRequestBody.sha256(args)
                            val priorResult = db.getLocalArtifactToolResult(chatId, requestId, id)
                            val artifact = if (priorResult != null) {
                                if (priorResult.optString("tool_name") != name || priorResult.optString("arguments_sha256") != argumentsSha)
                                    throw IOException("Повторный call_id с другими аргументами")
                                priorResult
                            } else {
                                val candidate = artifactExecutor.patchLocalFile(args, batchFiles, active = ::toolsActive,
                                    artifactKey = "$chatId\u0000$requestId\u0000$id")
                                    .put("tool_name", name).put("arguments_sha256", argumentsSha).put("tool_call_id", id)
                                val registered = db.registerLocalArtifactToolResultIfActive(chatId, requestId, id, candidate)
                                if (registered == null || JsonProtocol.string(registered, "path") != JsonProtocol.string(candidate, "path")) {
                                    val durable = db.getLocalArtifactToolResult(chatId, requestId, id)
                                    if (durable?.let { JsonProtocol.string(it, "path") } != JsonProtocol.string(candidate, "path")) {
                                        java.io.File(candidate.getString("path")).delete()
                                        JsonProtocol.nonBlank(candidate, "patch_diff_path")?.let { java.io.File(it).delete() }
                                    }
                                }
                                if (registered == null) throw CancellationException("Владелец файла изменён")
                                registered
                            }
                            ensureActive()
                            val added = OpenRouterLocalAttachment.fromJson(artifact.getJSONObject("attachment"))
                            OpenRouterAttachmentPreflight.verifySources(listOf(added))
                            local.register(added)
                            if (batchFiles.none { it.id == added.id }) {
                                batchFiles += added
                                checkpoint.getJSONArray("attachments").put(added.toJson())
                            }
                            val savedArtifacts = checkpoint.optJSONArray("local_artifacts")
                                ?: JSONArray().also { checkpoint.put("local_artifacts", it) }
                            if ((0 until savedArtifacts.length()).none { savedArtifacts.optJSONObject(it)?.let { value -> JsonProtocol.string(value, "path") } == JsonProtocol.string(artifact, "path") })
                                savedArtifacts.put(JsonProtocol.objectCopy(artifact))
                            LocalArtifactToolExecutor.toolResult(artifact).toString()
                        } catch (error: IOException) {
                            if (!toolsActive()) throw error
                            JSONObject().put("ok", false).put("error", error.message).toString()
                        }
                    } else local.execute(name, args)
                    val wireResult = if (name == "file_list") {
                        JSONObject(result).apply {
                            optJSONArray("files")?.let { list -> for (i in 0 until list.length()) {
                                list.optJSONObject(i)?.put("reading", "Use search_attached_file, read_attached_file or read_attached_table with this file_id.")
                            } }
                        }.toString()
                    } else result
                    results.put(JSONObject().put("type", "function_call_output").put("call_id", id).put("output", wireResult))
                }
                val prior = checkpoint.getJSONArray("prior_output")
                for (i in 0 until output.length()) prior.put(JsonProtocol.copy(output.get(i)))
                for (i in 0 until results.length()) prior.put(JsonProtocol.copy(results.get(i)))
                val telemetry = checkpoint.getJSONObject("telemetry")
                val executedTools = telemetry.getJSONArray("executed_tools")
                calls.forEach { executedTools.put(it.getString("name")) }
                telemetry.put("rounds_completed", nextRound)
                    .put("calls_executed", telemetry.getInt("calls_executed") + results.length())
                checkpoint.put("round", nextRound).put("parent_id", response.getString("id"))
                    .put("next_input", results).put("phase", "prepared")
                persist()
            }
        } finally {
            runtimeLeaseScope.close()
            try {
                fun owners() = PreparedAttachmentFiles.Owners(
                    durable = db.listAllLocalFilePaths().toSet() + LocalAttachmentReferences.allComposerPaths(
                        context.getSharedPreferences("app", Context.MODE_PRIVATE).all),
                    active = OpenRouterResponseRuntime.localFilePaths() + db.listActiveOpenAiToolAttachmentPaths()
                )
                runtimeLease.release(owners())
                PreparedAttachmentFiles.cleanupPending(context, ::owners)
            } catch (_: Exception) { runtimeLease.defer() }
        }
    }

    private fun committedTelemetry(checkpoint: JSONObject): JSONObject {
        val prior = checkpoint.optJSONArray("prior_output") ?: JSONArray()
        val outputIds = (0 until prior.length()).mapNotNull { prior.optJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toSet()
        val tools = JSONArray()
        for (i in 0 until prior.length()) {
            val item = prior.optJSONObject(i) ?: continue
            if (item.optString("type") == "function_call" && item.optString("call_id") in outputIds && item.optString("name") in names)
                tools.put(item.getString("name"))
        }
        return JSONObject().put("rounds_completed", checkpoint.optInt("round"))
            .put("calls_executed", tools.length()).put("executed_tools", tools)
    }

    fun attachReceivedPrefix(db: ChatDatabase, chatId: String, requestId: String, response: JSONObject): JSONObject = response.apply {
        val saved = db.getOpenAiToolCheckpoint(chatId, requestId)
        if (!optBoolean(OUTPUT_PREFIX_FIELD)) {
            val received = receivedOutput(saved, JsonProtocol.nonBlank(this, "id"), optJSONArray("output") ?: JSONArray())
            // Absence means no provider-authored typed output arrived. Do not fabricate an empty array.
            if (received.length() > 0 || has("output")) put("output", received)
            put(OUTPUT_PREFIX_FIELD, true)
        }
        put("app_local_artifacts", JsonProtocol.arrayCopy(saved?.optJSONArray("local_artifacts") ?: JSONArray()))
        OpenAiUsageLedger.attach(this, OpenAiUsageLedger.resolve(saved, this))
    }

    /** Complete or partial paid response, suitable for transactional failure/Stop preservation. */
    fun durableResponse(checkpoint: JSONObject?): JSONObject? {
        val saved = checkpoint ?: return null
        val finished = saved.optString("phase") == "finished"
        val source = if (finished) saved.optJSONObject("final_response") else saved.optJSONObject("received_response")
        val prior = saved.optJSONArray("prior_output")
        if (source == null && (prior == null || prior.length() == 0)) return null
        val response = source?.let(JsonProtocol::objectCopy) ?: JSONObject()
            .put("id", JsonProtocol.nonBlank(saved, "parent_id") ?: JSONObject.NULL)
        if (!response.optBoolean(OUTPUT_PREFIX_FIELD)) {
            val output = if (finished) response.optJSONArray("output") ?: JSONArray()
                else receivedOutput(saved, JsonProtocol.nonBlank(response, "id"), response.optJSONArray("output") ?: JSONArray())
            if (output.length() > 0 || response.has("output")) response.put("output", output)
            response.put(OUTPUT_PREFIX_FIELD, true)
        }
        response.put("app_local_artifacts", JsonProtocol.arrayCopy(saved.optJSONArray("local_artifacts") ?: JSONArray()))
        return OpenAiUsageLedger.attach(response, OpenAiUsageLedger.resolve(saved, response))
    }

    fun extractTelemetrySummary(checkpoint: JSONObject?): JSONObject? {
        val saved = checkpoint ?: return null
        val out = saved.optJSONObject("telemetry")?.let(JsonProtocol::objectCopy) ?: committedTelemetry(saved)
        val ledger = OpenAiUsageLedger.fromCheckpoint(saved)
        if (OpenAiUsageLedger.total(ledger) != null) out.put(OpenAiUsageLedger.FIELD, ledger)
        return out.takeIf { it.length() > 0 }
    }

    fun extractTelemetrySummary(db: ChatDatabase, chatId: String, requestId: String): JSONObject? =
        extractTelemetrySummary(db.getOpenAiToolCheckpoint(chatId, requestId))

    /** Preserve paid prior rounds even if the current child fails before emitting any text. */
    fun receivedOutput(db: ChatDatabase, chatId: String, requestId: String, responseId: String?, current: JSONArray): JSONArray {
        return receivedOutput(db.getOpenAiToolCheckpoint(chatId, requestId), responseId, current)
    }

    private fun receivedOutput(saved: JSONObject?, responseId: String?, current: JSONArray): JSONArray {
        if (saved == null) return current
        if (saved.optString("phase") == "finished") return saved.optJSONObject("final_response")?.optJSONArray("output") ?: current
        val prior = saved.optJSONArray("prior_output") ?: return current
        val paid = saved.optJSONObject("received_response")
        // A prepared/posting batch already includes its parent's output. Legacy checkpoints
        // cannot name every earlier parent, so a stale first response must not be appended.
        if (prior.length() > 0 && saved.optString("phase") in setOf("prepared", "posting")) return JsonProtocol.arrayCopy(prior)
        val paidId = paid?.let { JsonProtocol.nonBlank(it, "id") }
        val waitingId = JsonProtocol.nonBlank(saved, "response_id")
        val belongsToWaitingChild = saved.optString("phase") == "waiting" && responseId == waitingId
        if (prior.length() > 0 && saved.optString("phase") == "waiting" && !belongsToWaitingChild && paid == null)
            return JsonProtocol.arrayCopy(prior)
        val paidIsTerminal = paid?.optString("status") in terminalStatuses
        val usePaid = paid != null && (current.length() == 0 || paidIsTerminal && (paidId == responseId || paidId == waitingId) ||
            responseId != paidId && !belongsToWaitingChild)
        val effectiveCurrent = if (usePaid) paid?.optJSONArray("output") ?: JSONArray() else current
        if (prior.length() == 0) return effectiveCurrent
        val result = JsonProtocol.arrayCopy(prior)
        val effectiveId = if (usePaid) paidId else responseId
        if (effectiveId != saved.optString("parent_id")) for (i in 0 until effectiveCurrent.length()) result.put(JsonProtocol.copy(effectiveCurrent.get(i)))
        return result
    }

    /** Replay of an interrupted turn must pair every received call without executing it again. */
    fun completePendingOutputs(output: JSONArray): JSONArray = JsonProtocol.arrayCopy(output).apply {
        val answered = (0 until length()).mapNotNull { optJSONObject(it) }
            .filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }.toMutableSet()
        val calls = (0 until length()).mapNotNull { optJSONObject(it) }.filter { it.optString("type") == "function_call" }
        for (call in calls) {
            val id = JsonProtocol.nonBlank(call, "call_id") ?: continue
            if (answered.add(id)) put(JSONObject().put("type", "function_call_output").put("call_id", id)
                .put("output", JSONObject().put("ok", false).put("cancelled", true)
                    .put("error", "Запрос остановлен до завершения инструмента").toString()))
        }
    }

}
