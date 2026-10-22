package com.example.openaichat

/** Model scripts run only in an opaque iframe; the surrounding document contains app markup only. */
object InteractiveSandboxDocument {
    const val ORIGIN = "https://interactive-sandbox.invalid/"
    const val MAX_SOURCE_CHARACTERS = 200_000
    internal const val HOST_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; frame-src about:; connect-src 'none'; font-src 'none'; object-src 'none'; media-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"
    internal const val FRAME_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; frame-src 'none'; connect-src 'none'; font-src 'none'; object-src 'none'; media-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"

    // This source is exclusively app-owned. It runs in the same opaque srcdoc
    // and policy as model HTML, before that HTML is ever supplied to WebView.
    private val preflightSource = """
        <script>
        (()=>{
          let safe=false;
          try {
            const names=['RTCPeerConnection','webkitRTCPeerConnection','mozRTCPeerConnection','RTCIceGatherer','RTCIceTransport','RTCDtlsTransport','RTCSctpTransport'];
            function locked(w){
              if(!w)return false;
              try {
                const all=Array.from(new Set(names.concat(Object.getOwnPropertyNames(w).filter(n=>/^(?:webkit|moz)?RTC/.test(n)))));
                for(const n of all){const d=Object.getOwnPropertyDescriptor(w,n);
                  if(w[n]!==undefined || !d || d.writable || d.configurable || d.get || d.set)return false;}
                let p=Object.getPrototypeOf(w),depth=0;
                while(p && depth++<8){for(const n of names){const d=Object.getOwnPropertyDescriptor(p,n);if(d&&(d.get||d.set||d.value!==undefined))return false;}p=Object.getPrototypeOf(p);}
                return true;
              }catch(e){return e && e.name==='SecurityError';}
            }
            const marker=Object.getOwnPropertyDescriptor(globalThis,'__integralSandboxRtcDisabled');
            safe=location.origin==='null' && marker && marker.value===true && !marker.writable && !marker.configurable && locked(window);
            const frame=document.createElement('iframe');document.body.appendChild(frame);
            safe=!!safe && locked(frame.contentWindow) && locked(window.frames[window.frames.length-1]);
            frame.remove();
          }catch(_){safe=false;}
          parent.postMessage({kind:'integral-trusted-rtc-preflight',safe:!!safe},'*');
        })();
        </script>
    """.trimIndent()

    fun preflight(theme: ThemeColors): String = document(preflightSource, theme, """
        <script>
        window.__integralSandboxPreflight=null;
        addEventListener('message',event=>{
          const frame=document.querySelector('iframe');
          if(frame && event.source===frame.contentWindow && event.data && event.data.kind==='integral-trusted-rtc-preflight')
            window.__integralSandboxPreflight=event.data.safe===true;
        });
        </script>
    """.trimIndent())

    fun create(source: String, theme: ThemeColors): String {
        require(source.length <= MAX_SOURCE_CHARACTERS) { "Код слишком велик для песочницы; исходник сохранён полностью." }
        return document(source, theme, "")
    }

    private fun document(source: String, theme: ThemeColors, trustedHostScript: String): String {
        fun css(color: Int) = "#%06x".format(color and 0xFFFFFF)
        val background = css(theme.assistantBubbleBg)
        val foreground = css(theme.textPrimary)
        // srcdoc inherits its parent's policy. Both policies permit inline code and
        // local images; neither permits a script URL, network connection or eval.
        val frame = "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$FRAME_CSP\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{background:$background;color:$foreground;font:16px sans-serif}</style></head><body>$source</body></html>"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"$HOST_CSP\">$trustedHostScript</head><body style=\"margin:0;background:$background\"><iframe title=\"Интерактивная офлайн-песочница\" sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" allow=\"\" srcdoc=\"${ResponseRenderSafety.html(frame)}\" style=\"border:0;width:100%;height:100vh\"></iframe></body></html>"
    }
}
