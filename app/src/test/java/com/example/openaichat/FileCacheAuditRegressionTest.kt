package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileCacheAuditRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun cache(name: String): OpenAiFileUploadCache {
        val store = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        return OpenAiFileUploadCache(store).also { it.clear() }
    }

    @Test fun bothOpenAiFileIdPrefixesAreValidatedReusedAndEvictedExactly() {
        val dir = File(context.cacheDir, "audit-id-prefixes").apply { deleteRecursively(); mkdirs() }
        try {
            for (id in listOf("file_abc123", "file-abc123")) {
                val uploadCache = cache("audit-id-prefix-$id")
                val file = File(dir, "$id.txt").apply { writeText("original") }
                var calls = 0
                fun prepare() = uploadCache.getOrUpload(file, file.name, "text/plain", "a", { true }) { calls++; id }
                assertTrue(isOpenAiFileId(id)); assertEquals(id, prepare()); assertEquals(id, prepare())
                assertEquals(1, calls); assertEquals(setOf(id), uploadCache.knownIds())
                fun error(mentioned: String) = JSONObject().put("code", "file_not_found")
                    .put("message", "No such file: '$mentioned'.")
                assertNull(OpenAiFileFailure.recover(uploadCache, error(id + "_extra"), 404))
                assertNull(OpenAiFileFailure.recover(uploadCache, error("prefix" + id), 404))
                assertEquals(setOf(id), uploadCache.knownIds())
                assertNotNull(OpenAiFileFailure.recover(uploadCache, error(id), 404))
                assertTrue(uploadCache.knownIds().isEmpty()); assertEquals(1, calls)
                assertEquals(id, prepare()); assertEquals(2, calls)
            }
            for (id in listOf(null, "", "file_", "file-", "file/a", " file-abc", "file_abc\n", "file_abc/extra", "xfile-abc")) {
                assertFalse("Invalid ID: $id", isOpenAiFileId(id))
            }
            assertEquals(listOf("file_abc", "file-def"), findOpenAiFileIds("'file_abc', file-def; xfile-abc").toList())
        } finally { dir.deleteRecursively() }
    }

    @Test fun fileIdRecoveryRejectsFilenamePrefixesAndAmbiguousDiagnostics() {
        val uploadCache = cache("audit-unambiguous-file-id")
        val dir = File(context.cacheDir, "audit-unambiguous-file-id").apply { deleteRecursively(); mkdirs() }
        try {
            val first = File(dir, "first.txt").apply { writeText("first") }
            val second = File(dir, "second.txt").apply { writeText("second") }
            uploadCache.getOrUpload(first, first.name, "text/plain", "a", { true }) { "file-first" }
            uploadCache.getOrUpload(second, second.name, "text/plain", "a", { true }) { "file-second" }
            val both = setOf("file-first", "file-second")
            fun error(message: String) = JSONObject().put("code", "file_not_found")
                .put("message", message)

            for (suffix in listOf("file-first.txt", "file-first/extra", "file-first\\extra", "file-first%2Fextra")) {
                assertNull("Must not match file ID prefix in $suffix",
                    OpenAiFileFailure.recover(uploadCache, error("File '$suffix' does not exist"), 404))
            }
            assertEquals(both, uploadCache.knownIds())
            assertNull(OpenAiFileFailure.recover(uploadCache,
                error("File file-first does not exist; file-second was also supplied"), 404))
            assertEquals(both, uploadCache.knownIds())
            assertNull(OpenAiFileFailure.recover(uploadCache,
                error("File file-first does not exist; file-other was also supplied"), 404))
            assertEquals(both, uploadCache.knownIds())

            assertNotNull(OpenAiFileFailure.recover(uploadCache,
                error("File file-first does not exist; file-second was also supplied")
                    .put("file_id", "file-first"), 404))
            assertEquals(setOf("file-second"), uploadCache.knownIds())
            assertNull(OpenAiFileFailure.recover(uploadCache,
                error("File file-second does not exist").put("file_id", "file-second/extra"), 404))
            assertEquals(setOf("file-second"), uploadCache.knownIds())
        } finally { dir.deleteRecursively() }
    }

    @Test fun historicalDocumentIdsUseCurrentAccountCacheWhileVisionImagesStayInline() {
        val dir = File(context.cacheDir, "audit-account").apply { deleteRecursively(); mkdirs() }
        val uploadCache = cache("audit-account-cache")
        try {
            val pdf = File(dir, "original.pdf").apply { writeText("original-pdf") }
            val imageBytes = TestPngFixtures.png(3, 2, 0xffd52c36.toInt())
            val image = File(dir, "original.png").apply { writeBytes(imageBytes) }
            val message = JSONObject().put("role", "user").put("provider", "openai")
                .put("file_paths", JSONArray().put(pdf.path)).put("file_ids", JSONArray().put("file_old_document"))
                .put("image_path", image.path).put("image_file_id", "file_old_image")
                .put("attachments", JSONArray()
                    .put(OpenRouterLocalAttachment("d", pdf.name, "pdf", pdf.path, "application/pdf", pdf.length(), "").toJson())
                    .put(OpenRouterLocalAttachment("i", image.name, "image", image.path, "image/png", image.length(), "").toJson()))
            val calls = AtomicInteger()
            fun prepare(account: String) = OpenAiFileAnalysis.prepare(listOf(message), { true }) { file, name, mime ->
                assertFalse("Vision photos must not use the server upload cache", mime.startsWith("image/"))
                uploadCache.getOrUpload(file, name, mime, account, { true }) { "file_new_${calls.incrementAndGet()}" }
            }
            val first = prepare("account-a")
            assertEquals(setOf(pdf.path), first.keys)
            assertEquals(first, prepare("account-a")); assertEquals(1, calls.get())
            val second = prepare("account-b")
            assertEquals(2, calls.get()); assertTrue(first.values.none { it in second.values })
            assertEquals("file_old_document", message.getJSONArray("file_ids").getString(0))
            assertEquals("file_old_image", message.getString("image_file_id"))
            val writer = StringWriter()
            StreamingJsonRequestBody.writeJson(OpenAiLocalContextEncoder.encode(listOf(message), second), writer)
            val encoded = writer.toString()
            assertTrue(second.values.all { encoded.contains(it) }); assertFalse(encoded.contains("file_old_"))
            val parts = JSONArray(encoded).getJSONObject(0).getJSONArray("content")
            val vision = (0 until parts.length()).map { parts.getJSONObject(it) }.single { it.optString("type") == "input_image" }
            assertFalse(vision.has("file_id"))
            assertArrayEquals(imageBytes, java.util.Base64.getDecoder().decode(vision.getString("image_url").substringAfter(',')))
            assertArrayEquals(imageBytes, image.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun slowUploadDoesNotBlockAnotherFileAndSameFileUploadsOnlyOnce() {
        val dir = File(context.cacheDir, "audit-parallel").apply { deleteRecursively(); mkdirs() }
        val uploadCache = cache("audit-parallel-cache")
        val pool = Executors.newFixedThreadPool(3)
        val started = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        try {
            val first = File(dir, "first.txt").apply { writeText("one") }
            val other = File(dir, "other.txt").apply { writeText("two") }
            val slow = pool.submit<String> {
                uploadCache.getOrUpload(first, first.name, "text/plain", "a", { true }) {
                    calls.incrementAndGet(); started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "file_first"
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val same = pool.submit<String> {
                uploadCache.getOrUpload(first, first.name, "text/plain", "a", { true }) { calls.incrementAndGet(); "file_duplicate" }
            }
            val independent = pool.submit<String> {
                uploadCache.getOrUpload(other, other.name, "text/plain", "a", { true }) { "file_other" }
            }
            assertEquals("file_other", independent.get(2, TimeUnit.SECONDS))
            release.countDown()
            assertEquals("file_first", slow.get(2, TimeUnit.SECONDS)); assertEquals("file_first", same.get(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun clearingDuringUploadDoesNotRepopulateCache() {
        val dir = File(context.cacheDir, "audit-reset").apply { deleteRecursively(); mkdirs() }
        val uploadCache = cache("audit-reset-cache")
        val file = File(dir, "f.txt").apply { writeText("bytes") }
        val pool = Executors.newSingleThreadExecutor(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val result = pool.submit<String> {
                uploadCache.getOrUpload(file, file.name, "text/plain", "a", { true }) {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "file_before_reset"
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS)); uploadCache.clear(); release.countDown()
            assertEquals("file_before_reset", result.get(2, TimeUnit.SECONDS)); assertTrue(uploadCache.knownIds().isEmpty())
            assertEquals("file_after_reset", uploadCache.getOrUpload(file, file.name, "text/plain", "a", { true }) { "file_after_reset" })
        } finally { release.countDown(); pool.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun uploadPurposeIsPartOfTheAccountAndByteIdentity() {
        val file = File(context.cacheDir, "audit-purpose.png").apply { writeText("same bytes") }
        val uploadCache = cache("audit-purpose-cache")
        try {
            assertEquals("file_vision", uploadCache.getOrUpload(file, file.name, "image/png", "a", { true }) { "file_vision" })
            assertEquals("file_user", uploadCache.getOrUpload(file, file.name, "image/png", "a", { true }, purpose = "user_data") { "file_user" })
            assertEquals("file_vision", uploadCache.getOrUpload(file, file.name, "image/png", "a", { true }) { throw AssertionError("Must reuse same purpose") })
        } finally { file.delete() }
    }

    @Test fun identicalBytesKeepDistinctNamesMimeCredentialsAndPurposes() {
        val dir = File(context.cacheDir, "audit-exact-metadata-${System.nanoTime()}").apply { mkdirs() }
        val uploadCache = cache("audit-exact-metadata-cache")
        try {
            val first = File(dir, "a.txt").apply { writeText("identical bytes") }
            val second = File(dir, "b.txt").apply { writeBytes(first.readBytes()) }
            var calls = 0
            fun prepare(file: File, name: String, mime: String = "text/plain", credential: String = "a", purpose: String = "user_data") =
                uploadCache.getOrUpload(file, name, mime, credential, { true }, purpose) { "file_exact_${++calls}" }
            val a = prepare(first, "a.txt")
            assertEquals(a, prepare(second, "a.txt"))
            val b = prepare(second, "b.txt")
            assertNotEquals(a, b)
            assertEquals(b, prepare(first, "b.txt"))
            assertNotEquals(a, prepare(first, "a.txt", mime = "text/markdown"))
            assertNotEquals(a, prepare(first, "a.txt", credential = "b"))
            assertNotEquals(a, prepare(first, "a.txt", purpose = "vision"))
            assertEquals(5, calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidatingOneIdKeepsAnotherConcurrentUploadReusable() {
        val dir = File(context.cacheDir, "audit-scoped-reset").apply { deleteRecursively(); mkdirs() }
        val uploadCache = cache("audit-scoped-reset-cache")
        val pool = Executors.newSingleThreadExecutor(); val started = CountDownLatch(1); val release = CountDownLatch(1)
        try {
            val old = File(dir, "old.txt").apply { writeText("old") }; val other = File(dir, "other.txt").apply { writeText("other") }
            uploadCache.getOrUpload(old, old.name, "text/plain", "a", { true }) { "file_gone" }
            val result = pool.submit<String> {
                uploadCache.getOrUpload(other, other.name, "text/plain", "a", { true }) {
                    started.countDown(); check(release.await(5, TimeUnit.SECONDS)); "file_other"
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS)); uploadCache.invalidate("file_gone"); release.countDown()
            assertEquals("file_other", result.get(2, TimeUnit.SECONDS))
            assertEquals("file_other", uploadCache.getOrUpload(other, other.name, "text/plain", "a", { true }) { throw AssertionError("Unrelated invalidation must not cause another upload") })
            assertEquals(setOf("file_other"), uploadCache.knownIds())
        } finally { release.countDown(); pool.shutdownNow(); dir.deleteRecursively() }
    }

    @Test fun laterGithubPagesRequireEtagBeforeAnyFetchWithOrWithoutDiskCache() {
        val dir = File(context.cacheDir, "audit-etag").apply { deleteRecursively(); mkdirs() }
        try {
            for (tag in listOf<String?>(null, "")) {
                assertThrows(IOException::class.java) {
                    GitHubTextPageCache(dir).read("repo/file", 1, 20, tag, { true }) { _, _ -> throw AssertionError("Must not fetch") }
                }
            }
            for (disk in listOf<File?>(null, dir)) {
                val executor = GitHubToolExecutor(SecureStore(context), { "o/r" }, { setOf("main") }, { _, _ -> false }, { _, _ -> }, cacheDirectory = disk)
                val result = JSONObject(executor.execute("get_file_range", JSONObject().put("owner", "o").put("repo", "r")
                    .put("ref", "main").put("path", "source.kt").put("offset", 10)))
                assertFalse(result.getBoolean("ok")); assertTrue(result.toString().contains("ETag"))
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun diskBudgetIncludesReplacementAndPartialSnapshotsAndSameEtagNeedsNoSecondCopy() {
        val dir = File(context.cacheDir, "audit-budget").apply { deleteRecursively(); mkdirs() }
        val budget = 10000L
        fun used() = dir.listFiles().orEmpty().filter { it.extension in setOf("utf16", "partial") }.sumOf { it.length() }
        val reader = GitHubTextPageCache(dir, budget)
        fun read(key: String, tag: String, text: String) = reader.read(key, 0, 20, null, { true }) { _, save ->
            InputStreamReader(text.byteInputStream(), Charsets.UTF_8).use { save(it, tag) }
            assertTrue(used() <= budget); tag
        }
        try {
            assertEquals("a".repeat(20), read("first", "v1", "a".repeat(3500)).getString("content"))
            read("second", "v1", "b".repeat(3500)); assertTrue(used() <= budget)
            assertEquals(1, dir.listFiles().orEmpty().count { it.extension == "utf16" })
            read("second", "v1", "b".repeat(3500)); assertTrue(used() <= budget)
            File(dir, StreamingJsonRequestBody.sha256("second") + ".partial").writeBytes(ByteArray(8000))
            reader.read("second", 0, 20, "v1", { true }) { tag, _ -> assertEquals("v1", tag); "v1" }
            assertTrue(used() <= budget); assertTrue(dir.listFiles().orEmpty().none { it.extension == "partial" })
            read("second", "v2", "c".repeat(3500)); assertTrue(used() <= budget)
            assertThrows(IOException::class.java) { read("oversize", "v1", "d".repeat(6000)) }
            assertTrue(used() <= budget); assertTrue(dir.listFiles().orEmpty().none { it.extension == "partial" })
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingServerFileEvictsOnlyNamedEntryAndNextExplicitPreparationUploadsOnce() {
        val dir = File(context.cacheDir, "audit-remote-delete").apply { deleteRecursively(); mkdirs() }
        val uploadCache = cache("audit-remote-delete-cache")
        try {
            val a = File(dir, "a.txt").apply { writeText("a") }; val b = File(dir, "b.txt").apply { writeText("b") }
            var calls = 0
            fun prepare(file: File, id: String) = uploadCache.getOrUpload(file, file.name, "text/plain", "a", { true }) { calls++; id }
            prepare(a, "file_gone"); prepare(b, "file_kept")
            val failure = JSONObject().put("code", "file_not_found").put("message", "File file_gone does not exist")
            assertNull(OpenAiFileFailure.recover(uploadCache, failure, 401))
            assertNull(OpenAiFileFailure.recover(uploadCache, JSONObject().put("message", "File file_gone temporarily unavailable"), 500))
            assertNull(OpenAiFileFailure.recover(uploadCache, JSONObject().put("message", "File file_gone_extra does not exist"), 404))
            assertEquals(setOf("file_gone", "file_kept"), uploadCache.knownIds())
            assertTrue(OpenAiFileFailure.recover(uploadCache, failure, 400)!!.contains("Повторите запрос"))
            assertEquals(2, calls); assertEquals(setOf("file_kept"), uploadCache.knownIds())
            assertEquals("file_reloaded", prepare(a, "file_reloaded")); assertEquals("file_kept", prepare(b, "file_wrong"))
            assertEquals(3, calls)
        } finally { dir.deleteRecursively() }
    }

    @Test fun telemetryCountsActualCanonicalScansButSizeChecksDoNotReadFiles() {
        val file = File(context.cacheDir, "audit-io.pdf").apply { writeBytes(ByteArray(1024 * 1024) { it.toByte() }) }
        try {
            val wire = FileBackedDataUrl(file, "application/pdf")
            val body = JSONObject().put("file_data", wire)
            assertEquals(1L, wire.ioStats().readPasses)
            repeat(3) { StreamingJsonRequestBody.countBytes(body) }
            assertEquals(1L, wire.ioStats().readPasses)
            StreamingJsonRequestBody.sha256(body) // fingerprint v1 deliberately includes full wire bytes
            val discard = object : java.io.Writer() {
                override fun write(chars: CharArray, off: Int, len: Int) {}
                override fun flush() {}
                override fun close() {}
            }
            repeat(2) { StreamingJsonRequestBody.writeJson(body, discard) }
            val stats = FileBackedIoTelemetry.snapshot(JSONArray().put(body).put(body))
            assertEquals(1, stats.files); assertEquals(4L, stats.readPasses)
            assertEquals(file.length() * 4L, stats.readBytes); assertTrue(stats.workNanos > 0L)
            file.writeBytes(ByteArray(1024 * 1024))
            assertThrows(IOException::class.java) { StreamingJsonRequestBody.writeJson(body, discard) }
        } finally { file.delete() }
    }

    @Test fun rootAndNestedSseErrorsEvictTheFileAndKeepTheRecoveryHint() {
        val file = File(context.cacheDir, "audit-sse.txt").apply { writeText("bytes") }
        val uploadCache = cache("openai_file_upload_cache")
        try {
            val error = JSONObject().put("code", "file_not_found").put("message", "File file_sse does not exist")
            val fixtures = listOf(JsonProtocol.objectCopy(error).put("type", "error"),
                JSONObject().put("type", "response.failed").put("response", JSONObject().put("id", "resp_test")
                    .put("status", "failed").put("error", JsonProtocol.objectCopy(error))))
            for (event in fixtures) {
                uploadCache.getOrUpload(file, file.name, "text/plain", "a", { true }) { "file_sse" }
                var hint: String? = null
                val result = OpenAiSseStreamReader.read(("data: " + event + "\n\n").reader().buffered()) {
                    hint = OpenAiFileFailure.onEvent(context, it); true
                }
                assertEquals(OpenAiStreamEnd.FAILED, result.end); assertTrue(hint!!.contains("Повторите запрос"))
                if (event.getString("type") == "error") assertTrue(result.errorMessage!!.contains("Повторите запрос"))
                assertTrue(uploadCache.knownIds().isEmpty())
            }
        } finally { file.delete() }
    }
}
