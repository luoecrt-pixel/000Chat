package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolSafetyTest {
    private val sha = "1234567890abcdef1234567890abcdef12345678"
    private fun pr(base: String = "main", head: String = sha, state: String = "open", draft: Boolean = false) =
        JSONObject().put("state", state).put("draft", draft)
            .put("base", JSONObject().put("ref", base))
            .put("head", JSONObject().put("sha", head).put("ref", "feature/test"))

    private fun workflow(name: String, event: String, conclusion: String, id: Long = 10, head: String = sha,
                         status: String = "completed") =
        JSONObject().put("id", id).put("name", name).put("event", event).put("head_sha", head)
            .put("status", status).put("conclusion", conclusion)

    @Test fun fileReadMustNameAllowedBranchRatherThanFallThroughToDefault() {
        assertEquals("main", GitHubAllowedRefPolicy.validate(" main ", setOf("main")))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubAllowedRefPolicy.validate("", setOf("feature"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubAllowedRefPolicy.validate("main", setOf("feature"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            GitHubAllowedRefPolicy.validate("forbidden", setOf("main"))
        }
    }

    @Test fun targetMustBeOpenNonDraftAllowedBaseWithExactHeadSha() {
        assertEquals(sha, GitHubAllowedRefPolicy.approvedMergeHead(pr(), setOf("main")))
        for (unsafe in listOf(pr(base = "forbidden"), pr(head = "123"), pr(state = "closed"),
                              pr(draft = true))) {
            assertThrows(IllegalArgumentException::class.java) {
                GitHubAllowedRefPolicy.approvedMergeHead(unsafe, setOf("main"))
            }
        }
    }

    @Test fun sourceAndWorkflowPathsDetermineRequiredChecks() {
        assertEquals(setOf("Verify handoff"), GitHubMergeCheckPolicy.required(listOf("README.md")))
        assertEquals(setOf("Verify handoff"), GitHubMergeCheckPolicy.required(listOf("docs/guides/notes.md")))
        assertEquals(setOf("Verify handoff"), GitHubMergeCheckPolicy.required(listOf("NEXT_AGENT_HANDOFF.md")))
        assertEquals(setOf("Build Android APK", "Build Android Release"),
            GitHubMergeCheckPolicy.required(listOf("app/src/main/MainActivity.kt")))
        assertEquals(setOf("Build Android APK", "Build Android Release", "Verify handoff"),
            GitHubMergeCheckPolicy.required(listOf("tools/verifier.py", "NEXT_AGENT_HANDOFF.md")))
        assertThrows(IllegalArgumentException::class.java) { GitHubMergeCheckPolicy.required(emptyList()) }
    }

    @Test fun onlySuccessfulPushBuildsOnCurrentHeadAuthorizeCodeMerge() {
        val required = GitHubMergeCheckPolicy.required(listOf("app/src/main/A.kt"))
        val good = JSONArray()
            .put(workflow("Build Android APK", "push", "success", 1))
            .put(workflow("Build Android Release", "push", "success", 2))
        val summary = GitHubMergeCheckPolicy.check(sha, required, good)
        assertTrue(summary.contains("Build Android APK #1"))
        assertTrue(summary.contains("Build Android Release #2"))

        val missing = JSONArray().put(workflow("Build Android APK", "push", "success"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, missing)
        }
        val skipped = JSONArray().put(workflow("Build Android APK", "push", "skipped"))
            .put(workflow("Build Android Release", "push", "success"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, skipped)
        }
        val pending = JSONArray().put(workflow("Build Android APK", "push", "", status = "in_progress"))
            .put(workflow("Build Android Release", "push", "success"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, pending)
        }
        val wrongSha = JSONArray().put(workflow("Build Android APK", "push", "success", head = "0".repeat(40)))
            .put(workflow("Build Android Release", "push", "success"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, wrongSha)
        }
        val pullRequestEventOnly = JSONArray().put(workflow("Build Android APK", "pull_request", "success"))
            .put(workflow("Build Android Release", "pull_request", "success"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, pullRequestEventOnly)
        }
    }

    @Test fun newestFailedAttemptOverridesOlderSuccess() {
        val required = setOf("Build Android APK")
        val runs = JSONArray()
            .put(workflow("Build Android APK", "push", "success", 8))
            .put(workflow("Build Android APK", "push", "failure", 9))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required, runs)
        }
    }

    @Test fun docsOnlyRequireSuccessfulPrVerifierAndNotSkippedAndroidBuilds() {
        val required = GitHubMergeCheckPolicy.required(listOf("NEXT_AGENT_HANDOFF.md"))
        val good = JSONArray()
            .put(workflow("Verify handoff", "pull_request", "success", 7))
        assertTrue(GitHubMergeCheckPolicy.check(sha, required, good).contains("Verify handoff #7"))
        assertThrows(IllegalArgumentException::class.java) {
            GitHubMergeCheckPolicy.check(sha, required,
                JSONArray().put(workflow("Verify handoff", "pull_request", "skipped", 7)))
        }
    }

    @Test fun confirmationsShowWholeCommentReviewAndVerifiedMergeContext() {
        val body = "Полный проверяемый текст " + "abc".repeat(2000)
        val comment = GitHubConfirmationPreview.text("create_issue_comment", JSONObject()
            .put("owner", "me").put("repo", "repository").put("issue_number", 42).put("body", body))
        assertTrue(comment.contains(body))
        assertTrue(comment.contains("#42"))
        val review = GitHubConfirmationPreview.text("submit_pull_request_review", JSONObject()
            .put("owner", "me").put("repo", "repository").put("pull_number", 12)
            .put("event", "REQUEST_CHANGES").put("body", body))
        assertTrue(review.contains(body))
        assertTrue(review.contains("REQUEST_CHANGES"))
        val merge = GitHubConfirmationPreview.text("merge_pull_request", JSONObject()
            .put("owner", "me").put("repo", "repository").put("pull_number", 4)
            .put("merge_method", "squash").put("_verified_title", "Fix race")
            .put("_verified_base_ref", "main").put("_verified_head_ref", "fix/race")
            .put("_verified_head_sha", sha).put("_verified_workflows", "Build Android APK #1"))
        assertTrue(merge.contains("main"))
        assertTrue(merge.contains(sha))
        assertTrue(merge.contains("Build Android APK #1"))
        assertFalse(merge.contains("null"))
    }
}
