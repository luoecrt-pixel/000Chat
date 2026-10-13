package com.example.openaichat

import java.io.IOException

/** Re-reading the same stream cannot fix a local decoder/storage boundary. */
open class UnrecoverableResponsesStreamException(message: String, cause: Throwable? = null) :
    IOException(message, cause)

class ResponsesSseLimitException(message: String) : UnrecoverableResponsesStreamException(message)

class ResponsesResponseIdentityException : UnrecoverableResponsesStreamException(
    "Responses event does not belong to the original response"
)

class ResponsesArchiveStorageException(cause: Throwable) : UnrecoverableResponsesStreamException(
    "Не удалось сохранить исходные данные ответа. Полученная часть сохранена, где это было возможно.", cause
)
