package com.example.openaichat

import android.util.Base64
import org.json.JSONObject

/** Human-readable, non-truncated pre-publication details for the user's approval. */
object GitHubConfirmationPreview {
    private const val MAX_UI_PREVIEW_CHARS = 100_000

    fun text(name: String, a: JSONObject): String {
        val repository = a.optString("owner") + "/" + a.optString("repo")
        return when (name) {
            "create_issue_comment" ->
                "Опубликовать комментарий в $repository, issue/PR #${a.optInt("issue_number")}?" +
                    "\n\nПолный текст:\n${JsonProtocol.string(a, "body")}\n\nРазрешить публикацию?"
            "submit_pull_request_review" ->
                "Отправить review в $repository, PR #${a.optInt("pull_number")}?" +
                    "\nРешение: ${a.optString("event")}" +
                    "\n\nПолный текст:\n${JsonProtocol.string(a, "body").ifBlank { "(без текста)" }}" +
                    "\n\nРазрешить публикацию?"
            "merge_pull_request" ->
                "Объединить PR #${a.optInt("pull_number")} в $repository?" +
                    "\nНазвание: ${a.optString("_verified_title")}" +
                    "\nВетка PR: ${a.optString("_verified_head_ref")}" +
                    "\nЦелевая ветка: ${a.optString("_verified_base_ref")}" +
                    "\nHead SHA: ${a.optString("_verified_head_sha")}" +
                    "\nМетод: ${a.optString("merge_method")}" +
                    "\nПроверки: ${a.optString("_verified_workflows")}" +
                    "\n\nРазрешить объединение именно этой версии PR?"
            "create_or_update_file" -> {
                val path = JsonProtocol.string(a, "path")
                val branch = a.optString("branch")
                val message = JsonProtocol.string(a, "message")
                val content = if (!a.isNull("content")) {
                    JsonProtocol.string(a, "content")
                } else {
                    try {
                        String(Base64.decode(a.optString("content_base64"), Base64.DEFAULT), Charsets.UTF_8)
                    } catch (_: Exception) { "" }
                }
                val bytesCount = content.toByteArray(Charsets.UTF_8).size
                val shown = if (content.length > MAX_UI_PREVIEW_CHARS) {
                    content.take(MAX_UI_PREVIEW_CHARS) + "\n… [показаны первые $MAX_UI_PREVIEW_CHARS символов из ${content.length}] …"
                } else {
                    content
                }
                "Модель хочет записать файл в GitHub.\nРепозиторий: $repository" +
                    "\nВетка: $branch" +
                    "\nПуть: $path" +
                    (if (message.isNotBlank()) "\nКоммит: $message" else "") +
                    "\nРазмер: $bytesCount байт" +
                    "\n\nПолный текст файла:\n$shown" +
                    "\n\nРазрешить?"
            }
            "trigger_workflow_dispatch" ->
                "Модель хочет запустить GitHub Actions workflow ${a.optString("workflow_id")}" +
                    " на ветке/ref ${a.optString("ref")} в $repository.\n\nРазрешить?"
            else -> "Модель хочет выполнить GitHub-действие $name в $repository.\n\nРазрешить?"
        }
    }
}
