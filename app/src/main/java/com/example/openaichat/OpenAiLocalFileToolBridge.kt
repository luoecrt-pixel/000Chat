package com.example.openaichat

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.CancellationException

/** Durable, request-scoped, read-only Responses tool continuation, shared by Activity and Worker. */
object OpenAiLocalFileToolBridge {
    private val names = linkedMapOf(
        "list_attached_files" to "file_list", "search_attached_file" to "file_search",
        "read_attached_file" to "file_read_range", "read_attached_table" to "file_table_stats"
    )
    const val MAX_ROUNDS = 6

    fun schemas(): JSONArray = JSONArray().apply {
        val source = LocalFileTools.schemas()
        for (i in 0 until source.length()) {
            val function = source.getJSONObject(i).getJSONObject("function")
            val name = names.entries.firstOrNull { it.value == function.optString("name") }?.key ?: continue
            put(JSONObject().put("type", "function").put("name", name).put("strict", false)
                .put("description", function.getString("description"))
                .put("parameters", JsonProtocol.objectCopy(function.getJSONObject("parameters"))))
        }
    }

    fun attachments(history: List<JSONObject>): List<OpenRouterLocalAttachment> = history.flatMap { message ->
        val items = message.optJSONArray("attachments") ?: JSONArray()
        (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(OpenRouterLocalAttachment::fromJson) }
    }.filter { it.kind in setOf("text", "document", "table") }.distinctBy { it.id }

    fun configure(db: ChatDatabase, chatId: String, requestId: String, body: JSONObject,
        files: List<OpenRouterLocalAttachment>) {
        if (files.isEmpty()) return
        val tools = body.optJSONArray("tools") ?: JSONArray().also { body.put("tools", it) }
        val local = schemas()
        for (i in 0 until local.length()) tools.put(local.get(i))
        val template = JsonProtocol.objectCopy(body).apply { remove("input"); remove("previous_response_id") }
        check(db.saveOpenAiToolCheckpoint(chatId, requestId, JSONObject().put("version", 1)
            .put("template", template).put("attachments", JSONArray().apply { files.forEach { put(it.toJson()) } })
            .put("round", 0).put("phase", "idle").put("prior_output", JSONArray()).put("usage", JSONObject())))
    }

    fun durableTerminal(db: ChatDatabase, chatId: String, requestId: String, fallback: JSONObject?): JSONObject? {
        val saved = db.getOpenAiToolCheckpoint(chatId, requestId)
        return if (saved?.optString("phase") == "finished") saved.optJSONObject("final_response") else fallback
    }

