package com.example.openaichat

import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import java.security.MessageDigest
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
                val digest = MessageDigest.getInstance("SHA-256")
                listOf(
                    label,
                    text,
                    user.toString(),
                    annotations?.toString().orEmpty(),
                    reasoningSummary.orEmpty()
                ).forEach { field ->
                    val bytes = field.toByteArray(Charsets.UTF_8)
                    // Length-prefix each field so content containing a separator
                    // cannot hide a change in a neighboring rendered field.
                    repeat(4) { byteIndex ->
                        digest.update((bytes.size ushr (24 - byteIndex * 8)).toByte())
                    }
                    digest.update(bytes)
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
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
    private val renderBody: (ChatBubbleItem) -> CharSequence,
    private val renderExtras: (LinearLayout, ChatBubbleItem, TextView) -> Unit = { _, _, _ -> }
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
                oldItem.text == newItem.text &&
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
            holder.body?.let { renderExtras(holder.card, item, it) }
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
        renderExtras(holder.card, item, body)
        bindHolder(item.id, holder)
    }

    private fun bindHolder(id: String, holder: ViewHolder) {
        holder.itemId = id
        boundHolders.filterValues { it === holder }.keys.toList().forEach { staleId ->
            if (staleId != id) boundHolders.remove(staleId)
        }
        boundHolders[id] = holder
    }

    private fun rebuildModelIndexes() {
        itemById.clear()
        itemIndexById.clear()
        items.forEachIndexed { index, item ->
            itemById[item.id] = item
            itemIndexById[item.id] = index
        }
    }

    private fun refreshModelItem(index: Int, item: ChatBubbleItem) {
        items[index] = item
        itemById[item.id] = item
    }

    private val modelRevision = java.util.concurrent.atomic.AtomicLong(0L)
    @Volatile private var committedRevision = 0L

    private fun submitModel(onCommitted: (() -> Unit)? = null) {
        val revision = modelRevision.incrementAndGet()
        val snapshot = if (items.isEmpty()) null else items.toList()
        differ.submitList(snapshot) {
            committedRevision = maxOf(committedRevision, revision)
            onCommitted?.invoke()
        }
    }

    fun isListCommitPending(): Boolean =
        committedRevision != modelRevision.get() || differ.currentList.size != items.size

    fun submitItems(newItems: List<ChatBubbleItem>, onCommitted: (() -> Unit)? = null) {
        if (!isListCommitPending() && items == newItems && differ.currentList == newItems) {
            onCommitted?.invoke()
            return
        }
        items.clear()
        items.addAll(newItems)
        rebuildModelIndexes()
        submitModel(onCommitted)
    }

    fun addItem(item: ChatBubbleItem, onCommitted: (() -> Unit)? = null) {
        items.add(item)
        itemById[item.id] = item
        itemIndexById[item.id] = items.lastIndex
        submitModel(onCommitted)
    }

    fun replaceItem(id: String, item: ChatBubbleItem, onCommitted: (() -> Unit)? = null): Boolean {
        val index = itemIndexById[id] ?: return false
        val replacement = item.copy(id = id)
        refreshModelItem(index, replacement)
        submitModel(onCommitted)
        return true
    }

    fun updateText(id: String, text: String): Boolean {
        val index = itemIndexById[id] ?: return false
        val current = items[index]
        val updatedMessage = current.message?.let { JsonProtocol.objectCopy(it).put("text", text) }
        val updated = ChatBubbleItem.create(
            id = current.id,
            label = current.label,
            text = text,
            user = current.user,
            annotations = current.annotations,
            message = updatedMessage,
            chatIndex = current.chatIndex,
            reasoningSummary = current.reasoningSummary
        )
        refreshModelItem(index, updated)
        val displayPosition = displayPositionById[id]
        if (displayPosition != null) {
            notifyItemChanged(displayPosition, PAYLOAD_TEXT)
        } else {
            submitModel()
        }
        return true
    }

    fun updateReasoningSummary(id: String, summary: String): Boolean {
        val index = itemIndexById[id] ?: return false
        val current = items[index]
        val updated = if (current.message == null) {
            ChatBubbleItem.create(
                id = current.id,
                label = current.label,
                text = current.text,
                user = current.user,
                annotations = current.annotations,
                message = null,
                chatIndex = current.chatIndex,
                reasoningSummary = summary
            )
        } else {
            current.copy(reasoningSummary = summary)
        }
        refreshModelItem(index, updated)
        val displayPosition = displayPositionById[id]
        if (displayPosition != null) {
            notifyItemChanged(displayPosition)
        } else {
            submitModel()
        }
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
        rebuildModelIndexes()
        submitModel()
    }

    fun removeFrom(index: Int) {
        if (index !in 0 until items.size) return
        for (item in items.subList(index, items.size)) {
            boundHolders.remove(item.id)
        }
        repeat(items.size - index) { items.removeAt(items.lastIndex) }
        rebuildModelIndexes()
        submitModel()
    }

    fun clearItems(onCommitted: (() -> Unit)? = null) {
        if (!isListCommitPending() && items.isEmpty() && differ.currentList.isEmpty()) {
            onCommitted?.invoke()
            return
        }
        items.clear()
        itemById.clear()
        itemIndexById.clear()
        boundHolders.clear()
        submitModel(onCommitted)
    }

    fun bodyForId(id: String): TextView? = boundHolders[id]?.body

    fun cardForId(id: String): LinearLayout? = boundHolders[id]?.card

    fun indexOfId(id: String): Int = itemIndexById[id] ?: -1

    fun itemAt(index: Int): ChatBubbleItem? = items.getOrNull(index)

    fun snapshot(): List<ChatBubbleItem> = items.toList()

    fun committedSnapshot(): List<ChatBubbleItem> = differ.currentList
}
