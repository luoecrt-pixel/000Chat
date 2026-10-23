package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Native app-private filesystem proof, without Robolectric or paid API calls.
 * Execute on API 26 for the minSdk compatibility gate; a run on a newer Android
 * version proves only that device/filesystem combination.
 */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class LocalArtifactPublicationDeviceTest {
    @Test fun deterministicPublicationVerifiesBytesAndRefusesAnExistingDifferentResult() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = LocalArtifactToolExecutor(context)
        val arguments = JSONObject().put("filename", "native-publication.txt")
            .put("content", "a".repeat(65_535) + "😀" + "проверка UTF-8" + "\uD800")
        val key = "native-${UUID.randomUUID()}"
        val intent = executor.prepareCreateTextFile(arguments, key)
        val target = File(intent.getString("path"))
        assertEquals(File(context.filesDir, "generated").canonicalPath, target.parentFile!!.canonicalPath)
        assertFalse(target.exists())
        try {
            val published = executor.createTextFile(arguments, key)
            assertEquals(intent.toString(), published.toString())
            assertArrayEquals(arguments.getString("content").toByteArray(Charsets.UTF_8), target.readBytes())
            assertEquals(intent.getString("sha256"), LocalUtf8FilePatch.sha256(target))
            assertEquals(published.toString(), executor.createTextFile(arguments, key).toString())
            try {
                executor.createTextFile(JSONObject().put("filename", "native-publication.txt").put("content", "different"), key)
                fail("completed artifact must not be overwritten")
            } catch (_: IOException) {}
            assertEquals(intent.getString("sha256"), LocalUtf8FilePatch.sha256(target))
        } finally { target.delete() }
    }

    @Test fun legacyCreateSucceedsWithoutCallingTheDeterministicPublisher() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val executor = LocalArtifactToolExecutor(context) { _, _ ->
            throw UnsupportedOperationException("synthetic filesystem without hard links")
        }
        val result = executor.createTextFile(JSONObject().put("filename", "legacy.txt").put("content", "legacy bytes"))
        val target = File(result.getString("path"))
        try { assertEquals("legacy bytes", target.readText()) } finally { target.delete() }
    }
}
