package com.example.openaichat

import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepSeekReasoningAdapterTest {
    @Test fun firstReasoningFragmentCreatesPanelAndLaterDeltasPreserveTheCardAndBody() {
        val context = RuntimeEnvironment.getApplication()
        var fullBinds = 0
        val adapter = ChatMessageAdapter(
            createCard = { LinearLayout(context) },
            bindCard = { card, item ->
                fullBinds++
                card.removeAllViews()
                val text = TextView(context).also(card::addView)
                item.reasoningSummary?.takeIf { it.isNotEmpty() }?.let { summary ->
                    card.addView(TextView(context).apply { tag = "reasoning-summary-body"; this.text = summary })
                }
                text
            },
            renderBody = { it.text }
        )
        val payloads = mutableListOf<Any?>()
        adapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) { payloads += payload }
        })
        adapter.submitItems(listOf(ChatBubbleItem.create("answer", "DeepSeek", "", false)))
        val holder = adapter.onCreateViewHolder(LinearLayout(context), 0)
        adapter.onBindViewHolder(holder, 0)
        assertNull(holder.card.findViewWithTag<TextView>("reasoning-summary-body"))

        assertTrue(adapter.updateReasoningSummary("answer", "first"))
        assertEquals(listOf<Any?>(null), payloads)
        adapter.onBindViewHolder(holder, 0)
        val reasoning = holder.card.findViewWithTag<TextView>("reasoning-summary-body")!!
        val body = holder.body
        assertEquals("first", reasoning.text.toString())
        assertEquals(2, fullBinds)

        payloads.clear()
        repeat(50) { adapter.updateReasoningSummary("answer", "delta $it") }
        assertEquals(setOf<Any?>("reasoning"), payloads.toSet())
        adapter.onBindViewHolder(holder, 0, mutableListOf("reasoning"))
        assertSame(reasoning, holder.card.findViewWithTag<TextView>("reasoning-summary-body"))
        assertSame(body, holder.body)
        assertEquals("delta 49", reasoning.text.toString())
        assertEquals(2, fullBinds)

        adapter.updateText("answer", "visible text")
        adapter.updateReasoningSummary("answer", "last reasoning")
        adapter.onBindViewHolder(holder, 0, mutableListOf("text", "reasoning"))
        assertEquals("visible text", holder.body!!.text.toString())
        assertEquals("last reasoning", reasoning.text.toString())
        assertEquals(2, fullBinds)
        assertFalse(adapter.updateReasoningSummary("other-request", "must not appear"))
        assertEquals("last reasoning", reasoning.text.toString())
    }
}
