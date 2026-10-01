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
        val rawName = arguments.optString("filename").trim()
        val content = arguments.optString("content", "")
        if (rawName.isBlank()) throw IOException("filename is required")
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(160)
            .ifBlank { "generated.txt" }
        val dir = File(context.filesDir, "generated").apply { mkdirs() }
        val file = File(dir, UUID.randomUUID().toString() + "_" + safeName)
        file.writeText(content, Charsets.UTF_8)
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
