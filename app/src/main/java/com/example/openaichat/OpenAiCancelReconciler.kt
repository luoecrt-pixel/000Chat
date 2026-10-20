package com.example.openaichat

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Bounded reconciliation after local Stop: POST /cancel, then at most two GETs
 * for responses which have no final usage. No model generation is initiated.
 * Callers own the database, UI and accounting identity; this class does I/O only.
 */
class OpenAiCancelReconciler(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
    private val maxRetrieves: Int = 2,
    private val bodyLimits: BoundedResponseDecoder.BodyLimits = BoundedResponseDecoder.BodyLimits(),
    private val sleep: (Long) -> Boolean = { delay ->
        try { Thread.sleep(delay); true } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    },
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val wallClockMillis: () -> Long = System::currentTimeMillis,
    private val reconciliationTimeoutMs: Long = 60_000L
) {
    init { require(reconciliationTimeoutMs >= 0L) }

    data class Outcome(
        val cancelStatus: Int,
        val retrievals: Int,
        val acceptedSnapshots: Int,
        val terminalStatus: String?,
        val hasUsage: Boolean,
        val decoderDiagnostic: String? = null
    )

    fun reconcile(
        responseId: String,
        apiKey: String,
        onSnapshot: (JSONObject) -> Unit
    ): Outcome {
        // A response ID must be one URL path segment, never a client-supplied URL.
        require(Regex("resp_[A-Za-z0-9_-]+").matches(responseId)) { "Invalid Responses identifier" }
        require(apiKey.isNotBlank()) { "Missing OpenAI credentials" }
        val endpoint = baseUrl.newBuilder().addPathSegment("responses")
            .addPathSegment(responseId).build()
        var accepted = 0
        var terminal: String? = null
        var hasUsage = false
        var terminalWithUsage = false
        var decoderDiagnostic: String? = null
        // This bounds usage retrieval I/O, not the generation or its recovery.
        // Even an exhausted polling budget must not prevent compensating /cancel.
        val startedAt = nowMillis()
        fun remainingMillis() = (reconciliationTimeoutMs - (nowMillis() - startedAt).coerceAtLeast(0L))
            .coerceAtLeast(0L)
        var retryAfterMs: Long? = null
        fun retryAfter(value: String?): Long? {
            val raw = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (raw.all { it in '0'..'9' }) {
                val seconds = raw.toLongOrNull() ?: return Long.MAX_VALUE
                return if (seconds > Long.MAX_VALUE / 1000L) Long.MAX_VALUE else seconds * 1000L
            }
            return try {
                val at = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                (at - wallClockMillis()).coerceAtLeast(0L)
            } catch (_: Exception) { null }
        }
        fun observe(body: String?) {
            if (body.isNullOrBlank()) return
            val json = try { JSONObject(body) } catch (_: Exception) { return }
            if (json.optString("id") != responseId) return
            val state = json.optString("status")
            if (state !in setOf("queued", "in_progress", "completed", "cancelled", "failed", "incomplete")) return
            onSnapshot(json)
            accepted++
            if (state in setOf("completed", "cancelled", "failed", "incomplete")) terminal = state
            val usage = json.optJSONObject("usage")
            if (usage != null && usage.has("input_tokens") && usage.has("output_tokens") &&
                !usage.isNull("input_tokens") && !usage.isNull("output_tokens")) {
                hasUsage = true
                if (state in setOf("completed", "cancelled", "failed", "incomplete")) {
                    terminalWithUsage = true
                }
            }
        }
        val cancel = Request.Builder()
            .url(endpoint.newBuilder().addPathSegment("cancel").build())
            .header("Authorization", "Bearer $apiKey")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val code = http.newCall(cancel).execute().use { response ->
            val status = response.code
            retryAfterMs = retryAfter(response.header("Retry-After"))
            if (status in 200..299) observe(BoundedResponseDecoder.readBody(response.body, bodyLimits))
            else BoundedResponseDecoder.readErrorBody(response.body, onDecodeFailure = {
                decoderDiagnostic = "HTTP $status ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            })
            status
        }
        // Authentication, rate-limit and server failures do not trigger extra calls.
        if (code !in 200..299 && code != 404 && code != 409) {
            return Outcome(code, 0, accepted, terminal, hasUsage, decoderDiagnostic)
        }
        if (terminalWithUsage) return Outcome(code, 0, accepted, terminal, hasUsage, decoderDiagnostic)
        var count = 0
        for (attempt in 0 until maxRetrieves.coerceIn(0, 2)) {
            if (Thread.currentThread().isInterrupted) break
            val waitMs = maxOf(250L * (attempt + 1L), retryAfterMs ?: 0L)
            // Never shorten a provider's Retry-After merely to fit the budget.
            if (waitMs >= remainingMillis()) break
            val waited = try { sleep(waitMs) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!waited || Thread.currentThread().isInterrupted) break
            val remaining = remainingMillis()
            if (remaining <= 0L) break
            val get = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").get().build()
            val call = http.newCall(get)
            val configuredTimeout = call.timeout().timeoutNanos()
            call.timeout().timeout(if (configuredTimeout > 0L) minOf(remaining,
                TimeUnit.NANOSECONDS.toMillis(configuredTimeout).coerceAtLeast(1L)) else remaining, TimeUnit.MILLISECONDS)
            val responseCode = call.execute().use { response ->
                val status = response.code
                retryAfterMs = retryAfter(response.header("Retry-After"))
                if (status in 200..299) observe(BoundedResponseDecoder.readBody(response.body, bodyLimits))
                else BoundedResponseDecoder.readErrorBody(response.body, onDecodeFailure = {
                    decoderDiagnostic = "HTTP $status ${it.javaClass.simpleName}: ${it.message.orEmpty()}"
                })
                status
            }
            count++
            if (responseCode !in 200..299 || terminalWithUsage) break
        }
        return Outcome(code, count, accepted, terminal, hasUsage, decoderDiagnostic)
    }
}
