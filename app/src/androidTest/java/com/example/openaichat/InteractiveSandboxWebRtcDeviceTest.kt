package com.example.openaichat

import android.app.AlertDialog
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real native ICE transport, confined to an app-local UDP listener; no public STUN service or media capture. */
@RunWith(AndroidJUnit4::class)
class InteractiveSandboxWebRtcDeviceTest {
    private class StunListener(forbiddenPort: Int = -1) : AutoCloseable {
        private val socket = run {
            var candidate = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            while (candidate.localPort == forbiddenPort) {
                candidate.close()
                candidate = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
            }
            candidate.apply { soTimeout = 100 }
        }
        val port: Int get() = socket.localPort
        val packets = AtomicInteger()
        private val first = CountDownLatch(1)
        private val active = AtomicBoolean(true)
        private val failure = AtomicReference<Throwable?>()
        private val receiver = Thread({
            val bytes = ByteArray(2048)
            while (active.get()) {
                try {
                    val packet = DatagramPacket(bytes, bytes.size)
                    socket.receive(packet)
                    // RFC 5389 Binding Request, including the magic cookie. A JS flag is not packet evidence.
                    if (packet.length >= 20 && bytes[0].toInt() == 0 && bytes[1].toInt() == 1 &&
                        (bytes[4].toInt() and 255) == 0x21 && (bytes[5].toInt() and 255) == 0x12 &&
                        (bytes[6].toInt() and 255) == 0xa4 && (bytes[7].toInt() and 255) == 0x42) {
                        packets.incrementAndGet(); first.countDown()
                    }
                } catch (_: SocketTimeoutException) {
                    // Check shutdown promptly without sending anything off device.
                } catch (error: SocketException) {
                    if (active.get()) failure.compareAndSet(null, error)
                    break
                } catch (error: Throwable) {
                    failure.compareAndSet(null, error); break
                }
            }
        }, "sandbox-loopback-stun").apply { isDaemon = true; start() }

        fun awaitPacket(milliseconds: Long): Boolean {
            val observed = first.await(milliseconds, TimeUnit.MILLISECONDS)
            failure.get()?.let { throw AssertionError("Local STUN listener failed", it) }
            return observed
        }

