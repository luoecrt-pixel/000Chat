package com.example.openaichat

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Whole non-image files in Python; vision images keep their original input_image wire source. */
object OpenAiFileAnalysis {
    fun prepare(history: List<JSONObject>, active: () -> Boolean,
                upload: (File, String, String) -> String): Map<String, String> {
        val files = linkedMapOf<String, String>()
        // Historical IDs have no reliable account/project provenance. Resolve every owned
        // source through the credential- and byte-keyed upload cache instead of trusting them.
        // The activity materializes and persists these manifests before entering this method;
        // this also supports callers restoring legacy messages without a manifest yet.
        OpenAiLocalContextEncoder.materialize(history, true) { _, _ -> }
        history.filter { it.optString("role") == "user" }.forEach { message ->
            AttachmentHistoryPolicy.validate(message)
            val manifest = message.optJSONArray("attachments") ?: org.json.JSONArray()
            for (i in 0 until manifest.length()) {
                if (!active()) throw IOException("Подготовка файлов отменена")
                val a = OpenRouterLocalAttachment.fromJson(manifest.getJSONObject(i))
                // Enabling file analysis for a document does not opt its companion photos
                // into a second, unnecessary upload. Keep source verification here, then
                // the context encoder sends input_image independently of the container.
                if (a.kind == "image") {
                    OpenRouterAttachmentPreflight.verifySources(listOf(a))
                    continue
                }
                if (a.kind !in setOf("text", "document", "table", "pdf", "container", "container_document")) continue
                OpenRouterAttachmentPreflight.verifySources(listOf(a))
                if (files.containsKey(a.path)) continue
                val nativeOffice = TextFileFormatPolicy.extension(a.name) in setOf("xlsx", "docx", "pptx")
                val nativeVisual = a.kind in setOf("pdf", "container", "container_document")
                val source = File(if (nativeOffice || nativeVisual) a.path else a.textPath ?: a.path)
                if (!source.isFile) throw IOException("Файл для полного анализа недоступен: ${a.name}")
                files[a.path] = upload(source, if (nativeOffice || nativeVisual) a.name else a.name + ".txt",
                    if (nativeOffice || nativeVisual) a.mime else "text/plain")
            }
        }
        return files
    }
}
