package com.example.openaichat

import org.json.JSONObject

interface ActiveResponsePersistence {
    fun setActiveResponse(
        chatId: String,
        responseId: String?,
        requestId: String?,
        text: String,
        status: String,
        startedAt: Long,
        effortPref: String,
        effectiveEffort: String,
        autoSelected: Boolean,
        promptChars: Int,
        attachments: Int,
        backgroundEnabled: Boolean
    )

    fun updateActiveResponse(
        chatId: String,
        responseId: String? = null,
        text: String? = null,
        status: String? = null,
        lastSequenceNumber: Long? = null,
        expectedRequestId: String? = null
    )

    fun getActiveResponse(chatId: String): JSONObject?

    fun listActiveResponses(): List<JSONObject>

    fun clearActiveResponse(chatId: String, expectedRequestId: String? = null)
}
