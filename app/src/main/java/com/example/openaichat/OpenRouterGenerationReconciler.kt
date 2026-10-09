package com.example.openaichat

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Bounded read-only OpenRouter generation reconciliation. Every request is
 * an already-observed generation ID, never a new model completion. The metadata
 * callback is responsible for immutable local request and round ownership.
 */
class OpenRouterGenerationReconciler(
    private val http: OkHttpClient,
    private val baseUrl: HttpUrl = "https://openrouter.ai/api/v1/".toHttpUrl(),
    private val maxLookups: Int = 4
) {
    fun reconcile(
        rounds: List<JSONObject>,
        apiKey: String,
        onMetadata: (generationId: String, metadata: JSONObject) -> Unit
    ): Int {
        if (apiKey.isBlank()) return 0
        var attempts = 0
        val candidates = rounds.asSequence()
            .filter { it.optString("cost_kind", "unknown") != "provider_reported" }
            .mapNotNull { it.optString("generation_id").takeIf { id ->
                id.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            } }
            .distinct()
            .take(maxLookups.coerceIn(0, 4))
        for (generationId in candidates) {
            val url = baseUrl.newBuilder().addPathSegment("generation")
                .addQueryParameter("id", generationId).build()
            val request = Request.Builder().url(url)
                .header("Authorization", "Bearer $apiKey").get().build()
            var code = 0
            var metadata: JSONObject? = null
            try {
                http.newCall(request).execute().use { response ->
                    code = response.code
                    metadata = if (response.isSuccessful) {
                        try { JSONObject(response.body?.string().orEmpty()).optJSONObject("data") }
                        catch (_: Exception) { null }
                    } else null
                }
            } catch (_: Exception) {
                break
            }
            attempts++
            if (code == 401 || code == 403 || code == 429 || code >= 500) break
            val verified = metadata ?: continue
            if (code != 200 || verified.optString("id") != generationId) continue
            // total_cost is the USD amount; 'usage' is not a token usage JSON here.
            val valid = verified.opt("total_cost")?.toString()?.toDoubleOrNull()
                ?.takeIf { it.isFinite() && it >= 0.0 }
            if (valid != null) onMetadata(generationId, verified)
        }
        return attempts
    }
}
