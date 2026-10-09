package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Reconstructs typed Responses API input from owned local bytes, not provider file IDs.
 * Must run on a worker: history may contain multi-megabyte images and PDFs.
 */
object OpenAiLocalContextEncoder {
    fun encode(history: List<JSONObject>): JSONArray {
        val input = JSONArray()
        history.forEach { message ->
            val role = message.optString("role")
            if (role != "user" && role != "assistant") return@forEach
            val text = message.optString("text").trim()
            if (role == "assistant") {
                if (text.isNotEmpty()) {
                    input.put(JSONObject().put("role", role).put("content", text))
                }
                return@forEach
            }
            val parts = JSONArray()
            if (text.isNotEmpty()) parts.put(JSONObject().put("type", "input_text").put("text", text))
            val manifest = message.optJSONArray("attachments")
            val items = if (manifest != null && manifest.length() > 0) {
                (0 until manifest.length()).mapNotNull { idx ->
                    manifest.optJSONObject(idx)?.let(OpenRouterLocalAttachment::fromJson)
                }
            } else {
                legacyItems(message)
            }
            items.forEach { item ->
                if (!File(item.path).isFile) {
                    throw IOException("В истории не найдена локальная копия: " + item.name +
                        ". Восстановите файл перед отправкой в OpenAI.")
                }
                if (item.sha256.isNotBlank() && digest(File(item.path)) != item.sha256) {
                    throw IOException("Изменились байты вложения: " + item.name +
                        ". Проверьте исходный файл.")
                }
                when (item.kind) {
                    "image" -> parts.put(JSONObject()
                        .put("type", "input_image")
                        .put("image_url", OpenRouterAttachmentStore.toDataUrl(item)))
                    "pdf" -> parts.put(JSONObject()
                        .put("type", "input_file")
                        .put("filename", item.name)
                        .put("file_data", OpenRouterAttachmentStore.toDataUrl(item)))
                    "text" -> parts.put(JSONObject()
                        .put("type", "input_text")
                        .put("text", "<file name=\"" + item.name.replace("\"", "&quot;") +
                            "\">\n" + OpenRouterAttachmentStore.textContent(item) +
                            "\n</file>"))
                    else -> throw IOException("Формат файла не поддерживается OpenAI: " +
                        item.name + ". Удалите его из контекста или выберите совместимый файл.")
                }
            }
            // A lost response chain must not silently discard remote IDs.
            if (manifest == null || manifest.length() == 0) {
                val filesCount = message.optJSONArray("file_ids")?.length() ?: 0
                val imagesCount = if (message.optString("image_file_id").isNotBlank() &&
                    message.optString("image_file_id") != "null") 1 else 0
                val localFilesCount = message.optJSONArray("file_paths")?.length() ?: 0
                val hasLocalImage = message.optString("image_path").isNotBlank()
                if (filesCount > localFilesCount || imagesCount > 0 && !hasLocalImage) {
                    throw IOException("В истории есть вложение без доступных локальных байтов. " +
                        "Откройте исходный чат и восстановите файл перед переключением.")
                }
            }
            if (parts.length() > 0) {
                input.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        return input
    }

    private fun legacyItems(message: JSONObject): List<OpenRouterLocalAttachment> {
        val result = mutableListOf<OpenRouterLocalAttachment>()
        val paths = message.optJSONArray("file_paths")
        val names = message.optJSONArray("file_names")
        val mimes = message.optJSONArray("file_mimes")
        if (paths != null) for (idx in 0 until paths.length()) {
            val path = paths.optString(idx)
            if (path.isBlank()) continue
            val file = File(path)
            if (!file.isFile) throw IOException("Локальный файл в истории недоступен: " +
                names?.optString(idx, file.name))
            result += OpenRouterAttachmentStore.classifyLocalFile(
                file, names?.optString(idx, file.name) ?: file.name,
                mimes?.optString(idx, "") ?: ""
            )
        }
        message.optString("image_path").takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (!file.isFile) throw IOException("Изображение из истории недоступно: " +
                message.optString("image_name"))
            result += OpenRouterAttachmentStore.classifyLocalFile(
                file, message.optString("image_name", file.name),
                message.optString("image_mime", "image/*")
            )
        }
        return result
    }

    private fun digest(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { source ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buf)
                if (count < 0) break
                md.update(buf, 0, count)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
