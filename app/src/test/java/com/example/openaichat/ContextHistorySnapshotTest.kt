package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ContextHistorySnapshotTest {
    private class ObservedObject(raw: String = "{}") : JSONObject(raw) {
        val visits = AtomicInteger()
        val serializations = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun keys(): MutableIterator<String> { visits.incrementAndGet(); return super.keys() }
        override fun toString(): String { serializations += Thread.currentThread().name; return super.toString() }
    }

    @Test fun captureDoesNotWalkProtocolGraphAndDurableValidationRunsOnWorker() {
        val tail = JSONArray().apply { repeat(2000) { put(ObservedObject().put("content", "immutable payload")) } }
        val protocol = ObservedObject().put("version", 1).put("binding_sha256", "bound").put("tail", tail) as ObservedObject
        val message = ObservedObject().put("role", "assistant").put("text", "captured")
            .put("openrouter_protocol", protocol) as ObservedObject
        val card = ChatBubbleItem.create("answer", "Assistant", "captured", false, message = message)
        val durable = JSONObject(message.toString())
        val durableProtocol = ObservedObject(durable.getJSONObject("openrouter_protocol").toString())
        durable.put("openrouter_protocol", durableProtocol)
        message.visits.set(0); protocol.visits.set(0); protocol.serializations.clear()
        val captured = ContextHistorySnapshot.capture(listOf(message), listOf(card))
        assertEquals(0, message.visits.get())
        assertEquals(0, protocol.visits.get())
        assertTrue(protocol.serializations.isEmpty())
        for (i in 0 until tail.length()) assertEquals(0, (tail.getJSONObject(i) as ObservedObject).visits.get())
        assertNull(captured.messages.single().getJSONObject("openrouter_protocol").optJSONArray("tail"))
        message.put("text", "later UI change")
        protocol.put("binding_sha256", "later binding")
        assertEquals("captured", captured.messages.single().getString("text"))
        assertEquals("bound", captured.messages.single().getJSONObject("openrouter_protocol").getString("binding_sha256"))
        val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "context-history-worker") }
        try {
            val validated = worker.submit<List<JSONObject>?> { captured.validatedDurableHistory(listOf(durable)) }.get(5, TimeUnit.SECONDS)
            assertNotNull(validated)
            assertTrue(durableProtocol.serializations.isNotEmpty())
            assertTrue(durableProtocol.serializations.all { it == "context-history-worker" })
        } finally { worker.shutdownNow() }
    }

    @Test fun unpersistedOrChangedHistoryCannotBeReplacedWithStaleDatabaseRows() {
        val message = JSONObject().put("role", "user").put("text", "current unsaved text")
            .put("attachments", JSONArray().put(JSONObject().put("id", "f").put("name", "current.txt").put("kind", "text")))
        val missingSignature = ContextHistorySnapshot.capture(listOf(message), emptyList())
        assertNull(missingSignature.validatedDurableHistory(listOf(JSONObject().put("role", "user").put("text", "old text"))))
        assertEquals("current unsaved text", missingSignature.messages.single().getString("text"))
        message.getJSONArray("attachments").getJSONObject(0).put("name", "later.txt")
        assertEquals("current.txt", missingSignature.messages.single().getJSONArray("attachments").getJSONObject(0).getString("name"))
        val card = ChatBubbleItem.create("user", "User", "current unsaved text", true, message = message)
        val captured = ContextHistorySnapshot.capture(listOf(message), listOf(card))
        assertNull(captured.validatedDurableHistory(emptyList()))
        assertNull(captured.validatedDurableHistory(listOf(JSONObject(message.toString()).put("text", "old text"))))
    }
}
