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

    @Test fun legacyDefaultDoesNotDependOnDeterministicHardLinkPublication() {
        val unavailableHardLinks = LocalArtifactToolExecutor(context) { _, _ ->
            throw UnsupportedOperationException("synthetic unsupported filesystem")
        }
        val result = unavailableHardLinks.createTextFile(args("OpenAI/OpenRouter legacy source 😀"))
        val file = File(result.getString("path"))
        try {
            assertEquals("OpenAI/OpenRouter legacy source 😀", file.readText())
            assertFalse(file.name.startsWith("create_"))
            assertEquals(result.getString("sha256"), LocalUtf8FilePatch.sha256(file))
        } finally { file.delete() }
    }

    @Test fun unsupportedDeepSeekPublicationIsExplicitAndKeepsTheStableIntent() {
        val unavailableHardLinks = LocalArtifactToolExecutor(context) { _, _ ->
            throw UnsupportedOperationException("synthetic unsupported filesystem")
        }
        val arguments = args("DeepSeek planned output")
        val key = "unsupported-${UUID.randomUUID()}"
        val intent = unavailableHardLinks.prepareCreateTextFile(arguments, key)
        val file = File(intent.getString("path"))
        try {
            unavailableHardLinks.createTextFile(arguments, key)
            fail("unsupported deterministic publication must fail closed")
        } catch (error: IOException) {
            assertTrue(error.message!!.contains("не поддерживает"))
            assertTrue(error.cause is UnsupportedOperationException)
        }
        assertFalse(file.exists())
        assertEquals(intent.toString(), unavailableHardLinks.prepareCreateTextFile(arguments, key).toString())
    }

    @Test fun publicationFailureAfterLinkCannotDeleteTheCompletedArtifact() {
        val uncertainPublisher = LocalArtifactToolExecutor(context) { target, staged ->
            java.nio.file.Files.createLink(target.toPath(), staged.toPath())
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
            } catch (_: IOException) {}
            assertTrue(file.isFile)
            assertEquals(plan.getString("sha256"), LocalUtf8FilePatch.sha256(file))
            assertEquals(plan.toString(), executor.createTextFile(arguments, key).toString())
        } finally { file.delete() }
    }

    @Test fun invalidCreateIsRejectedBeforeIntentCanBePrepared() {
        for (invalid in listOf(JSONObject().put("filename", "image.png").put("content", "fake binary"),
            JSONObject().put("filename", "result.txt").put("content", 123),
            JSONObject().put("filename", " ").put("content", "text"))) {
            try {
                executor.prepareCreateTextFile(invalid, "invalid-${UUID.randomUUID()}")
                fail("invalid file operation must not produce durable intent metadata")
            } catch (_: IOException) {}
        }
    }
}
