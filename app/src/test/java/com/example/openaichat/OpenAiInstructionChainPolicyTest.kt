package com.example.openaichat

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiInstructionChainPolicyTest {
    private fun fingerprint(instructions: GenerationInstructions, key: String = "sk-chain-fixture",
                            model: String = "gpt-6-astra") =
        OpenAiInstructionChainPolicy.fingerprint(instructions, key, model)

    private fun instructions(system: String? = "system", developer: String? = "developer",
                             presentation: String? = "format") =
        GenerationInstructions(system, developer, presentation)

    private fun completed(id: String, signature: String) = JSONObject()
        .put("role", "assistant")
        .put("provider", "openai")
        .put("status", "completed")
        .put("response_id", id)
        .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, signature)

    @Test fun reuseOnlyForMatchingCompletedImmediateAssistant() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        assertTrue(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_2", listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse(null, listOf(previous), signature))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), signature, regenerating = true))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1",
            listOf(previous, JSONObject().put("role", "user")), signature))
    }

    @Test fun changingEitherInstructionRoleOrPresentationBreaksChain() {
        val current = instructions()
        val signature = fingerprint(current)
        val previous = completed("resp_1", signature)
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(system = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(developer = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(presentation = null))))
        assertEquals(signature, fingerprint(
            instructions(system = " system ", developer = " developer ", presentation = " format ")))
        // Same words in different privileged roles must not reuse the chain.
        assertFalse(signature == fingerprint(
            instructions(system = "developer", developer = "system")))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), ""))
    }

    @Test fun credentialModelAndLegacyInstructionOnlyHashCannotInheritChain() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        assertTrue(OpenAiInstructionChainPolicy.matchesCredential(signature, "sk-chain-fixture"))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(signature, "sk-other-account"))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(signature, ""))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(null, "sk-chain-fixture"))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(), key = "sk-other-account")))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            fingerprint(instructions(), model = "gpt-6-sol")))
        assertEquals(signature, fingerprint(instructions(), model = " gpt-6-astra "))
        assertFalse(signature == fingerprint(instructions(), model = "GPT-6-ASTRA"))
        assertFalse(signature.contains("sk-chain-fixture"))
        // Even identical old instruction-only hashes never acquire a binding.
        val legacy = "f".repeat(64)
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1",
            listOf(completed("resp_1", legacy)), legacy))
        assertFalse(OpenAiInstructionChainPolicy.matchesCredential(legacy, "sk-chain-fixture"))
        assertEquals("", fingerprint(instructions(), key = ""))
    }

    @Test fun legacyIncompleteFailedAndForeignTurnsForceReplay() {
        val signature = fingerprint(instructions())
        val previous = completed("resp_1", signature)
        val mutations = listOf(
            JSONObject(previous.toString()).apply { remove(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD) },
            JSONObject(previous.toString()).put("status", "incomplete"),
            JSONObject(previous.toString()).put("status", "failed"),
            JSONObject(previous.toString()).put("status", "cancelled"),
            JSONObject(previous.toString()).put("provider", "openrouter"),
            JSONObject(previous.toString()).put("response_id", "resp_other")
        )
        mutations.forEach { last ->
            assertFalse(last.toString(), OpenAiInstructionChainPolicy.canReuse("resp_1",
                listOf(last), signature))
        }
    }

    @Test fun matchingChainDoesNotRepeatRoleMessagesAndFallbackPreservesTypedInput() {
        val instructions = instructions()
        val signature = fingerprint(instructions)
        val previous = completed("resp_1", signature)
        val nextTurn = JSONObject().put("role", "user").put("content", JSONArray()
            .put(JSONObject().put("type", "input_text").put("text", "Next"))
            .put(JSONObject().put("type", "input_file").put("file_id", "file_example")))
        val chained = JSONObject().put("previous_response_id", "resp_1")
            .put("input", JSONArray().put(nextTurn))
        if (!OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), signature)) {
            instructions.applyToOpenAiRequest(chained)
        }
        assertEquals(1, chained.getJSONArray("input").length())
        assertEquals("file_example", chained.getJSONArray("input").getJSONObject(0)
            .getJSONArray("content").getJSONObject(1).getString("file_id"))

        val history = JSONArray()
            .put(JSONObject().put("role", "user").put("content", JSONArray()
                .put(JSONObject().put("type", "input_image")
                    .put("image_url", "data:image/png;base64,AAAA"))))
            .put(JSONObject().put("role", "assistant").put("content", "Earlier answer"))
            .put(nextTurn)
        val fallback = JSONObject().put("input", history)
        instructions.applyToOpenAiRequest(fallback)
        assertFalse(fallback.has("previous_response_id"))
        assertEquals(history.length() + 2, fallback.getJSONArray("input").length())
        assertEquals("input_image", fallback.getJSONArray("input").getJSONObject(2)
            .getJSONArray("content").getJSONObject(0).getString("type"))
        assertEquals("file_example", fallback.getJSONArray("input").getJSONObject(4)
            .getJSONArray("content").getJSONObject(1).getString("file_id"))
    }
    @Test fun strictHttpFixtureChecksFirstChainedAndChangedInstructionsTurns() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200))
            server.enqueue(MockResponse().setResponseCode(200))
            server.start()
            val client = OkHttpClient()
            fun send(body: JSONObject) {
                client.newCall(Request.Builder().url(server.url("/v1/responses"))
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()).execute().use { response -> assertEquals(200, response.code) }
            }

            val settings = instructions()
            val fingerprint = fingerprint(settings)
            val first = JSONObject().put("input", JSONArray().put(
                JSONObject().put("role", "user").put("content", "first")))
            settings.applyToOpenAiRequest(first)
            send(first)

            val completed = completed("resp_first", fingerprint)
            val second = JSONObject().put("previous_response_id", "resp_first")
                .put("input", JSONArray().put(
                    JSONObject().put("role", "user").put("content", "second")))
            if (!OpenAiInstructionChainPolicy.canReuse("resp_first", listOf(completed), fingerprint)) {
                settings.applyToOpenAiRequest(second)
            }
            send(second)

            val changed = instructions(developer = "new developer")
            val changedFingerprint = fingerprint(changed)
            val third = JSONObject().put("input", JSONArray()
                .put(JSONObject().put("role", "user").put("content", JSONArray()
                    .put(JSONObject().put("type", "input_image")
                        .put("image_url", "data:image/png;base64,AAAA"))))
                .put(JSONObject().put("role", "assistant").put("content", "earlier"))
                .put(JSONObject().put("role", "user").put("content", "third")))
            assertFalse(OpenAiInstructionChainPolicy.canReuse(
                "resp_first", listOf(completed), changedFingerprint))
            changed.applyToOpenAiRequest(third)
            send(third)

            val firstWire = JSONObject(server.takeRequest().body.readUtf8())
            val secondWire = JSONObject(server.takeRequest().body.readUtf8())
            val thirdWire = JSONObject(server.takeRequest().body.readUtf8())
            listOf(firstWire, secondWire, thirdWire).forEach { wire ->
                assertFalse(wire.has("instructions"))
                assertTrue(wire.optJSONArray("input") != null)
            }
            assertEquals(3, firstWire.getJSONArray("input").length())
            assertEquals("system", firstWire.getJSONArray("input").getJSONObject(0).getString("role"))
            assertEquals("resp_first", secondWire.getString("previous_response_id"))
            assertEquals(1, secondWire.getJSONArray("input").length())
            assertFalse(thirdWire.has("previous_response_id"))
            assertEquals("new developer", thirdWire.getJSONArray("input")
                .getJSONObject(1).getString("content"))
            assertEquals("input_image", thirdWire.getJSONArray("input")
                .getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
            assertEquals(5, thirdWire.getJSONArray("input").length())
        }
    }

}
