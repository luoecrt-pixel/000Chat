package com.example.openaichat

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/**
 * Bounded reconciliation after local Stop: POST /cancel, then at most two GETs
 * for responses which have no final usage. No model generation is initiated.
 * Callers own the database, UI and accounting identity; this class does I/O only.
 */
class OpenAiCancelReconciler(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.openai.com/v1/".toHttpUrl(),
    private val maxRetrieves: Int = 2
) {
    data class Outcome(
        val cancelStatus: Int,
        val retrievals: Int,
        val acceptedSnapshots: Int,
        val terminalStatus: String?,
        val hasUsage: Boolean
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
            if (usage != null && !usage.isNull("input_tokens") && !usage.isNull("output_tokens") &&
                (usage.has("input_tokens") || usage.has("output_tokens"))) {
                hasUsage = true
            }
        }
        val cancel = Request.Builder()
            .url(endpoint.newBuilder().addPathSegment("cancel").build())
            .header("Authorization", "Bearer $apiKey")
            .post("{}".toRequestBody("application/json".toMediaType()))
            .build()
        val code = http.newCall(cancel).execute().use { response ->
            val status = response.code
            if (status in 200..299) observe(response.body?.string())
            status
        }
        // Authentication, rate-limit and server failures do not trigger extra calls.
        if (code !in 200..299 && code != 404 && code != 409) {
            return Outcome(code, 0, accepted, terminal, hasUsage)
        }
        if (terminal != null && hasUsage) return Outcome(code, 0, accepted, terminal, hasUsage)
        var count = 0
        for (attempt in 0 until maxRetrieves.coerceIn(0, 2)) {
            val get = Request.Builder().url(endpoint).header("Authorization", "Bearer $apiKey").get().build()
            val responseCode = http.newCall(get).execute().use { response ->
                val status = response.code
                if (status in 200..299) observe(response.body?.string())
                status
            }
            count++
            if (responseCode !in 200..299 || (terminal != null && hasUsage)) break
        }
        return Outcome(code, count, accepted, terminal, hasUsage)
    }
}
