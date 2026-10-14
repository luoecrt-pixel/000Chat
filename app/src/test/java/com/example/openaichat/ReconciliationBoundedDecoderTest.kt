package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReconciliationBoundedDecoderTest {
    @Test fun cancelAndMetadataPreserveCompleteOwnedUnicodeSnapshots() {
        withServer { server ->
            server.enqueue(MockResponse().setBody(
                """{"id":"resp_owned","status":"cancelled","usage":{"input_tokens":7,"output_tokens":0},"text":"привет 😀"}"""
            ))
            server.enqueue(MockResponse().setBody(
                """{"data":{"id":"gen-owned","total_cost":0.125,"provider_name":"привет 😀"}}"""
            ))
            val snapshots = mutableListOf<JSONObject>()
            val outcome = OpenAiCancelReconciler(OkHttpClient(), server.url("/v1/"))
                .reconcile("resp_owned", "synthetic-key") { snapshots += it }
            assertEquals(200, outcome.cancelStatus)
            assertEquals(0, outcome.retrievals)
            assertEquals("привет 😀", snapshots.single().getString("text"))
            assertTrue(outcome.hasUsage)
            assertNull(outcome.decoderDiagnostic)
            val metadata = mutableListOf<JSONObject>()
            val lookups = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"))
                .reconcile(rounds(), "synthetic-key") { _, body -> metadata += body }
            assertEquals(1, lookups)
            assertEquals("привет 😀", metadata.single().getString("provider_name"))
            assertEquals(2, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            assertEquals("GET", server.takeRequest().method)
        }
    }

    @Test fun cancelOversizedSuccessfulBodyCannotAttributePartialUsageOrIssueAnotherRequest() {
        withServer { server ->
            server.enqueue(MockResponse().setBody(JSONObject().put("id", "resp_owned").put("status", "cancelled")
                .put("usage", JSONObject().put("input_tokens", 999).put("output_tokens", 9))
                .put("extra", "x".repeat(200)).toString()))
            var snapshots = 0
            val failure = assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiCancelReconciler(OkHttpClient(), server.url("/v1/"), bodyLimits =
                    BoundedResponseDecoder.BodyLimits(maxBytes = 128, maxChars = 512))
                    .reconcile("resp_owned", "synthetic-key") { snapshots++ }
            }
            assertTrue(failure.message.orEmpty().contains("byte limit"))
            assertEquals(0, snapshots)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cancelOversizedAuthErrorPreservesStatusAndBoundDiagnosticWithoutRetrieve() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("x".repeat(1024 * 1024 + 1)))
            val outcome = OpenAiCancelReconciler(OkHttpClient(), server.url("/v1/"))
                .reconcile("resp_owned", "synthetic-key") { error("Auth body must not attribute usage") }
            assertEquals(401, outcome.cancelStatus)
            assertEquals(0, outcome.retrievals)
            assertFalse(outcome.hasUsage)
            assertTrue(outcome.decoderDiagnostic.orEmpty().contains("HTTP 401"))
            assertTrue(outcome.decoderDiagnostic.orEmpty().contains("byte limit"))
            assertFalse(outcome.decoderDiagnostic.orEmpty().contains("synthetic-key"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun metadataSuccessfulBodyLimitOrInvalidUtf8LeavesUnknownAccountingAndStopsLookup() {
        val responses = listOf(
            MockResponse().setBody(JSONObject().put("data", JSONObject().put("id", "gen-owned")
                .put("total_cost", 123).put("extra", "x".repeat(200))).toString()),
            MockResponse().setBody(Buffer().write(byteArrayOf(0xF0.toByte(), 0x9F.toByte())))
        )
        for ((index, response) in responses.withIndex()) withServer { server ->
            server.enqueue(response)
            val diagnostics = mutableListOf<Pair<Int, String>>()
            var snapshots = 0
            val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"),
                bodyLimits = BoundedResponseDecoder.BodyLimits(maxBytes = 128, maxChars = 512),
                onDiagnostic = { status, reason -> diagnostics += status to reason })
                .reconcile(rounds() + JSONObject().put("generation_id", "gen-second"), "synthetic-key") { _, _ -> snapshots++ }
            assertEquals(1, count)
            assertEquals(0, snapshots)
            assertEquals(200, diagnostics.single().first)
            assertTrue(diagnostics.single().second.contains(if (index == 0) "byte limit" else "MalformedInputException"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun metadataOversizedForbiddenErrorPreservesStatusDiagnosticAndStopsLookup() {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("x".repeat(1024 * 1024 + 1)))
            val diagnostics = mutableListOf<Pair<Int, String>>()
            val count = OpenRouterGenerationReconciler(OkHttpClient(), server.url("/v1/"),
                onDiagnostic = { status, reason -> diagnostics += status to reason })
                .reconcile(rounds() + JSONObject().put("generation_id", "gen-second"), "synthetic-key") { _, _ ->
                    error("Forbidden metadata cannot attribute cost")
                }
            assertEquals(1, count)
            assertEquals(403, diagnostics.single().first)
            assertTrue(diagnostics.single().second.contains("byte limit"))
            assertFalse(diagnostics.single().second.contains("synthetic-key"))
            assertEquals(1, server.requestCount)
        }
    }

    private fun rounds() = listOf(JSONObject().put("generation_id", "gen-owned").put("cost_kind", "unknown"))
    private fun withServer(block: (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
