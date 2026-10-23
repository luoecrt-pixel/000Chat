package com.example.openaichat

import java.io.IOException

/** A local file may already be published; preserve its intent and stop the tool chain. */
class FileWriteOutcomeUnknownException(message: String, cause: Throwable? = null) : IOException(message, cause)
