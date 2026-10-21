package com.example.openaichat

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest

/** Plans every replacement against one immutable snapshot; never rewrites the source. */
object LocalUtf8FilePatch {
    data class Replacement(val search: String, val replace: String)
    data class Hunk(val start: Long, val end: Long, val line: Long, val replacement: Replacement)
    class Plan internal constructor(private val snapshot: File, val sourceSha256: String,
        val hunks: List<Hunk>, private val active: () -> Boolean) : Closeable {
        fun write(target: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            try {
                snapshot.inputStream().use { input ->
                    DigestOutputStream(target.outputStream(), digest).use { output ->
                        var position = 0L
                        for (hunk in hunks) {
                            transfer(input, output, hunk.start - position, active)
                            transfer(input, null, hunk.end - hunk.start, active)
                            checkActive(active)
                            output.write(hunk.replacement.replace.toByteArray(Charsets.UTF_8))
                            position = hunk.end
                        }
                        transfer(input, output, snapshot.length() - position, active)
                    }
                }
                return hex(digest.digest())
            } catch (error: Throwable) { target.delete(); throw error }
        }
        override fun close() { snapshot.delete() }
    }

    fun prepare(source: File, directory: File, patches: List<Replacement>, expectedSha256: String?,
        active: () -> Boolean = { true }): Plan {
        checkActive(active)
        if (patches.isEmpty() || patches.size > 64) throw IOException("patches должен содержать от 1 до 64 блоков")
        patches.forEach {
            if (it.search.isEmpty()) throw IOException("Пустой search запрещён")
            if (!Charsets.UTF_8.newEncoder().canEncode(it.search) || !Charsets.UTF_8.newEncoder().canEncode(it.replace))
                throw IOException("Патч содержит некорректный Unicode")
        }
        directory.mkdirs()
        val snapshot = File.createTempFile("local_patch_source_", ".tmp", directory)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestInputStream(source.inputStream(), digest).use { input ->
                BoundedAttachmentCopy.copy(input, snapshot, shouldContinue = { active() && !Thread.currentThread().isInterrupted })
            }
            val sourceSha = hex(digest.digest())
            if (expectedSha256 != null && !sourceSha.equals(expectedSha256, true))
                throw IOException("SHA-256 исходного файла изменился; перечитайте файл")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            InputStreamReader(snapshot.inputStream(), decoder).use { reader ->
                val buffer = CharArray(8192)
                while (true) { checkActive(active); if (reader.read(buffer) < 0) break }
            }
            val hunks = patches.mapIndexed { index, patch -> findUnique(snapshot, patch, index, active) }.sortedBy { it.start }
            hunks.zipWithNext().forEach { (a, b) -> if (a.end > b.start) throw IOException("Блоки патча перекрываются") }
            val outputBytes = snapshot.length() + hunks.sumOf {
                it.replacement.replace.toByteArray(Charsets.UTF_8).size.toLong() - (it.end - it.start)
            }
            if (outputBytes > BoundedAttachmentCopy.MAX_INPUT_BYTES) throw IOException("Результат патча превышает лимит файла 25 МБ")
            return Plan(snapshot, sourceSha, hunks, active)
        } catch (error: Throwable) { snapshot.delete(); throw error }
    }

    private fun findUnique(file: File, patch: Replacement, index: Int, active: () -> Boolean): Hunk {
        val needle = patch.search.toByteArray(Charsets.UTF_8)
        val prefix = IntArray(needle.size)
        for (i in 1 until needle.size) {
            var j = prefix[i - 1]
            while (j > 0 && needle[i] != needle[j]) j = prefix[j - 1]
            if (needle[i] == needle[j]) j++
            prefix[i] = j
        }
        var matched = 0
        var position = 0L
        var newlines = 0L
        val needleNewlines = needle.count { it == 10.toByte() }
        var result: Hunk? = null
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkActive(active)
                val count = input.read(buffer); if (count < 0) break
                for (i in 0 until count) {
                    val byte = buffer[i]
                    if (byte == 10.toByte()) newlines++
                    while (matched > 0 && byte != needle[matched]) matched = prefix[matched - 1]
                    if (byte == needle[matched]) matched++
                    if (matched == needle.size) {
                        if (result != null) throw IOException("search блока ${index + 1} встречается несколько раз")
                        result = Hunk(position + 1 - needle.size, position + 1, newlines - needleNewlines + 1, patch)
                        matched = prefix[matched - 1]
                    }
                    position++
                }
            }
        }
        return result ?: throw IOException("search блока ${index + 1} не найден в исходном файле")
    }
    private fun transfer(input: InputStream, output: OutputStream?, bytes: Long, active: () -> Boolean) {
        var remaining = bytes
        val buffer = ByteArray(64 * 1024)
        while (remaining > 0) {
            checkActive(active)
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (count < 0) throw IOException("Снимок файла повреждён")
            output?.write(buffer, 0, count)
            remaining -= count
        }
    }
    internal fun checkActive(active: () -> Boolean) {
        if (!active() || Thread.currentThread().isInterrupted) throw IOException("Изменение файла отменено")
    }
    internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    fun sha256(file: File, active: () -> Boolean = { true }): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { checkActive(active); val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return hex(digest.digest())
    }
}
