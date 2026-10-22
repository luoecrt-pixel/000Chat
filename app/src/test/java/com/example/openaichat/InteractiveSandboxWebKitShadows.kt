package com.example.openaichat

import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Assert.*
import org.robolectric.Shadows
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter

/** Wiring only. Real document timing and native ICE are tested on Android. */
internal object InteractiveSandboxWebKitState {
    var supported = true
    var installationFailure = false
    var installationAttempts = 0
    var installed = 0
    var removed = 0
    var script: String? = null
    var origins: Set<String>? = null
    fun reset() {
        supported = true; installationFailure = false; installationAttempts = 0; installed = 0; removed = 0
        script = null; origins = null
    }
}

@Implements(value = WebViewFeature::class, isInAndroidSdk = false)
class ShadowInteractiveSandboxWebViewFeature {
    companion object {
        @JvmStatic @Implementation fun isFeatureSupported(feature: String): Boolean =
            feature == WebViewFeature.DOCUMENT_START_SCRIPT && InteractiveSandboxWebKitState.supported
        @JvmStatic @Resetter fun reset() = InteractiveSandboxWebKitState.reset()
    }
}

@Implements(value = WebViewCompat::class, isInAndroidSdk = false)
class ShadowInteractiveSandboxWebViewCompat {
    companion object {
        @JvmStatic @Implementation fun addDocumentStartJavaScript(web: WebView, script: String,
            allowedOriginRules: Set<String>): ScriptHandler {
            assertFalse("The guard must be installed before enabling JavaScript", web.settings.javaScriptEnabled)
            assertNull("The guard must be installed before loading a document", Shadows.shadowOf(web).lastLoadedUrl)
            InteractiveSandboxWebKitState.installationAttempts++
            if (InteractiveSandboxWebKitState.installationFailure) error("Simulated document-start installation failure")
            InteractiveSandboxWebKitState.installed++
            InteractiveSandboxWebKitState.script = script
            InteractiveSandboxWebKitState.origins = allowedOriginRules
            return object : ScriptHandler {
                override fun remove() { InteractiveSandboxWebKitState.removed++ }
            }
        }
    }
}
