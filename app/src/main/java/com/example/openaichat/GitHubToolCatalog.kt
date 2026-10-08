package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

object GitHubToolCatalog {
    private fun schema(
        name: String,
        description: String,
        properties: JSONObject,
        required: JSONArray = JSONArray()
    ): JSONObject = JSONObject()
        .put("type", "function")
        .put("function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters",
                    JSONObject()
                        .put("type", "object")
                        .put("properties", properties)
                        .put("required", required)
                        .put("additionalProperties", false)
                )
        )

    private fun stringProp(description: String? = null): JSONObject =
        JSONObject().put("type", "string").apply { description?.let { put("description", it) } }

    private fun intProp(description: String? = null): JSONObject =
        JSONObject().put("type", "integer").apply { description?.let { put("description", it) } }

    private fun enumProp(values: Array<String>): JSONObject =
        JSONObject().put("type", "string").put("enum", JSONArray(values))

    private fun required(vararg names: String): JSONArray =
        JSONArray().apply { names.forEach(::put) }

    private fun commonRepoProperties(): JSONObject =
        JSONObject()
            .put("owner", stringProp("GitHub repository owner/login"))
            .put("repo", stringProp("GitHub repository name"))

    fun tools(): JSONArray {
        val out = JSONArray()
        out.put(schema("get_pull_request", "Get a pull request.", commonRepoProperties()
            .put("pull_number", intProp("Pull request number")), required("owner", "repo", "pull_number")))
        out.put(schema("list_pull_requests", "List pull requests.", commonRepoProperties()
            .put("state", enumProp(arrayOf("open", "closed", "all"))), required("owner", "repo", "state")))
        out.put(schema("list_issues", "List issues.", commonRepoProperties()
            .put("state", enumProp(arrayOf("open", "closed", "all"))), required("owner", "repo", "state")))
        out.put(schema("get_workflow_runs", "List GitHub Actions workflow runs for a branch.",
            commonRepoProperties().put("branch", stringProp("Allowed branch/ref")), required("owner", "repo", "branch")))
        out.put(schema("get_workflow_run_jobs", "List jobs for a workflow run.",
            commonRepoProperties().put("run_id", intProp("Workflow run id")), required("owner", "repo", "run_id")))
        out.put(schema("get_file", "Read one repository file as UTF-8 text.",
            commonRepoProperties().put("path", stringProp("Repository-relative file path"))
                .put("ref", stringProp("Explicit allowed branch/ref required")), required("owner", "repo", "path", "ref")))
        out.put(schema("list_directory", "List files/directories at a repository path.",
            commonRepoProperties().put("path", stringProp("Repository-relative directory path; empty for root"))
                .put("ref", stringProp("Explicit allowed branch/ref required")), required("owner", "repo", "path", "ref")))
        out.put(schema("create_issue_comment", "Create a comment on an issue or pull request. Requires confirmation.",
            commonRepoProperties().put("issue_number", intProp("Issue or pull request number"))
                .put("body", stringProp("Comment body")), required("owner", "repo", "issue_number", "body")))
        out.put(schema("submit_pull_request_review", "Submit a pull request review. Requires confirmation.",
            commonRepoProperties().put("pull_number", intProp("Pull request number"))
                .put("event", enumProp(arrayOf("COMMENT", "APPROVE", "REQUEST_CHANGES")))
                .put("body", stringProp("Review body; optional")), required("owner", "repo", "pull_number", "event")))
        out.put(schema("merge_pull_request", "Merge an open pull request. Requires confirmation.",
            commonRepoProperties().put("pull_number", intProp("Pull request number"))
                .put("merge_method", enumProp(arrayOf("merge", "squash", "rebase"))),
            required("owner", "repo", "pull_number", "merge_method")))
        out.put(schema("create_or_update_file", "Create or replace one repository file. Requires confirmation.",
            commonRepoProperties().put("path", stringProp("Repository-relative file path"))
                .put("message", stringProp("Commit message"))
                .put("content", stringProp("Complete UTF-8 file contents as ordinary text"))
                .put("content_base64", stringProp("Legacy compatibility: complete UTF-8 file contents encoded as base64"))
                .put("branch", stringProp("Allowed target branch"))
                .put("sha", stringProp("Current blob sha when replacing; optional because the app can resolve it")),
            required("owner", "repo", "path", "message", "content", "branch")))
        out.put(schema("trigger_workflow_dispatch", "Dispatch a GitHub Actions workflow. Requires confirmation.",
            commonRepoProperties().put("workflow_id", stringProp("Workflow file name or workflow id"))
                .put("ref", stringProp("Allowed branch/ref"))
                .put("inputs", JSONObject().put("type", "object")),
            required("owner", "repo", "workflow_id", "ref")))
        return out
    }
}
