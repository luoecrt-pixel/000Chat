package com.example.openaichat

object ResponseStatusLabel {
    fun restored(status: String?) = when (status) {
        "failed" -> "Последний ответ завершился с ошибкой"
        "disconnected" -> "Соединение прервано; частичный ответ сохранён"
        "incomplete" -> "Последний ответ был неполным"
        "cancelled", "stopped" -> "Ответ остановлен"
        "queued", "in_progress" -> "Ответ ещё не завершён"
        "completed", null, "" -> "Готово"
        else -> "Последний ответ не подтверждён как завершённый"
    }
}
