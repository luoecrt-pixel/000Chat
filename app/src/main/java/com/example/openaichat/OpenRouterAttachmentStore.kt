package com.example.openaichat

import android.content.Context
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import fr.free.nrw.commons.ajpegtran.Jpegtran
import fr.free.nrw.commons.ajpegtran.Properties
import fr.free.nrw.commons.ajpegtran.rotate.RotationDegree
import java.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

data class OpenRouterLocalAttachment(
    val id: String,
    val name: String,
    val kind: String,
    val path: String,
    val mime: String,
    val size: Long,
    val sha256: String,
    val textPath: String? = null,
    val textEncoding: String? = null,
    val wirePath: String? = null,
    val wireMime: String? = null
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind).put("path", path).put("mime", mime).put("size", size).put("sha256", sha256).apply {
        textPath?.let { put("text_path", it) }
        textEncoding?.let { put("text_encoding", it) }
        wirePath?.let { put("wire_path", it) }
        wireMime?.let { put("wire_mime", it) }
    }
    companion object {
        fun fromJson(o: JSONObject) = OpenRouterLocalAttachment(
            o.optString("id"), o.optString("name", "attachment"), o.optString("kind", "unknown"),
            o.optString("path"), o.optString("mime", "application/octet-stream"), o.optLong("size", 0L),
            o.optString("sha256"), o.optString("text_path").takeIf { it.isNotBlank() }, o.optString("text_encoding").takeIf { it.isNotBlank() },
            o.optString("wire_path").takeIf { it.isNotBlank() }, o.optString("wire_mime").takeIf { it.isNotBlank() }
        )
    }
}
 
fun interface AttachmentInputStreamSource {
    fun openInputStream(context: Context, uri: Uri): InputStream?
}

object DefaultAttachmentInputStreamSource : AttachmentInputStreamSource {
    override fun openInputStream(context: Context, uri: Uri): InputStream? =
        context.contentResolver.openInputStream(uri)
}


object OpenRouterAttachmentStore {
    const val MAX_ATTACHMENTS = 12
    const val MAX_ZIP_ENTRIES = 100
    const val MAX_ZIP_ENTRY_BYTES = 25L * 1024L * 1024L
    const val MAX_ZIP_TOTAL_BYTES = 200L * 1024L * 1024L
    private val textExtensions = setOf("txt","md","kt","java","json","csv","xml","yaml","yml","log")
    private val imageExtensions = setOf("png","jpg","jpeg","webp","gif")

    fun supportedFormatsText() = "txt, md, kt, java, json, csv, xml, yaml, yml, log, png, jpg, jpeg, webp, gif, pdf, zip"

