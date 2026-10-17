package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiUsageLedgerTest {
    private fun usage(input: Long, output: Long = 0L) = JSONObject().put("input_tokens", input).put("output_tokens", output)
    private fun raw(id: String, input: Long) = JSONObject().put("id", id).put("status", "completed").put("usage", usage(input))
    private fun tokens(ledger: JSONObject) = OpenAiUsageLedger.total(ledger)!!.getLong("input_tokens")

    @Test fun aggregateResponseNeverBecomesTheRawUsageOfItsLastRound() {
        val parent = OpenAiUsageLedger.observe(null, "resp_parent", usage(100), "completed")
        val checkpoint = JSONObject().put(OpenAiUsageLedger.FIELD, parent)
        val child = raw("resp_child", 20)
        OpenAiUsageLedger.attach(child, OpenAiUsageLedger.resolve(checkpoint, child))
        assertEquals(120L, child.getJSONObject("usage").getLong("input_tokens"))
        repeat(3) {
            val ledger = OpenAiUsageLedger.resolve(checkpoint, child)
            assertEquals(120L, tokens(ledger))
            OpenAiUsageLedger.attach(child, ledger)
        }
        assertEquals(20L, child.getJSONObject(OpenAiUsageLedger.FIELD).getJSONObject("responses")
            .getJSONObject("resp_child").getJSONObject("usage").getLong("input_tokens"))
    }

    @Test fun repeatedChildSnapshotsAndMergesAreIdempotent() {
        val parent = OpenAiUsageLedger.observe(null, "resp_parent", usage(100), "completed")
        val first = OpenAiUsageLedger.observe(parent, "resp_child", usage(20), "cancelled")
        val repeated = OpenAiUsageLedger.observe(first, "resp_child", usage(20), "cancelled")
        assertEquals(120L, tokens(repeated))
        assertEquals(120L, tokens(OpenAiUsageLedger.merge(first, repeated)))
    }

    @Test fun oldCheckpointRetainsUnattributedAggregateAcrossStopAndLateChild() {
        val checkpoint = JSONObject().put("phase", "waiting").put("parent_id", "resp_parent")
            .put("response_id", "resp_child").put("usage", usage(100))
        val migrated = OpenAiUsageLedger.fromCheckpoint(checkpoint)
        assertEquals(100L, tokens(OpenAiUsageLedger.observe(migrated, "resp_parent", usage(100), "completed")))
        val child = OpenAiUsageLedger.observe(migrated, "resp_child", usage(20), "cancelled")
        assertEquals(120L, tokens(child))
        assertEquals(120L, tokens(OpenAiUsageLedger.observe(child, "resp_child", usage(20), "cancelled")))
        assertEquals(120L, tokens(OpenAiUsageLedger.merge(migrated, child)))
    }

    @Test fun oldFinishedFinalResponseTotalIsAuthoritativeWithoutInventedRoundEntries() {
        val checkpoint = JSONObject().put("phase", "finished").put("parent_id", "resp_parent")
            .put("usage", usage(100)).put("final_response", raw("resp_final", 150))
        assertEquals(150L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_final", 50))))
        assertEquals(150L, tokens(OpenAiUsageLedger.fromCheckpoint(checkpoint)))
    }

    @Test fun terminalUsageSurvivesStalePartialAndSupportsAuthoritativeCorrection() {
        val partial = OpenAiUsageLedger.observe(null, "resp", usage(100, 5), "in_progress")
        val terminal = OpenAiUsageLedger.observe(partial, "resp", usage(90, 8), "completed")
        assertEquals(90L, tokens(terminal))
        assertEquals(90L, tokens(OpenAiUsageLedger.observe(terminal, "resp", usage(120, 12), "in_progress")))
        assertEquals(90L, tokens(OpenAiUsageLedger.merge(terminal, partial)))
        val correction = OpenAiUsageLedger.observe(terminal, "resp", usage(95), "completed")
        assertEquals(95L, tokens(correction))
    }

    @Test fun legacyAggregateIgnoresAnEarlierParentWhoseIdWasNotRetained() {
        val checkpoint = JSONObject().put("phase", "waiting").put("parent_id", "resp_second")
            .put("response_id", "resp_third").put("usage", usage(130))
        assertEquals(130L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_first", 100))))
        assertEquals(150L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_third", 20))))
        checkpoint.put("phase", "prepared")
        assertEquals(130L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_first", 100))))
        checkpoint.put("phase", "posting")
        assertEquals(130L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_first", 100))))
    }

    @Test fun legacyFinishedAggregateIgnoresEveryRawSnapshotIncludingAnUnknownOldParent() {
        val checkpoint = JSONObject().put("phase", "finished").put("parent_id", "resp_second")
            .put("usage", usage(130)).put("final_response", raw("resp_final", 150))
        assertEquals(150L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_first", 100))))
        assertEquals(150L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_second", 30))))
        assertEquals(150L, tokens(OpenAiUsageLedger.resolve(checkpoint, raw("resp_final", 20))))
    }
}
