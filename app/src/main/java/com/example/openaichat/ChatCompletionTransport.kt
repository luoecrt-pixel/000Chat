package com.example.openaichat

import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject

/** Identity is part of the contract, including transports targeting a test HTTP server. */
interface ChatCompletionTransport {
    val provider: String

    fun complete(
        apiKey: String,
        model: String,
        messages: JSONArray,
        sessionId: String? = null,
        tools: JSONArray?,
        maxTokens: Int?,
        reasoningEffort: String?,
        verbosity: String?,
        parallelToolCalls: Boolean,
        executeTool: (name: String, arguments: JSONObject) -> String,
        onCallCreated: (Call) -> Unit = {},
        shouldContinue: () -> Boolean = { true },
        onRoundObserved: (roundIndex: Int, generationId: String?, usage: JSONObject?) -> Unit = { _, _, _ -> },
        onTextDelta: (String) -> Unit = {},
        onPayloadObserved: (roundIndex: Int, jsonBytes: Int) -> Unit = { _, _ -> },
        cacheMode: String = "1h",
        onProtocolWarning: (String) -> Unit = {},
        onProtocolCheckpoint: (JSONObject) -> Unit = {},
        pdfEngine: String? = null,
        responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO,
        onFileIoObserved: (roundIndex: Int, stats: JSONObject) -> Unit = { _, _ -> },
        onReasoningDelta: (String) -> Unit = {}
    ): OpenRouterCompletionResult

    /** Called while the request still owns its SQLite slot, before Stop revokes it. */
    fun flushCheckpoint(sessionId: String) {}
    fun close()
}
