package com.example.openaichat

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Executes untrusted inline code in a real opaque WebView iframe, without a remote API. */
@RunWith(AndroidJUnit4::class)
class InteractiveSandboxDeviceTest {
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    @Test fun inlineInteractionWorksButParentStorageAndNetworkAreUnavailable() {
        val source = """
            <button id="go" onclick="this.textContent='done'">Run</button>
            <script>
            addEventListener('message', async event => {
              if(event.data !== 'sandbox-check') return;
              document.getElementById('go').click();
              let parentDenied=false, storageDenied=false, networkDenied=false;
              try { void parent.document.body; } catch(_) { parentDenied=true; }
              try { localStorage.setItem('sandbox-probe','secret'); } catch(_) { storageDenied=true; }
              try { await fetch('https://tracker.invalid/sandbox-probe'); } catch(_) { networkDenied=true; }
              console.log('sandbox-result:'+JSON.stringify({
                interaction:document.getElementById('go').textContent==='done',parentDenied,storageDenied,networkDenied,
                bridgeAbsent:typeof Android==='undefined',opaque:location.origin==='null'
              }));
            });
            </script>
        """.trimIndent()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = AtomicReference<AlertDialog>()
            val web = AtomicReference<WebView>()
            val ready = CountDownLatch(1)
            val result = AtomicReference<String>()
            try {
                scenario.onActivity { activity ->
                    val created = InteractiveSandboxDialog.create(activity, source) {}
                    dialog.set(created); created.show()
                    val renderer = findWeb(created.window!!.decorView) ?: throw AssertionError("Missing sandbox")
                    web.set(renderer)
                    renderer.webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                            if (message.message().startsWith("sandbox-result:")) {
                                result.set(message.message().removePrefix("sandbox-result:")); ready.countDown()
                            }
                            return true
                        }
                    }
                    val client = renderer.webViewClient!!
                    renderer.webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest) = client.shouldOverrideUrlLoading(view, request)
                        override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest) = client.shouldInterceptRequest(view, request)
                        override fun onPageFinished(view: WebView, url: String?) {
                            client.onPageFinished(view, url)
                            view.evaluateJavascript("document.querySelector('iframe').contentWindow.postMessage('sandbox-check','*')", null)
                        }
                    }
                }
                assertTrue("Sandbox script did not complete", ready.await(15, TimeUnit.SECONDS))
                val evidence = JSONObject(result.get())
                listOf("interaction", "parentDenied", "storageDenied", "networkDenied", "bridgeAbsent", "opaque").forEach {
                    assertTrue("Missing sandbox protection/interaction: $it ($evidence)", evidence.getBoolean(it))
                }
            } finally { scenario.onActivity { dialog.get()?.dismiss() } }
        }
    }
}
