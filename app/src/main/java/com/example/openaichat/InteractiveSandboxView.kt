package com.example.openaichat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.webkit.ScriptHandler
import java.io.ByteArrayInputStream
import java.util.UUID

/** Separate from static previews and trusted scientific engines. No app bridge or remote assets. */
class InteractiveSandboxView(
    context: Context,
    private val source: String,
    private val theme: ThemeColors = AppThemePalette.byId(context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("app_theme", "dark"))
) : LinearLayout(context), AutoCloseable {
    private val handler = Handler(Looper.getMainLooper())
    private val documentUrl = InteractiveSandboxDocument.ORIGIN + UUID.randomUUID() + "/index.html"
    private val preflightUrl = documentUrl.removeSuffix("index.html") + "preflight.html"
    private enum class Stage { PREFLIGHT, MODEL, CLOSED }
    @Volatile private var stage = Stage.CLOSED
    @Volatile private var modelDocument: String? = null
    @Volatile private var renderer: WebView? = null
    @Volatile private var closed = false
    private var documentStartScript: ScriptHandler? = null
    private var preflightPoll: Runnable? = null
    private var preflightEvaluationPending = false
    private val notice = TextView(context).apply {
        text = "JavaScript запускается только здесь, без сети и доступа к данным приложения."
        setTextColor(theme.textSecondary); textSize = 12f
    }
    private val original = TextView(context).apply {
        text = source; typeface = Typeface.MONOSPACE; setTextColor(theme.textPrimary)
        textSize = 14f; setTextIsSelectable(true); setHorizontallyScrolling(true)
    }
    private val originalScroll = ScrollView(context).apply {
        addView(HorizontalScrollView(context).apply { addView(original, LayoutParams(-2, -2)) },
            LayoutParams(-1, -2))
        visibility = GONE
    }
    private val loadTimeout = Runnable { if (!closed && renderer != null) fail("Песочница не загрузилась. Исходник сохранён полностью.") }

    init {
        orientation = VERTICAL; setBackgroundColor(theme.assistantBubbleBg)
        addView(notice)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(Button(context).apply {
                text = "Исходник"; AppThemeViews.button(this, theme)
                setOnClickListener { originalScroll.visibility = if (originalScroll.visibility == GONE) VISIBLE else GONE }
            })
            addView(Button(context).apply {
                text = "Остановить"; AppThemeViews.button(this, theme); setOnClickListener { close() }
            })
        })
        addView(originalScroll, LayoutParams(-1, (240 * resources.displayMetrics.density).toInt()))
    }

    /** Only the explicit dialog launch calls this; constructing/rebinding a chat card never does. */
    @SuppressLint("SetJavaScriptEnabled")
    fun start() {
        if (closed || renderer != null) return
        if (source.length > InteractiveSandboxDocument.MAX_SOURCE_CHARACTERS) {
            fail("Код слишком велик для песочницы; исходник сохранён полностью."); return
        }
        try {
            if (!InteractiveSandboxScriptGuard.supported()) {
                fail("WebView не поддерживает безопасный запуск JavaScript. Исходник сохранён полностью.")
                return
            }
            val preflightDocument = InteractiveSandboxDocument.preflight(theme)
            stage = Stage.PREFLIGHT
            val web = WebView(context)
            renderer = web
            // Register for every origin/frame while JavaScript is still disabled,
            // before either the host or opaque srcdoc can begin loading.
            documentStartScript = InteractiveSandboxScriptGuard.install(web)
            web.apply {
                setBackgroundColor(theme.assistantBubbleBg)
                importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false; settings.allowUniversalAccessFromFileURLs = false
                settings.domStorageEnabled = false; settings.databaseEnabled = false
                settings.blockNetworkLoads = true; settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.javaScriptCanOpenWindowsAutomatically = false; settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false); settings.mediaPlaybackRequiresUserGesture = true
                settings.saveFormData = false
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                setDownloadListener { _, _, _, _, _ -> }
                webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?) = false
                    override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, false, false)
                    }
                    override fun onShowFileChooser(view: WebView?, callback: ValueCallback<Array<Uri>>?, params: WebChromeClient.FileChooserParams?): Boolean {
                        callback?.onReceiveValue(null); return true
                    }
                    override fun onConsoleMessage(message: ConsoleMessage) = true
                    override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult): Boolean { result.cancel(); return true }
                    override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult): Boolean { result.cancel(); return true }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    @Deprecated("Legacy navigation is denied too")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        val expectedUrl = if (stage == Stage.PREFLIGHT) preflightUrl else documentUrl
                        val document = if (stage == Stage.PREFLIGHT) preflightDocument else modelDocument
                        if (!closed && stage != Stage.CLOSED && document != null && renderer === view && request.isForMainFrame &&
                            request.method == "GET" && request.url.toString() == expectedUrl) {
                            return WebResourceResponse("text/html", "UTF-8", 200, "OK", mapOf(
                                "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff",
                                "Content-Security-Policy" to InteractiveSandboxDocument.HOST_CSP,
                                "Permissions-Policy" to "camera=(), microphone=(), geolocation=(), clipboard-read=(), clipboard-write=(), payment=(), usb=(), accelerometer=(), gyroscope=(), magnetometer=(), fullscreen=()",
                                "Referrer-Policy" to "no-referrer"
                            ), ByteArrayInputStream(document.toByteArray(Charsets.UTF_8)))
                        }
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (!closed && renderer === view && stage == Stage.PREFLIGHT && url == preflightUrl) {
                            checkPreflight(view)
                        } else if (!closed && renderer === view && stage == Stage.MODEL && url == documentUrl) {
                            handler.removeCallbacks(loadTimeout); notice.text = "Офлайн-песочница · исходник сохранён"
                        }
                    }
                    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                        if (request.isForMainFrame && renderer === view) fail("Песочница недоступна. Исходник сохранён полностью.")
                    }
                    override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                        if (renderer === view) fail("Процесс песочницы остановлен. Исходник сохранён полностью.")
                        return true
                    }
                }
            }
            addView(web, LayoutParams(-1, (360 * resources.displayMetrics.density).toInt()))
            preflightPoll = Runnable { checkPreflight(web) }
            handler.postDelayed(loadTimeout, 12_000)
            web.loadUrl(preflightUrl)
        } catch (_: Exception) { fail("Не удалось открыть песочницу. Исходник сохранён полностью.") }
    }

    private fun checkPreflight(view: WebView) {
        if (closed || renderer !== view || stage != Stage.PREFLIGHT || preflightEvaluationPending) return
        preflightEvaluationPending = true
        view.evaluateJavascript("window.__integralSandboxPreflight === true ? true : (window.__integralSandboxPreflight === false ? false : null)") {
            onPreflightResult(view, it)
        }
    }

    private fun onPreflightResult(view: WebView, value: String?) {
        if (closed || renderer !== view || stage != Stage.PREFLIGHT) return
        preflightEvaluationPending = false
        when (value) {
            "true" -> {
                // No untrusted HTML is created or served before actual trusted
                // code in this engine's opaque frame confirms the guard.
                modelDocument = InteractiveSandboxDocument.create(source, theme)
                stage = Stage.MODEL
                view.loadUrl(documentUrl)
            }
            "false" -> fail("WebView не подтвердил изоляцию WebRTC. Исходник сохранён полностью.")
            else -> preflightPoll?.let { handler.postDelayed(it, 16L) }
        }
    }

    private fun destroyRenderer() {
        handler.removeCallbacks(loadTimeout)
        preflightPoll?.let(handler::removeCallbacks)
        preflightPoll = null
        preflightEvaluationPending = false
        stage = Stage.CLOSED
        modelDocument = null
        val script = documentStartScript
        documentStartScript = null
        val old = renderer
        renderer = null
        if (old == null) { script?.remove(); return }
        (old.parent as? ViewGroup)?.removeView(old)
        try {
            old.settings.javaScriptEnabled = false
            old.stopLoading()
        } finally {
            try { script?.remove() } finally { old.destroy() }
        }
    }
    private fun fail(message: String) {
        destroyRenderer(); notice.text = message; originalScroll.visibility = VISIBLE
    }
    override fun close() {
        if (closed) return
        closed = true; destroyRenderer(); originalScroll.visibility = VISIBLE
        notice.text = "Песочница остановлена; исходник сохранён полностью."
    }
    override fun onDetachedFromWindow() { close(); super.onDetachedFromWindow() }
}
