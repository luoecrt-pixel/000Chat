package com.example.openaichat

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

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
        val attachments = history.filter { it.optString("role") == "user" }.flatMap { message ->
            AttachmentHistoryPolicy.validate(message)
            val manifest = message.optJSONArray("attachments") ?: org.json.JSONArray()
            (0 until manifest.length()).map { OpenRouterLocalAttachment.fromJson(manifest.getJSONObject(it)) }
        }
        // Reject a broken companion photo before paying to upload any other file, regardless
        // of picker order. Images still travel exclusively through the input_image encoder.
        attachments.forEach { attachment ->
            if (!active()) throw IOException("Подготовка файлов отменена")
            OpenRouterAttachmentPreflight.verifySources(listOf(attachment))
        }
        attachments.forEach { a ->
            if (!active()) throw IOException("Подготовка файлов отменена")
            if (a.kind !in setOf("text", "document", "table", "pdf", "container", "container_document")) return@forEach
            if (files.containsKey(a.path)) return@forEach
            val nativeOffice = TextFileFormatPolicy.extension(a.name) in setOf("xlsx", "docx", "pptx")
            val nativeBinary = a.kind in setOf("pdf", "container", "container_document")
            val original = File(a.path)
            val source = if (nativeOffice || nativeBinary) original else {
                // materialize() reconstructs missing derivatives using the persisted encoding.
                // If one disappears afterwards, fail rather than silently uploading encoded text
                // or an ODF archive as if it were normalized UTF-8 text.
                val normalized = a.textPath?.let(::File)?.takeIf { it.isFile }
                    ?: throw IOException("Нормализованный файл для полного анализа недоступен: ${a.name}")
                val originalSha = a.sha256.takeIf { it.isNotBlank() } ?: sha256(original, active)
                if (sha256(normalized, active) == originalSha) original else normalized
            }
            if (!source.isFile) throw IOException("Файл для полного анализа недоступен: ${a.name}")
            val native = nativeOffice || nativeBinary
            val keepName = native || (a.kind == "text" && TextFileFormatPolicy.knownText(a.name))
            val name = if (keepName) a.name else a.name + ".txt"
            val mime = if (native) a.mime else if (a.kind == "text") {
                a.mime.takeIf { it.isNotBlank() && it != "application/octet-stream" } ?: "text/plain"
            } else "text/plain"
            files[a.path] = upload(source, name, mime)
        }
        return files
    }

    private fun sha256(file: File, active: () -> Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (!active()) throw IOException("Подготовка файлов отменена")
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
