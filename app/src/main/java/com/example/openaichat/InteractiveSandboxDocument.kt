package com.example.openaichat

/** Model scripts run only in an opaque iframe; the surrounding document contains app markup only. */
object InteractiveSandboxDocument {
    const val ORIGIN = "https://interactive-sandbox.invalid/"
    const val MAX_SOURCE_CHARACTERS = 200_000
    internal const val HOST_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; frame-src about:; connect-src 'none'; font-src 'none'; object-src 'none'; media-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"
    internal const val FRAME_CSP = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src data: blob:; frame-src 'none'; connect-src 'none'; font-src 'none'; object-src 'none'; media-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"

    fun create(source: String, theme: ThemeColors): String {
        require(source.length <= MAX_SOURCE_CHARACTERS) { "Код слишком велик для песочницы; исходник сохранён полностью." }
        fun css(color: Int) = "#%06x".format(color and 0xFFFFFF)
        val background = css(theme.assistantBubbleBg)
        val foreground = css(theme.textPrimary)
        // srcdoc inherits its parent's policy. Both policies permit inline code and
        // local images; neither permits a script URL, network connection or eval.
        val frame = "<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"$FRAME_CSP\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>body{background:$background;color:$foreground;font:16px sans-serif}</style></head><body>$source</body></html>"
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"$HOST_CSP\"></head><body style=\"margin:0;background:$background\"><iframe title=\"Интерактивная офлайн-песочница\" sandbox=\"allow-scripts\" referrerpolicy=\"no-referrer\" allow=\"\" srcdoc=\"${ResponseRenderSafety.html(frame)}\" style=\"border:0;width:100%;height:100vh\"></iframe></body></html>"
    }
}
