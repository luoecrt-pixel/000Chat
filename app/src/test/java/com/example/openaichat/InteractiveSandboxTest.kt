package com.example.openaichat

import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InteractiveSandboxTest {
    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun request(url: String, main: Boolean = false, method: String = "GET") = object : WebResourceRequest {
        override fun getUrl() = Uri.parse(url)
        override fun isForMainFrame() = main
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = method
        override fun getRequestHeaders() = emptyMap<String, String>()
    }

    @Test fun sourceCannotEscapeTheOpaqueFrameAndBothPoliciesPermitOnlyInlineCode() {
        val attack = "\"></iframe><script>window.attack=1</script>&<img src=\"https://tracker.invalid\">"
        val document = InteractiveSandboxDocument.create(attack, AppThemePalette.DARK)
        assertFalse(document.contains(attack))
        assertTrue(document.contains(ResponseRenderSafety.html(attack)))
        assertEquals(1, Regex("<iframe ").findAll(document).count())
        assertTrue(document.contains("sandbox=\"allow-scripts\""))
        assertFalse(document.contains("allow-same-origin"))
        assertFalse(document.contains("allow-top-navigation"))
        assertFalse(document.contains("allow-popups"))
        assertTrue(InteractiveSandboxDocument.HOST_CSP.contains("script-src 'unsafe-inline'"))
        assertTrue(InteractiveSandboxDocument.FRAME_CSP.contains("script-src 'unsafe-inline'"))
        assertTrue(InteractiveSandboxDocument.FRAME_CSP.contains("frame-src 'none'"))
        assertTrue(document.contains("worker-src 'none'"))
        assertTrue(document.contains("connect-src 'none'"))
        assertFalse(document.contains("unsafe-eval"))
        assertFalse(document.contains("addJavascriptInterface"))
    }

    @Test fun explicitStartAppliesSandboxSettingsAndOnlyTheOwnedMainDocumentIsServed() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val sandbox = InteractiveSandboxView(activity, "<button onclick=\"this.textContent='done'\">Run</button>")
            activity.setContentView(sandbox)
            assertTrue(descendants(sandbox).none { it is WebView })
            sandbox.start()
            val web = descendants(sandbox).filterIsInstance<WebView>().single()
            assertTrue(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.domStorageEnabled)
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
            val url = requireNotNull(Shadows.shadowOf(web).lastLoadedUrl)
            val client = web.webViewClient!!
            assertEquals(200, client.shouldInterceptRequest(web, request(url, true))!!.statusCode)
            listOf("https://tracker.invalid/pixel", "file:///data/private", "content://private/file", url + "?x=1").forEach {
                assertEquals(403, client.shouldInterceptRequest(web, request(it, true))!!.statusCode)
                assertTrue(client.shouldOverrideUrlLoading(web, request(it, true)))
            }
            assertEquals(403, client.shouldInterceptRequest(web, request(url, false))!!.statusCode)
            assertEquals(403, client.shouldInterceptRequest(web, request(url, true, "POST"))!!.statusCode)
            var chooserCalled = false
            assertTrue(web.webChromeClient!!.onShowFileChooser(web, { value -> assertNull(value); chooserCalled = true }, null))
            assertTrue(chooserCalled)
            sandbox.close()
            assertTrue(descendants(sandbox).none { it is WebView })
            assertEquals(403, client.shouldInterceptRequest(web, request(url, true))!!.statusCode)
            sandbox.start()
            assertTrue(descendants(sandbox).none { it is WebView })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun dialogAndDetachDestroyTheRendererWhileKeepingCompleteSource() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val source = "<script>document.body.append('😀 Кириллица');</script>"
            var dismissed = false
            val dialog = InteractiveSandboxDialog.create(activity, source) { dismissed = true }
            assertTrue(descendants(dialog.window!!.decorView).none { it is WebView })
            dialog.show()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            val content = dialog.window!!.decorView
            assertEquals(1, descendants(content).filterIsInstance<WebView>().size)
            dialog.dismiss()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(dismissed)
            assertTrue(descendants(content).none { it is WebView })
            val sandbox = InteractiveSandboxView(activity, source)
            activity.setContentView(sandbox); sandbox.start()
            activity.setContentView(View(activity))
            assertTrue(descendants(sandbox).none { it is WebView })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun oversizedInputAndRendererFailureKeepSourceWithoutPartialExecution() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val source = "x".repeat(InteractiveSandboxDocument.MAX_SOURCE_CHARACTERS + 1)
            val sandbox = InteractiveSandboxView(activity, source)
            activity.setContentView(sandbox); sandbox.start()
            assertTrue(descendants(sandbox).none { it is WebView })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString().contains("слишком велик") })
            try { InteractiveSandboxDocument.create(source, AppThemePalette.DARK); fail("Oversize must be rejected") }
            catch (_: IllegalArgumentException) { }
            val small = InteractiveSandboxView(activity, "<p>original</p>")
            activity.setContentView(small); small.start()
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(12))
            assertTrue(descendants(small).none { it is WebView })
            assertTrue(descendants(small).filterIsInstance<TextView>().any { it.text.toString() == "<p>original</p>" })
        } finally { controller.pause().stop().destroy() }
    }
}
