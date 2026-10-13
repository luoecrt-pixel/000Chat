package com.example.openaichat

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction

/**
 * SSE is UTF-8. The default InputStreamReader replaces malformed byte
 * sequences with U+FFFD and could silently change model/tool output.
 *
 * Report malformed input explicitly; the surrounding OpenAiRawSseArchive
 * retains the exact transport bytes already received for investigation and
 * future owned recovery. This is not a claim to recover unread server bytes.
 */
object OpenAiSseUtf8Reader {
    fun open(input: InputStream): BufferedReader =
        BufferedReader(
            InputStreamReader(
                input,
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
            )
        )
}
