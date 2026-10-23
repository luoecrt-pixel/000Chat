package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LocalArtifactCreateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val executor get() = LocalArtifactToolExecutor(context)
    private fun args(content: String) = JSONObject().put("filename", "result.txt").put("content", content)

    @Test fun planningWritesNothingAndUnicodePublicationMatchesExactUtf8Bytes() {
        val input = "a".repeat(65_535) + "\uD83D\uDE00" + "Жё漢字" + "\uD800"
        val arguments = args(input)
        val key = "unicode-${UUID.randomUUID()}"
        val plan = executor.prepareCreateTextFile(arguments, key)
        val file = File(plan.getString("path"))
        assertFalse(file.exists())
        val expected = input.toByteArray(Charsets.UTF_8)
        assertEquals(expected.size.toLong(), plan.getLong("size_bytes"))
        assertEquals(LocalUtf8FilePatch.hex(MessageDigest.getInstance("SHA-256").digest(expected)), plan.getString("sha256"))
        try {
            val result = executor.createTextFile(arguments, key)
            assertEquals(plan.toString(), result.toString())
            assertArrayEquals(expected, file.readBytes())
            assertEquals(plan.getString("sha256"), result.getJSONObject("attachment").getString("sha256"))
        } finally { file.delete() }
    }

    @Test fun completedDeterministicFileIsRecoveredWithoutAnotherWrite() {
        val arguments = args("confirmed operation")
        val key = "idempotent-${UUID.randomUUID()}"
        val first = executor.createTextFile(arguments, key)
        val file = File(first.getString("path"))
        try {
            assertTrue(file.setLastModified(123_000L))
            val second = executor.createTextFile(arguments, key)
            assertEquals(first.toString(), second.toString())
            assertEquals(123_000L, file.lastModified())
            assertEquals("confirmed operation", file.readText())
        } finally { file.delete() }
    }

    @Test fun mismatchedExistingFileCannotBeOverwrittenOrDeleted() {
        val key = "conflict-${UUID.randomUUID()}"
        val first = executor.createTextFile(args("confirmed original"), key)
        val file = File(first.getString("path"))
        try {
            try {
                executor.createTextFile(args("different output"), key)
                fail("same artifact key must not overwrite a confirmed file")
            } catch (_: IOException) {}
            assertTrue(file.exists())
            assertEquals("confirmed original", file.readText())
            assertEquals(first.getString("sha256"), LocalUtf8FilePatch.sha256(file))
        } finally { file.delete() }
    }

    @Test fun legacyDefaultDoesNotDependOnDeterministicAtomicPublication() {
        val unavailableAtomicMoves = LocalArtifactToolExecutor(context) { _, _ ->
            throw UnsupportedOperationException("synthetic filesystem without atomic move")
        }
        val result = unavailableAtomicMoves.createTextFile(args("OpenAI/OpenRouter legacy source 😀"))
        val file = File(result.getString("path"))
        try {
            assertEquals("OpenAI/OpenRouter legacy source 😀", file.readText())
            assertFalse(file.name.startsWith("create_"))
            assertEquals(result.getString("sha256"), LocalUtf8FilePatch.sha256(file))
        } finally { file.delete() }
    }

    @Test fun unsupportedDeepSeekPublicationIsExplicitAndKeepsTheStableIntent() {
        val unavailableAtomicMoves = LocalArtifactToolExecutor(context) { _, _ ->
            throw UnsupportedOperationException("synthetic filesystem without atomic move")
        }
        val arguments = args("DeepSeek planned output")
        val key = "unsupported-${UUID.randomUUID()}"
        val intent = unavailableAtomicMoves.prepareCreateTextFile(arguments, key)
        val file = File(intent.getString("path"))
        try {
            unavailableAtomicMoves.createTextFile(arguments, key)
            fail("unsupported deterministic publication must fail closed")
        } catch (error: FileWriteOutcomeUnknownException) {
            assertTrue(error.message!!.contains("не подтверждён"))
            assertTrue(generateSequence<Throwable>(error) { it.cause }.any { it is UnsupportedOperationException })
        }
        assertFalse(file.exists())
        assertEquals(intent.toString(), unavailableAtomicMoves.prepareCreateTextFile(arguments, key).toString())
    }

    @Test fun publicationFailureAfterMoveIsTypedAndCannotDeleteTheCompletedArtifact() {
        val uncertainPublisher = LocalArtifactToolExecutor(context) { target, staged ->
            java.nio.file.Files.move(staged.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            throw java.nio.file.FileSystemException("synthetic post-publication failure")
        }
        val arguments = args("confirmed bytes survive metadata failure")
        val key = "post-publication-${UUID.randomUUID()}"
        val plan = uncertainPublisher.prepareCreateTextFile(arguments, key)
        val file = File(plan.getString("path"))
        try {
            try {
                uncertainPublisher.createTextFile(arguments, key)
                fail("publication outcome should be reported for durable reconciliation")
            } catch (_: FileWriteOutcomeUnknownException) {}
            assertTrue(file.isFile)
            assertEquals(plan.getString("sha256"), LocalUtf8FilePatch.sha256(file))
            assertEquals(plan.toString(), executor.createTextFile(arguments, key).toString())
        } finally { file.delete() }
    }

    @Test fun interruptedPublicationDoesNotPublishOrClearTheInterrupt() {
        val arguments = args("interrupted operation")
        val key = "interrupted-${UUID.randomUUID()}"
        val plan = executor.prepareCreateTextFile(arguments, key)
        Thread.currentThread().interrupt()
        try {
            try {
                executor.createTextFile(arguments, key)
                fail("interrupted publication must stop before file bytes are published")
            } catch (_: java.io.InterruptedIOException) {}
            assertTrue(Thread.currentThread().isInterrupted)
            assertFalse(File(plan.getString("path")).exists())
        } finally { Thread.interrupted() }
    }

    @Test fun patchIntentIsObservedBeforeEitherFinalFileAndIncludesExactSidecarHash() {
        val original = executor.createTextFile(args("original content"))
        val source = File(original.getString("path"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val arguments = JSONObject().put("file_id", attachment.id).put("patches", org.json.JSONArray().put(
            JSONObject().put("search", "original").put("replace", "modified")))
        var planned: JSONObject? = null
        try {
            try {
                executor.patchLocalFile(arguments, listOf(attachment), artifactKey = "patch-intent-${UUID.randomUUID()}",
                    beforePublish = { metadata ->
                        planned = metadata
                        assertFalse(File(metadata.getString("path")).exists())
                        assertFalse(File(metadata.getString("patch_diff_path")).exists())
                        assertEquals(original.getString("sha256"), metadata.getString("source_sha256"))
                        assertTrue(metadata.getString("operation_sha256").matches(Regex("[0-9a-f]{64}")))
                        assertTrue(metadata.getString("patch_sidecar_sha256").matches(Regex("[0-9a-f]{64}")))
                        assertTrue(metadata.getLong("patch_sidecar_size_bytes") > 0)
                        throw java.util.concurrent.CancellationException("stop before durable intent acceptance")
                    })
                fail("rejected intent must prevent publication")
            } catch (_: java.util.concurrent.CancellationException) {}
            assertNotNull(planned)
            assertFalse(File(planned!!.getString("path")).exists())
            assertFalse(File(planned!!.getString("patch_diff_path")).exists())
            assertEquals("original content", source.readText())
        } finally { source.delete() }
    }

    @Test fun patchAfterResultMoveThrowsTypedUnknownAndPreservesOriginalAndPublishedBytes() {
        val original = executor.createTextFile(args("original content"))
        val source = File(original.getString("path"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val arguments = JSONObject().put("file_id", attachment.id).put("patches", org.json.JSONArray().put(
            JSONObject().put("search", "original").put("replace", "modified")))
        var intent: JSONObject? = null
        var publications = 0
        val failing = LocalArtifactToolExecutor(context) { target, stage ->
            publications++
            java.nio.file.Files.move(stage.toPath(), target.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            throw IOException("synthetic failure after actual result move")
        }
        try {
            try {
                failing.patchLocalFile(arguments, listOf(attachment), artifactKey = "postmove-patch-${UUID.randomUUID()}",
                    beforePublish = { intent = it })
                fail("published patch outcome must be typed unknown")
            } catch (_: FileWriteOutcomeUnknownException) {}
            assertEquals(1, publications)
            assertNotNull(intent)
            val result = File(intent!!.getString("path"))
            assertEquals("modified content", result.readText())
            assertEquals(intent!!.getString("sha256"), LocalUtf8FilePatch.sha256(result))
            assertFalse(File(intent!!.getString("patch_diff_path")).exists())
            assertEquals("original content", source.readText())
        } finally {
            source.delete()
            intent?.let { File(it.getString("path")).delete(); File(it.getString("patch_diff_path")).delete() }
        }
    }

    @Test fun rejectedPrepublicationIntentIsAnOrdinaryIoFailureWithoutAnyPublishedEffect() {
        val original = executor.createTextFile(args("original content"))
        val source = File(original.getString("path"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val arguments = JSONObject().put("file_id", attachment.id).put("patches", org.json.JSONArray().put(
            JSONObject().put("search", "original").put("replace", "modified")))
        var intent: JSONObject? = null
        try {
            try {
                executor.patchLocalFile(arguments, listOf(attachment), artifactKey = "beforewrite-${UUID.randomUUID()}",
                    beforePublish = { intent = it; throw IOException("synthetic SQLite intent rejection") })
                fail("rejected intent must abort before publication")
            } catch (error: IOException) { assertFalse(error is FileWriteOutcomeUnknownException) }
            assertNotNull(intent)
            assertFalse(File(intent!!.getString("path")).exists())
            assertFalse(File(intent!!.getString("patch_diff_path")).exists())
            assertEquals("original content", source.readText())
        } finally { source.delete() }
    }

    @Test fun invalidCreateIsRejectedBeforeIntentCanBePrepared() {
        for (invalid in listOf(JSONObject().put("filename", "image.png").put("content", "fake binary"),
            JSONObject().put("filename", "result.txt").put("content", 123),
            JSONObject().put("filename", " ").put("content", "text"))) {
            try {
                executor.prepareCreateTextFile(invalid, "invalid-${UUID.randomUUID()}")
                fail("invalid file operation must not produce durable intent metadata")
            } catch (error: IOException) { assertFalse(error is FileWriteOutcomeUnknownException) }
        }
    }
}
