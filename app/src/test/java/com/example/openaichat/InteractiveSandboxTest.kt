package com.example.openaichat

import android.app.Activity
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowInteractiveSandboxWebViewFeature::class, ShadowInteractiveSandboxWebViewCompat::class])
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

    @Test fun unsupportedDocumentStartCapabilityRefusesExecutionAndKeepsCompleteSource() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            InteractiveSandboxWebKitState.supported = false
            val source = "<script>window.mustNeverRun='😀 исходник';</script>"
            val sandbox = InteractiveSandboxView(controller.get(), source)
            controller.get().setContentView(sandbox); sandbox.start()
            assertTrue(descendants(sandbox).none { it is WebView })
            assertEquals(0, InteractiveSandboxWebKitState.installed)
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString().contains("не поддерживает") })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun failedNativeGuardInstallationNeverEnablesOrLoadsTheModelRenderer() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            InteractiveSandboxWebKitState.installationFailure = true
            val source = "<script>window.mustNeverRun=true</script>"
            val sandbox = InteractiveSandboxView(controller.get(), source)
            controller.get().setContentView(sandbox); sandbox.start()
            assertEquals(0, InteractiveSandboxWebKitState.installed)
            assertTrue(descendants(sandbox).none { it is WebView })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
        } finally { controller.pause().stop().destroy() }
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
            assertEquals(1, InteractiveSandboxWebKitState.installed)
            assertEquals(setOf("*"), InteractiveSandboxWebKitState.origins)
            assertEquals(InteractiveSandboxScriptGuard.bootstrap, InteractiveSandboxWebKitState.script)
            assertTrue(InteractiveSandboxScriptGuard.bootstrap.contains("configurable: false"))
            assertTrue(InteractiveSandboxScriptGuard.bootstrap.contains("writable: false"))
            assertTrue(web.settings.javaScriptEnabled)
            assertFalse(web.settings.allowFileAccess)
            assertFalse(web.settings.allowContentAccess)
            assertFalse(web.settings.domStorageEnabled)
            assertTrue(web.settings.blockNetworkLoads)
            assertFalse(web.settings.javaScriptCanOpenWindowsAutomatically)
            val url = requireNotNull(Shadows.shadowOf(web).lastLoadedUrl)
            val client = web.webViewClient!!
            assertEquals(200, client.shouldInterceptRequest(web, request(url, true))!!.statusCode)
            listOf("https://tracker.invalid/pixel", "file:///data/private", "content://private/file",
                "intent://private/file", "data:text/html,private", "blob:https://interactive-sandbox.invalid/private",
                "about:blank", url + "?x=1", url + "#fragment").forEach {
                assertEquals(403, client.shouldInterceptRequest(web, request(it, true))!!.statusCode)
                assertTrue(client.shouldOverrideUrlLoading(web, request(it, true)))
            }
            assertEquals(403, client.shouldInterceptRequest(web, request(url, false))!!.statusCode)
            assertEquals(403, client.shouldInterceptRequest(web, request(url, true, "POST"))!!.statusCode)
            var chooserCalled = false
            assertTrue(web.webChromeClient!!.onShowFileChooser(web, { value -> assertNull(value); chooserCalled = true }, null))
            assertTrue(chooserCalled)
            sandbox.close()
            assertEquals(1, InteractiveSandboxWebKitState.removed)
            assertTrue(descendants(sandbox).none { it is WebView })
            assertEquals(403, client.shouldInterceptRequest(web, request(url, true))!!.statusCode)
            sandbox.start()
            assertTrue(descendants(sandbox).none { it is WebView })
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun trustedPreflightWithholdsModelUntilPositiveResultAndIgnoresStaleCallbacks() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val source = "<script>window.uniqueModelPayload='never-in-preflight'</script>"
            val sandbox = InteractiveSandboxView(controller.get(), source)
            controller.get().setContentView(sandbox); sandbox.start()
            val web = descendants(sandbox).filterIsInstance<WebView>().single()
            val preflight = requireNotNull(Shadows.shadowOf(web).lastLoadedUrl)
            assertTrue(preflight.endsWith("/preflight.html"))
            val model = preflight.removeSuffix("preflight.html") + "index.html"
            val client = web.webViewClient!!
            val preflightBody = client.shouldInterceptRequest(web, request(preflight, true))!!.data.bufferedReader().readText()
            assertFalse(preflightBody.contains("uniqueModelPayload"))
            assertTrue(preflightBody.contains("integral-trusted-rtc-preflight"))
            assertEquals(403, client.shouldInterceptRequest(web, request(model, true))!!.statusCode)
            val callback = InteractiveSandboxView::class.java.getDeclaredMethod("onPreflightResult", WebView::class.java, String::class.java)
                .apply { isAccessible = true }
            callback.invoke(sandbox, web, "null")
            assertEquals(preflight, Shadows.shadowOf(web).lastLoadedUrl)
            callback.invoke(sandbox, web, "true")
            assertEquals(model, Shadows.shadowOf(web).lastLoadedUrl)
            assertEquals(403, client.shouldInterceptRequest(web, request(preflight, true))!!.statusCode)
            val modelBody = client.shouldInterceptRequest(web, request(model, true))!!.data.bufferedReader().readText()
            assertTrue(modelBody.contains("uniqueModelPayload"))
            sandbox.close()
            callback.invoke(sandbox, web, "true")
            assertTrue(descendants(sandbox).none { it is WebView })
            assertEquals(403, client.shouldInterceptRequest(web, request(model, true))!!.statusCode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun rejectedTrustedPreflightDestroysRendererWithoutEverServingModel() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val source = "<script>window.mustNeverRun=true</script>"
            val sandbox = InteractiveSandboxView(controller.get(), source)
            controller.get().setContentView(sandbox); sandbox.start()
            val web = descendants(sandbox).filterIsInstance<WebView>().single()
            val model = requireNotNull(Shadows.shadowOf(web).lastLoadedUrl).removeSuffix("preflight.html") + "index.html"
            val client = web.webViewClient!!
            InteractiveSandboxView::class.java.getDeclaredMethod("onPreflightResult", WebView::class.java, String::class.java)
                .apply { isAccessible = true }.invoke(sandbox, web, "false")
            assertTrue(descendants(sandbox).none { it is WebView })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
            assertEquals(403, client.shouldInterceptRequest(web, request(model, true))!!.statusCode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun permissionsWindowsAndRendererCrashKeepTheSandboxFailClosed() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val source = "<canvas>original</canvas>"
            val sandbox = InteractiveSandboxView(activity, source)
            activity.setContentView(sandbox); sandbox.start()
            val web = descendants(sandbox).filterIsInstance<WebView>().single()
            var denied = false
            web.webChromeClient!!.onPermissionRequest(object : PermissionRequest() {
                override fun getOrigin() = Uri.parse(InteractiveSandboxDocument.ORIGIN)
                override fun getResources() = arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                override fun grant(resources: Array<out String>) { fail("Permission must never be granted") }
                override fun deny() { denied = true }
            })
            assertTrue(denied)
            assertFalse(web.webChromeClient!!.onCreateWindow(web, false, true, null))
            var locationDenied = false
            web.webChromeClient!!.onGeolocationPermissionsShowPrompt(InteractiveSandboxDocument.ORIGIN) { _, allow, remember ->
                assertFalse(allow); assertFalse(remember); locationDenied = true
            }
            assertTrue(locationDenied)
            assertTrue(web.webViewClient!!.onRenderProcessGone(web, object : RenderProcessGoneDetail() {
                override fun didCrash() = true
                override fun rendererPriorityAtExit() = 0
            }))
            assertTrue(descendants(sandbox).none { it is WebView })
            assertTrue(descendants(sandbox).filterIsInstance<TextView>().any { it.text.toString() == source })
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

    @Test fun longFallbackSourceCanScrollVerticallyAndHorizontallyWithoutLosingText() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val source = (1..200).joinToString("\n") { "line $it " + "long_column_".repeat(40) }
            val sandbox = InteractiveSandboxView(activity, source)
            activity.setContentView(sandbox)
            sandbox.close()
            sandbox.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            sandbox.layout(0, 0, 360, 500)
            val vertical = descendants(sandbox).filterIsInstance<ScrollView>().single()
            val horizontal = descendants(vertical).filterIsInstance<HorizontalScrollView>().single()
            val original = descendants(vertical).filterIsInstance<TextView>().single()
            assertEquals(source, original.text.toString())
            assertTrue(original.isTextSelectable)
            assertTrue("Source must have a vertical scroll range", vertical.getChildAt(0).height > vertical.height)
            vertical.scrollTo(0, vertical.getChildAt(0).height)
            horizontal.scrollTo(original.width, 0)
            assertTrue(vertical.scrollY > 0)
            assertTrue(horizontal.scrollX > 0)
            assertEquals(source, original.text.toString())
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
