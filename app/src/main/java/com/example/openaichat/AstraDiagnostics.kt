package com.example.openaichat

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AstraDiagnostics(context: Context) {
    private val lock = Any()
    private val dir = File(context.filesDir, "diagnostics")
    private val file = File(dir, "astra.log")
    private val maxBytes = 512 * 1024L

    init { dir.mkdirs() }

    fun log(event: String, fields: Map<String, Any?> = emptyMap()) {
        val line = buildString {
            append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
            append(" ").append(event)
            fields.forEach { (k, v) ->
                val value = sanitizedValue(k, v)
                append(" ").append(k).append("=").append(value.take(512))
            }
            append('\n')
        }
        synchronized(lock) {
            try {
                if (file.exists() && file.length() > maxBytes) rotate()
                file.appendText(line, Charsets.UTF_8)
            } catch (_: Exception) {
                // Diagnostics must never break the chat.
            }
        }
        Log.d("AstraDiagnostics", line.trimEnd())
    }

    private fun sanitizedValue(field: String, raw: Any?): String {
        if (Regex("(?i)(token|secret|api.?key|authorization|password|credential)").containsMatchIn(field)) {
            return "[REDACTED]"
        }
        if (field in setOf("arguments", "prompt", "body", "request_body")) return "[CONTENT OMITTED]"
        var value = raw?.toString()?.replace("\n", " ")?.replace("\r", " ") ?: "null"
        value = value.replace(
            Regex("(?i)(Bearer\\s+)[A-Za-z0-9._-]+"),
            "$1[REDACTED]"
        )
        value = value.replace(
            Regex("(?i)([?&](?:api_key|token|access_token|key|secret)=)[^&\\s]+"),
            "$1[REDACTED]"
        )
        value = value.replace(
            Regex("(?i)(data:[a-z0-9.+/-]+;base64,)[A-Za-z0-9+/=]+"),
            "$1[BASE64 OMITTED]"
        )
        return value
    }

    fun tail(maxLines: Int = 80): String = synchronized(lock) {
        try {
            if (!file.exists()) return@synchronized "Диагностический лог пока пуст."
            file.readLines(Charsets.UTF_8).takeLast(maxLines.coerceIn(1, 300)).joinToString("\n")
        } catch (e: Exception) {
            "Не удалось прочитать лог: ${e.message}"
        }
    }

    fun clear() = synchronized(lock) {
        try { file.delete() } catch (_: Exception) {}
    }

    private fun rotate() {
        val old = File(dir, "astra.log.1")
        old.delete()
        if (file.exists()) file.renameTo(old)
    }
}
