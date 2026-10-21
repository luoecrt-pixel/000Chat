package com.example.openaichat

import android.app.Activity
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.widget.TextView
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowWebView
import java.time.Duration

/** Android layout and engine readiness are independent; JS itself is covered on device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ScientificMathTextLayoutTest.LayoutWebViewShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class ScientificMathTextLayoutTest {
    /** Robolectric's dummy WebViewProvider returns false from ViewDelegate.setFrame,
     * so ordinary WebView.layout never acquires bounds. Preserve Android's normal
     * bounds/layout-listener behavior while retaining ShadowWebView's JS callbacks. */
    @Implements(WebView::class)
    class LayoutWebViewShadow : ShadowWebView() {
        @RealObject private lateinit var actualView: WebView
        @Implementation protected fun setFrame(left: Int, top: Int, right: Int, bottom: Int): Boolean {
            val changed = actualView.left != left || actualView.top != top || actualView.right != right || actualView.bottom != bottom
            actualView.left = left; actualView.top = top; actualView.right = right; actualView.bottom = bottom
            return changed
        }
    }
    private fun layout(web: WebView, width: Int) {
        web.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY))
        web.layout(0, 0, width, 48)
        assertEquals(width, web.width)
    }
    private fun preview(): Pair<ScientificMathText, TextView> {
        val context = RuntimeEnvironment.getApplication()
        val original = TextView(context).apply { text = "x^2" }
        return ScientificMathText(context, original, autoPreview = true) {
            ScientificRendererDocument.math(listOf(MathDelimiterScanner.Formula(0, 3, "x^2", "x^2", true)))
        } to original
    }
    private fun renderer(preview: ScientificMathText) =
        (0 until preview.childCount).map { preview.getChildAt(it) }.filterIsInstance<WebView>().single()
    private fun ready(web: WebView) = web.webChromeClient!!.onReceivedTitle(web, "renderer:ready")
    private fun dimensions(web: WebView) =
        shadowOf(web).lastEvaluatedJavascriptCallback!!.onReceiveValue(JSONObject.quote("[100,400]"))

    @Test fun readyBeforeLayoutKeepsTheRendererThenMeasuresWhenWidthArrives() {
        val (preview, original) = preview()
        val web = renderer(preview)
        assertEquals(0, web.width)
        ready(web)
        // Simulate an immediate measurement too, if a regressed implementation asks
        // JS before Android layout. A valid engine result must not destroy the preview.
        shadowOf(web).lastEvaluatedJavascriptCallback?.onReceiveValue(JSONObject.quote("[100,400]"))
        assertSame(preview, web.parent)
        assertEquals(View.INVISIBLE, web.visibility)
        assertNull(shadowOf(web).lastEvaluatedJavascript)
        layout(web, 400)
        assertNotNull(shadowOf(web).lastEvaluatedJavascript)
        dimensions(web)
        assertEquals(View.VISIBLE, web.visibility)
        assertEquals(View.GONE, original.visibility)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertSame(preview, web.parent)
    }

    @Test fun widthLostDuringTheJsCallbackWaitsForANewLayoutInsteadOfFallingBack() {
        val (preview, original) = preview()
        val web = renderer(preview)
        layout(web, 400)
        ready(web)
        val firstCallback = shadowOf(web).lastEvaluatedJavascriptCallback!!
        layout(web, 0)
        firstCallback.onReceiveValue(JSONObject.quote("[100,400]"))
        assertSame(preview, web.parent)
        layout(web, 320)
        assertNotSame(firstCallback, shadowOf(web).lastEvaluatedJavascriptCallback)
        dimensions(web)
        assertEquals(View.VISIBLE, web.visibility)
        assertEquals(View.GONE, original.visibility)
    }

    @Test fun pendingLayoutStillTimesOutAndItsListenerCannotReviveTheRenderer() {
        val (preview, original) = preview()
        val web = renderer(preview)
        ready(web)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertNull(web.parent)
        assertEquals(View.VISIBLE, original.visibility)
        layout(web, 400)
        assertNull(shadowOf(web).lastEvaluatedJavascript)
    }

    @Test fun detachRemovesPendingLayoutAndIgnoresTheOldReadyCallback() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            val original = TextView(activity).apply { text = "x^2" }
            val preview = ScientificMathText(activity, original, autoPreview = true) {
                ScientificRendererDocument.math(listOf(MathDelimiterScanner.Formula(0, 3, "x^2", "x^2", true)))
            }
            activity.setContentView(preview)
            val web = renderer(preview)
            layout(web, 0)
            ready(web)
            activity.setContentView(View(activity))
            assertNull(web.parent)
            layout(web, 400)
            ready(web)
            assertNull(shadowOf(web).lastEvaluatedJavascript)
            assertEquals(View.VISIBLE, original.visibility)
        } finally { controller.pause().stop().destroy() }
    }
}
