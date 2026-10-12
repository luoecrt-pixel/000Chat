package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiProtocolMetadataCodecTest {
    @Test
    fun exactTypedOutputAndDigestRoundTripThroughMetadataCodec() {
        val signed = "opaque-encrypted-reasoning".repeat(10000)
        val digest = "abcdef0123456789".repeat(4)
        val item = JSONObject().put("type", "reasoning").put("id", "rsn")
            .put("encrypted_content", signed)
        val original = JSONObject()
            .put("openai_protocol_output", JSONArray().put(item))
            .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, digest)
        assertTrue(MessageMetadataCodec.allKeys.contains("openai_protocol_output"))
        assertTrue(MessageMetadataCodec.allKeys.contains(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
        val restored = JSONObject()
        for (key in listOf("openai_protocol_output",
            OpenAiInstructionChainPolicy.FINGERPRINT_FIELD)) {
            val serialized = MessageMetadataCodec.serialized(original, key)
            assertNotNull(serialized)
            MessageMetadataCodec.decodeInto(restored, key, serialized)
        }
        assertEquals(digest, restored.getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
        assertEquals(signed, restored.getJSONArray("openai_protocol_output")
            .getJSONObject(0).getString("encrypted_content"))
        assertEquals(original.getJSONArray("openai_protocol_output").toString(),
            restored.getJSONArray("openai_protocol_output").toString())
    }
}
