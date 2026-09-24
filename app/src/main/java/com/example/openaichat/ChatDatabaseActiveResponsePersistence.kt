package com.example.openaichat

import org.json.JSONObject

class ChatDatabaseActiveResponsePersistence(
    private val database: ChatDatabase
) : ActiveResponsePersistence {
    override fun setActiveResponse(
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
    ) = database.setActiveResponse(
        chatId = chatId,
        responseId = responseId,
        requestId = requestId,
        text = text,
        status = status,
        startedAt = startedAt,
        effortPref = effortPref,
        effectiveEffort = effectiveEffort,
        autoSelected = autoSelected,
        promptChars = promptChars,
        attachments = attachments,
        backgroundEnabled = backgroundEnabled
    )

    override fun updateActiveResponse(
        chatId: String,
        responseId: String?,
        text: String?,
        status: String?,
        lastSequenceNumber: Long?,
        expectedRequestId: String?
    ) = database.updateActiveResponse(
        chatId = chatId,
        responseId = responseId,
        text = text,
        status = status,
        lastSequenceNumber = lastSequenceNumber,
        expectedRequestId = expectedRequestId
    )

    override fun getActiveResponse(chatId: String): JSONObject? =
        database.getActiveResponse(chatId)

    override fun listActiveResponses(): List<JSONObject> =
        database.listActiveResponses()

    override fun clearActiveResponse(chatId: String, expectedRequestId: String?) =
        database.clearActiveResponse(chatId, expectedRequestId)
}
