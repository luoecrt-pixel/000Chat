package com.example.openaichat

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** App-owned code installed by the renderer before any document's own script. */
internal object InteractiveSandboxScriptGuard {
    // Native document-start injection is essential: a late evaluateJavascript call
    // would let the first model script capture a constructor before it was removed.
    // The source is constant and is never assembled from model HTML or JavaScript.
    val bootstrap = """
        (() => {
          'use strict';
          const root = globalThis;
          const names = new Set([
            'RTCPeerConnection', 'webkitRTCPeerConnection', 'mozRTCPeerConnection',
            'RTCIceGatherer', 'RTCIceTransport', 'RTCDtlsTransport', 'RTCSctpTransport',
            'RTCDataChannel', 'RTCDataChannelEvent', 'RTCRtpSender', 'RTCRtpReceiver',
            'RTCRtpTransceiver'
          ]);
          for (const name of Object.getOwnPropertyNames(root)) {
            if (/^(?:webkit|moz)?RTC/.test(name)) names.add(name);
          }
          for (const name of names) {
            Object.defineProperty(root, name, {
              value: undefined, writable: false, configurable: false, enumerable: false
            });
          }
          Object.defineProperty(root, '__integralSandboxRtcDisabled', {
            value: true, writable: false, configurable: false, enumerable: false
          });
        })();
    """.trimIndent()

    fun supported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun install(web: WebView): ScriptHandler = WebViewCompat.addDocumentStartJavaScript(
        web, bootstrap, setOf("*")
    )
}
