package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiInstructionChainPolicyTest {
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
        val signature = OpenAiInstructionChainPolicy.fingerprint(instructions())
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
        val signature = OpenAiInstructionChainPolicy.fingerprint(current)
        val previous = completed("resp_1", signature)
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            OpenAiInstructionChainPolicy.fingerprint(instructions(system = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            OpenAiInstructionChainPolicy.fingerprint(instructions(developer = "changed"))))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous),
            OpenAiInstructionChainPolicy.fingerprint(instructions(presentation = null))))
        assertEquals(signature, OpenAiInstructionChainPolicy.fingerprint(
            instructions(system = " system ", developer = " developer ", presentation = " format ")))
        assertFalse(OpenAiInstructionChainPolicy.canReuse("resp_1", listOf(previous), ""))
    }

    @Test fun legacyIncompleteFailedAndForeignTurnsForceReplay() {
        val signature = OpenAiInstructionChainPolicy.fingerprint(instructions())
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
        val signature = OpenAiInstructionChainPolicy.fingerprint(instructions)
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
}
