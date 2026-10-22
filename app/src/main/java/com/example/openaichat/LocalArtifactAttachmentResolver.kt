package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** A durable per-artifact manifest; paths alone never establish byte identity. */
object LocalArtifactAttachmentResolver {
    private val sha256Format = Regex("[0-9a-f]{64}")

    /** Checkpoint/history tools retain their captured ID while repairing only verified originals. */
    fun resolveForTools(context: Context, attachment: OpenRouterLocalAttachment): OpenRouterLocalAttachment =
        resolve(context, null, "", JSONObject().put("path", attachment.path).put("filename", attachment.name)
            .put("mime_type", attachment.mime).put("attachment_manifest", attachment.toJson()))

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
                !previous.sha256.matches(sha256Format)) throw IOException("Изменилась идентичность локального файла")
            OpenRouterAttachmentPreflight.verifySources(listOf(previous))
            fun verifiedCopy(path: String?, expected: String?): Boolean {
                if (path == null) return true
                val copy = File(path)
                if (!copy.isFile) return false
                // Original bytes were verified immediately above; do not read the
                // same file twice when a freshly created UTF-8 version is its own text source.
                if (copy.canonicalPath == file.canonicalPath) return expected == null || expected == previous.sha256
                val digest = expected ?: return false
                if (!digest.matches(sha256Format)) throw IOException("Повреждена идентичность производного файла")
                return OpenRouterAttachmentStore.sha256(copy) == digest
            }
            val textReady = previous.kind !in setOf("text", "document", "table") ||
                previous.textPath?.let { File(it).isFile } == true
            if (textReady && verifiedCopy(previous.textPath, previous.textSha256) &&
                verifiedCopy(previous.wirePath, previous.wireSha256)) return previous
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
        rebuilt = rebuilt.copy(
            id = previous?.id ?: rebuilt.id,
            encodingGuessed = if (previous?.kind == "text" && !previous.encodingGuessed && previous.textEncoding != null)
                false else rebuilt.encodingGuessed,
            textSha256 = rebuilt.textSha256 ?: rebuilt.textPath?.let { OpenRouterAttachmentStore.sha256(File(it)) },
            wireSha256 = rebuilt.wireSha256 ?: rebuilt.wirePath?.let { OpenRouterAttachmentStore.sha256(File(it)) }
        )
        // The database validates either an artifact row or an exact legacy
        // annotation reference; captured paths alone cannot create ownership.
        db?.persistLocalArtifactAttachment(chatId, path, rebuilt.size, rebuilt.sha256, rebuilt)
        return rebuilt
    }
}
