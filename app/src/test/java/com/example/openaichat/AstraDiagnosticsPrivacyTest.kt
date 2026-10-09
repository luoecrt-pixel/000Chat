package com.example.openaichat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment

class AstraDiagnosticsPrivacyTest {
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
}
