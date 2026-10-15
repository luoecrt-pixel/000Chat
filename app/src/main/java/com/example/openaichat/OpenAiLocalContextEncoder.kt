package com.example.openaichat

import android.content.Context
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
    private val closingFileTagPattern = Regex("(?i)</file")

    /**
     * For completed pre-v23 OpenAI history the signed reasoning/tool items
     * were never stored. Only explicit user consent can downgrade that turn
     * to clearly labelled visible text; never fabricate protocol output.
     */
    fun missingCompletedTypedOutput(history: List<JSONObject>): Int =
        history.count { message ->
            message.optString("role") == "assistant" &&
                message.optString("provider") == "openai" &&
                message.optJSONArray("openai_protocol_output") == null &&
                !OpenAiInterruptedTurnReplay.isInterrupted(message)
        }

    internal fun escapeXmlAttribute(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                '\r' -> append("&#13;")
                '\n' -> append("&#10;")
                else -> append(ch)
            }
        }
    }

    internal fun escapeClosingFileTags(content: String): String =
        if (!closingFileTagPattern.containsMatchIn(content)) content
        else closingFileTagPattern.replace(content) { match -> "&lt;" + match.value.drop(1) }

    private fun containsClosingFileTag(file: File): Boolean {
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            return lines.any { closingFileTagPattern.containsMatchIn(it) }
        }
    }

    private fun sanitizeLargeTextFile(source: File): File {
        val dir = source.parentFile ?: throw IOException("Нет каталога для безопасной сериализации файла")
        val sanitized = PreparedAttachmentFiles.derivative(dir, ".utf8")
        try {
            source.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
                sanitized.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    val buffer = CharArray(8192)
                    var carry = ""
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw IOException("Подготовка файла отменена")
                        val n = reader.read(buffer)
                        if (n < 0) break
                        val combined = carry + String(buffer, 0, n)
                        var safeCut = (combined.length - 6).coerceAtLeast(0)
                        // Keep a closing-tag prefix whole when it crosses the emit/carry boundary.
                        val crossingTag = closingFileTagPattern.find(combined, (safeCut - 5).coerceAtLeast(0))
                        if (crossingTag != null && crossingTag.range.first < safeCut) safeCut = crossingTag.range.first
                        val emit = combined.substring(0, safeCut)
                        carry = combined.substring(safeCut)
                        writer.write(escapeClosingFileTags(emit))
                    }
                    if (carry.isNotEmpty()) {
                        writer.write(escapeClosingFileTags(carry))
                    }
                }
            }
            return sanitized
        } catch (e: Exception) {
            sanitized.delete()
            throw e
        }
    }

    fun encode(
        history: List<JSONObject>,
        containerFiles: Map<String, String> = emptyMap(),
        onManifest: ((JSONObject, JSONObject) -> Unit)? = null
    ): JSONArray = encode(history, containerFiles, false, null, onManifest = onManifest)

    fun encode(
        history: List<JSONObject>,
        containerFiles: Map<String, String>,
        allowLegacyVisibleTextOnly: Boolean,
        onManifest: ((JSONObject, JSONObject) -> Unit)? = null
    ): JSONArray = encode(history, containerFiles, allowLegacyVisibleTextOnly, null, onManifest = onManifest)

    fun encode(
        history: List<JSONObject>,
        containerFiles: Map<String, String>,
        allowLegacyVisibleTextOnly: Boolean,
        context: Context?,
        localFileTools: Boolean = false,
        onManifest: ((JSONObject, JSONObject) -> Unit)? = null
    ): JSONArray {
        val input = JSONArray()
        history.forEach { message ->
            AttachmentHistoryPolicy.validate(message)
            val role = message.optString("role")
            if (role != "user" && role != "assistant") return@forEach
            val text = JsonProtocol.string(message, "text")
            if (role == "assistant") {
                if (message.optString("provider") == "openai") {
                    val interrupted = OpenAiInterruptedTurnReplay.isInterrupted(message)
                    val output = message.optJSONArray("openai_protocol_output")?.let(OpenAiLocalFileToolBridge::completePendingOutputs)
                    if (output == null && !interrupted) {
                        if (!allowLegacyVisibleTextOnly) {
                            throw IOException(
                                "Старый ответ OpenAI не содержит полного протокольного вывода. " +
                                "Для безопасного продолжения цепочки необходим исходный Responses output; " +
                                "только видимый текст не заменяет reasoning и результаты инструментов."
                            )
                        }
                        if (text.isBlank()) {
                            throw IOException("У старого завершённого ответа OpenAI нет даже видимого текста. " +
                                "Контекст не может быть безопасно восстановлен.")
                        }
                        input.put(JSONObject().put("role", "assistant").put("content",
                            "[Исторический ответ OpenAI: сохранён ТОЛЬКО видимый текст; " +
                            "подписанные reasoning и результаты инструментов отсутствуют, " +
                            "НЕ предполагайте их наличие.]\n" + text))
                        return@forEach
                    }
                    // Retain all actually received signed reasoning/tool/message items.
                    // These are NOT synthesized from the incomplete visible text.
                    if (output != null) for (index in 0 until output.length()) {
                        val item = output.optJSONObject(index)
                            ?: throw IOException("Недействительный элемент OpenAI output в локальной истории")
                        input.put(JSONObject(item.toString()))
                    }
                    if (interrupted) {
                        // The marker describes the unsuccessful turn, but only repeats
                        // fragments not already retained in its typed output above.
                        // Never strip message items: they also own refusal/annotations.
                        input.put(OpenAiInterruptedTurnReplay.asVisibleTranscript(message, output))
                    }
                } else if (text.isNotEmpty()) {
                    input.put(JSONObject().put("role", role).put("content", text))
                }
                return@forEach
            }
            val parts = JSONArray()
            if (text.isNotEmpty()) parts.put(JSONObject().put("type", "input_text").put("text", text))
            val manifest = message.optJSONArray("attachments")
            val manifestItems = if (manifest != null) {
                (0 until manifest.length()).mapNotNull { idx ->
                    manifest.optJSONObject(idx)?.let { OpenRouterLocalAttachment.fromJson(it) }
                }
            } else emptyList()
            // A lost response chain must not silently discard remote IDs.
            // Validate before mutating the message or persisting its manifest.
            val filesCount = message.optJSONArray("file_ids")?.length() ?: 0
            val imagesCount = if (JsonProtocol.string(message, "image_file_id").isNotBlank() &&
                JsonProtocol.string(message, "image_file_id") != "null") 1 else 0
            val localFilesCount = message.optJSONArray("file_paths")?.length() ?: 0
            val hasLocalImage = JsonProtocol.string(message, "image_path").isNotBlank() ||
                manifestItems.any { it.kind == "image" }
            if (filesCount > localFilesCount || imagesCount > 0 && !hasLocalImage) {
                throw IOException("В истории есть вложение без доступных локальных байтов. " +
                    "Откройте исходный чат и восстановите файл перед переключением.")
            }
            // A message may combine a preserved OR manifest and local OpenAI files.
            val before = JsonProtocol.objectCopy(message)
            val refreshed = manifestItems.map { item ->
                val missingText = item.kind in setOf("text", "document", "table") && (item.textPath.isNullOrBlank() || !File(item.textPath!!).isFile)
                if (missingText) {
                    OpenRouterAttachmentPreflight.verifySources(listOf(item))
                    val rebuilt = if (context != null) {
                        OpenRouterAttachmentStore.classifyLocalFile(context, File(item.path), item.name, item.mime)
                    } else {
                        OpenRouterAttachmentStore.classifyLocalFile(File(item.path), item.name, item.mime)
                    }
                    if (item.kind == "text" && !item.encodingGuessed && item.textEncoding != null) {
                        val selected = OpenRouterAttachmentStore.withTextEncoding(rebuilt, item.textEncoding)
                        rebuilt.textPath?.let { File(it).delete() }; selected
                    } else rebuilt
                } else item
            }
            val items = (refreshed + legacyItems(message, containerFiles, refreshed.map { it.path }.toSet(), context)).distinctBy { it.path + "|" + it.name }
            if (items.isNotEmpty()) message.put("attachments", JSONArray().apply { items.forEach { put(it.toJson()) } })
            if (AttachmentMessageState.fingerprint(before) != AttachmentMessageState.fingerprint(message)) onManifest?.invoke(before, message)
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
                    "image" -> parts.put(JSONObject().put("type", "input_image").apply {
                        if (containerFiles.containsKey(item.path)) put("file_id", containerFiles.getValue(item.path))
                        else put("image_url", OpenRouterAttachmentStore.streamingDataUrl(item))
                    })
                    "pdf", "container_document" -> parts.put(JSONObject().put("type", "input_file").apply {
                        if (containerFiles.containsKey(item.path)) put("file_id", containerFiles.getValue(item.path))
                        else { put("filename", item.name); put("file_data", OpenRouterAttachmentStore.streamingDataUrl(item)) }
                    })
                    "text", "document", "table", "container" -> if (localFileTools && item.kind != "container") {
                        parts.put(JSONObject().put("type", "input_text").put("text", LocalFileTools.descriptor(item)
                            .put("reading", "Complete local source is available through search_attached_file, read_attached_file and read_attached_table. Use the file_id, never a path.").toString()))
                    } else if (containerFiles.containsKey(item.path)) {
                        parts.put(JSONObject().put("type", "input_text").put("text", JSONObject()
                            .put("file_id", containerFiles.getValue(item.path)).put("filename", item.name)
                            .put("available", "Complete file in the Code Interpreter container. Choose search, partial reading or all rows as appropriate. Do not infer whole-table results from a preview.").toString()))
                    } else {
                        if (item.kind == "container") throw IOException("Бинарный файл " + item.name + " требует включённого полного анализа файлов")
                        val prefix = "<file name=\"" + escapeXmlAttribute(item.name) + "\">\n"
                        val source = File(item.textPath ?: item.path)
                        if (!source.isFile) throw IOException("Текстовое вложение недоступно: " + item.name)
                        val value: Any = if (source.length() > 64L * 1024L) {
                            val wireSource = if (containsClosingFileTag(source)) sanitizeLargeTextFile(source) else source
                            FileBackedText(wireSource, prefix, "\n</file>")
                        } else {
                            prefix + escapeClosingFileTags(source.readText(Charsets.UTF_8)) + "\n</file>"
                        }
                        parts.put(JSONObject().put("type", "input_text").put("text", value))
                    }
                    else -> throw IOException("Формат файла не поддерживается OpenAI: " +
                        item.name + ". Удалите его из контекста или выберите совместимый файл.")
                }
            }
            if (parts.length() > 0) {
                input.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        return input
    }

    /** Persisted manifests keep extraction/encoding stable across requests and provider switches. */
    fun materialize(
        history: List<JSONObject>,
        allowContainer: Boolean,
        context: Context? = null,
        onManifest: (JSONObject, JSONObject) -> Unit
    ) {
        history.filter { JsonProtocol.string(it, "role") == "user" }.forEach { message ->
            AttachmentHistoryPolicy.validate(message)
            val before = JsonProtocol.objectCopy(message)
            val manifest = message.optJSONArray("attachments") ?: JSONArray()
            val imagesCount = if (JsonProtocol.string(message, "image_file_id").isNotBlank() &&
                JsonProtocol.string(message, "image_file_id") != "null") 1 else 0
            val hasLocalImage = JsonProtocol.string(message, "image_path").isNotBlank() ||
                (0 until manifest.length()).any { manifest.optJSONObject(it)?.optString("kind") == "image" }
            if (imagesCount > 0 && !hasLocalImage) {
                throw IOException("В истории есть вложение без доступных локальных байтов. " +
                    "Откройте исходный чат и восстановите файл перед переключением.")
            }
            val existing = (0 until manifest.length()).mapNotNull { manifest.optJSONObject(it)?.let { o -> OpenRouterLocalAttachment.fromJson(o) } }.map { item ->
                val missing = item.kind in setOf("text", "document", "table") && (item.textPath.isNullOrBlank() || !File(item.textPath!!).isFile)
                if (!missing) item else {
                    OpenRouterAttachmentPreflight.verifySources(listOf(item))
                    val rebuilt = if (context != null) {
                        OpenRouterAttachmentStore.classifyLocalFile(context, File(item.path), item.name, item.mime)
                    } else {
                        OpenRouterAttachmentStore.classifyLocalFile(File(item.path), item.name, item.mime)
                    }
                    if (item.kind == "text" && !item.encodingGuessed && item.textEncoding != null) {
                        val selected = OpenRouterAttachmentStore.withTextEncoding(rebuilt, item.textEncoding)
                        rebuilt.textPath?.let { File(it).delete() }; selected
                    } else rebuilt
                }
            }
            val paths = message.optJSONArray("file_paths") ?: JSONArray()
            val names = message.optJSONArray("file_names") ?: JSONArray()
            val containers = mutableMapOf<String, String>()
            if (allowContainer) for (i in 0 until paths.length()) {
                val path = JsonProtocol.string(paths, i)
                val name = JsonProtocol.string(names, i, File(path).name)
                if (TextFileFormatPolicy.extension(name) in setOf("doc", "ppt", "xls", "zip")) containers[path] = "container_pending"
            }
            val additions = legacyItems(message, containers, existing.map { it.path }.toSet(), context)
            if (existing.isNotEmpty() || additions.isNotEmpty()) {
                message.put("attachments", JSONArray().apply { (existing + additions).forEach { put(it.toJson()) } })
                if (AttachmentMessageState.fingerprint(before) != AttachmentMessageState.fingerprint(message)) onManifest(before, message)
            }
        }
    }

    private fun legacyItems(
        message: JSONObject,
        containerFiles: Map<String, String>,
        manifestPaths: Set<String>,
        context: Context? = null
    ): List<OpenRouterLocalAttachment> {
        val result = mutableListOf<OpenRouterLocalAttachment>()
        val paths = message.optJSONArray("file_paths")
        val names = message.optJSONArray("file_names")
        val mimes = message.optJSONArray("file_mimes")
        if (paths != null) for (idx in 0 until paths.length()) {
            val path = JsonProtocol.string(paths, idx)
            if (path.isBlank() || path in manifestPaths) continue
            val file = File(path)
            if (!file.isFile) throw IOException("Локальный файл в истории недоступен: " +
                names?.let { JsonProtocol.string(it, idx, file.name) })
            val filename = names?.let { JsonProtocol.string(it, idx, file.name) } ?: file.name
            val mime = mimes?.let { JsonProtocol.string(it, idx) } ?: ""
            result += if (containerFiles.containsKey(path) && !mime.startsWith("image/") &&
                TextFileFormatPolicy.extension(filename) != "pdf") {
                OpenRouterLocalAttachment("container_" + digest(file), filename,
                    if (TextFileFormatPolicy.extension(filename) in setOf("doc", "docx", "ppt", "pptx")) "container_document" else "container",
                    path, mime, file.length(), digest(file))
            } else if (context != null) {
                OpenRouterAttachmentStore.classifyLocalFile(context, file, filename, mime)
            } else {
                OpenRouterAttachmentStore.classifyLocalFile(file, filename, mime)
            }
        }
        JsonProtocol.string(message, "image_path").takeIf { it.isNotBlank() }?.let { path ->
            val file = File(path)
            if (path in manifestPaths) return@let
            if (!file.isFile) throw IOException("Изображение из истории недоступно: " +
                JsonProtocol.string(message, "image_name"))
            result += if (context != null) {
                OpenRouterAttachmentStore.classifyLocalFile(
                    context, file, JsonProtocol.string(message, "image_name", file.name),
                    JsonProtocol.string(message, "image_mime", "image/*")
                )
            } else {
                OpenRouterAttachmentStore.classifyLocalFile(
                    file, JsonProtocol.string(message, "image_name", file.name),
                    JsonProtocol.string(message, "image_mime", "image/*")
                )
            }
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
