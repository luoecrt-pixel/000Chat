package com.example.openaichat

import java.io.File
import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalArtifactPatchTest {
    private fun args(id: String, replacement: String = "new") = JSONObject().put("file_id", id)
        .put("patches", JSONArray().put(JSONObject().put("search", "old").put("replace", replacement)))

    @Test fun newVersionDiffAndManifestSurviveIdempotentReplayWithoutExposingFullDiff() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "version.txt").put("content", "header\nold\n"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val key = "test-" + System.nanoTime()
        var updated: JSONObject? = null
        try {
            val result = executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key).also { updated = it }
            val replay = executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key)
            assertEquals(result.getString("path"), replay.getString("path"))
            assertEquals("header\nnew\n", File(result.getString("path")).readText())
            assertEquals("header\nold\n", File(original.getString("path")).readText())
            assertNotEquals(original.getString("path"), result.getString("path"))
            val manifest = result.getJSONObject("attachment")
            assertEquals(result.getString("path"), manifest.getString("text_path"))
            assertEquals(result.getString("sha256"), manifest.getString("sha256"))
            val diff = JSONObject(File(result.getString("patch_diff_path")).readText())
            assertEquals(result.getString("sha256"), diff.getString("result_sha256"))
            assertEquals(2L, diff.getJSONArray("hunks").getJSONObject(0).getLong("line"))
            val wire = LocalArtifactToolExecutor.toolResult(result)
            assertFalse(wire.has("path")); assertFalse(wire.has("patch_diff_path")); assertFalse(wire.has("attachment"))
            assertFalse(wire.toString().contains("header"))
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(attachment.id, "different"), listOf(attachment), artifactKey = key) }
        } finally {
            File(original.getString("path")).delete()
            updated?.let { File(it.getString("path")).delete(); File(it.getString("patch_diff_path")).delete() }
        }
    }

    @Test fun arbitraryPathsAmbiguousNamesAndExpectedShaMismatchFailClosed() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val first = executor.createTextFile(JSONObject().put("filename", "same.txt").put("content", "old"))
        val second = executor.createTextFile(JSONObject().put("filename", "same.txt").put("content", "old other"))
        val files = listOf(first, second).map { OpenRouterLocalAttachment.fromJson(it.getJSONObject("attachment")) }
        try {
            assertThrows(IOException::class.java) { executor.patchLocalFile(args("same.txt"), files) }
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(first.getString("path")), files) }
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(files.first().id).put("expected_sha256", "0".repeat(64)), files) }
            assertEquals("old", File(first.getString("path")).readText())
            val changed = File(first.getString("path")).apply { writeText("OLD") }
            val staleManifestArgs = args(files.first().id).put("patches", JSONArray()
                .put(JSONObject().put("search", "OLD").put("replace", "new")))
            val stale = assertThrows(IOException::class.java) { executor.patchLocalFile(staleManifestArgs, files) }
            assertTrue(stale.message.orEmpty().contains("SHA-256"))
            assertEquals("OLD", changed.readText())
        } finally { listOf(first, second).forEach { File(it.getString("path")).delete() } }
    }

    @Test fun crashBetweenVersionAndSidecarPublicationRepairsOnlyVerifiedMetadata() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "crash.txt").put("content", "old"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val key = "crash-publish-" + System.nanoTime()
        var version: File? = null
        var diff: File? = null
        try {
            val result = executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key)
            version = File(result.getString("path"))
            diff = File(result.getString("patch_diff_path"))
            val timestamp = version.lastModified()
            assertTrue(diff.delete()) // Crash after complete target move, before sidecar move / DB registration.
            val recovered = executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key)
            assertEquals(version.path, recovered.getString("path"))
            assertEquals(timestamp, version.lastModified())
            assertEquals("new", version.readText())
            assertEquals(recovered.getString("sha256"), JSONObject(diff.readText()).getString("result_sha256"))

            assertTrue(diff.delete())
            version.writeText("bad") // Same byte length: rejection must depend on SHA, not size alone.
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key) }
            assertEquals("bad", version.readText())
            assertFalse(diff.exists())
            assertEquals("old", File(original.getString("path")).readText())
        } finally { File(original.getString("path")).delete(); version?.delete(); diff?.delete() }
    }

    @Test fun missingPublishedVersionIsNotResurrectedFromSurvivingSidecar() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "deleted.txt").put("content", "old"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val key = "deleted-publish-" + System.nanoTime()
        var diff: File? = null
        try {
            val result = executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key)
            val version = File(result.getString("path"))
            diff = File(result.getString("patch_diff_path"))
            assertTrue(version.delete())
            val savedDiff = diff.readBytes()
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(attachment.id), listOf(attachment), artifactKey = key) }
            assertFalse(version.exists())
            assertArrayEquals(savedDiff, diff.readBytes())
        } finally { File(original.getString("path")).delete(); diff?.delete() }
    }

    @Test fun unavailableDiffDirectoryLeavesNoStagingFilesOrChangedOriginal() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "blocked.txt").put("content", "old"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val generated = File(context.filesDir, "generated")
        val diffDirectory = File(generated, "patch-diffs")
        val backup = File(generated, "saved-diffs-" + System.nanoTime())
        val hadDirectory = diffDirectory.exists()
        if (hadDirectory) assertTrue(diffDirectory.renameTo(backup))
        try {
            diffDirectory.writeText("not a directory")
            val before = generated.listFiles()!!.map { it.name }.toSet()
            val cacheBefore = context.cacheDir.listFiles()!!.map { it.name }.toSet()
            assertThrows(IOException::class.java) { executor.patchLocalFile(args(attachment.id), listOf(attachment)) }
            assertEquals(before, generated.listFiles()!!.map { it.name }.toSet())
            assertEquals(cacheBefore, context.cacheDir.listFiles()!!.map { it.name }.toSet())
            assertEquals("old", File(original.getString("path")).readText())
        } finally {
            diffDirectory.delete()
            if (hadDirectory) assertTrue(backup.renameTo(diffDirectory))
            File(original.getString("path")).delete()
        }
    }

    @Test fun missingCapturedSourceReturnsSafeErrorWithoutPrivateDevicePath() {
        val context = RuntimeEnvironment.getApplication()
        val executor = LocalArtifactToolExecutor(context)
        val original = executor.createTextFile(JSONObject().put("filename", "private-source.txt").put("content", "old"))
        val attachment = OpenRouterLocalAttachment.fromJson(original.getJSONObject("attachment"))
        val originalPath = original.getString("path")
        assertTrue(File(originalPath).delete())
        val error = assertThrows(IOException::class.java) { executor.patchLocalFile(args(attachment.id), listOf(attachment)) }
        val output = JSONObject().put("ok", false).put("error", error.message).toString()
        assertFalse(output.contains(originalPath))
        assertFalse(output.contains(context.filesDir.path))
        assertTrue(error.message.orEmpty().contains("недоступен"))
        assertFalse(Thread.currentThread().isInterrupted)
    }
}
