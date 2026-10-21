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
        } finally { listOf(first, second).forEach { File(it.getString("path")).delete() } }
    }
}