        override fun close() {
            active.set(false); socket.close(); receiver.join(1_000)
            check(!receiver.isAlive) { "Local STUN listener did not stop" }
        }
    }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun awaitJavascript(scenario: ActivityScenario<MainActivity>, web: WebView, expression: String,
        accepted: (String) -> Boolean): String {
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
            assertTrue("Real WebView did not reach expected probe state", complete.await(15, TimeUnit.SECONDS))
            return result.get()
        } finally { active.set(false) }
    }

    @Test fun documentStartBlocksNativeRtcAndFreshRealmsAfterAnUnprotectedControlActuallySendsStun() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val controlDialog = AtomicReference<AlertDialog>()
            val controlWeb = AtomicReference<WebView>()
            val sandboxDialog = AtomicReference<AlertDialog>()
            val sandboxWeb = AtomicReference<WebView>()
            val version = AtomicReference<String>()
            val controlDestroyed = AtomicBoolean(false)
            var controlPort = -1
            try {
                scenario.onActivity {
                    version.set("Android ${Build.VERSION.SDK_INT}; WebView ${WebView.getCurrentWebViewPackage()?.versionName}")
                    assertTrue("A native RTC isolation proof requires a supported document-start renderer (${version.get()})",
                        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
                }
                StunListener().use { control ->
                    controlPort = control.port
                    scenario.onActivity { activity ->
                        val web = WebView(activity).also(controlWeb::set)
                        web.settings.javaScriptEnabled = true
                        web.settings.blockNetworkLoads = false
                        web.settings.allowFileAccess = false; web.settings.allowContentAccess = false
                        val dialog = AlertDialog.Builder(activity).setView(web).create().also(controlDialog::set)
                        dialog.show()
                        // Synthetic HTTPS origin makes the context secure without loading any URL.
                        web.loadDataWithBaseURL("https://rtc-control.invalid/", """
                            <!doctype html><script>
                            window.__rtcControl={available:typeof RTCPeerConnection==='function',offerSet:false,error:null};
                            (async()=>{try{
                              window.__rtcControlPeer=new RTCPeerConnection({iceServers:[{urls:'stun:127.0.0.1:${control.port}'}]});
                              window.__rtcControlPeer.createDataChannel('local-positive-control');
                              await window.__rtcControlPeer.setLocalDescription(await window.__rtcControlPeer.createOffer());
                              window.__rtcControl.offerSet=true;
                            }catch(error){window.__rtcControl.error=String(error);}})();
                            </script>
                        """.trimIndent(), "text/html", "UTF-8", null)
                    }
                    val evidence = JSONObject(awaitJavascript(scenario, controlWeb.get(), "window.__rtcControl || null") {
                        it != "null" && (JSONObject(it).optBoolean("offerSet") || !JSONObject(it).isNull("error"))
                    })
                    assertTrue("The positive-control WebView must expose native RTC (${version.get()}): $evidence", evidence.getBoolean("available"))
                    assertTrue("Native RTC offer failed (${version.get()}): $evidence", evidence.getBoolean("offerSet"))
                    assertTrue("No real local STUN packet: network isolation would be inconclusive (${version.get()})", control.awaitPacket(12_000))
                    assertTrue(control.packets.get() > 0)
                    val closed = CountDownLatch(1)
                    scenario.onActivity {
                        controlWeb.get().evaluateJavascript("window.__rtcControlPeer.close();true") { closed.countDown() }
                    }
                    assertTrue(closed.await(5, TimeUnit.SECONDS))
                    scenario.onActivity { controlDialog.get().dismiss(); controlWeb.get().destroy(); controlDestroyed.set(true) }
                }

                StunListener(controlPort).use { isolated ->
                    val source = hostileSource(isolated.port)
                    scenario.onActivity { activity ->
                        val dialog = InteractiveSandboxDialog.create(activity, source) {}.also(sandboxDialog::set)
                        dialog.show()
                    }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity {
                        sandboxWeb.set(findWeb(sandboxDialog.get().window!!.decorView)
                            ?: throw AssertionError("Supported renderer refused the production sandbox (${version.get()})"))
                    }
                    val web = sandboxWeb.get()
                    awaitJavascript(scenario, web, "location.pathname.endsWith('/index.html') && document.readyState==='complete' && document.querySelector('iframe')!==null") { it == "true" }
                    val expression = """
                        (function(){
                          if(!window.__rtcProbeListener){
                            window.__rtcProbeListener=true;
                            addEventListener('message',event=>{
                              if(event.source===document.querySelector('iframe').contentWindow && event.data && event.data.kind==='rtc-probe-result')
                                window.__rtcProbeResult=event.data.evidence;
                            });
                          }
                          document.querySelector('iframe').contentWindow.postMessage('rtc-probe-report','*');
                          return window.__rtcProbeResult || null;
                        })()
                    """.trimIndent()
                    fun readEvidence() = JSONObject(awaitJavascript(scenario, web, expression) { it != "null" })
                    var evidence = readEvidence()
                    assertProtectedEvidence(evidence, version.get())
                    // Constructor reachability is a failure even when the platform happens to suppress UDP.
                    // Conversely, the listener independently observes native traffic outside WebResourceRequest.
                    assertFalse("Production sandbox emitted a native STUN Binding request (${version.get()})", isolated.awaitPacket(5_000))
                    evidence = readEvidence()
                    assertProtectedEvidence(evidence, version.get())
                    assertEquals(0, isolated.packets.get())
                    scenario.onActivity { sandboxDialog.get().dismiss() }
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { assertNull(web.parent) }
                }
            } finally {
                scenario.onActivity {
                    sandboxDialog.get()?.dismiss()
                    controlDialog.get()?.dismiss()
                    controlWeb.get()?.let { web ->
                        if (controlDestroyed.compareAndSet(false, true)) {
                            (web.parent as? ViewGroup)?.removeView(web)
                            web.stopLoading(); web.destroy()
                        }
                    }
                }
            }
        }
    }

    private fun assertProtectedEvidence(evidence: JSONObject, version: String) {
        assertTrue("Bootstrap missing at the first model instruction ($version): $evidence", evidence.getBoolean("firstMarker"))
        assertTrue("Bootstrap marker can be replaced ($version): $evidence", evidence.getBoolean("markerLocked"))
        assertTrue("Ordinary inline calculator stopped working ($version): $evidence", evidence.getBoolean("calculatorWorks"))
        assertEquals("A native RTC global or alias is reachable ($version): $evidence", 0, evidence.getJSONArray("violations").length())
        assertEquals("Fresh realm regained native RTC ($version): $evidence", 0, evidence.getJSONArray("nativeConstructors").length())
        val nested = evidence.getJSONArray("nestedExecution")
        for (index in 0 until nested.length()) {
            val child = nested.getJSONObject(index)
            assertTrue("Nested model script ran before the bootstrap ($version): $child", child.getBoolean("marker"))
            assertEquals("A nested realm recovered a native RTC constructor ($version): $child", 0, child.getJSONArray("nativeNames").length())
            assertTrue("A nested RTC property can be restored ($version): $child", child.getBoolean("locked"))
        }
        // A nested source which CSP refused to load is not evidence that a script ran there.
        // Synchronous realm access is independently inspected before navigation below.
        assertTrue("Synchronous fresh realms were not exercised ($version): $evidence", evidence.getJSONArray("realmChecks").length() >= 5)
    }

    private fun hostileSource(port: Int): String = """
        <button id="calculate" onclick="document.getElementById('answer').textContent=String(6*7)">Calculate</button><output id="answer"></output>
        <script>
        const firstMarker=globalThis.__integralSandboxRtcDisabled===true;
        const violations=[],nativeConstructors=[],realmChecks=[],nestedExecution=[],peers=[];
        const explicit=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCIceGatherer','RTCIceTransport','RTCDtlsTransport','RTCSctpTransport'];
        function tryNative(C,label){
          nativeConstructors.push(label);
          try{const pc=new C({iceServers:[{urls:'stun:127.0.0.1:$port'}]});peers.push(pc);pc.createDataChannel('local-adversarial-probe');
            pc.createOffer().then(offer=>pc.setLocalDescription(offer)).catch(()=>{});
          }catch(_){}
        }
        function inspect(w,label){
          try{
            const names=Array.from(new Set(explicit.concat(Object.getOwnPropertyNames(w).filter(n=>/^(?:(?:webkit|moz))?RTC/.test(n)))));
            for(const name of names){
              const value=w[name],descriptor=Object.getOwnPropertyDescriptor(w,name);
              if(value!==undefined || !descriptor || descriptor.configurable || descriptor.writable || descriptor.get || descriptor.set)
                violations.push(label+':'+name);
              if(typeof value==='function' && /PeerConnection/.test(name))tryNative(value,label+':'+name);
            }
            let prototype=Object.getPrototypeOf(w),depth=0;
            while(prototype && depth++<8){
              for(const name of explicit){const d=Object.getOwnPropertyDescriptor(prototype,name);
                if(d && typeof d.value==='function'){violations.push(label+':prototype:'+name);if(/PeerConnection/.test(name))tryNative(d.value,label+':prototype:'+name);}}
              prototype=Object.getPrototypeOf(prototype);
            }
            realmChecks.push(label+':inspected');
          }catch(_){realmChecks.push(label+':access-denied');}
        }
        inspect(window,'initial-model');
        for(const name of explicit){try{window[name]=function fakeRtc(){};delete window[name];Object.defineProperty(window,name,{value:function fakeRtc(){}});}catch(_){}}
        inspect(window,'after-restore-attempt');
        let markerLocked=false;
        try{Object.defineProperty(window,'__integralSandboxRtcDisabled',{value:false});}catch(_){}
        const markerDescriptor=Object.getOwnPropertyDescriptor(window,'__integralSandboxRtcDisabled');
        markerLocked=window.__integralSandboxRtcDisabled===true && markerDescriptor && !markerDescriptor.configurable && !markerDescriptor.writable;
        const childCode="(()=>{const marker=globalThis.__integralSandboxRtcDisabled===true;const names=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCIceGatherer','RTCIceTransport','RTCDtlsTransport','RTCSctpTransport'];const nativeNames=names.filter(n=>typeof globalThis[n]!=='undefined');const locked=names.every(n=>{const d=Object.getOwnPropertyDescriptor(globalThis,n);return d&&!d.configurable&&!d.writable&&!d.get&&!d.set;});parent.postMessage({kind:'rtc-nested-executed',marker,nativeNames,locked},'*');for(const n of nativeNames){if(!/PeerConnection/.test(n))continue;try{const pc=new globalThis[n]({iceServers:[{urls:'stun:127.0.0.1:$port'}]});pc.createDataChannel('nested-probe');pc.createOffer().then(o=>pc.setLocalDescription(o)).catch(()=>{});globalThis.__nestedPeer=pc;}catch(_){}}})();";
        const childHtml='<scr'+'ipt>'+childCode+'<'+ '/script>';
        function checkFrame(f,label){
          document.body.appendChild(f);inspect(f.contentWindow,label+':contentWindow');
          inspect(window.frames[window.frames.length-1],label+':window.frames');
          try{f.contentWindow.document.open();f.contentWindow.document.write(childHtml);f.contentWindow.document.close();realmChecks.push(label+':document-write-attempted');}
          catch(_){realmChecks.push(label+':document-write-denied');}
        }
        checkFrame(document.createElement('iframe'),'createElement');
        const box=document.createElement('div');box.innerHTML='<iframe></iframe>';checkFrame(box.firstElementChild,'innerHTML');
        const template=document.createElement('template');template.innerHTML='<iframe></iframe>';
        checkFrame(template.content.cloneNode(true).firstElementChild,'template');
        const srcdoc=document.createElement('iframe');srcdoc.srcdoc=childHtml;checkFrame(srcdoc,'srcdoc');
        const ns=document.createElementNS('http://www.w3.org/1999/xhtml','iframe');checkFrame(ns,'namespace');
        addEventListener('message',event=>{
          if(event.data && event.data.kind==='rtc-nested-executed')nestedExecution.push(event.data);
          if(event.data!=='rtc-probe-report')return;
          document.getElementById('calculate').click();
          parent.postMessage({kind:'rtc-probe-result',evidence:{firstMarker,markerLocked,violations,nativeConstructors,realmChecks,nestedExecution,
            calculatorWorks:document.getElementById('answer').textContent==='42'}},'*');
        });
        </script>
    """.trimIndent()
}
