package com.example.openaichat

import android.app.AlertDialog
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Real WebView probes leave both production security clients installed. No provider API is used. */
@RunWith(AndroidJUnit4::class)
class InteractiveSandboxDeviceTest {
    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    /** Wait on WebView evaluation/frame callbacks rather than replacing its clients or sleeping. */
    private fun awaitJavascript(
        scenario: ActivityScenario<MainActivity>, web: WebView, expression: String,
        accepted: (String) -> Boolean
    ): String {
        val complete = CountDownLatch(1)
        val active = AtomicBoolean(true)
        val result = AtomicReference<String>()
        scenario.onActivity {
            fun query() {
                if (!active.get()) return
                web.evaluateJavascript(expression) { value ->
                    if (!active.get()) return@evaluateJavascript
                    if (accepted(value)) { result.set(value); complete.countDown() }
                    else web.postOnAnimation { query() }
                }
            }
            query()
        }
        try {
            assertTrue("WebView did not reach the expected state", complete.await(15, TimeUnit.SECONDS))
            return result.get()
        } finally { active.set(false) }
    }

    @Test fun inlineChartInteractionWorksButParentStorageAndNetworkAreUnavailable() {
        val source = """
            <button id="go" onclick="document.getElementById('scale').value=25;draw();this.textContent='done'">Run</button>
            <input id="scale" type="range" min="1" max="30" value="5" oninput="draw()">
            <canvas id="plot" width="40" height="40"></canvas>
            <script>
            function draw(){
              const c=document.getElementById('plot').getContext('2d');
              c.clearRect(0,0,40,40);c.fillStyle='#123456';
              c.fillRect(0,0,Number(document.getElementById('scale').value),10);
            }
            addEventListener('message', async event => {
              if(event.data !== 'sandbox-check') return;
              document.getElementById('go').click();
              let parentDenied=false, storageDenied=false, networkDenied=false;
              try { void parent.document.body; } catch(_) { parentDenied=true; }
              try { localStorage.setItem('sandbox-probe','secret'); } catch(_) { storageDenied=true; }
              try { await fetch('https://tracker.invalid/sandbox-probe'); } catch(_) { networkDenied=true; }
              try { top.location='https://tracker.invalid/navigation-probe'; } catch(_) {}
              const pixel=document.getElementById('plot').getContext('2d').getImageData(20,5,1,1).data;
              parent.postMessage({kind:'sandbox-result',result:{
                interaction:document.getElementById('go').textContent==='done',
                chartDrawn:pixel[0]===18&&pixel[1]===52&&pixel[2]===86&&pixel[3]===255,
                parentDenied,storageDenied,networkDenied,
                bridgeAbsent:typeof Android==='undefined',opaque:location.origin==='null'
              }},'*');
            });
            </script>
        """.trimIndent()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val dialog = AtomicReference<AlertDialog>()
            val web = AtomicReference<WebView>()
            try {
                scenario.onActivity { activity ->
                    val created = InteractiveSandboxDialog.create(activity, source) {}
                    dialog.set(created); created.show()
                }
                // Dialog.OnShowListener is posted to the main queue. Drain that event
                // before inspecting its WebView, which intentionally does not exist earlier.
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity {
                    web.set(findWeb(dialog.get().window!!.decorView) ?: throw AssertionError("Missing sandbox after onShow"))
                }
                val renderer = web.get()
                awaitJavascript(scenario, renderer,
                    "location.pathname.endsWith('/index.html') && document.readyState==='complete' && document.querySelector('iframe')!==null") { it == "true" }
                awaitJavascript(scenario, renderer, """
                    window.__sandboxTestEvidence=null;
                    addEventListener('message',event=>{
                      if(event.source===document.querySelector('iframe').contentWindow && event.data && event.data.kind==='sandbox-result')
                        window.__sandboxTestEvidence=event.data.result;
                    });
                    document.querySelector('iframe').contentWindow.postMessage('sandbox-check','*');true
                """.trimIndent()) { it == "true" }
                val evidence = JSONObject(awaitJavascript(scenario, renderer, "window.__sandboxTestEvidence || null") { it != "null" })
                listOf("interaction", "chartDrawn", "parentDenied", "storageDenied", "networkDenied", "bridgeAbsent", "opaque").forEach {
                    assertTrue("Missing sandbox protection/interaction: $it ($evidence)", evidence.getBoolean(it))
                }
                scenario.onActivity {
                    assertTrue("Sandbox must not navigate its host", renderer.url!!.startsWith(InteractiveSandboxDocument.ORIGIN))
                    assertTrue(renderer.settings.blockNetworkLoads)
                    dialog.get().dismiss()
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { assertNull("Closing the dialog must detach its renderer", renderer.parent) }
            } finally { scenario.onActivity { dialog.get()?.dismiss() } }
        }
    }
}
