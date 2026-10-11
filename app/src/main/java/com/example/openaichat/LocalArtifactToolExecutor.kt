package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

class LocalArtifactToolExecutor(private val context: Context) {
    fun execute(name: String, arguments: JSONObject): String {
        return when (name) {
            "create_local_text_file" -> createTextFile(arguments).toString()
            else -> throw IOException("Unknown local artifact tool: $name")
        }
    }

    fun createTextFile(arguments: JSONObject): JSONObject {
        val rawName = JsonProtocol.nonBlank(arguments, "filename")?.trim() ?: throw IOException("filename is required")
        val content = JsonProtocol.text(arguments, "content") ?: throw IOException("content must be a string")
        if (rawName.isBlank()) throw IOException("filename is required")
        if (TextFileFormatPolicy.extension(rawName) in TextFileFormatPolicy.office + setOf("pdf", "doc", "xls", "ppt", "png", "jpg", "jpeg", "gif", "webp", "zip", "mp3", "mp4"))
            throw IOException("create_local_text_file создаёт UTF-8 текст, а не настоящий бинарный ${TextFileFormatPolicy.extension(rawName)}. Используйте текстовый формат, например TXT, Markdown, CSV или исходный код.")
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(160)
            .ifBlank { "generated.txt" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + "_" + safeName)
        try {
            // A whole String -> UTF-8 ByteArray duplicates a potentially very large answer.
            file.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { writer ->
                var offset = 0
                while (offset < content.length) {
                    val count = minOf(64 * 1024, content.length - offset)
                    writer.write(content, offset, count)
                    offset += count
                }
            }
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        return JSONObject()
            .put("ok", true)
            .put("type", "local_file")
            .put("filename", rawName)
            .put("path", file.absolutePath)
            .put("mime_type", mimeForFilename(rawName))
            .put("size_bytes", file.length())
    }

    private fun mimeForFilename(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt","log" -> "text/plain"
        "md","markdown" -> "text/markdown"
        "kt","java","kotlin","py","js","ts","xml","yaml","yml","csv" -> "text/plain"
        "json" -> "application/json"
        else -> "text/plain"
    }
}
