package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DialogConversationExporterTest {
    private fun message(role: String, source: String) = JSONObject().put("role", role).put("text", source)

    @Test fun bothFormatsKeepRawUnicodeMathNestedFencesTablesAndMessageOrder() {
        val raw = "  Кириллица 😀\n\n\$x^2\$\n| a | b |\n|---|---|\n| 1 | 2 |\n````md\n```kt\nval x = 1\n```\n````\n  "
        for (format in DialogConversationExporter.Format.values()) {
            val exported = DialogConversationExporter.export("Диалог", listOf(message("user", raw), message("assistant", "После").put("ok", true)), format = format)
            assertTrue(exported.contains(raw))
            assertTrue(exported.indexOf(raw) < exported.indexOf("После"))
            assertTrue(exported.contains("Статус: неизвестно"))
            assertEquals(exported, exported.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8))
            assertEquals(format == DialogConversationExporter.Format.MARKDOWN, exported.startsWith("# "))
        }
    }

    @Test fun hiddenRolesAndProtocolMetadataNeverEnterExportButRawUserSecretsAreNotRewritten() {
        val user = message("user", "Literal user text sk-user-owned is retained")
            .put("reasoning_summary", "HIDDEN_REASONING").put("encrypted_content", "HIDDEN_ENCRYPTED")
            .put("checkpoint", "HIDDEN_CHECKPOINT").put("api_key", "HIDDEN_KEY")
        val exported = DialogConversationExporter.export("Title", JSONArray()
            .put(message("system", "HIDDEN_SYSTEM")).put(message("developer", "HIDDEN_DEVELOPER"))
            .put(message("tool", "HIDDEN_TOOL_PAYLOAD")).put(user))
        assertTrue(exported.contains("sk-user-owned"))
        assertFalse(exported.contains("HIDDEN_"))
    }

    @Test fun actualRouterTraceUsesOnlyKnownNamesAndExplicitStatusWithoutArbitraryMetadata() {
        val trace = JSONArray()
            .put(ToolTraceSummary.entry("file_info", JSONObject().put("path", "HIDDEN_PATH"), """{"ok":false,"error":"HIDDEN_ERROR"}"""))
            .put(JSONObject().put("name", "file_search").put("status", "cancelled").put("summary", "HIDDEN_SUMMARY"))
            .put(JSONObject().put("name", "HIDDEN_NAME").put("status", "HIDDEN_STATUS").put("ok", true))
        val exported = DialogConversationExporter.export("Title", listOf(message("assistant", "Answer").put("tool_trace", trace)))
        assertTrue(exported.contains("file_info — ошибка"))
        assertTrue(exported.contains("file_search — отменено"))
        assertTrue(exported.contains("инструмент — неизвестно"))
        assertFalse(exported.contains("HIDDEN_"))
    }

    @Test fun openAiCallsPairByCallIdAndSummarizeRealResultsWithoutRawArgumentsOrOutput() {
        val output = JSONArray()
            .put(JSONObject().put("type", "reasoning").put("encrypted_content", "HIDDEN_REASONING"))
            .put(JSONObject().put("type", "function_call").put("call_id", "a").put("name", "create_local_text_file").put("arguments", "HIDDEN_ARGUMENTS"))
            .put(JSONObject().put("type", "function_call").put("call_id", "b").put("name", "file_info").put("status", "completed"))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "a").put("output",
                JSONObject().put("ok", true).put("type", "local_file").put("filename", "report.md").put("path", "HIDDEN_PATH").put("payload", "HIDDEN_PAYLOAD").toString()))
            .put(JSONObject().put("type", "function_call_output").put("call_id", "b").put("output", """{"ok":false,"cancelled":true,"error":"HIDDEN_ERROR"}"""))
            .put(JSONObject().put("type", "web_search_call").put("status", "failed").put("action", "HIDDEN_QUERY"))
        val exported = DialogConversationExporter.export("Title", listOf(message("assistant", "Answer").put("openai_protocol_output", output)))
        assertTrue(exported.contains("create_local_text_file — готово; файл: report.md"))
        assertTrue(exported.contains("file_info — отменено"))
        assertTrue(exported.contains("web_search — ошибка"))
        assertFalse(exported.contains("HIDDEN_"))
    }

    @Test fun missingMalformedOrUnknownToolResultDoesNotPretendExecutionCompleted() {
        val calls = JSONArray()
        for ((id, result) in listOf("missing" to null, "bad" to "HIDDEN_BAD_JSON", "unknown" to """{"status":"alien","ok":true}""")) {
            calls.put(JSONObject().put("type", "function_call").put("call_id", id).put("name", "file_info").put("status", "completed"))
            result?.let { calls.put(JSONObject().put("type", "function_call_output").put("call_id", id).put("output", it)) }
        }
        val exported = DialogConversationExporter.export("Title", listOf(message("assistant", "Answer").put("openai_protocol_output", calls)))
        assertEquals(3, Regex("file_info — неизвестно").findAll(exported).count())
        assertFalse(exported.contains("готово")); assertFalse(exported.contains("HIDDEN_"))
    }

    @Test fun attachmentsExposeDisplayNamesFromBothProvidersWithoutPathsIdsOrPayloads() {
        val input = message("user", "Files")
            .put("file_names", JSONArray().put("данные.csv")).put("file_ids", JSONArray().put("HIDDEN_ID"))
            .put("file_paths", JSONArray().put("HIDDEN_PATH"))
            .put("image_name", "photo.png")
            .put("attachments", JSONArray().put(JSONObject().put("name", "notes.txt").put("path", "HIDDEN_PATH")))
        val answer = message("assistant", "Results").put("annotations", JSONArray().put(JSONObject()
            .put("type", "container_file_citation").put("filename", "result.pdf").put("local_path", "HIDDEN_PATH")))
            .put("generated_images", JSONArray().put(JSONObject().put("filename", "plot.png").put("base64", "HIDDEN_PAYLOAD")))
        val exported = DialogConversationExporter.export("Title", listOf(input, answer))
        for (name in listOf("данные.csv", "photo.png", "notes.txt", "result.pdf", "plot.png")) assertTrue(exported.contains(name))
        assertFalse(exported.contains("HIDDEN_"))
    }

    @Test fun activePartialReplacesSameRequestPlaceholderWithoutDuplicatingOrMutatingInputs() {
        val stored = message("assistant", "").put("request_id", "r").put("status", "in_progress")
        val active = JSONObject().put("request_id", "r").put("text", "  partial 😀  ").put("status", "in_progress")
        val exported = DialogConversationExporter.export("Title", listOf(stored), active)
        assertTrue(exported.contains("  partial 😀  "))
        assertTrue(exported.contains("незавершённый активный ответ"))
        assertEquals(1, Regex("## Ассистент").findAll(exported).count())
        assertEquals("", stored.getString("text")); assertFalse(active.has("role"))
        stored.put("text", "Final").put("status", "completed")
        val final = DialogConversationExporter.export("Title", listOf(stored), active)
        assertTrue(final.contains("Final")); assertFalse(final.contains("partial"))
        assertFalse(final.contains("незавершённый активный ответ"))
    }

    @Test fun activeOtherRequestIsAppendedAndHiddenActiveRoleIsRejected() {
        val stored = message("assistant", "Earlier").put("request_id", "a")
        val active = JSONObject().put("request_id", "b").put("text", "Current")
        val exported = DialogConversationExporter.export("Title", listOf(stored), active)
        assertTrue(exported.indexOf("Earlier") < exported.indexOf("Current"))
        assertTrue(exported.contains("незавершённый активный ответ"))
        active.put("role", "system").put("text", "HIDDEN_ACTIVE")
        assertFalse(DialogConversationExporter.export("Title", listOf(stored), active).contains("HIDDEN_ACTIVE"))
    }
}
