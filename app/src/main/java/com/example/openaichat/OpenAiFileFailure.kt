package com.example.openaichat

import android.content.Context
import org.json.JSONObject

/** Reactive eviction only: no per-turn probe, automatic upload or paid request retry. */
internal object OpenAiFileFailure {
    private val missing = Regex("(?i)(not found|does not exist|doesn't exist|no such file|has been deleted|was deleted|file is deleted)")
    fun recover(context: Context, error: JSONObject?, httpStatus: Int? = null): String? =
        recover(OpenAiFileUploadCache(context.getSharedPreferences("openai_file_upload_cache", Context.MODE_PRIVATE)), error, httpStatus)

    fun onEvent(context: Context, event: JSONObject): String? {
        val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
            ?: event.takeIf { JsonProtocol.string(it, "type") in setOf("error", "response.error") }
        val hint = recover(context, error) ?: return null
        error?.put("message", hint)
        return hint
    }

    fun recover(cache: OpenAiFileUploadCache, error: JSONObject?, httpStatus: Int? = null): String? {
        if (error == null || httpStatus != null && httpStatus !in setOf(400, 404, 410)) return null
        val message = JsonProtocol.string(error, "message")
        val code = JsonProtocol.string(error, "code")
        val explicitMissingFile = code in setOf("file_not_found", "file_deleted", "invalid_file_id") ||
            (message.contains("file", ignoreCase = true) && missing.containsMatchIn(message))
        if (!explicitMissingFile) return null
        val known = cache.knownIds()
        // A provider error can mention several uploads while reporting only one missing
        // file. Never evict every mentioned ID; without an explicit structured ID the
        // message must contain exactly one unambiguous complete file ID.
        val explicitId = JsonProtocol.text(error, "file_id")
        val affected = if (explicitId != null) {
            explicitId.takeIf { isOpenAiFileId(it) && it in known }
        } else {
            findOpenAiFileIds(message).distinct().toList()
                .takeIf { it.size == 1 }?.singleOrNull()?.takeIf { it in known }
        } ?: return null
        cache.invalidate(affected)
        return message + "\nФайл OpenAI больше недоступен. Его запись в кэше сброшена. " +
            "Повторите запрос с включённым полным анализом файлов либо приложите локальный оригинал заново. " +
            "Автоматического повтора не было."
    }

    fun fromHttp(context: Context, raw: String, status: Int): String {
        val payload = try { JSONObject(raw) } catch (_: Exception) { return raw }
        val error = payload.optJSONObject("error") ?: return raw
        recover(context, error, status)?.let { error.put("message", it) }
        return payload.toString()
    }
}
