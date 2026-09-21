package com.example.openaichat

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

data class ChatBubbleItem(
    val id: String,
    val label: String,
    val text: String,
    val user: Boolean,
    val annotations: JSONArray? = null,
    val message: JSONObject? = null,
    val chatIndex: Int? = null,
    val reasoningSummary: String? = null,
    val contentSignature: String
) {
    companion object {
        fun create(
            id: String,
            label: String,
            text: String,
            user: Boolean,
            annotations: JSONArray? = null,
            message: JSONObject? = null,
            chatIndex: Int? = null,
            reasoningSummary: String? = null
        ): ChatBubbleItem {
            val signature = if (message != null) {
                ChatMessageFingerprint.of(message)
            } else {
                listOf(
                    label,
                    text,
                    user.toString(),
                    annotations?.toString().orEmpty(),
                    reasoningSummary.orEmpty()
                ).joinToString("\u001f")
            }
            return ChatBubbleItem(
                id = id,
                label = label,
                text = text,
                user = user,
                annotations = annotations,
                message = message,
                chatIndex = chatIndex,
                reasoningSummary = reasoningSummary,
                contentSignature = signature
            )
        }
    }
}

class ChatMessageAdapter(
    private val createCard: (ViewGroup) -> LinearLayout,
    private val bindCard: (LinearLayout, ChatBubbleItem) -> TextView,
    private val renderBody: (ChatBubbleItem) -> CharSequence
) : RecyclerView.Adapter<ChatMessageAdapter.ViewHolder>() {

    companion object {
        private const val PAYLOAD_TEXT = "text"
    }

    class ViewHolder(val card: LinearLayout) : RecyclerView.ViewHolder(card) {
        var body: TextView? = null
        var itemId: String? = null
    }

    private val items = mutableListOf<ChatBubbleItem>()
    private val boundHolders = mutableMapOf<String, ViewHolder>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val card = createCard(parent)
        if (card.layoutParams == null) {
            card.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        return ViewHolder(card)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindFull(holder, items[position])
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = items[position]
        if (payloads.contains(PAYLOAD_TEXT)) {
            holder.body?.text = renderBody(item)
            bindHolder(item.id, holder)
            return
        }
        bindFull(holder, item)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.itemId?.let { id ->
            if (boundHolders[id] === holder) boundHolders.remove(id)
        }
        holder.itemId = null
        holder.body = null
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    private fun bindFull(holder: ViewHolder, item: ChatBubbleItem) {
        val body = bindCard(holder.card, item)
        holder.body = body
        body.text = renderBody(item)
        bindHolder(item.id, holder)
    }

    private fun bindHolder(id: String, holder: ViewHolder) {
        holder.itemId = id
        boundHolders.filterValues { it === holder }.keys.toList().forEach { staleId ->
            if (staleId != id) boundHolders.remove(staleId)
        }
        boundHolders[id] = holder
    }

    fun submitItems(newItems: List<ChatBubbleItem>) {
        val oldItems = items.toList()
        if (oldItems === newItems) return
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldItems.size
            override fun getNewListSize(): Int = newItems.size

            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == newItems[newItemPosition].id

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].contentSignature == newItems[newItemPosition].contentSignature &&
                    oldItems[oldItemPosition].reasoningSummary == newItems[newItemPosition].reasoningSummary
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    fun addItem(item: ChatBubbleItem) {
        val index = items.size
        items.add(item)
        notifyItemInserted(index)
    }

    fun replaceItem(id: String, item: ChatBubbleItem): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = item.copy(id = id)
        notifyItemChanged(index)
        return true
    }

    fun updateText(id: String, text: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].copy(text = text)
        notifyItemChanged(index, PAYLOAD_TEXT)
        return true
    }

    fun updateReasoningSummary(id: String, summary: String): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = items[index].copy(reasoningSummary = summary)
        notifyItemChanged(index)
        return true
    }

    fun removeLast(count: Int) {
        val actual = count.coerceAtMost(items.size)
        if (actual <= 0) return
        val start = items.size - actual
        for (item in items.subList(start, items.size)) {
            if (boundHolders[item.id]?.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                boundHolders.remove(item.id)
            }
        }
        repeat(actual) { items.removeAt(items.lastIndex) }
        notifyItemRangeRemoved(start, actual)
    }

    fun removeFrom(index: Int) {
        if (index !in 0 until items.size) return
        for (item in items.subList(index, items.size)) {
            boundHolders.remove(item.id)
        }
        val count = items.size - index
        repeat(count) { items.removeAt(items.lastIndex) }
        notifyItemRangeRemoved(index, count)
    }

    fun clearItems() {
        if (items.isEmpty()) return
        items.clear()
        boundHolders.clear()
        notifyDataSetChanged()
    }

    fun bodyForId(id: String): TextView? = boundHolders[id]?.body

    fun cardForId(id: String): LinearLayout? = boundHolders[id]?.card

    fun indexOfId(id: String): Int = items.indexOfFirst { it.id == id }

    fun itemAt(index: Int): ChatBubbleItem? = items.getOrNull(index)

    fun snapshot(): List<ChatBubbleItem> = items.toList()
}
