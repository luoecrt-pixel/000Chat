package com.example.openaichat

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.nio.charset.CharacterCodingException

/**
 * A parser limit stops interpretation, not retention of an already paid body.
 * Drain only this existing HTTP stream to its app-private archive, in fixed-size
 * buffers. The owning Call still enforces Stop and the original 5-hour deadline.
 * No JSON is allocated and no tool is executed while draining. Disk/socket
 * failure retains the received prefix and never triggers another generation.
 */
object OpenAiReceivedSseReader {
    fun read(
        reader: BufferedReader,
        archive: OpenAiRawSseArchive?,
        onEvent: (JSONObject) -> Boolean
    ): OpenAiSseReadResult = read(reader, archive, OpenAiSseStreamReader.Limits(), onEvent)

    internal fun read(
        reader: BufferedReader,
        archive: OpenAiRawSseArchive?,
        limits: OpenAiSseStreamReader.Limits,
        onEvent: (JSONObject) -> Boolean
    ): OpenAiSseReadResult {
        try {
            return OpenAiSseStreamReader.read(reader, limits, onEvent)
        } catch (limit: ResponsesSseLimitException) {
            try {
                archive?.receiveRemaining()
            } catch (failure: IOException) {
                limit.addSuppressed(failure)
            }
            throw limit
        } catch (failure: CharacterCodingException) {
            throw UnrecoverableResponsesStreamException("Malformed Responses SSE UTF-8", failure)
        }
    }
}
