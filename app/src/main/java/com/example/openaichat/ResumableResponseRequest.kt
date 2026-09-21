package com.example.openaichat

import okhttp3.HttpUrl

object ResumableResponseRequest {
    fun url(responseId: String, startingAfter: Long): HttpUrl {
        require(responseId.isNotBlank()) { "responseId is blank" }
        return HttpUrl.Builder()
            .scheme("https")
            .host("api.openai.com")
            .addPathSegment("v1")
            .addPathSegment("responses")
            .addPathSegment(responseId)
            .addQueryParameter("stream", "true")
            .apply {
                if (startingAfter >= 0L) {
                    addQueryParameter("starting_after", startingAfter.toString())
                }
            }
            .build()
    }
}