    fun finish(context: Context, db: ChatDatabase, chatId: String, requestId: String, initial: JSONObject,
        client: OkHttpClient, active: () -> Boolean = { true }, onCall: (Call) -> Unit = {},
        onStatus: (String) -> Unit = {},
        credential: () -> String = { SecureStore(context).getApiKey() }): JSONObject {
        val checkpoint = db.getOpenAiToolCheckpoint(chatId, requestId) ?: return initial
        if (checkpoint.optInt("version") != 1) throw UnrecoverableResponsesStreamException("Неизвестная версия файлового checkpoint OpenAI")
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
        fun persist() {
            ensureActive()
            if (!db.compareOpenAiToolCheckpoint(chatId, requestId, persisted, checkpoint)) throw CancellationException("Раунд уже принадлежит другому читателю")
            persisted = checkpoint.toString()
        }
        fun http(body: JSONObject?, responseId: String? = null): JSONObject {
            ensureActive(); val key = ensureCredential()
            val transport = ResumableResponseStreamClient.transportForTests
            val original = okhttp3.HttpUrl.Builder().scheme("https").host("api.openai.com")
                .addPathSegment("v1").addPathSegment("responses").apply { responseId?.let(::addPathSegment) }.build()
            val url = if (transport == null) original else original.newBuilder().host(transport.second.host).port(transport.second.port).build()
            val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
                .apply { if (body != null) post(StreamingJsonRequestBody(body)) }.build()
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
        var response = initial
        while (true) {
            ensureActive()
            when (checkpoint.getString("phase")) {
                "finished" -> return checkpoint.getJSONObject("final_response")
                "posting" -> throw UnrecoverableResponsesStreamException("Исход продолжения OpenAI неизвестен после прерывания POST; платный запрос не повторён")
                "prepared" -> {
                    ensureCredential()
                    val next = JsonProtocol.objectCopy(checkpoint.getJSONObject("template"))
                        .put("stream", false).put("previous_response_id", checkpoint.getString("parent_id"))
                        .put("input", checkpoint.getJSONArray("next_input"))
                    checkpoint.put("phase", "posting"); persist()
                    response = http(next)
                    val id = JsonProtocol.nonBlank(response, "id") ?: throw IOException("Нет ID продолжения OpenAI")
                    if (id == checkpoint.getString("parent_id")) throw UnrecoverableResponsesStreamException("OpenAI повторил ID вместо нового раунда")
                    checkpoint.put("phase", "waiting").put("response_id", id)
                    if (!db.advanceOpenAiToolResponse(chatId, requestId, checkpoint.getString("parent_id"), id, checkpoint))
                        throw CancellationException("Владелец ответа изменён")
                    persisted = checkpoint.toString()
                }
                "waiting" -> {
                    val id = checkpoint.getString("response_id")
                    if (response.optString("id") != id || response.optString("status") in setOf("queued", "in_progress")) response = http(null, id)
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
                if (checkpoint.optInt("round") == 0) return response
                val combined = JsonProtocol.arrayCopy(checkpoint.getJSONArray("prior_output"))
                for (i in 0 until output.length()) combined.put(JsonProtocol.copy(output.get(i)))
                response = JsonProtocol.objectCopy(response).put("output", combined)
                response.put("usage", addUsage(checkpoint.getJSONObject("usage"), response.optJSONObject("usage")))
                checkpoint.put("phase", "finished").put("final_response", response); persist()
                return response
            }
            if (checkpoint.getInt("round") >= MAX_ROUNDS) throw UnrecoverableResponsesStreamException("Достигнут предел 6 раундов локальных файловых инструментов")
            val ids = HashSet<String>()
            val validated = calls.map { call ->
                val name = call.optString("name")
                val id = JsonProtocol.nonBlank(call, "call_id") ?: throw IOException("Нет call_id инструмента")
                if (name !in names || !ids.add(id)) throw UnrecoverableResponsesStreamException("Необъявленный или повторный вызов инструмента: $name")
                val raw = JsonProtocol.string(call, "arguments", "{}")
                if (raw.length > 256 * 1024) throw IOException("Аргументы инструмента слишком велики")
                val arguments = try { JSONObject(raw) } catch (_: Exception) {
                    throw UnrecoverableResponsesStreamException("Недействительный JSON аргументов инструмента: $name")
                }
                Triple(id, names.getValue(name), arguments)
            }
            ensureCredential()
            val files = attachments(listOf(JSONObject().put("attachments", checkpoint.getJSONArray("attachments"))))
            try { OpenRouterAttachmentPreflight.verifySources(files) } catch (error: IOException) {
                if (Thread.currentThread().isInterrupted || !active()) throw CancellationException("Подготовка инструмента остановлена")
                throw UnrecoverableResponsesStreamException("Изменился или недоступен исходник файлового инструмента: ${error.message}")
            }
            val nextRound = checkpoint.getInt("round") + 1
            val toolLabels = calls.mapNotNull { it.optString("name").takeIf(String::isNotBlank) }.distinct().joinToString(", ")
            onStatus("🔧 Локальные файлы ($nextRound/$MAX_ROUNDS): $toolLabels")
            val local = LocalFileTools(files) { active() && db.isActiveRequest(chatId, requestId) && !Thread.currentThread().isInterrupted }
            val results = JSONArray()
            for ((id, name, args) in validated) {
                ensureActive()
                val result = local.execute(name, args)
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
            checkpoint.put("usage", addUsage(checkpoint.getJSONObject("usage"), response.optJSONObject("usage")))
                .put("round", checkpoint.getInt("round") + 1).put("parent_id", response.getString("id"))
                .put("next_input", results).put("phase", "prepared")
            persist()
        }
    }

    fun attachReceivedPrefix(db: ChatDatabase, chatId: String, requestId: String, response: JSONObject): JSONObject = response.apply {
        val received = receivedOutput(db, chatId, requestId, JsonProtocol.nonBlank(this, "id"), optJSONArray("output") ?: JSONArray())
        // Absence means no provider-authored typed output arrived. Do not fabricate even an empty array.
        if (received.length() > 0 || has("output")) put("output", received)
    }

    /** Preserve paid prior rounds even if the current child fails before emitting any text. */
    fun receivedOutput(db: ChatDatabase, chatId: String, requestId: String, responseId: String?, current: JSONArray): JSONArray {
        val saved = db.getOpenAiToolCheckpoint(chatId, requestId) ?: return current
        if (saved.optString("phase") == "finished") return saved.optJSONObject("final_response")?.optJSONArray("output") ?: current
        val prior = saved.optJSONArray("prior_output") ?: return current
        if (prior.length() == 0) return current
        val result = JsonProtocol.arrayCopy(prior)
        if (responseId != saved.optString("parent_id")) for (i in 0 until current.length()) result.put(JsonProtocol.copy(current.get(i)))
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

    private fun addUsage(total: JSONObject, extra: JSONObject?): JSONObject = JsonProtocol.objectCopy(total).apply {
        extra?.keys()?.forEach { key -> when (val value = extra.opt(key)) {
            is Number -> put(key, optLong(key) + value.toLong())
            is JSONObject -> put(key, addUsage(optJSONObject(key) ?: JSONObject(), value))
        } }
    }
}
