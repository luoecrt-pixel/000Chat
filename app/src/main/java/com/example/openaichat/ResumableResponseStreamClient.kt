package com.example.openaichat

import android.content.Context
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class ResponseStreamSequenceGapException(
    val expectedSequenceNumber: Long,
    val receivedSequenceNumber: Long
) : IOException("Responses SSE sequence gap: expected $expectedSequenceNumber, received $receivedSequenceNumber")

data class ResumableStreamResult(
    val lastSequenceNumber: Long,
    val terminalResponse: JSONObject?,
    val terminalEventType: String?,
    val requestId: String?
)

/**
 * Raw Responses SSE transport used for reconnecting an already-created background response.
 *
 * The API resumes from the server-side cursor via starting_after. The caller owns event
 * accumulation/persistence because the same transport is used by Activity and WorkManager.
 */
class ResumableResponseStreamClient(
    context: Context
) {
    companion object {
        /** Trusted local HTTPS transport for instrumentation; never enabled in release. */
        @Volatile internal var transportForTests: Pair<OkHttpClient, HttpUrl>? = null
            set(value) {
                check(value == null || BuildConfig.DEBUG) { "Test transport requires a Debug build" }
                require(value == null || value.second.scheme == "https") { "Test transport requires HTTPS" }
                field = value
            }
    }

    private val appContext = context.applicationContext
    private val secureStore = SecureStore(context.applicationContext)
    private val rawFilesDir = context.applicationContext.filesDir
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }

    fun stream(
        responseId: String,
        startingAfter: Long,
        ownerChatId: String? = null,
        ownerRequestId: String? = null,
        onCallCreated: (Call) -> Unit = {},
        startedAtEpochMs: Long? = null,
        onEvent: (JSONObject) -> Unit = {}
    ): ResumableStreamResult {
        if (responseId.isBlank()) throw IllegalArgumentException("responseId is blank")
        val key = secureStore.getApiKey()
        if (key.isBlank() && (ownerChatId.isNullOrBlank() || ownerRequestId.isNullOrBlank()))
            throw IOException("API key is not configured")
        if (!ownerChatId.isNullOrBlank() && !ownerRequestId.isNullOrBlank()) {
            val binding = ChatDatabase(appContext).use { db ->
                db.getOpenAiRequestFingerprint(ownerChatId, ownerRequestId)
            }
            if (!OpenAiInstructionChainPolicy.matchesCredential(binding, key)) {
                throw UnrecoverableResponsesStreamException(
                    "Исходный API-ключ фонового ответа изменён или его привязка отсутствует; " +
                        "полученные данные сохранены локально")
            }
        }

        val transport = transportForTests
        val productionUrl = ResumableResponseRequest.url(responseId, startingAfter)
        val url = if (transport == null) productionUrl else productionUrl.newBuilder()
            .host(transport.second.host).port(transport.second.port).build()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "text/event-stream")
            .build()

        val call = (transport?.first ?: httpClient).newCall(request)
        startedAtEpochMs?.let { ResponseDeadlinePolicy.boundCall(call, it, System.currentTimeMillis()) }
        onCallCreated(call)

        val sequenceCursor = ResponseStreamSequenceCursor(startingAfter)
        var terminalResponse: JSONObject? = null
        var terminalEventType: String? = null
        var requestId: String? = null
        fun consume(event: JSONObject): Boolean {
            requestId = event.optString("request_id").takeIf { it.isNotBlank() }
                ?: requestId

            val sequence = event.optLong("sequence_number", -1L)
            if (sequence >= 0L) {
                if (sequenceCursor.observe(sequence) == ResponseStreamSequenceObservation.DUPLICATE) return false
            }

            val type = event.optString("type")
            when (type) {
                "response.completed",
                "response.failed",
                "response.incomplete",
                "response.cancelled" -> {
                    terminalEventType = type
                    terminalResponse = event.optJSONObject("response")
                }
                "error", "response.error" -> {
                    terminalEventType = type
                    terminalResponse = ResponseEventReducer.reduce(
                        ResponseEventState(responseId = responseId), event).state.terminalResponse
                }
            }

            onEvent(event)
            return true
        }

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val raw = BoundedResponseDecoder.readErrorBody(response.body)
                val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()
                    ?.coerceIn(1L, 300L)?.times(1000L)
                throw ApiHttpException(response.code, retryAfterMs, raw.take(4096))
            }
            val body = response.body ?: throw IOException("Empty Responses SSE body")
            val source = if (!ownerChatId.isNullOrBlank() && !ownerRequestId.isNullOrBlank()) {
                OpenAiRawSseArchive.begin(
                    body.byteStream(), rawFilesDir, ownerChatId, ownerRequestId)
            } else body.byteStream()
            OpenAiSseUtf8Reader.open(OpenAiSseWireByteLimitStream(source)).use { reader ->
                val outcome = OpenAiReceivedSseReader.read(reader, source as? OpenAiRawSseArchive) { consume(it) }
                if (outcome.errorMessage != null && terminalResponse == null) {
                    // A semantic SSE error is a terminal failure, not a transient
                    // disconnect to retry. Preserve the owned response identity.
                    terminalEventType = "response.failed"
                    terminalResponse = JSONObject()
                        .put("id", responseId)
                        .put("status", "failed")
                        .put("error", JSONObject().put("message", outcome.errorMessage))
                }
            }
        }

        return ResumableStreamResult(
            lastSequenceNumber = sequenceCursor.lastSequenceNumber,
            terminalResponse = terminalResponse,
            terminalEventType = terminalEventType,
            requestId = requestId ?: responseId
        )
    }
}
