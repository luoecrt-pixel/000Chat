package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Display-only snapshot. It never retains credentials, prompts, paths or signed reasoning. */
data class ContextStatusSnapshot(
    val model: String,
    val route: String,
    val mode: String,
    val explanation: String,
    val messages: Int,
    val textCharacters: Long,
    val attachmentNames: List<String>,
    val tools: List<String>,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val lastResponseAgeMillis: Long?,
    val lastResponseProvider: String? = null,
    val lastResponseModel: String? = null
) {
    val badgeText: String get() = "Контекст · $mode"
    fun details(): String = buildString {
        append("Модель: $model\nМаршрут: $route\nКонтекст: $mode\n$explanation\n\n")
        append("История: $messages сообщений · $textCharacters символов видимого текста\n")
        append("Вложения · уникальные имена: ${attachmentNames.size}")
        if (attachmentNames.isNotEmpty()) append("\n" + attachmentNames.joinToString("\n") { "• $it" })
        append("\nИнструменты: " + tools.joinToString(", ").ifEmpty { "отключены" })
        if (lastResponseProvider != null || lastResponseModel != null)
            append("\n\nПоследний запрос: " + listOfNotNull(lastResponseProvider, lastResponseModel).joinToString(" · "))
        append("\n\nПоследний ответ: " + (lastResponseAgeMillis?.let { "${it / 1000} с назад" } ?: "время неизвестно"))
        append("\nПолучено из кэша: " + (cacheReadTokens?.toString() ?: "неизвестно"))
        append("\nЗаписано в кэш: " + (cacheWriteTokens?.toString() ?: "неизвестно"))
        append("\n\nЭто локальная проверка готовности. Срок кэша и сохранённая цепочка не гарантируют серверный cache hit. Размер в токенах и полный wire-размер определяются при подготовке запроса.")
    }
}

object ContextStatusPolicy {
    private fun counter(source: JSONObject?, key: String): Long? =
        source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }

    fun build(
        provider: String, model: String, history: List<JSONObject>,
        previousResponseId: String?, openAiFingerprint: String,
        pendingManifests: Boolean, expectedOpenRouterBinding: String?,
        route: OpenRouterClaudeRoute, cacheMode: String,
        attachmentNames: List<String>, tools: List<String>,
        accounting: JSONObject?, lastResponseAtMillis: Long?, nowMillis: Long,
        openRouterPrefixMatches: Boolean? = null, openRouterPrefixReason: String? = null,
        lastRequest: ContextLastRequest? = null
    ): ContextStatusSnapshot {
        val last = history.lastOrNull()
        val reusable = provider == "openai" && !pendingManifests &&
            OpenAiInstructionChainPolicy.canReuse(previousResponseId, history, openAiFingerprint)
        val mode: String
        val reason: String
        if (provider == "openai") {
            mode = if (reusable) "цепочка" else "локальный replay"
            reason = when {
                reusable -> "Следующий обычный ход может использовать previous_response_id."
                pendingManifests -> "Локальные manifests требуют полного typed replay."
                last?.optString("status") != "completed" -> "Нет непосредственно предшествующего завершённого ответа OpenAI."
                last?.optString("provider") != "openai" -> "Предыдущий ход принадлежит другому провайдеру."
                last?.optString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD) != openAiFingerprint ->
                    "Изменились модель, аккаунт или инструкции либо отсутствует совместимая привязка."
                else -> "Нет совместимого идентификатора предыдущего ответа."
            }
        } else {
            mode = if (OpenRouterClaudeRoute.isClaude(model)) "кэш $cacheMode" else "полная история"
            val previous = history.lastOrNull { it.optString("role") == "assistant" }
            val protocol = previous?.optJSONObject("openrouter_protocol")
            val saved = protocol?.let { JsonProtocol.nonBlank(it, "binding_sha256") }
            val bindingReason = when {
                previous?.optString("protocol_context_state") == "invalidated" -> "Подписанная история сброшена после изменения контекста."
                protocol == null -> "Сохранённой подписанной привязки нет."
                protocol.optInt("version") != 1 -> "Версия сохранённого протокола не поддерживается."
                protocol.optJSONArray("tail")?.length()?.let { it > 0 } != true -> "В сохранённом протоколе нет воспроизводимой истории."
                saved == null -> "Сохранённой подписанной привязки нет."
                saved == expectedOpenRouterBinding -> "Привязка модели, маршрута и инструментов сохранена."
                else -> "Привязка изменилась: модель, аккаунт, маршрут, формат или инструменты."
            }
            val prefixReason = when (openRouterPrefixMatches) {
                true -> "Полный локальный префикс совпадает с сохранённым."
                false -> openRouterPrefixReason ?: "Полный префикс изменился: системные инструкции, история или вложения."
                null -> openRouterPrefixReason ?: "Полный префикс, включая системные инструкции и вложения, проверяется при подготовке."
            }
            reason = "$bindingReason $prefixReason История при проверке не изменяется."
        }
        val usage = if (lastRequest != null) lastRequest.accounting else accounting
        val terminalAt = if (lastRequest != null) lastRequest.terminalAtMillis else lastResponseAtMillis
        return ContextStatusSnapshot(model,
            if (provider == "openai") "OpenAI Responses" else if (OpenRouterClaudeRoute.isClaude(model) && route == OpenRouterClaudeRoute.ANTHROPIC)
                "Anthropic через OpenRouter" else "OpenRouter · Auto",
            mode, reason, history.size, history.sumOf { JsonProtocol.string(it, "text").length.toLong() },
            attachmentNames.distinct(), tools.distinct(), counter(usage, "cached_tokens"),
            counter(usage, "cache_write_tokens"), terminalAt?.takeIf { it > 0L && it <= nowMillis }?.let { nowMillis - it },
            lastRequest?.provider, lastRequest?.model)
    }

    /** Only schema names are shown; arguments and hidden protocol data never enter the inspector. */
    fun toolNames(schemas: JSONArray): List<String> = (0 until schemas.length()).mapNotNull { i ->
        schemas.optJSONObject(i)?.let { item ->
            JsonProtocol.nonBlank(item.optJSONObject("function") ?: item, "name")
                ?: JsonProtocol.nonBlank(item, "type")
        }
    }
}
