package com.example.openaichat

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.io.FileNotFoundException
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.FileSystemException

class LocalArtifactToolExecutor internal constructor(
    private val context: Context,
    private val publishDeterministicFile: (destination: File, staged: File) -> Unit
) {
    constructor(context: Context) : this(context, { destination, staged ->
        Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        Unit
    })
    companion object {
        private val patchLock = Any()
        /** Only descriptors, never device paths or full local diff contents, reach the model. */
        fun toolResult(metadata: JSONObject): JSONObject = JsonProtocol.objectCopy(metadata).apply {
            remove("path"); remove("attachment"); remove("patch_diff_path"); remove("source_path")
        }
    }
    fun execute(name: String, arguments: JSONObject): String {
        return when (name) {
            "create_local_text_file" -> createTextFile(arguments).toString()
            else -> throw IOException("Unknown local artifact tool: $name")
        }
    }

    /** Durable intent metadata, computed without creating a directory or writing file bytes. */
    fun prepareCreateTextFile(arguments: JSONObject, artifactKey: String): JSONObject {
        if (artifactKey.isBlank()) throw IOException("artifactKey must not be blank")
        return planCreateTextFile(arguments, "create_" + StreamingJsonRequestBody.sha256(JSONObject().put("artifact_key", artifactKey)))
    }

    /** A stable artifact key permits recovery of a completed publication, never another write. */
    fun createTextFile(arguments: JSONObject, artifactKey: String? = null): JSONObject = synchronized(patchLock) {
        // Existing OpenAI/OpenRouter callers retain their direct UUID-file contract.
        // The locked atomic-publication contract is used only by durable DeepSeek calls.
        if (artifactKey == null) return@synchronized createLegacyTextFile(arguments)
        val metadata = prepareCreateTextFile(arguments, artifactKey)
        val content = JsonProtocol.text(arguments, "content") ?: throw IOException("content must be a string")
        val file = File(metadata.getString("path"))
        val dir = file.parentFile ?: throw IOException("Каталог файла недоступен")
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) throw IOException("Каталог файла недоступен")
        fun verifyPublished() {
            if (!file.isFile || file.length() != metadata.getLong("size_bytes") ||
                LocalUtf8FilePatch.sha256(file) != metadata.getString("sha256"))
                throw IOException("Идентификатор вызова уже использован другим или неполным файлом; перезапись запрещена")
        }
        var publicationMayHaveOccurred = false
        try { withPublicationLock(file) {
            if (file.exists()) {
                publicationMayHaveOccurred = true
                verifyPublished()
                return@withPublicationLock metadata
            }
            val staged = File.createTempFile(".create-", ".tmp", dir)
            try {
                java.io.FileOutputStream(staged).use { output ->
                    val nonClosing = object : java.io.FilterOutputStream(output) {
                        override fun write(buffer: ByteArray, offset: Int, length: Int) { output.write(buffer, offset, length) }
                        override fun close() { flush() }
                    }
                    nonClosing.writer(Charsets.UTF_8).buffered(64 * 1024).use { writeCreateContent(content, it) }
                    output.fd.sync()
                }
                if (staged.length() != metadata.getLong("size_bytes") ||
                    LocalUtf8FilePatch.sha256(staged) != metadata.getString("sha256"))
                    throw IOException("Файл записан не полностью; публикация отменена")
                checkPublicationInterrupted()
                try {
                    // The stable cross-process lock and target check above are mandatory:
                    // ATOMIC_MOVE alone is allowed to replace an existing destination.
                    publicationMayHaveOccurred = true
                    publishDeterministicFile(file, staged)
                } catch (collision: java.nio.file.FileAlreadyExistsException) {
                    verifyPublished()
                } catch (unsupported: UnsupportedOperationException) {
                    throw IOException("Файловая система не поддерживает безопасную публикацию файла DeepSeek. " +
                        "Результат операции не подтверждён; автоматический повтор не выполнялся.", unsupported)
                } catch (filesystem: FileSystemException) {
                    throw IOException("Не удалось подтвердить безопасную публикацию файла DeepSeek. " +
                        "Сохранённое намерение остаётся для проверки; автоматический повтор не выполнялся.", filesystem)
                }
                verifyPublished()
                metadata
            } finally {
                // A published target is immutable even if a later verification fails.
                staged.delete()
            }
        } } catch (error: Exception) {
            if (error is FileWriteOutcomeUnknownException) throw error
            if (publicationMayHaveOccurred) throw FileWriteOutcomeUnknownException(
                "Исход создания файла не подтверждён: файл мог быть опубликован. " +
                    "Намерение сохранено для проверки; повторное исполнение не выполнялось.", error)
            throw error
        }
    }

    private fun checkPublicationInterrupted() {
        if (Thread.currentThread().isInterrupted)
            throw java.io.InterruptedIOException("Публикация файла прервана до подтверждения результата")
    }

    private inline fun <T> withPublicationLock(destination: File, block: () -> T): T {
        val directory = File(destination.parentFile, ".publication-locks")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
            throw IOException("Каталог блокировок публикации недоступен")
        val token = StreamingJsonRequestBody.sha256(JSONObject().put("destination", destination.name))
        val lockFile = File(directory, "$token.lock")
        checkPublicationInterrupted()
        try {
            java.io.RandomAccessFile(lockFile, "rw").use { handle ->
                handle.channel.lock().use {
                    checkPublicationInterrupted()
                    return block()
                }
            }
        } catch (interrupted: java.nio.channels.FileLockInterruptionException) {
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("Ожидание блокировки публикации прервано").apply { initCause(interrupted) }
        }
        // Lockfiles are deliberately retained: unlinking allows two processes to
        // hold locks on different inodes for the same publication destination.
    }

    private fun createLegacyTextFile(arguments: JSONObject): JSONObject {
        val metadata = planCreateTextFile(arguments, UUID.randomUUID().toString())
        val content = JsonProtocol.text(arguments, "content") ?: throw IOException("content must be a string")
        val file = File(metadata.getString("path"))
        val directory = file.parentFile ?: throw IOException("Каталог файла недоступен")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
            throw IOException("Каталог файла недоступен")
        // Reserve a fresh UUID name, never remove somebody else's complete artifact.
        if (!file.createNewFile()) throw IOException("Имя файла уже занято; перезапись запрещена")
        try {
            file.bufferedWriter(Charsets.UTF_8, 64 * 1024).use { writeCreateContent(content, it) }
            if (file.length() != metadata.getLong("size_bytes") ||
                LocalUtf8FilePatch.sha256(file) != metadata.getString("sha256"))
                throw IOException("Файл записан не полностью")
            return metadata
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
    }

    private fun writeCreateContent(content: String, writer: java.io.Writer) {
        var offset = 0
        while (offset < content.length) {
            val count = minOf(64 * 1024, content.length - offset)
            writer.write(content, offset, count)
            offset += count
        }
    }

    private fun planCreateTextFile(arguments: JSONObject, token: String): JSONObject {
        val rawName = JsonProtocol.nonBlank(arguments, "filename")?.trim() ?: throw IOException("filename is required")
        val content = JsonProtocol.text(arguments, "content") ?: throw IOException("content must be a string")
        if (TextFileFormatPolicy.extension(rawName) in TextFileFormatPolicy.office + setOf("pdf", "doc", "xls", "ppt", "png", "jpg", "jpeg", "gif", "webp", "zip", "mp3", "mp4"))
            throw IOException("create_local_text_file создаёт UTF-8 текст, а не настоящий бинарный ${TextFileFormatPolicy.extension(rawName)}. Используйте текстовый формат, например TXT, Markdown, CSV или исходный код.")
        val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_").take(160).ifBlank { "generated.txt" }
        val file = File(File(context.filesDir, "generated"), "${token}_$safeName")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val sink = object : java.io.OutputStream() {
            override fun write(value: Int) { digest.update(value.toByte()); bytes++ }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                digest.update(buffer, offset, length); bytes += length
            }
        }
        // Hash exactly the same UTF-8 encoding as publication without a content-sized copy.
        sink.writer(Charsets.UTF_8).buffered(64 * 1024).use { writeCreateContent(content, it) }
        val sha = LocalUtf8FilePatch.hex(digest.digest())
        return withAttachment(JSONObject().put("ok", true).put("type", "local_file")
            .put("filename", rawName).put("path", file.absolutePath)
            .put("mime_type", mimeForFilename(rawName)).put("size_bytes", bytes)
            .put("operation_sha256", StreamingJsonRequestBody.sha256(arguments)), sha)
    }

    fun patchLocalFile(arguments: JSONObject, files: List<OpenRouterLocalAttachment>,
        active: () -> Boolean = { true }, artifactKey: String? = null,
        beforePublish: (JSONObject) -> Unit = {}): JSONObject {
        var publicationMayHaveOccurred = false
        return try { synchronized(patchLock) {
        LocalUtf8FilePatch.checkActive(active)
        if (StreamingJsonRequestBody.countBytes(arguments) > 256 * 1024) throw IOException("Аргументы патча превышают 256 КБ")
        val selector = JsonProtocol.nonBlank(arguments, "file_id") ?: JsonProtocol.nonBlank(arguments, "filename")
            ?: throw IOException("Укажите file_id или однозначное filename")
        val candidates = files.filter { it.id == selector }.ifEmpty { files.filter { it.name == selector } }
            .distinctBy { File(it.path).canonicalPath }
        if (candidates.size != 1) throw IOException("Файл отсутствует или имя неоднозначно; укажите точный file_id")
        val source = candidates.single()
        if (source.kind != "text" || (source.textEncoding != null && !source.textEncoding.equals("UTF-8", true)))
            throw IOException("Патч поддерживает только исходные UTF-8 текстовые файлы, не извлечённые офисные документы")
        JsonProtocol.nonBlank(arguments, "filename")?.let { if (it != source.name) throw IOException("filename не соответствует выбранному file_id") }
        val expected = JsonProtocol.nonBlank(arguments, "expected_sha256")
        if (arguments.has("expected_sha256") && expected == null) throw IOException("expected_sha256 должен быть непустой строкой")
        if (expected != null && !Regex("[0-9a-fA-F]{64}").matches(expected)) throw IOException("expected_sha256 должен содержать 64 hex символа")
        if (expected != null && source.sha256.isNotBlank() && !source.sha256.equals(expected, true))
            throw IOException("expected_sha256 не соответствует версии файла")
        val rawPatches = arguments.optJSONArray("patches") ?: throw IOException("patches должен быть массивом")
        val patches = (0 until rawPatches.length()).map { index ->
            val patch = rawPatches.optJSONObject(index) ?: throw IOException("Недействительный блок patches")
            LocalUtf8FilePatch.Replacement(JsonProtocol.text(patch, "search") ?: throw IOException("search должен быть строкой"),
                JsonProtocol.text(patch, "replace") ?: throw IOException("replace должен быть строкой"))
        }
        LocalUtf8FilePatch.prepare(File(source.path), context.cacheDir, patches,
            source.sha256.takeIf { it.isNotBlank() } ?: expected, active).use { plan ->
            val operation = StreamingJsonRequestBody.sha256(JSONObject().put("source", source.id)
                .put("sha256", plan.sourceSha256).put("filename", source.name).put("patches", rawPatches))
            val token = artifactKey?.let { LocalUtf8FilePatch.hex(java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray(Charsets.UTF_8))) }
                ?: UUID.randomUUID().toString()
            val dir = File(context.filesDir, "generated")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Каталог версий файла недоступен")
            val diffDir = File(dir, "patch-diffs")
            if (!diffDir.isDirectory && !diffDir.mkdirs()) throw IOException("Каталог изменений файла недоступен")
            val safeName = source.name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._-]"), "_").take(160).ifBlank { "patched.txt" }
            val target = File(dir, "patch_${token}_$safeName")
            val diff = File(diffDir, "$token.json")
            // A process may die between publishing the complete version and
            // its sidecar. Reconstruct only that metadata after proving that
            // the existing bytes are exactly this operation's expected result.
            // Sidecar identity is shared by the operation even if source filenames differ.
            withPublicationLock(diff) {
            if (target.exists()) publicationMayHaveOccurred = true
            val recoverSidecar = target.isFile && !diff.exists()
            if ((target.exists() || diff.exists()) && !recoverSidecar) {
                if (!target.isFile || !diff.isFile || diff.length() > 2L * 1024 * 1024)
                    throw IOException("Предыдущее применение патча неполно; повторная запись запрещена")
                val saved = JSONObject(diff.readText(Charsets.UTF_8))
                if (saved.optString("operation_sha256") != operation || saved.optString("result_sha256") != LocalUtf8FilePatch.sha256(target, active))
                    throw IOException("Идентификатор вызова уже использован другой версией патча")
                val metadata = saved.getJSONObject("artifact")
                if (JsonProtocol.string(metadata, "path") != target.absolutePath || metadata.optString("patch_diff_path") != diff.absolutePath)
                    throw IOException("Метаданные версии патча повреждены")
                val manifest = metadata.optJSONObject("attachment")
                if (manifest == null || metadata.optString("sha256") != saved.optString("result_sha256") ||
                    manifest.optString("sha256") != metadata.optString("sha256") ||
                    JsonProtocol.string(manifest, "path") != target.absolutePath || manifest.optString("text_path") != target.absolutePath)
                    throw IOException("Manifest версии патча повреждён")
                return@synchronized JsonProtocol.objectCopy(metadata)
            }
            // Staging belongs to reclaimable cache if the process dies before
            // finally. Only complete, published versions live in filesDir.
            val staged = File.createTempFile("patch_result_", ".tmp", context.cacheDir)
            var stagedDiff: File? = null
            try {
                // Keep the first allocation guarded if the second one fails.
                val pendingDiff = File.createTempFile("patch_diff_", ".tmp", context.cacheDir).also { stagedDiff = it }
                val resultSha = plan.write(staged)
                if (recoverSidecar && (target.length() != staged.length() || LocalUtf8FilePatch.sha256(target, active) != resultSha))
                    throw IOException("Существующая версия файла не соответствует результату патча; перезапись запрещена")
                val metadata = withAttachment(JSONObject().put("ok", true).put("type", "local_file")
                    .put("filename", source.name).put("path", target.absolutePath).put("mime_type", source.mime)
                    .put("size_bytes", staged.length()).put("patch_count", plan.hunks.size)
                    .put("source_file_id", source.id).put("source_sha256", plan.sourceSha256)
                    .put("source_path", source.path).put("patch_diff_path", diff.absolutePath)
                    .put("operation_sha256", operation), resultSha)
                val hunks = JSONArray().apply { plan.hunks.forEach { h -> put(JSONObject().put("line", h.line)
                    .put("search", h.replacement.search).put("replace", h.replacement.replace)) } }
                val sidecar = JSONObject().put("version", 1).put("source_file_id", source.id).put("source_sha256", plan.sourceSha256)
                    .put("result_sha256", resultSha).put("operation_sha256", operation).put("hunks", hunks).put("artifact", metadata)
                pendingDiff.bufferedWriter(Charsets.UTF_8).use { StreamingJsonRequestBody.writeJson(sidecar, it) }
                java.io.RandomAccessFile(pendingDiff, "rw").use { it.fd.sync() }
                java.io.RandomAccessFile(staged, "rw").use { it.fd.sync() }
                // These observations are outside the serialized artifact to avoid
                // a self-referential sidecar hash.
                metadata.put("patch_sidecar_sha256", LocalUtf8FilePatch.sha256(pendingDiff))
                    .put("patch_sidecar_size_bytes", pendingDiff.length())
                // Durable intent is committed before either immutable result appears.
                beforePublish(JsonProtocol.objectCopy(metadata))
                LocalUtf8FilePatch.checkActive(active)
                checkPublicationInterrupted()
                if (!recoverSidecar) {
                    publicationMayHaveOccurred = true
                    publishDeterministicFile(target, staged)
                }
                publicationMayHaveOccurred = true
                publishDeterministicFile(diff, pendingDiff)
                if (!target.isFile || target.length() != metadata.getLong("size_bytes") ||
                    LocalUtf8FilePatch.sha256(target) != metadata.getString("sha256") ||
                    !diff.isFile || diff.length() != metadata.getLong("patch_sidecar_size_bytes") ||
                    LocalUtf8FilePatch.sha256(diff) != metadata.getString("patch_sidecar_sha256"))
                    throw IOException("Проверка опубликованной версии файла или sidecar не завершена")
                metadata
            } catch (error: Throwable) {
                // Never erase a complete version or an existing sidecar after a
                // callback, Stop, or late I/O failure. Recovery proves their bytes.
                throw error
            } finally { staged.delete(); stagedDiff?.delete() }
            }
        }
    } } catch (error: Exception) {
        if (error is FileWriteOutcomeUnknownException) throw error
        if (publicationMayHaveOccurred) throw FileWriteOutcomeUnknownException(
            "Исход изменения файла не подтверждён: версия могла быть опубликована. " +
                "Намерение сохранено для проверки; повторное исполнение не выполнялось.", error)
        when (error) {
            is FileNotFoundException -> throw IOException("Исходный файл или каталог версии недоступен. Исходник не изменён.", error)
            is FileSystemException -> throw IOException("Не удалось прочитать или сохранить версию файла. Исходник не изменён.", error)
            else -> throw error
        }
    }
    }

    private fun withAttachment(metadata: JSONObject, sha: String): JSONObject {
        val path = metadata.getString("path")
        val name = metadata.getString("filename")
        // Bytes may return to an older value (or a patch may be a no-op). The
        // published filename carries the immutable version token, so retained
        // versions stay addressable while a retry of the same target keeps its ID.
        // Persisted manifests retain their original IDs; only new versions use v2.
        val id = "local_v2_" + StreamingJsonRequestBody.sha256(JSONObject()
            .put("version_token", File(path).name).put("sha256", sha).put("filename", name))
        val attachment = OpenRouterLocalAttachment(id, name, "text", path, metadata.getString("mime_type"),
            metadata.getLong("size_bytes"), sha, textPath = path, textEncoding = "UTF-8")
        return metadata.put("sha256", sha).put("file_id", id).put("attachment", attachment.toJson())
    }

    private fun mimeForFilename(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "csv" -> "text/csv"
        "html", "htm" -> "text/html"
        "kt", "java", "kotlin", "py", "js", "ts", "xml", "yaml", "yml" -> "text/plain"
        "json" -> "application/json"
        else -> "text/plain"
    }
}
