package com.example.openaichat

import org.json.JSONObject

/** Explicit branch selection is mandatory for repository reads and writes. */
object GitHubAllowedRefPolicy {
    fun validate(ref: String, allowedBranches: Set<String>): String {
        val value = ref.trim()
        require(value.isNotEmpty() && value in allowedBranches) {
            "The requested ref is missing or outside the configured allow-list"
        }
        return value
    }

    fun approvedMergeHead(pr: JSONObject, allowedBranches: Set<String>): String {
        val base = pr.optJSONObject("base")?.optString("ref").orEmpty()
        validate(base, allowedBranches)
        require(pr.optString("state") == "open" && !pr.optBoolean("draft", false)) {
            "Pull request is not open and ready"
        }
        val sha = pr.optJSONObject("head")?.optString("sha").orEmpty()
        require(sha.matches(Regex("[0-9a-fA-F]{40}"))) { "Expected a full immutable PR head SHA" }
        return sha
    }
}
