package com.example.openaichat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class BackgroundResponseWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val KEY_CHAT_ID = "chat_id"
        const val KEY_RESPONSE_ID = "response_id"
        const val KEY_REQUEST_ID = "request_id"

        private const val CHANNEL_ID = "astra_response_recovery"
        private const val NOTIFICATION_ID = 41062
        // One-response recovery window; this is not a session-wide chat limit.
        private const val MAX_RECOVERY_MS = 5L * 60L * 60L * 1000L
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun doWork(): Result {
        val chatId = inputData.getString(KEY_CHAT_ID).orEmpty()
        val responseId = inputData.getString(KEY_RESPONSE_ID).orEmpty()
        val requestId = inputData.getString(KEY_REQUEST_ID).orEmpty()
        if (chatId.isBlank() || responseId.isBlank() || requestId.isBlank()) return Result.failure()

        val db = ChatDatabase(applicationContext)
        val diagnostics = AstraDiagnostics(applicationContext)
        val streamClient = ResumableResponseStreamClient(applicationContext)
        var activeCall: okhttp3.Call? = null
        currentCoroutineContext().job.invokeOnCompletion {
            activeCall?.cancel()
        }

        try {
            val activeAtStart = db.getActiveResponse(chatId)
            if (activeAtStart?.optString("request_id") != requestId) return Result.success()
            if (!activeAtStart.optBoolean("background_enabled", true)) return Result.success()

            setForeground(createForegroundInfo(db.getChatTitle(chatId).orEmpty().ifBlank { "Чат" }))
            val startedAt = SystemClock.elapsedRealtime()
            val deadline = startedAt + MAX_RECOVERY_MS
            var retryDelayMs = 1_500L

            while (!isStopped) {
                if (SystemClock.elapsedRealtime() >= deadline) {
                    diagnostics.log("worker_recovery_timeout", mapOf(
                        "chat_id" to chatId,
                        "response_id" to responseId,
                        "request_id" to requestId
                    ))
                    db.clearActiveResponse(chatId, expectedRequestId = requestId)
                    return Result.success()
                }

                val current = db.getActiveResponse(chatId)
                    ?: return Result.success()
                if (current.optString("request_id") != requestId) return Result.success()
                if (!current.optBoolean("background_enabled", true)) return Result.success()

                val cursor = current.optLong("last_sequence_number", -1L)
                val full = StringBuilder(if (cursor >= 0L) current.optString("text").orEmpty() else "")
                var lastSequence = cursor
                var lastCheckpointAt = SystemClock.elapsedRealtime()

                try {
                    val result = streamClient.stream(
                        responseId = responseId,
                        startingAfter = cursor,
                        onCallCreated = { call -> activeCall = call }
                    ) { event ->
                        val sequence = event.optLong("sequence_number", -1L)
                        if (sequence >= 0L && sequence > lastSequence) {
                            lastSequence = sequence
                        }

                        if (event.optString("type") == "response.output_text.delta") {
                            val delta = event.optString("delta")
                            if (delta.isNotEmpty()) full.append(delta)
                        }

                        val type = event.optString("type")
                        val terminal = type == "response.completed" ||
                            type == "response.failed" ||
                            type == "response.incomplete" ||
                            type == "response.cancelled"
                        val now = SystemClock.elapsedRealtime()
                        if (terminal || now - lastCheckpointAt >= 500L) {
                            lastCheckpointAt = now
                            db.updateActiveResponse(
                                chatId,
                                responseId = responseId,
                                text = full.toString(),
                                status = when (type) {
                                    "response.completed" -> "completed"
                                    "response.failed" -> "failed"
                                    "response.incomplete" -> "incomplete"
                                    "response.cancelled" -> "cancelled"
                                    else -> "in_progress"
                                },
                                lastSequenceNumber = lastSequence,
                                expectedRequestId = requestId
                            )
                        }
                    }

                    activeCall = null
                    val latest = db.getActiveResponse(chatId)
                    if (latest?.optString("request_id") != requestId) return Result.success()

                    if (result.terminalResponse != null) {
                        db.updateActiveResponse(
                            chatId,
                            responseId = responseId,
                            text = full.toString(),
                            status = when (result.terminalEventType) {
                                "response.completed" -> "completed"
                                "response.failed" -> "failed"
                                "response.incomplete" -> "incomplete"
                                "response.cancelled" -> "cancelled"
                                else -> "in_progress"
                            },
                            lastSequenceNumber = maxOf(lastSequence, result.lastSequenceNumber),
                            expectedRequestId = requestId
                        )
                        finalize(
                            chatId,
                            responseId,
                            requestId,
                            result.terminalResponse!!,
                            db,
                            diagnostics
                        )
                        return if (result.terminalEventType == "response.failed") Result.failure() else Result.success()
                    }

                    db.updateActiveResponse(
                        chatId,
                        responseId = responseId,
                        text = full.toString(),
                        status = "in_progress",
                        lastSequenceNumber = maxOf(lastSequence, result.lastSequenceNumber),
                        expectedRequestId = requestId
                    )

                    if (isStopped) return Result.success()
                    delay(BackgroundRecoveryPolicy.jitteredDelayMs(retryDelayMs))
                    retryDelayMs = BackgroundRecoveryPolicy.nextDelayMs(retryDelayMs)
                } catch (e: ResponseStreamSequenceGapException) {
                    activeCall = null
                    diagnostics.log("worker_sequence_gap", mapOf(
                        "chat_id" to chatId,
                        "response_id" to responseId,
                        "request_id" to requestId,
                        "expected_sequence_number" to e.expectedSequenceNumber,
                        "received_sequence_number" to e.receivedSequenceNumber
                    ))
                    delay(BackgroundRecoveryPolicy.jitteredDelayMs(retryDelayMs))
                    retryDelayMs = BackgroundRecoveryPolicy.nextDelayMs(retryDelayMs)
                } catch (e: ApiHttpException) {
                    activeCall = null
                    when (BackgroundRecoveryPolicy.actionForHttpStatus(e.statusCode)) {
                        BackgroundRecoveryAction.AUTH_FAILURE -> {
                            diagnostics.log("worker_auth_failure", mapOf(
                                "chat_id" to chatId,
                                "response_id" to responseId,
                                "status" to e.statusCode
                            ))
                            db.clearActiveResponse(chatId, expectedRequestId = requestId)
                            return Result.failure()
                        }
                        BackgroundRecoveryAction.RESPONSE_GONE -> {
                            diagnostics.log("worker_response_gone", mapOf(
                                "chat_id" to chatId,
                                "response_id" to responseId,
                                "status" to e.statusCode
                            ))
                            db.clearActiveResponse(chatId, expectedRequestId = requestId)
                            return Result.success()
                        }
                        BackgroundRecoveryAction.RETRY -> {
                            val waitMs = e.retryAfterMs?.coerceIn(1_000L, 300_000L)
                                ?: retryDelayMs.coerceAtMost(60_000L)
                            diagnostics.log("worker_http_retry", mapOf(
                                "chat_id" to chatId,
                                "response_id" to responseId,
                                "status" to e.statusCode,
                                "retry_after_ms" to waitMs
                            ))
                            delay(if (e.retryAfterMs != null) waitMs else BackgroundRecoveryPolicy.jitteredDelayMs(waitMs))
                            retryDelayMs = BackgroundRecoveryPolicy.nextDelayMs(retryDelayMs)
                        }
                        BackgroundRecoveryAction.FATAL -> {
                            diagnostics.log("worker_http_fatal", mapOf(
                                "chat_id" to chatId,
                                "response_id" to responseId,
                                "status" to e.statusCode
                            ))
                            db.clearActiveResponse(chatId, expectedRequestId = requestId)
                            return Result.failure()
                        }
                    }
                } catch (e: Exception) {
                    activeCall = null
                    if (isStopped) return Result.success()
                    diagnostics.log("worker_stream_failed", mapOf(
                        "chat_id" to chatId,
                        "response_id" to responseId,
                        "error" to (e.message ?: e.toString()).take(400)
                    ))
                    delay(BackgroundRecoveryPolicy.jitteredDelayMs(retryDelayMs))
                    retryDelayMs = BackgroundRecoveryPolicy.nextDelayMs(retryDelayMs)
                }
            }
            return Result.success()
        } finally {
            activeCall?.cancel()
            try { db.close() } catch (_: Exception) {}
            try { streamClient.close() } catch (_: Exception) {}
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    private fun extractResponseText(response: JSONObject): String {
        val output = response.optJSONArray("output") ?: return ""
        val out = StringBuilder()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") != "message") continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val c = content.optJSONObject(j) ?: continue
                if (c.optString("type") == "output_text") out.append(c.optString("text"))
            }
        }
        return out.toString()
    }

    private fun finalize(chatId: String, responseId: String, requestId: String, response: JSONObject, db: ChatDatabase, diagnostics: AstraDiagnostics) {
        val active = db.getActiveResponse(chatId) ?: return
        if (active.optString("request_id") != requestId) return
        val state = response.optString("status").ifBlank { active.optString("status") }
        val fallbackText = active.optString("text")
        val text = extractResponseText(response).ifBlank { fallbackText }
        val model = AstraResponseModel.fromResponse(
            response, text, requestId, state,
            response.optJSONObject("error")?.optString("message")
                ?: response.optJSONObject("incomplete_details")?.optString("reason")
        )
        val savedImages = saveGeneratedImages(model.generatedImageResults, diagnostics)
        val assistant = model.toChatJson().apply {
            if (savedImages.length() > 0) put("generated_images", savedImages)
        }

        val chatObj = db.getChat(chatId) ?: return
        val arr = chatObj.optJSONArray("messages") ?: JSONArray()
        val messages = mutableListOf<JSONObject>()
        var already = false
        for (i in 0 until arr.length()) {
            val message = arr.optJSONObject(i) ?: continue
            if (message.optString("response_id") == responseId) already = true
            messages += JSONObject(message.toString())
        }
        if (!already && state != "cancelled") messages += assistant

        val effortPref = active.optString("effort_pref").ifBlank { "high" }
        val effectiveEffort = active.optString("effective_effort").ifBlank { "high" }
        val startedAt = active.optLong("started_at", System.currentTimeMillis())
        val latency = (System.currentTimeMillis() - startedAt).coerceAtLeast(0L)
        val usage = response.optJSONObject("usage")
        val inTok = usage?.optLong("input_tokens", 0L) ?: 0L
        val outTok = usage?.optLong("output_tokens", 0L) ?: 0L
        val cachedTok = usage?.optJSONObject("input_tokens_details")?.optLong("cached_tokens", 0L) ?: 0L
        val cacheWriteTok = usage?.optJSONObject("input_tokens_details")?.optLong("cache_write_tokens", 0L) ?: 0L
        val reasoningTok = usage?.optJSONObject("output_tokens_details")?.optLong("reasoning_tokens", 0L) ?: 0L
        val cost = estimateCost(inTok, cachedTok, cacheWriteTok, outTok)
        val committed = db.upsertCurrentChatIfActiveRequest(
            chatId, chatObj.optString("title", "Новый чат"), responseId.takeIf { it.isNotBlank() }, effectiveEffort, messages, requestId
        )
        if (!committed) {
            diagnostics.log("worker_stale_finalization_ignored", mapOf("chat_id" to chatId, "response_id" to responseId, "request_id" to requestId))
            return
        }
        db.recordEffortMetric(
            requestId, chatId, "resumed", effortPref, effectiveEffort, active.optBoolean("auto_selected", false),
            active.optInt("prompt_chars", 0), active.optInt("attachments", 0), latency,
            inTok, cachedTok, cacheWriteTok, outTok, reasoningTok, state == "completed",
            if (state == "completed") null else state.ifBlank { "background_failed" }, cost
        )
        db.clearActiveResponse(chatId, expectedRequestId = requestId)
        diagnostics.log("worker_response_finalized", mapOf("chat_id" to chatId, "response_id" to responseId, "request_id" to requestId, "status" to state, "latency_ms" to latency, "estimated_cost_usd" to "%.6f".format(cost)))
    }

    private fun estimateCost(inputTokens: Long, cachedTokens: Long, cacheWriteTokens: Long, outputTokens: Long): Double {
        val longContext = inputTokens > 272_000
        val inMultiplier = if (longContext) 2.0 else 1.0
        val outMultiplier = if (longContext) 1.5 else 1.0
        val uncached = (inputTokens - cachedTokens - cacheWriteTokens).coerceAtLeast(0)
        return (uncached * (10.0 * inMultiplier) + cachedTokens * (1.0 * inMultiplier) + cacheWriteTokens * (12.50 * inMultiplier) + outputTokens * (50.0 * outMultiplier)) / 1_000_000.0
    }

    private fun saveGeneratedImages(results: List<String>, diagnostics: AstraDiagnostics): JSONArray {
        val out = JSONArray()
        if (results.isEmpty()) return out
        val maxEncodedBytes = 72L * 1024L * 1024L
        val maxDecodedBytes = 50L * 1024L * 1024L
        val dir = File(applicationContext.filesDir, "generated_images").apply { mkdirs() }
        results.take(4).forEachIndexed { index, encoded ->
            try {
                if (encoded.length.toLong() > maxEncodedBytes) throw IOException("generated image too large")
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
                if (bytes.size.toLong() > maxDecodedBytes) throw IOException("generated image too large")
                if (bytes.size < 8 || (bytes[0].toInt() and 0xFF) != 0x89 || (bytes[1].toInt() and 0xFF) != 0x50 || (bytes[2].toInt() and 0xFF) != 0x4E || (bytes[3].toInt() and 0xFF) != 0x47) throw IOException("expected PNG")
                val file = File(dir, "astra_${System.currentTimeMillis()}_${System.nanoTime()}_$index.png")
                file.outputStream().use { it.write(bytes) }
                out.put(JSONObject().put("filename", file.name).put("local_path", file.absolutePath).put("mime_type", "image/png").put("size_bytes", file.length()).put("state", "READY"))
            } catch (e: Exception) {
                diagnostics.log("generated_image_failed", mapOf("error" to (e.message ?: e.toString()).take(300)))
            }
        }
        return out
    }

    private fun createForegroundInfo(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Astra — фоновые ответы", NotificationManager.IMPORTANCE_LOW))
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setContentTitle("Astra продолжает ответ")
            .setContentText(title.take(80))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(NOTIFICATION_ID, notification)
    }
}