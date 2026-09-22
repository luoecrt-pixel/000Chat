package com.example.openaichat

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
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
    private val itemById = mutableMapOf<String, ChatBubbleItem>()
    private val itemIndexById = mutableMapOf<String, Int>()
    private val displayPositionById = mutableMapOf<String, Int>()
    private val boundHolders = mutableMapOf<String, ViewHolder>()

    private val differ = AsyncListDiffer(this, object : DiffUtil.ItemCallback<ChatBubbleItem>() {
        override fun areItemsTheSame(oldItem: ChatBubbleItem, newItem: ChatBubbleItem): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: ChatBubbleItem, newItem: ChatBubbleItem): Boolean =
            oldItem.contentSignature == newItem.contentSignature &&
                oldItem.reasoningSummary == newItem.reasoningSummary
    })

    init {
        differ.addListListener { _, current ->
            displayPositionById.clear()
            current.forEachIndexed { index, item ->
                displayPositionById[item.id] = index
            }
        }
    }

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

    private fun itemForBoundPosition(position: Int): ChatBubbleItem {
        val structural = differ.currentList[position]
        return itemById[structural.id] ?: structural
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        bindFull(holder, itemForBoundPosition(position))
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        val item = itemForBoundPosition(position)
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

    override fun getItemCount(): Int = differ.currentList.size

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

    private fun submitModel() {
        differ.submitList(items.toList())
    }

    fun submitItems(newItems: List<ChatBubbleItem>) {
        if (items == newItems) return
        items.clear()
        items.addAll(newItems)
        submitModel()
    }

    fun addItem(item: ChatBubbleItem) {
        items.add(item)
        submitModel()
    }

    fun replaceItem(id: String, item: ChatBubbleItem): Boolean {
        val index = items.indexOfFirst { it.id == id }
        if (index < 0) return false
        items[index] = item.copy(id = id)
        submitModel()
        return true
    }

    fun updateText(id: String, text: String): Boolean {
        val index = itemIndexById[id] ?: return false
        refreshModelItem(index, items[index].copy(text = text))
        displayPositionById[id]?.let { notifyItemChanged(it, PAYLOAD_TEXT) }
        return true
    }

    fun updateReasoningSummary(id: String, summary: String): Boolean {
        val index = itemIndexById[id] ?: return false
        refreshModelItem(index, items[index].copy(reasoningSummary = summary))
        displayPositionById[id]?.let { notifyItemChanged(it) }
        return true
    }

    fun removeLast(count: Int) {
        val actual = count.coerceAtMost(items.size)
        if (actual <= 0) return
        val start = items.size - actual
        for (item in items.subList(start, items.size)) {
            boundHolders.remove(item.id)
        }
        repeat(actual) { items.removeAt(items.lastIndex) }
        submitModel()
    }

    fun removeFrom(index: Int) {
        if (index !in 0 until items.size) return
        for (item in items.subList(index, items.size)) {
            boundHolders.remove(item.id)
        }
        repeat(items.size - index) { items.removeAt(items.lastIndex) }
        submitModel()
    }

    fun clearItems() {
        if (items.isEmpty()) return
        items.clear()
        boundHolders.clear()
        submitModel()
    }

    fun bodyForId(id: String): TextView? = boundHolders[id]?.body

    fun cardForId(id: String): LinearLayout? = boundHolders[id]?.card

    fun indexOfId(id: String): Int = items.indexOfFirst { it.id == id }

    fun itemAt(index: Int): ChatBubbleItem? = items.getOrNull(index)

    fun snapshot(): List<ChatBubbleItem> = items.toList()
}
