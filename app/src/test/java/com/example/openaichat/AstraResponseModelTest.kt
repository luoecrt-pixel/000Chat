package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AstraResponseModelTest {
    @Test
    fun readsResponseModelFromApiResponse() {
        val model = AstraResponseModel.fromResponse(
            JSONObject()
                .put("id", "resp_1")
                .put("model", "gpt-6.1-sol")
                .put("output", JSONArray()),
            fallbackText = "hello",
            requestId = "req_1"
        )

        assertEquals("gpt-6.1-sol", model.model)
        assertEquals("gpt-6.1-sol", model.toChatJson().getString("response_model"))
    }

    @Test
    fun missingResponseModelIsPreservedAsUnknown() {
        val model = AstraResponseModel.fromResponse(
            JSONObject().put("id", "resp_1").put("output", JSONArray()),
            fallbackText = "hello"
        )

        assertNull(model.model)
        assertEquals(JSONObject.NULL, model.toChatJson().get("response_model"))
    }

    @Test
    fun restoresResponseModelFromChatHistory() {
        val model = AstraResponseModel.fromChatJson(
            JSONObject()
                .put("response_id", "resp_1")
                .put("request_id", "req_1")
                .put("response_model", "anthropic/claude-opus-5.5")
                .put("text", "hello")
        )

        assertEquals("anthropic/claude-opus-5.5", model.model)
    }
    @Test
    fun preservesEveryPaidImageIncludingFifthSixthAndLaterInResponseOrder() {
        val output = JSONArray()
        for (i in 0 until 7) {
            output.put(JSONObject().put("type", "image_generation_call")
                .put("id", "image_call_${i + 1}")
                .put("status", "completed")
                .put("result", "synthetic_image_payload_${i + 1}"))
        }
        val model = AstraResponseModel.fromResponse(
            JSONObject().put("id", "resp_seven_images").put("output", output),
            fallbackText = "", requestId = "paid_fixture")
        assertEquals(7, model.generatedImageResults.size)
        assertEquals((1..7).map { "synthetic_image_payload_$it" },
            model.generatedImageResults)
        assertEquals("resp_seven_images", model.id)
        assertEquals("completed", model.status)
    }

}