    fun queryName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null
        }
    } catch (_: Exception) {
        null
    } ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }

    fun copyAndClassify(
        context: Context,
        uri: Uri,
        forcedName: String? = null,
        inputStreamSource: AttachmentInputStreamSource = DefaultAttachmentInputStreamSource
    ): OpenRouterLocalAttachment {
        val name = sanitizeDisplayName(forcedName ?: queryName(context, uri) ?: "attachment")
        val mime = runCatching { context.contentResolver.getType(uri).orEmpty() }.getOrDefault("").ifBlank { mimeForFilename(name) }
        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
        val raw = File(dir, "or_" + UUID.randomUUID() + "_" + safeFileName(name))
        inputStreamSource.openInputStream(context, uri)?.use { input -> raw.outputStream().use { input.copyTo(it, 64 * 1024) } }
            ?: throw IOException("Не удалось открыть файл: " + name)
        return finalizeFile(context, dir, raw, name, mime)
    }

    fun classifyLocalFile(file: File, displayName: String = file.name, mime: String = mimeForFilename(displayName)): OpenRouterLocalAttachment =
        finalizeFile(null, file.parentFile ?: throw IOException("Нет каталога вложения"), file, sanitizeDisplayName(displayName), mime)

    fun classifyLocalFile(context: Context, file: File, displayName: String = file.name, mime: String = mimeForFilename(displayName)): OpenRouterLocalAttachment =
        finalizeFile(context, file.parentFile ?: throw IOException("Нет каталога вложения"), file, sanitizeDisplayName(displayName), mime)

    fun extractZipFile(context: Context, zipFile: File, zipName: String, report: (String) -> Unit = {}): List<OpenRouterLocalAttachment> {
        zipFile.inputStream().use { source ->
            return extractZipStream(context, source, zipName, report)
        }
    }

    private fun extractZipStream(context: Context, source: InputStream, zipName: String, report: (String) -> Unit): List<OpenRouterLocalAttachment> {
        val result = mutableListOf<OpenRouterLocalAttachment>()
        var entries = 0
        var total = 0L
        ZipInputStream(source.buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw IOException("ZIP содержит больше " + MAX_ZIP_ENTRIES + " файлов")
                val rawEntryPath = entry.name.replace('\\', '/')
                val unsafeEntry = isUnsafeZipPath(rawEntryPath)
                val entryName = rawEntryPath.substringAfterLast('/').trim()
                if (unsafeEntry) {
                    drainZipEntry(zin, entryName, report, "Небезопасный путь ZIP пропущен: " + entry.name) { n -> total += n }
                    if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                } else if (!entry.isDirectory && entryName.isNotBlank()) {
                    val ext = entryName.substringAfterLast('.', "").lowercase()
                    val supported = ext in textExtensions || ext in imageExtensions || ext == "pdf"
                    val nested = ext == "zip" || ext == "apk" || ext == "jar"
                    if (supported) {
                        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                        val local = File(dir, "zip_" + UUID.randomUUID() + "_" + safeFileName(entryName))
                        var entryBytes = 0L
                        local.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buffer); if (n < 0) break
                                entryBytes += n; total += n
                                if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP-файл " + entryName + " превышает 25 МБ")
                                if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                                out.write(buffer, 0, n)
                            }
                        }
                        if (entryBytes == 0L) { local.delete(); report("Пустой файл пропущен: " + entryName) }
                        else result += finalizeFile(context, dir, local, sanitizeDisplayName(entryName), mimeForFilename(entryName))
                    } else {
                        drainZipEntry(zin, entryName, report, if (nested) "Пропущен вложенный архив/бинарник: " + entryName else "Неподдерживаемый файл пропущен: " + entryName) { n -> total += n }
                        if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                    }
                }
                zin.closeEntry()
                entry = zin.nextEntry
            }
        }
        if (result.isEmpty()) throw IOException("В ZIP не найдено поддерживаемых файлов")
        report("ZIP " + zipName + ": подготовлено " + result.size + " поддерживаемых файлов")
        return result
    }

    internal fun isUnsafeZipPath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        return normalized.startsWith("/") || normalized.split('/').any { it == ".." } || Regex("^[A-Za-z]:/").containsMatchIn(normalized)
    }
    private fun drainZipEntry(zin: ZipInputStream, entryName: String, report: (String) -> Unit, message: String, onBytes: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var entryBytes = 0L
        while (true) {
            val n = zin.read(buffer); if (n < 0) break
            entryBytes += n
            onBytes(n.toLong())
            if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP entry превышает 25 МБ: " + entryName)
        }
        report(message)
    }

    fun extractZip(
        context: Context,
        uri: Uri,
        zipName: String,
        report: (String) -> Unit = {},
        inputStreamSource: AttachmentInputStreamSource = DefaultAttachmentInputStreamSource
    ): List<OpenRouterLocalAttachment> {
        val result = mutableListOf<OpenRouterLocalAttachment>()
        var entries = 0
        var total = 0L
        inputStreamSource.openInputStream(context, uri)?.use { source -> ZipInputStream(source.buffered()).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                entries++
                if (entries > MAX_ZIP_ENTRIES) throw IOException("ZIP содержит больше " + MAX_ZIP_ENTRIES + " файлов")
                val rawEntryPath = entry.name.replace('\\', '/')
                val unsafeEntry = isUnsafeZipPath(rawEntryPath)
                val entryName = rawEntryPath.substringAfterLast('/').trim()
                if (unsafeEntry) {
                    val buffer = ByteArray(64 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val n = zin.read(buffer); if (n < 0) break
                        entryBytes += n; total += n
                        if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP entry превышает 25 МБ")
                        if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                    }
                    report("Небезопасный путь ZIP пропущен: " + entry.name)
                } else if (!entry.isDirectory && entryName.isNotBlank()) {
                    val ext = entryName.substringAfterLast('.', "").lowercase()
                    val supported = ext in textExtensions || ext in imageExtensions || ext == "pdf"
                    val nested = ext == "zip" || ext == "apk" || ext == "jar"
                    if (supported) {
                        val dir = File(context.filesDir, "attachments").apply { mkdirs() }
                        val local = File(dir, "zip_" + UUID.randomUUID() + "_" + safeFileName(entryName))
                        var entryBytes = 0L
                        local.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = zin.read(buffer); if (n < 0) break
                                entryBytes += n; total += n
                                if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP-файл " + entryName + " превышает 25 МБ")
                                if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                                out.write(buffer, 0, n)
                            }
                        }
                        if (entryBytes == 0L) { local.delete(); report("Пустой файл пропущен: " + entryName) }
                        else result += finalizeFile(context, dir, local, sanitizeDisplayName(entryName), mimeForFilename(entryName))
                    } else {
                        val buffer = ByteArray(64 * 1024); var entryBytes = 0L
                        while (true) {
                            val n = zin.read(buffer); if (n < 0) break
                            entryBytes += n; total += n
                            if (entryBytes > MAX_ZIP_ENTRY_BYTES) throw IOException("ZIP entry превышает 25 МБ")
                            if (total > MAX_ZIP_TOTAL_BYTES) throw IOException("ZIP распаковывается больше 200 МБ")
                        }
                        report(if (nested) "Пропущен вложенный архив/бинарник: " else "Неподдерживаемый файл пропущен: " + entryName)
                    }
                }
                zin.closeEntry(); entry = zin.nextEntry
            }
        } } ?: throw IOException("Не удалось открыть ZIP")
        if (result.isEmpty()) throw IOException("В ZIP не найдено поддерживаемых файлов")
        report("ZIP " + zipName + ": подготовлено " + result.size + " поддерживаемых файлов")
        return result
    }

    fun toContentBlock(a: OpenRouterLocalAttachment): JSONObject = when (a.kind) {
        "text" -> JSONObject().put("type", "text").put("text", "<file name=\"" + a.name.replace("\"", "&quot;") + "\">\n" + textContent(a).replace("</file>", "<\\/file>") + "\n</file>")
        "image" -> JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", toDataUrl(a)))
        "pdf" -> JSONObject().put("type", "file").put("file", JSONObject().put("filename", a.name).put("file_data", toDataUrl(a)))
        else -> throw IOException("Вложение " + a.name + " нельзя отправить напрямую")
    }
    fun toDataUrl(a: OpenRouterLocalAttachment): String {
        val wireFile = File(a.wirePath ?: a.path)
        val wireMime = a.wireMime ?: a.mime
        return "data:" + wireMime + ";base64," + Base64.getEncoder().encodeToString(wireFile.readBytes())
    }

    fun textContent(a: OpenRouterLocalAttachment): String = File(a.textPath ?: a.path).readText(Charsets.UTF_8)

    private fun finalizeFile(context: Context?, dir: File, file: File, name: String, mime: String): OpenRouterLocalAttachment {
        val kind = classifyKind(file, name)
        if (kind == "unsupported") { file.delete(); throw IOException("Формат '" + name + "' не поддерживается для OpenRouter. Поддерживаются: " + supportedFormatsText()) }
        if (kind == "binary") { file.delete(); throw IOException("Файл '" + name + "' определён как бинарный и не входит в поддерживаемые форматы") }
        val size = file.length(); val sha = sha256(file)
        return if (kind == "text") {
            val decoded = decodeText(file, name)
            val textFile = File(dir, "txt_" + UUID.randomUUID() + ".utf8")
            textFile.writeText(decoded.first, Charsets.UTF_8)
            OpenRouterLocalAttachment(UUID.randomUUID().toString(), name, kind, file.absolutePath, mimeForFilename(name).takeIf { it != "application/octet-stream" } ?: mime, size, sha, textFile.absolutePath, decoded.second)
        } else if (kind == "image") {
            val normalized = context?.let { normalizeImageOrientation(it, file, dir, name) }
            OpenRouterLocalAttachment(UUID.randomUUID().toString(), name, kind, file.absolutePath,
                if (normalized != null) "image/jpeg" else (mimeForFilename(name).takeIf { it.startsWith("image/") } ?: mime),
                size, sha, null, null, normalized?.absolutePath, if (normalized != null) "image/jpeg" else null)
        } else {
            OpenRouterLocalAttachment(UUID.randomUUID().toString(), name, kind, file.absolutePath, if (kind == "pdf") "application/pdf" else mime, size, sha)
        }
    }

    private fun classifyKind(file: File, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(); val magic = ByteArray(12)
        file.inputStream().use { it.read(magic) }
        if (magic.copyOfRange(0,5).contentEquals("%PDF-".toByteArray())) return "pdf"
        if (magic.copyOfRange(0,4).contentEquals(byteArrayOf(0x89.toByte(),0x50,0x4E,0x47))) return "image"
        if (magic.copyOfRange(0,3).contentEquals(byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte()))) return "image"
        if (magic.copyOfRange(0,3).contentEquals("GIF".toByteArray())) return "image"
        if (magic.copyOfRange(0,4).contentEquals("RIFF".toByteArray()) && magic.copyOfRange(8,12).contentEquals("WEBP".toByteArray())) return "image"
        if (magic.copyOfRange(0,4).contentEquals(byteArrayOf(0x50,0x4B,0x03,0x04))) return "zip"
        if (ext in textExtensions) return "text"
        if (ext in imageExtensions) return "image"
        if (ext == "pdf") return "pdf"
        return "unsupported"
    }

    private fun normalizeImageOrientation(context: Context, file: File, dir: File, name: String): File? {
        if (!isJpegFile(file)) return null

        val orientation = try {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        if (orientation == ExifInterface.ORIENTATION_NORMAL || orientation == ExifInterface.ORIENTATION_UNDEFINED) return null

        val rotation = LosslessJpegExifPolicy.rotationDegrees(orientation) ?: return null

        return try {
            val propertiesUri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
            val properties = Properties.of(context, propertiesUri)
            if (!LosslessJpegExifPolicy.isPerfectRotation(
                    orientation = orientation,
                    width = properties.width,
                    height = properties.height,
                    mcuWidth = properties.MCU_Width,
                    mcuHeight = properties.MCU_Height
                )
            ) {
                // Never crop or partially transform MCU edge blocks. Keep the original
                // JPEG/EXIF wire representation when a strictly lossless transform
                // cannot be proven.
                return null
            }

            val rotationDegree = when (rotation) {
                90 -> RotationDegree.ROTATE_90
                180 -> RotationDegree.ROTATE_180
                270 -> RotationDegree.ROTATE_270
                else -> return null
            }

            val transformer = Jpegtran(context.applicationContext, Uri.fromFile(file))
            var transformed: File? = null
            try {
                transformed = transformer.rotate(rotationDegree, trim = false)
                val owned = File(dir, "wire_" + UUID.randomUUID() + ".jpg")
                transformed.copyTo(owned, overwrite = true)
                return owned
            } finally {
                transformer.cleanup()
                transformed?.takeIf { it.exists() }?.delete()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isJpegFile(file: File): Boolean {
        if (file.length() < 3L) return false
        return file.inputStream().use { input ->
            input.read() == 0xFF && input.read() == 0xD8 && input.read() == 0xFF
        }
    }

    private fun decodeText(file: File, name: String): Pair<String,String> {
        val bytes = file.readBytes()
        if (bytes.any { it.toInt() == 0 }) throw IOException("Файл '" + name + "' содержит нулевые байты и не считается текстом")
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it to "UTF-8" }
        decodeStrict(bytes, Charset.forName("windows-1251"))?.let { return it to "windows-1251" }
        throw IOException("Не удалось определить кодировку '" + name + "': UTF-8 и windows-1251 не подошли")
    }

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String? = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { null }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n=input.read(buffer); if (n<0) break; digest.update(buffer,0,n) } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sanitizeDisplayName(name: String) = name.substringAfterLast('/').substringAfterLast('\\').trim().ifBlank { "attachment" }.take(180)
    private fun safeFileName(name: String) = sanitizeDisplayName(name).replace(Regex("[^A-Za-z0-9._-]"), "_").take(120)
    private fun mimeForFilename(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "txt","log" -> "text/plain"; "md","markdown" -> "text/markdown"; "kt","java" -> "text/plain"; "json" -> "application/json"; "csv" -> "text/csv"; "xml" -> "application/xml"; "yaml","yml" -> "text/yaml"; "png" -> "image/png"; "jpg","jpeg" -> "image/jpeg"; "webp" -> "image/webp"; "gif" -> "image/gif"; "pdf" -> "application/pdf"; else -> "application/octet-stream"
    }
}