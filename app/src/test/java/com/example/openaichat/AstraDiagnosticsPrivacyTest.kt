package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AstraDiagnosticsPrivacyTest {
    @Test
    fun displayCopyAndExportRedactionPreservesLineBreaksAndSafeIds() {
        val diagnostics = AstraDiagnostics(RuntimeEnvironment.getApplication())
        val raw = "header\nreq_safe_123 sk-proj-SyntheticDisplaySecret1234567\n\n" +
            "Bearer display-bearer https://example.org/?token=display-query\n" +
            "data:image/png;base64,SECRETBASE64\nfooter\n"
        val text = diagnostics.redactDisplayText(raw)
        assertTrue(text.startsWith("header\nreq_safe_123 [REDACTED]\n\n"))
        assertTrue(text.endsWith("\nfooter\n"))
        assertTrue(text.count { it == '\n' } == raw.count { it == '\n' })
        assertFalse(text.contains("SyntheticDisplaySecret"))
        assertFalse(text.contains("display-bearer"))
        assertFalse(text.contains("display-query"))
        assertFalse(text.contains("SECRETBASE64"))
    }

    @Test
    fun secretsAndBase64AreRedactedWithoutDiscardingDiagnosticFields() {
        val log = AstraDiagnostics(RuntimeEnvironment.getApplication())
        log.clear()
        try {
            log.log("request_failed", mapOf(
                "request_id" to "local_safe_123",
                "provider" to "openrouter",
                "error_type" to "invalid_request_error",
                "api_key" to "sk-test-very-private",
                "message" to "Bearer bearer-private https://example.org/?api_key=query-private" +
                    " data:image/jpeg;base64,SECRETPHOTOBYTES"
            ))
            val text = log.tail()
            assertTrue(text.contains("request_id=local_safe_123"))
            assertTrue(text.contains("provider=openrouter"))
            assertTrue(text.contains("error_type=invalid_request_error"))
            assertTrue(text.contains("[REDACTED]"))
            assertFalse(text.contains("sk-test-very-private"))
            assertFalse(text.contains("bearer-private"))
            assertFalse(text.contains("query-private"))
            assertFalse(text.contains("SECRETPHOTOBYTES"))
        } finally {
            log.clear()
        }
    }

    @Test
    fun redactsBareApiKeysInArbitraryErrorMessagesWhilePreservingSafeIds() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val diagnostics = AstraDiagnostics(context)
        diagnostics.clear()
        val syntheticOpenAiKey = "sk-proj-SyntheticSecretKey1234567890"
        val syntheticOpenRouterKey = "sk-or-v1-SyntheticRouterSecret1234567890"
        val syntheticPat = "github_pat_11ABCDEF0123456789_secretTokenValue"
        diagnostics.log(
            "provider_error",
            mapOf(
                "request_id" to "req_safe_12345",
                "response_id" to "resp_safe_67890",
                "local_id" to "local_safe_abcdef",
                "error_message" to "Upstream rejected $syntheticOpenAiKey and $syntheticOpenRouterKey and $syntheticPat for file-safe123"
            )
        )
        val output = diagnostics.tail(20)
        org.junit.Assert.assertFalse(output.contains(syntheticOpenAiKey))
        org.junit.Assert.assertFalse(output.contains(syntheticOpenRouterKey))
        org.junit.Assert.assertFalse(output.contains(syntheticPat))
        org.junit.Assert.assertTrue(output.contains("req_safe_12345"))
        org.junit.Assert.assertTrue(output.contains("resp_safe_67890"))
        org.junit.Assert.assertTrue(output.contains("local_safe_abcdef"))
        org.junit.Assert.assertTrue(output.contains("file-safe123"))
    }
}
