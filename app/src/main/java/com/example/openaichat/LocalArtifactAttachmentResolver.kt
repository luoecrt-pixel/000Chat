package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A durable per-artifact manifest; paths alone never establish byte identity. */
object LocalArtifactAttachmentResolver {
    fun resolve(context: Context?, db: ChatDatabase?, chatId: String, metadata: JSONObject): OpenRouterLocalAttachment {
        if (Thread.currentThread().isInterrupted) throw IOException("Подготовка файла отменена")
        JsonProtocol.nonBlank(metadata, "chat_id")?.let { if (it != chatId) throw IOException("Файл принадлежит другому чату") }
        if (metadata.has("attachment_manifest") && metadata.optJSONObject("attachment_manifest") == null)
            throw IOException("Повреждён manifest локального файла")
        val path = JsonProtocol.nonBlank(metadata, "path") ?: throw IOException("Нет пути локального файла")
        val file = File(path)
        if (!file.isFile) throw IOException("Локальный файл недоступен")
        val manifest = metadata.optJSONObject("attachment_manifest") ?: metadata.optJSONObject("attachment")
        val previous = manifest?.let(OpenRouterLocalAttachment::fromJson)
        if (previous != null) {
            if (File(previous.path).canonicalPath != file.canonicalPath || previous.size != file.length() ||
                !previous.sha256.matches(Regex("[0-9a-f]{64}"))) throw IOException("Изменилась идентичность локального файла")
            OpenRouterAttachmentPreflight.verifySources(listOf(previous))
            val textReady = previous.kind !in setOf("text", "document", "table") ||
                previous.textPath?.let { File(it).isFile } == true
            val wireReady = previous.wirePath?.let { File(it).isFile } != false
            if (textReady && wireReady) return previous
        }
        val name = JsonProtocol.string(metadata, "filename", file.name).ifBlank { file.name }
        val mime = JsonProtocol.nonBlank(metadata, "mime_type")
        var rebuilt = when {
            context != null && mime != null -> OpenRouterAttachmentStore.classifyLocalFile(context, file, name, mime)
            context != null -> OpenRouterAttachmentStore.classifyLocalFile(context, file, name)
            mime != null -> OpenRouterAttachmentStore.classifyLocalFile(file, name, mime)
            else -> OpenRouterAttachmentStore.classifyLocalFile(file, name)
        }
        if (previous?.kind == "text" && !previous.encodingGuessed && previous.textEncoding != null &&
            rebuilt.textEncoding != previous.textEncoding) {
            val automatic = rebuilt
            rebuilt = OpenRouterAttachmentStore.withTextEncoding(rebuilt, previous.textEncoding)
            automatic.textPath?.takeIf { it != rebuilt.textPath }?.let { File(it).delete() }
        }
        OpenRouterAttachmentPreflight.verifySources(listOf(rebuilt))
        // The database validates either an artifact row or an exact legacy
        // annotation reference; captured paths alone cannot create ownership.
        db?.persistLocalArtifactAttachment(chatId, path, rebuilt.size, rebuilt.sha256, rebuilt)
        return rebuilt
    }
}
