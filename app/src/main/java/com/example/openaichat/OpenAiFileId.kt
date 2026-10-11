package com.example.openaichat

// Preserve the opaque ID exactly. Official Files examples include "file-abc123":
// https://developers.openai.com/api/reference/go/resources/files/methods/list
// Retain file_ compatibility for IDs already stored by this application.
private const val OPEN_AI_FILE_ID_PATTERN = "file[_-][A-Za-z0-9_-]+"
private val completeOpenAiFileId = Regex(OPEN_AI_FILE_ID_PATTERN)
// Fail closed for filenames, paths and escaped/extended identifiers. A full opaque ID
// may be quoted or followed by normal message punctuation, but never be the prefix
// of a longer path, filename extension or URL-encoded token.
private val openAiFileIdsInMessage = Regex(
    """(?<![A-Za-z0-9_./\\%+-])$OPEN_AI_FILE_ID_PATTERN(?![A-Za-z0-9_./\\%+-])"""
)

internal fun isOpenAiFileId(value: String?): Boolean =
    value != null && completeOpenAiFileId.matches(value)

internal fun findOpenAiFileIds(message: String): Sequence<String> =
    openAiFileIdsInMessage.findAll(message).map { it.value }
