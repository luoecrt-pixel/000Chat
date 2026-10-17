package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Bounded diagnostic projection; never serializes raw request, file, or round-ledger metadata. */
internal object RequestDiagnosticsSummary {
    private const val MAX_REQUESTS = 10
    private const val MAX_TOOLS = 32
    private const val MAX_COST_DETAILS_CHARS = 4_096
    private val toolName = Regex("[A-Za-z][A-Za-z0-9_]{0,63}")
    private val statuses = setOf("completed", "failed", "stopped", "running", "in_progress", "queued", "cancelled", "incomplete", "unknown")
    private val tiers = setOf("auto", "default", "priority", "flex", "scale", "standard", "batch", "performance", "fast", "on_demand", "reserved")
    private val moneyKeys = listOf("cost", "upstream_inference_cost", "upstream_inference_prompt_cost",
        "upstream_inference_completions_cost", "server_tool_cost")
    private val tokenKeys = listOf("prompt_tokens", "completion_tokens", "input_tokens", "output_tokens", "total_tokens")
    private val serverToolCounters = listOf("web_search_requests", "web_fetch_requests", "code_execution_requests",
        "code_interpreter_requests", "file_search_requests", "tool_search_requests", "image_generation_requests")

    /** Events are already enriched and ordered newest first by ChatDatabase.listRequestEvents. */
    fun format(events: List<JSONObject>): String {
        if (events.isEmpty()) return ""
        val text = buildString {
            append("=== Сводка последних запросов текущего чата ===\n")
            events.take(MAX_REQUESTS).forEachIndexed { index, event ->
                val status = JsonProtocol.string(event, "status").takeIf { it in statuses } ?: "unknown"
                val provider = JsonProtocol.string(event, "provider").takeIf { it in setOf("openai", "openrouter") } ?: "unknown"
                append("● Запрос ").append(index + 1).append(" · status=").append(status)
                    .append(" · provider=").append(provider).append(" · ").append(RequestCostFormatter.text(event)).append('\n')
                RequestEventFormatter.parseTelemetry(event)?.let { telemetry ->
                    if (telemetry.has("rounds_completed") || telemetry.has("calls_executed") || telemetry.has("executed_tools")) {
                        append("   Инструменты: раундов=").append(counter(telemetry, "rounds_completed"))
                            .append(", вызовов=").append(counter(telemetry, "calls_executed"))
                        telemetry.optJSONArray("executed_tools")?.let { names ->
                            val visible = (0 until minOf(names.length(), MAX_TOOLS)).map { i ->
                                val candidate = DiagnosticDisplayText.redact(JsonProtocol.string(names, i).trim())
                                when {
                                    candidate == "[REDACTED]" -> candidate
                                    toolName.matches(candidate) -> candidate
                                    else -> "[некорректное имя]"
                                }
                            }
                            if (visible.isNotEmpty()) append(" (").append(visible.joinToString(", ")).append(')')
                            if (names.length() > MAX_TOOLS) append(" [список сокращён]")
                        }
                        append('\n')
                    }
                    if (listOf("files", "source_read_passes", "source_read_bytes", "file_work_ms").any(telemetry::has)) {
                        append("   Файловый I/O: files=").append(counter(telemetry, "files"))
                            .append(", passes=").append(counter(telemetry, "source_read_passes"))
                            .append(", bytes=").append(counter(telemetry, "source_read_bytes"))
                            .append(", work_ms=").append(counter(telemetry, "file_work_ms")).append('\n')
                    }
                    telemetry.optJSONObject("cost_details")?.let { metadata ->
                        val clean = displayCostDetails(metadata)
                        if (clean.length() > 0) {
                            val details = DiagnosticDisplayText.redact(clean.toString())
                            append("   Детали расхода: ").append(details.take(MAX_COST_DETAILS_CHARS))
                            if (details.length > MAX_COST_DETAILS_CHARS) append(" [показ сокращён; данные сохранены]")
                            append('\n')
                        }
                    }
                }
            }
            if (events.size > MAX_REQUESTS) append("[Показаны только последние 10 запросов]\n")
            append('\n')
        }
        return DiagnosticDisplayText.redact(text)
    }

    private fun counter(value: JSONObject, key: String): String = value.opt(key)?.toString()?.toLongOrNull()
        ?.takeIf { it >= 0L }?.toString() ?: "неизвестно"

    /** Deliberately excludes model/error/arguments/paths and arbitrary metadata strings. */
    private fun displayCostDetails(source: JSONObject): JSONObject = JSONObject().apply {
        copyAmounts(source, this)
        source.optJSONObject("cost_details")?.let { nested ->
            val clean = JSONObject().also { copyAmounts(nested, it) }
            if (clean.length() > 0) put("cost_details", clean)
        }
        JsonProtocol.string(source, "service_tier").takeIf { it in tiers }?.let { put("service_tier", it) }
        source.optJSONObject("server_tool_use")?.let { counters ->
            val clean = JSONObject()
            serverToolCounters.forEach { key ->
                counters.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { clean.put(key, it) }
            }
            if (clean.length() > 0) put("server_tool_use", clean)
        }
        source.optJSONArray("iterations")?.let { iterations ->
            val clean = JSONArray()
            for (i in 0 until minOf(iterations.length(), MAX_TOOLS)) {
                val item = iterations.optJSONObject(i) ?: continue
                val entry = JSONObject()
                copyAmounts(item, entry)
                tokenKeys.forEach { key ->
                    item.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { entry.put(key, it) }
                }
                JsonProtocol.string(item, "service_tier").takeIf { it in tiers }?.let { entry.put("service_tier", it) }
                if (entry.length() > 0) clean.put(entry)
            }
            if (clean.length() > 0) put("iterations", clean)
        }
    }

    private fun copyAmounts(source: JSONObject, target: JSONObject) {
        moneyKeys.forEach { key ->
            source.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { target.put(key, it) }
        }
    }
}
