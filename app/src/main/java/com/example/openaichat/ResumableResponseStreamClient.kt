package com.example.openaichat

import android.content.Context
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

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
    context: Context,
    private val httpClient: OkHttpClient
) {
    private val secureStore = SecureStore(context.applicationContext)

    fun stream(
        responseId: String,
        startingAfter: Long,
        onCallCreated: (Call) -> Unit = {},
        onEvent: (JSONObject) -> Unit = {}
    ): ResumableStreamResult {
        if (responseId.isBlank()) throw IllegalArgumentException("responseId is blank")
        val key = secureStore.getApiKey()
        if (key.isBlank()) throw IOException("API key is not configured")

        val urlBuilder = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegment("v1")
            .addPathSegment("responses")
            .addPathSegment(responseId)
            .addQueryParameter("stream", "true")
        if (startingAfter >= 0L) {
            urlBuilder.addQueryParameter("starting_after", startingAfter.toString())
        }

        val request = Request.Builder()
            .url(urlBuilder.build())
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Accept", "text/event-stream")
            .build()

        val call = httpClient.newCall(request)
        onCallCreated(call)

        var lastSequenceNumber = startingAfter
        var terminalResponse: JSONObject? = null
        var terminalEventType: String? = null
        var requestId: String? = null
        val parser = SseEventParser()

        fun consume(data: String) {
            if (data == "[DONE]") return
            val event = try {
                JSONObject(data)
            } catch (e: Exception) {
                throw IOException("Invalid Responses SSE event", e)
            }

            requestId = event.optString("request_id").takeIf { it.isNotBlank() }
                ?: requestId

            val sequence = event.optLong("sequence_number", -1L)
            if (sequence >= 0L) {
                if (sequence <= lastSequenceNumber) return
                lastSequenceNumber = sequence
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
            }

            onEvent(event)
        }

        call.execute().use { response ->
            if (!response.isSuccessful) {
                val raw = response.body?.string().orEmpty()
                val retryAfterMs = response.header("Retry-After")?.trim()?.toLongOrNull()
                    ?.coerceIn(1L, 300L)?.times(1000L)
                throw ApiHttpException(response.code, retryAfterMs, raw.take(4096))
            }
            val body = response.body ?: throw IOException("Empty Responses SSE body")
            body.byteStream().bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    parser.acceptLine(line)?.let { consume(it.data) }
                }
            }
            parser.finish()?.let { consume(it.data) }
        }

        return ResumableStreamResult(
            lastSequenceNumber = lastSequenceNumber,
            terminalResponse = terminalResponse,
            terminalEventType = terminalEventType,
            requestId = requestId ?: responseId
        )
    }
}
