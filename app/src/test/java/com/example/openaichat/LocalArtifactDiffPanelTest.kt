package com.example.openaichat

import android.os.Looper
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class LocalArtifactDiffPanelTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val sha = "a".repeat(64)
    private fun sidecar(): File = File(context.filesDir, "generated/patch-diffs").apply { mkdirs() }
        .resolve(UUID.randomUUID().toString() + ".json").apply {
            writeText(JSONObject().put("version", 1).put("result_sha256", sha).put("hunks", JSONArray().put(
                JSONObject().put("line", 12).put("search", "старый код\n").put("replace", "новый код\n"))).toString())
        }
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun boundedReaderPreservesChangedUtf8AndRejectsOtherVersionOrPrivatePaths() {
        val file = sidecar()
        try {
            val preview = LocalArtifactDiffPanel.readPreview(context, file.path, sha)
            assertFalse(preview.limited)
            assertTrue(preview.lines.any { it.text == "- старый код" && it.added == false })
            assertTrue(preview.lines.any { it.text == "+ новый код" && it.added == true })
            for ((path, digest) in listOf(file.path to "b".repeat(64), File(context.filesDir, "private.json").path to sha)) {
                try { LocalArtifactDiffPanel.readPreview(context, path, digest); fail("Unbound diff must be rejected") }
                catch (_: IOException) { }
            }
        } finally { file.delete() }
    }

    @Test fun oversizedPresentationIsExplicitAndNeverChangesTheSidecar() {
        val file = sidecar()
        try {
            file.appendText("x".repeat(LocalArtifactDiffPanel.MAX_SIDECAR_BYTES))
            val size = file.length()
            try { LocalArtifactDiffPanel.readPreview(context, file.path, sha); fail("Expected explicit display budget") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("лимита превью")) }
            assertEquals(size, file.length())
        } finally { file.delete() }
    }

    @Test fun expandLoadsDiffOnDemandAndUsesThemeColorsWithoutOpeningOrChangingTheFile() {
        val file = sidecar()
        val prefs = context.getSharedPreferences("app", 0)
        val originalTheme = prefs.getString("app_theme", null)
        try {
            prefs.edit().putString("app_theme", "gray").commit()
            val panel = LocalArtifactDiffPanel(context, JSONObject().put("patch_diff_path", file.path).put("sha256", sha))
            assertTrue(descendants(panel).filterIsInstance<TextView>().none { it.text.toString().contains("новый код") })
            descendants(panel).filterIsInstance<Button>().single().performClick()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline && descendants(panel).filterIsInstance<TextView>().none { it.text.toString().contains("+ новый код") }) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            val rendered = descendants(panel).filterIsInstance<TextView>().single { it.text.toString().contains("+ новый код") }.text as Spanned
            val colors = rendered.getSpans(0, rendered.length, ForegroundColorSpan::class.java).map { it.foregroundColor }
            assertTrue(colors.contains(AppThemePalette.GRAY.diffAddedText))
            assertTrue(colors.contains(AppThemePalette.GRAY.errorText))
            assertTrue(file.readText().contains("старый код"))
            descendants(panel).filterIsInstance<Button>().single().performClick()
            assertEquals(View.GONE, (panel.getChildAt(1)).visibility)
        } finally {
            file.delete(); prefs.edit().apply { if (originalTheme == null) remove("app_theme") else putString("app_theme", originalTheme) }.commit()
        }
    }
}
