package com.example.openaichat

import org.json.JSONObject

/** Human-readable, non-truncated pre-publication details for the user's approval. */
object GitHubConfirmationPreview {
    fun text(name: String, a: JSONObject): String {
        val repository = a.optString("owner") + "/" + a.optString("repo")
        return when (name) {
            "create_issue_comment" ->
                "Опубликовать комментарий в $repository, issue/PR #${a.optInt("issue_number")}?" +
                    "\n\nПолный текст:\n${a.optString("body")}\n\nРазрешить публикацию?"
            "submit_pull_request_review" ->
                "Отправить review в $repository, PR #${a.optInt("pull_number")}?" +
                    "\nРешение: ${a.optString("event")}" +
                    "\n\nПолный текст:\n${a.optString("body").ifBlank { "(без текста)" }}" +
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
            else -> "Разрешить GitHub-действие $name в $repository?"
        }
    }
}
