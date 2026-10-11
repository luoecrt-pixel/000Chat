package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LocalArtifactWriteRegressionTest {
    @Test fun completeLargeSourceAndUtf8BoundarySurviveSaveAndReopen() {
        val source = "a".repeat(65535) + "😀\u0000\n" + "Философия科学\n".repeat(100000) + "THE_END"
        val result = LocalArtifactToolExecutor(RuntimeEnvironment.getApplication()).createTextFile(
            JSONObject().put("filename", "complete-source.txt").put("content", source))
        val file = File(result.getString("path"))
        try {
            assertEquals(source, file.readText(Charsets.UTF_8))
            assertEquals(file.length(), result.getLong("size_bytes"))
            assertEquals("text/plain", result.getString("mime_type"))
            assertTrue(file.readText(Charsets.UTF_8).endsWith("THE_END"))
        } finally { file.delete() }
    }
}
