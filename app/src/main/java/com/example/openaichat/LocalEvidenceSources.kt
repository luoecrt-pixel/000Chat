package com.example.openaichat

import android.content.Context
import java.io.File

/** Chat-bound, read-only source discovery. Never extracts, uploads, or creates files. */
object LocalEvidenceSources {
    fun forChat(context: Context, chatId: String): List<OpenRouterLocalAttachment> {
        if (chatId.isBlank()) return emptyList()
        return ChatDatabase(context.applicationContext).use { db ->
            val sources = db.localAttachmentManifests(chatId).toMutableList()
            for (metadata in db.listLocalFiles(chatId)) {
                val manifest = metadata.optJSONObject("attachment_manifest") ?: metadata.optJSONObject("attachment")
                if (manifest != null) sources.add(OpenRouterLocalAttachment.fromJson(manifest))
                else if (metadata.optString("mime_type").startsWith("text/") ||
                    TextFileFormatPolicy.extension(metadata.optString("filename")) in setOf("json", "csv", "tsv", "md", "txt")) {
                    val path = JsonProtocol.nonBlank(metadata, "path") ?: continue
                    sources.add(OpenRouterLocalAttachment("local_" + File(path).name,
                        metadata.optString("filename", File(path).name), "text", path,
                        metadata.optString("mime_type", "text/plain"), File(path).length(),
                        metadata.optString("sha256"), textEncoding = "UTF-8"))
                }
            }
            sources.distinctBy { File(it.path).canonicalPath }
        }
    }
}
