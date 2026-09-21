package com.example.openaichat

import java.io.IOException

class ApiHttpException(
    val statusCode: Int,
    val retryAfterMs: Long?,
    val responseBody: String
) : IOException("HTTP $statusCode: $responseBody")
