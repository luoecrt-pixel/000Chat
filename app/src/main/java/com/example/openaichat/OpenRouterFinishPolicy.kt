package com.example.openaichat

object OpenRouterFinishPolicy {
    fun completed(reason: String?) = reason == "stop"
    fun description(reason: String?) = when (reason) {
        "length" -> "Достигнут предел выходных токенов модели, включая мышление. Ответ не завершён."
        "content_filter" -> "Ответ остановлен фильтром провайдера."
        "error" -> "Провайдер остановил ответ с ошибкой."
        null, "" -> "Провайдер не сообщил причину завершения. Полнота ответа не подтверждена."
        else -> "Ответ остановлен провайдером: $reason."
    }
}
