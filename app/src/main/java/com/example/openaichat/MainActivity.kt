package com.example.openaichat

import android.app.*
import android.content.*
import android.content.ClipboardManager
import android.database.Cursor
import android.provider.OpenableColumns
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.text.method.LinkMovementMethod
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.text.*
import android.text.style.*
import android.util.Base64
import android.view.*
import android.widget.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.security.KeyStore
import java.util.concurrent.Executors
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class MainActivity : Activity() {
    private data class UploadCardViews(
        val root: LinearLayout,
        val preview: ImageView,
        val name: TextView,
        val details: TextView,
        val progress: ProgressBar,
        val actions: LinearLayout,
        val action: TextView
    )

    private data class LocalUploadedFile(
        val id: String,
        val name: String,
        val size: Long,
        val path: String,
        val mime: String
    )

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val onProgress: (Long, Long) -> Unit
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val totalBytes = delegate.contentLength()
            val countingSink = object : ForwardingSink(sink) {
                var writtenBytes = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    writtenBytes += byteCount
                    onProgress(writtenBytes, totalBytes)
                }
            }.buffer()
            delegate.writeTo(countingSink)
            countingSink.flush()
        }
    }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private val pollingClient = OkHttpClient.Builder().connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS).readTimeout(45, java.util.concurrent.TimeUnit.SECONDS).callTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    private val executor = Executors.newCachedThreadPool()

    private lateinit var prefs: SharedPreferences
    private lateinit var chatDb: ChatDatabase
    private lateinit var diagnostics: AstraDiagnostics
    private lateinit var scroll: RecyclerView
    private lateinit var messageAdapter: ChatMessageAdapter
    private lateinit var input: EditText
    private lateinit var send: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var toolLabel: TextView
    private lateinit var title: TextView
    private lateinit var attachRow: LinearLayout
    private var vsListArea: LinearLayout? = null
    private var vsStatusLine: TextView? = null
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var effortBadge: TextView

    private var currentCall: Call? = null
    private var requestGeneration = 0L
    private var currentResponseId: String? = null
    private var activeAssistantItemId: String? = null
    private var userStopRequestedGeneration: Long? = null
    private var activeRequestRegenerate = false
    private var activityVisible = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var draftSaveRunnable: Runnable? = null
    private var restoringComposer = false
    private val activeResumeChats = mutableSetOf<String>()
    private val toolStates = linkedMapOf<String, String>()
    private val pendingUploadUris = mutableListOf<Pair<String,String>>() // uri -> image|file|zip
    private var baselineEffort: String? = null
    private var pendingUploads = 0
    private var preparingZipAttachments = false
    private val uploadCalls = ConcurrentHashMap<String, Call>()
    private val uploadProgress = ConcurrentHashMap<String, Pair<Long, Int>>()
    private val activePendingUploadKeys = ConcurrentHashMap.newKeySet<String>()
    private val cancelledUploads = ConcurrentHashMap.newKeySet<String>()
    private val uploadCards = mutableMapOf<String, UploadCardViews>()
    private val pendingLocalUploadPaths = ConcurrentHashMap<String,String>()
    private val chat = mutableListOf<JSONObject>()
    private val attachedFileIds = mutableListOf<String>()
    private val attachedFileNames = mutableListOf<String>()
    private val attachedFileSizes = mutableListOf<Long>()
    private val attachedFilePaths = mutableListOf<String>()
    private val attachedFileMimes = mutableListOf<String>()
    private var pendingImagePath: String? = null
    private var pendingImageFileId: String? = null
    private var pendingImageName: String? = null
    private var pendingImageMime: String? = null
    private var pendingImageSize: Long = 0L
    private var lastUserText: String = ""

    private val secure by lazy { SecureStore(this) }
    private val pickAny = 5100
    private val pickImage = 5101
    private val pickVoice = 5102
    private val pickVectorStoreFile = 5103
    private val pickExportTxt = 5104
    private val pickSaveGenerated = 5105
    private var pendingExportBytes: ByteArray? = null
    private var pendingSaveSourcePath: String? = null
    private var pendingSaveFilename: String? = null
    private var pendingSaveMime: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("app", MODE_PRIVATE)
        chatDb = ChatDatabase(this)
        diagnostics = AstraDiagnostics(this)
        chatDb.migrateLegacyPrefs(prefs)
        ensureChatStore()
        buildUi()
        restoreCurrentChat()
        diagnostics.log("app_started", mapOf("version" to BuildConfig.VERSION_NAME, "model" to (prefs.getString("model", "gpt-6-astra") ?: "gpt-6-astra")))
    }

    override fun onStart() {
        super.onStart()
        activityVisible = true
        BackgroundResponseWorkScheduler.cancelAll(applicationContext)
        synchronized(activeResumeChats) { activeResumeChats.clear() }
        resumePendingUploads()
        resumeBackgroundResponses()
    }

    override fun onStop() {
        persistComposerStateNow()
        activityVisible = false
        val active = try { chatDb.listActiveResponses() } catch (_: Exception) { emptyList() }
        BackgroundResponseWorkScheduler.rescheduleAll(applicationContext, active)
        currentCall?.cancel()
        super.onStop()
    }

    override fun onDestroy() {
        persistComposerStateNow()
        requestGeneration++
        // Only close the local HTTP stream. Background Responses keep running on the
        // server when background=true; the next Activity instance will reconnect by polling.
        currentCall?.cancel()
        draftSaveRunnable?.let(mainHandler::removeCallbacks)
        executor.shutdownNow()
        try { chatDb.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun isRequestCurrent(chatId: String, generation: Long): Boolean =
        currentChatId() == chatId && requestGeneration == generation && !isFinishing

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(17,18,21))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(22,23,27))
        }

        val chatsBtn = button("☰") { showChats() }
        header.addView(chatsBtn, LinearLayout.LayoutParams(-2,-2).apply{rightMargin=dp(10)})

        title = TextView(this).apply {
            text = currentChatTitle()
            textSize = 19f
            setTextColor(Color.WHITE)
            setTypeface(null, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
        }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("＋") { newChat() }, LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(8)})
        header.addView(button("⚙") { showSettings() }, LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(8)})
        root.addView(header)
        root.addView(View(this).apply{ setBackgroundColor(Color.rgb(34,36,42)) }, LinearLayout.LayoutParams(-1,dp(1)))

        status = TextView(this).apply {
            text = "Готово"
            textSize = 12f
            setTextColor(Color.rgb(165,170,180))
            setPadding(dp(14), 0, dp(14), dp(4))
        }
        root.addView(status)

        messageAdapter = ChatMessageAdapter(
            createCard = { createChatBubbleCard() },
            bindCard = { card, item -> bindChatBubbleCard(card, item) },
            renderBody = { item -> renderChatBubbleBody(item) }
        )
        scroll = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
            itemAnimator = null
            clipToPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(12))
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        toolLabel = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(145,190,245))
            setPadding(dp(12), 0, dp(12), dp(4))
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setOnClickListener { showToolDetails() }
        }
        root.addView(toolLabel)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), dp(4))
        }
        row.addView(button("📎") { chooseAny() })
        row.addView(button("🖼") { chooseImage() })

        effortBadge = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(138,180,248))
            setPadding(dp(10),0,dp(6),0)
            text = "⚡ "+(prefs.getString("reasoning","high") ?: "high")
            setOnClickListener { showQuickEffortPicker() }
        }
        row.addView(effortBadge)

        val space = Space(this)
        row.addView(space, LinearLayout.LayoutParams(0,1,1f))

        val modelBadge = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            text = prefs.getString("model","gpt-6-astra")
        }
        row.addView(modelBadge)
        root.addView(row)

        attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        attachScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(dp(10), 0, dp(10), dp(6))
            visibility = View.GONE
            addView(attachRow, LinearLayout.LayoutParams(-2, -2))
        }
        root.addView(attachScroll)

        val composer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            setPadding(dp(10), dp(3), dp(10), dp(10))
        }
        input = EditText(this).apply {
            hint = "Сообщение…"
            setHintTextColor(Color.rgb(120,125,135))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(14),dp(11),dp(14),dp(11))
            background = rounded(Color.rgb(30,31,36),20)
            minLines = 1
            maxLines = 7
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        composer.addView(input, LinearLayout.LayoutParams(0,-2,1f))

        composer.addView(button("🎙") { chooseVoice() }, LinearLayout.LayoutParams(dp(46),-2).apply{leftMargin=dp(6)})

        send = Button(this).apply {
            text = "➤"
            minWidth=0; minimumWidth=0
            setTextColor(Color.rgb(18,20,24))
            background = rounded(Color.rgb(138,180,248),20)
            setOnClickListener { sendMessage(false) }
        }
        composer.addView(send, LinearLayout.LayoutParams(dp(52),-2).apply{leftMargin=dp(6)})

        stop = Button(this).apply {
            text = "■"
            minWidth=0; minimumWidth=0
            setTextColor(Color.WHITE)
            background = rounded(Color.rgb(120,60,60),20)
            visibility = View.GONE
            setOnClickListener { stopCurrentResponse() }
        }
        composer.addView(stop, LinearLayout.LayoutParams(dp(52),-2).apply{leftMargin=dp(6)})
        root.addView(composer)

        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets.consumeSystemWindowInsets()
        }

        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (!restoringComposer) persistComposerStateDebounced()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun showChats() {
        val all = loadAllChats()
        val names = all.map { it.optString("title","Новый чат") }.toMutableList()
        names.add("＋ Новый чат")
        AlertDialog.Builder(this)
            .setTitle("Чаты")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == names.lastIndex) newChat()
                else switchChat(all[which].optString("id"))
            }
            .setNeutralButton("Управление") { _, _ -> manageChats(all) }
            .show()
    }

    private fun manageChats(all: List<JSONObject>) {
        val names = all.map { it.optString("title","Новый чат") }
        if (names.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Управление чатами")
            .setItems(names.toTypedArray()) { _, which ->
                showChatActions(all[which])
            }.show()
    }

    private fun showChatActions(c: JSONObject) {
        AlertDialog.Builder(this)
            .setTitle(c.optString("title","Чат"))
            .setItems(arrayOf("Переименовать","Удалить")) { _, which ->
                when (which) {
                    0 -> renameChat(c.optString("id"))
                    1 -> deleteChat(c.optString("id"))
                }
            }.show()
    }

    private fun renameChat(id: String) {
        val chats = loadAllChats().toMutableList()
        val found = chats.firstOrNull { it.optString("id")==id } ?: return
        val edit = EditText(this).apply {
            setText(found.optString("title"))
            setTextColor(Color.WHITE)
        }
        AlertDialog.Builder(this)
            .setTitle("Название")
            .setView(edit)
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить") { _, _ ->
                found.put("title", edit.text.toString().trim().ifBlank { "Чат" })
                chatDb.renameChat(id, found.optString("title"))
                if (currentChatId()==id) title.text = found.optString("title")
            }.show()
    }

    private fun deleteChat(id: String) {
        val active=try{chatDb.getActiveResponse(id)}catch(_:Exception){null}
        val responseId=active?.optString("response_id").takeIf{!it.isNullOrBlank() && it!="null"}
        val requestId=active?.optString("request_id").takeIf{!it.isNullOrBlank() && it!="null"}
        val localPaths=linkedSetOf<String>().apply{
            try{addAll(chatDb.listLocalFilePaths(id))}catch(_:Exception){}
            addAll(listComposerLocalFilePaths(id))
        }
        if(currentChatId()==id){
            requestGeneration++
            currentCall?.cancel()
        }
        synchronized(activeResumeChats){activeResumeChats.remove(id)}
        BackgroundResponseWorkScheduler.cancel(applicationContext, id, requestId)
        chatDb.clearActiveResponse(id, expectedRequestId = requestId)
        if(responseId!=null)cancelRemoteResponse(id,responseId)
        localPaths.forEach(::deleteOwnedLocalFile)
        clearComposerState(id)
        chatDb.deleteChat(id)
        if (currentChatId()==id) {
            val newId = newChatId()
            prefs.edit().putString("current_chat",newId).apply()
            chat.clear()
            currentResponseId=null
            baselineEffort=null
            clearAttachedSelection()
            refreshAttachChips()
            messageAdapter.clearItems()
            toolLabel.text=""
            title.text="Новый чат"
            ensureChatEntry(newId,"Новый чат")
            status.text="Новый чат"
        }
    }

    // ---------- Settings ----------
    private fun showQuickEffortPicker(){
        val efforts=arrayOf("auto","low","medium","high","xhigh","max")
        val current=prefs.getString("reasoning","high") ?: "high"
        val idx=efforts.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Мышление")
            .setSingleChoiceItems(efforts,idx){d,which->
                prefs.edit().putString("reasoning",efforts[which]).apply()
                effortBadge.text="⚡ "+efforts[which]
                d.dismiss()
            }.show()
    }

    private fun showSettings() {
        val cardBg = Color.rgb(36,38,44)
        val accent = Color.rgb(138,180,248)
        val subtext = Color.rgb(150,155,165)
        val divider = Color.rgb(52,54,60)

        val box = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(14),dp(6),dp(14),dp(10)) }
        val advancedBox = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; visibility=View.GONE }
        var target: LinearLayout = box

        fun section(title:String){
            target.addView(TextView(this).apply{
                this.text=title
                textSize=12.5f
                setTypeface(null,Typeface.BOLD)
                setTextColor(accent)
                setPadding(dp(2),dp(20),0,dp(8))
            })
            target.addView(View(this).apply{ setBackgroundColor(divider) }, LinearLayout.LayoutParams(-1,dp(1)))
        }
        fun spaced(v:View){ target.addView(v, LinearLayout.LayoutParams(-1,-2).apply{ topMargin=dp(10) }) }

        fun field(hint:String,value:String,secret:Boolean=false) = EditText(this).apply {
            this.hint=hint; setText(value); setTextColor(Color.WHITE); setHintTextColor(subtext)
            setPadding(dp(12),dp(11),dp(12),dp(11))
            background=rounded(cardBg)
            if (secret) inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        fun labeled(caption:String,view:View){
            target.addView(TextView(this).apply{this.text=caption;textSize=12f;setTextColor(subtext);setPadding(dp(2),dp(10),0,dp(4))})
            target.addView(view)
        }
        fun spinner(values:Array<String>,pref:String,def:String):Spinner{
            val s=Spinner(this)
            s.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,values)
            s.setSelection(values.indexOf(prefs.getString(pref,def) ?: def).coerceAtLeast(0))
            s.setPadding(dp(10),dp(8),dp(10),dp(8))
            s.background=rounded(cardBg)
            return s
        }

        section("Подключение")
        val key = field("OpenAI API key", secure.getApiKey(), true); spaced(key)
        val model = field("Model ID", prefs.getString("model","gpt-6-astra") ?: "gpt-6-astra"); spaced(model)

        section("Мышление")
        val efforts = arrayOf("auto","low","medium","high","xhigh","max")
        val reasoning = spinner(efforts,"reasoning","high")
        labeled("Reasoning effort (auto = клиент сам подбирает по сложности запроса)",reasoning)

        val modes = arrayOf("standard","pro")
        val mode = spinner(modes,"reasoning_mode","standard")
        labeled("Reasoning mode",mode)

        section("Инструменты")
        fun chk(label:String,pref:String) = CheckBox(this).apply {
            text=label; setTextColor(Color.LTGRAY); isChecked=prefs.getBoolean(pref,false)
            setPadding(dp(4),dp(6),0,dp(6))
        }
        val web=chk("Web Search","web")
        val file=chk("File Search","file_search")
        val code=chk("Code Interpreter","code_interpreter")
        val img=chk("Image Generation","image_generation")
        val parallel=chk("Parallel tool calls","parallel_tools")
        val backgroundResponses=chk("Продолжать ответ, если Astra закрыта","background_responses").apply {
            isChecked = prefs.getBoolean("background_responses", true)
        }
        val toolsCard=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(4),dp(10),dp(4))
            background=rounded(cardBg)
        }
        listOf(web,file,code,img,parallel,backgroundResponses).forEach { toolsCard.addView(it) }
        spaced(toolsCard)

        lateinit var advancedToggle: TextView
        advancedToggle = TextView(this).apply{
            text="▸ Дополнительные настройки"
            setTextColor(accent); textSize=13f
            setPadding(dp(2),dp(20),0,dp(6))
            setOnClickListener{
                val show = advancedBox.visibility!=View.VISIBLE
                advancedBox.visibility = if(show) View.VISIBLE else View.GONE
                advancedToggle.text = if(show) "▾ Дополнительные настройки" else "▸ Дополнительные настройки"
            }
        }
        box.addView(advancedToggle)
        box.addView(advancedBox)
        target = advancedBox

        section("Формат ответа")
        val verbosities = arrayOf("low","medium","high")
        val verbosity = spinner(verbosities,"verbosity","medium")
        labeled("Verbosity",verbosity)

        val summaries = arrayOf("auto","concise","detailed","null")
        val summary = spinner(summaries,"reasoning_summary","auto")
        labeled("Краткий ход рассуждений",summary)

        val textFormats = arrayOf("text","json_object","json_schema")
        val textFormat = spinner(textFormats,"text_format","text")
        labeled("Text format — для обычного чата оставьте \"text\". json_object/json_schema нужны, только если ответ разбирает другая программа (не человек)",textFormat)

        val jsonSchema = field("JSON Schema — заполняйте только при Text format = json_schema", prefs.getString("json_schema","") ?: ""); spaced(jsonSchema)

        section("Прочее")
        val instructions = field("Developer instructions", prefs.getString("instructions","") ?: ""); spaced(instructions)
        val vector = field("Vector Store ID", prefs.getString("vector_store","") ?: ""); spaced(vector)
        spaced(button("📁 Управление файлами Vector Store"){ showVectorStoreManager() }.apply{
            setTextColor(accent); background=rounded(Color.rgb(40,48,66))
        })
        val max = field("Max output tokens", prefs.getString("max_output","128000") ?: "128000"); spaced(max)
        val cache = field("Prompt cache key (optional)", prefs.getString("cache_key","") ?: ""); spaced(cache)
        spaced(button("📊 Расход и токены") { showUsageStats() }.apply {
            setTextColor(accent); background=rounded(Color.rgb(40,48,66))
        })
        spaced(button("📁 Локальные файлы Astra") { showLocalFiles() }.apply {
            setTextColor(accent); background=rounded(Color.rgb(40,48,66))
        })
        spaced(button("📈 Статистика auto-эффорта") {
            AlertDialog.Builder(this)
                .setTitle("Auto-эффорт")
                .setMessage(chatDb.effortStats())
                .setPositiveButton("OK", null)
                .setNeutralButton("Очистить лог") { _, _ -> diagnostics.clear(); status.text = "Диагностический лог очищен" }
                .show()
        }.apply {
            setTextColor(accent); background=rounded(Color.rgb(40,48,66))
        })
        spaced(button("🧪 Диагностика") {
            AlertDialog.Builder(this)
                .setTitle("Astra diagnostics")
                .setMessage(diagnostics.tail())
                .setPositiveButton("OK", null)
                .show()
        }.apply {
            setTextColor(accent); background=rounded(Color.rgb(40,48,66))
        })

        val note=TextView(this).apply {
            text="Astra: 1.05M context, 128K max output. reasoning: auto/low/medium/high/xhigh/max."
            setTextColor(subtext); textSize=11.5f; setPadding(dp(2),dp(14),0,0)
        }
        box.addView(note)

        AlertDialog.Builder(this)
            .setTitle("Astra / OpenAI API")
            .setView(ScrollView(this).apply{addView(box)})
            .setNegativeButton("Отмена",null)
            .setPositiveButton("Сохранить") { _,_ ->
                secure.setApiKey(key.text.toString().trim())
                prefs.edit()
                    .putString("model",model.text.toString().trim())
                    .putString("instructions",instructions.text.toString())
                    .putString("vector_store",vector.text.toString().trim())
                    .putString("max_output",max.text.toString().trim())
                    .putString("cache_key",cache.text.toString().trim())
                    .putString("json_schema",jsonSchema.text.toString())
                    .putString("reasoning",efforts[reasoning.selectedItemPosition])
                    .putString("reasoning_mode",modes[mode.selectedItemPosition])
                    .putString("verbosity",verbosities[verbosity.selectedItemPosition])
                    .putString("reasoning_summary",summaries[summary.selectedItemPosition])
                    .putString("text_format",textFormats[textFormat.selectedItemPosition])
                    .putBoolean("web",web.isChecked)
                    .putBoolean("file_search",file.isChecked)
                    .putBoolean("code_interpreter",code.isChecked)
                    .putBoolean("image_generation",img.isChecked)
                    .putBoolean("parallel_tools",parallel.isChecked)
                    .putBoolean("background_responses",backgroundResponses.isChecked)
                    .apply()
                effortBadge.text="⚡ "+efforts[reasoning.selectedItemPosition]
                status.text="Настройки сохранены"
            }.show()
    }

    // ---------- Chat ----------
    private fun newChatId(): String = "chat_${System.currentTimeMillis()}_${System.nanoTime()}"

    private fun newChat() {
        persistComposerStateNow()
        requestGeneration++
        currentCall?.cancel()
        persistCurrentChat()
        val id = newChatId()
        prefs.edit().putString("current_chat",id).apply()
        chat.clear()
        currentResponseId=null
        baselineEffort=null
        lastUserText=""
        clearAttachedSelection()
        refreshAttachChips()
        messageAdapter.clearItems()
        toolLabel.text=""
        title.text="Новый чат"
        clearComposerState(id)
        ensureChatEntry(id,"Новый чат")
        toolStates.clear(); renderToolActivity()
        setBusy(false)
        status.text="Новый чат"
    }

    private fun switchChat(id:String) {
        persistComposerStateNow()
        requestGeneration++
        currentCall?.cancel()
        persistCurrentChat()
        prefs.edit().putString("current_chat",id).apply()
        restoreCurrentChat()
        resumeBackgroundResponses()
    }

    private fun classifyTask(text:String, hasAttachments:Boolean):String{
        val t=text.lowercase()
        return when {
            hasAttachments && (t.contains("анализ") || t.contains("разбери") || t.contains("проанализ") || t.contains("таблиц") || t.contains("файл")) -> "attachment_analysis"
            t.contains("код") || t.contains("функци") || t.contains("дебаг") || t.contains("отлад") || t.contains("рефактор") || t.contains("algorithm") || t.contains("debug") || t.contains("refactor") -> "coding"
            t.contains("проанализ") || t.contains("сравни") || t.contains("докажи") || t.contains("архитектур") || t.contains("исслед") || t.contains("analyze") || t.contains("compare") -> "analysis"
            text.length <= 80 -> "short"
            else -> "general"
        }
    }

    private fun autoEffortHeuristic(text:String, hasAttachments:Boolean):String{
        val len=text.length
        val heavy=Regex("(?i)(код|напиши функци|проанализируй|подробно|разбер|докажи|архитектур|дебаг|отладь|сравни|оптимизир|алгоритм|рефактор|explain in detail|analyze|debug)")
        return when {
            hasAttachments && len>200 -> "high"
            heavy.containsMatchIn(text) -> "high"
            len>600 -> "xhigh"
            len in 1..39 -> "low"
            else -> "medium"
        }
    }
    private fun fmtTokens(n:Int):String = fmtTokens(n.toLong())
    private fun fmtTokens(n:Long):String = when {
         n>=1_000_000L -> "%.1fM".format(n/1_000_000.0)
         n>=1_000L -> "%.1fK".format(n/1_000.0)
         else -> n.toString()
     }
    private fun estimateCost(inputTokens:Long, cachedTokens:Long, cacheWriteTokens:Long, outputTokens:Long):Double{
        // Astra standard tier: baseline $10/M input, $1/M cached input,
        // $12.50/M cache-write input and $50/M output. Above 272K input tokens
        // the documented long-context multiplier changes the effective rates.
        val longContext = inputTokens > 272_000
        val inMultiplier = if(longContext) 2.0 else 1.0
        val outMultiplier = if(longContext) 1.5 else 1.0
        val inRate=10.0*inMultiplier
        val cachedRate=1.0*inMultiplier
        val cacheWriteRate=12.50*inMultiplier
        val outRate=50.0*outMultiplier
        val uncached=(inputTokens-cachedTokens-cacheWriteTokens).coerceAtLeast(0)
        return (uncached*inRate + cachedTokens*cachedRate + cacheWriteTokens*cacheWriteRate + outputTokens*outRate) / 1_000_000.0
    }


    private fun cancelRemoteResponse(chatId:String,responseId:String){
        executor.execute{
            try{
                val key=secure.getApiKey()
                if(key.isBlank())return@execute
                val req=Request.Builder()
                    .url("https://api.openai.com/v1/responses/$responseId/cancel")
                    .addHeader("Authorization","Bearer $key")
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    .build()
                pollingClient.newCall(req).execute().use{resp->
                    diagnostics.log("background_response_cancel",mapOf("chat_id" to chatId,"response_id" to responseId,"status" to resp.code))
                }
            }catch(e:Exception){
                diagnostics.log("background_response_cancel_failed",mapOf("chat_id" to chatId,"response_id" to responseId,"error" to (e.message?:e.toString()).take(300)))
            }
        }
    }

    private fun stopCurrentResponse(){
        val chatId=currentChatId()
        val generation=requestGeneration
        userStopRequestedGeneration=generation
        requestGeneration++
        currentCall?.cancel()
        val active=try{chatDb.getActiveResponse(chatId)}catch(_:Exception){null}
        val responseId=active?.optString("response_id").takeIf{!it.isNullOrBlank() && it!="null"}
        val activeRequestId=active?.optString("request_id").takeIf{!it.isNullOrBlank() && it!="null"}
        chatDb.clearActiveResponse(chatId, expectedRequestId = activeRequestId)
        BackgroundResponseWorkScheduler.cancel(applicationContext, chatId, activeRequestId)
        if(responseId!=null){
            executor.execute{
                try{
                    val key=secure.getApiKey()
                    val req=Request.Builder()
                        .url("https://api.openai.com/v1/responses/$responseId/cancel")
                        .addHeader("Authorization","Bearer $key")
                        .post("{}".toRequestBody("application/json".toMediaType()))
                        .build()
                    pollingClient.newCall(req).execute().use{resp->
                        diagnostics.log("background_response_cancel",mapOf("chat_id" to chatId,"response_id" to responseId,"status" to resp.code))
                    }
                }catch(e:Exception){
                    diagnostics.log("background_response_cancel_failed",mapOf("chat_id" to chatId,"response_id" to responseId,"error" to (e.message?:e.toString()).take(300)))
                }
            }
        }
        if(!activeRequestRegenerate){
            restoringComposer=true
            try{
                input.setText(lastUserText)
                input.setSelection(input.text.length)
            }finally{restoringComposer=false}
            restoreLastUserAttachments()
        }
        if(!activeRequestRegenerate && chat.isNotEmpty() && chat.lastOrNull()?.optString("role")=="user"){
            chat.removeAt(chat.lastIndex)
            persistCurrentChat()
        }
        val assistantParent=activeAssistantBody?.parent
        if(assistantParent is ViewGroup){
            messages.removeView(assistantParent)
        }else if(messages.childCount>0){
            messages.removeViewAt(messages.childCount-1)
            if(!activeRequestRegenerate && messages.childCount>0) messages.removeViewAt(messages.childCount-1)
        }
        persistComposerStateNow()
        activeAssistantBody=null
        activeReasoningSummaryBody=null
        activeReasoningSummaryToggle=null
        setBusy(false)
        userStopRequestedGeneration=null
        activeRequestRegenerate=false
        status.text="Остановлено — запрос отменён"
        toolStates.clear(); renderToolActivity()
    }

    private fun sendMessage(regenerate:Boolean) {
        val key=secure.getApiKey()
        if(key.isBlank()){showSettings();return}
        if (pendingUploadUris.any { it.second == "zip" } && !preparingZipAttachments) {
            preparePendingZipsThenSend(regenerate)
            return
        }
        if(pendingUploads>0){ status.text="Дождитесь загрузки вложений…"; return }
        if(preparingZipAttachments){ status.text="Готовлю ZIP-вложения…"; return }
        val requestChatId = currentChatId()
        val requestGeneration = ++this.requestGeneration
        val startedAt = SystemClock.elapsedRealtime()

        var text = if(regenerate) lastUserText else input.text.toString().trim()
        if(regenerate) restoreLastUserAttachments()

        val imagePath=pendingImagePath
        val imageFileId=pendingImageFileId
        val requestFileIds = attachedFileIds.toList()
        val requestFileNames = attachedFileNames.toList()
        val requestFileSizes = attachedFileSizes.toList()
        val requestFilePaths = attachedFilePaths.toList()
        val requestFileMimes = attachedFileMimes.toList()
        val knownFileBytes = requestFileSizes.sumOf { it.coerceAtLeast(0L) }
        val maxTotalInputFileBytes = 50L * 1024L * 1024L
        if (knownFileBytes > maxTotalInputFileBytes) {
            status.text = "Суммарный размер файлов больше ${formatBytes(maxTotalInputFileBytes)} — уменьшите количество/размер вложений"
            return
        }
        val requestImagePath = imagePath
        val requestImageFileId = imageFileId
        if(!regenerate && text.isBlank() && requestImageFileId==null && requestFileIds.isEmpty()) return
        if(regenerate && text.isBlank()) return

        val model=prefs.getString("model","gpt-6-astra") ?: "gpt-6-astra"
        val localRequestId = "local_" + UUID.randomUUID().toString()
        val effortPref=prefs.getString("reasoning","high") ?: "high"
        val hasAttachments = requestFileIds.isNotEmpty() || requestImageFileId!=null
        val autoSelected = effortPref=="auto"
        val taskClass = classifyTask(text, hasAttachments)
        val baselineAutoEffort = autoEffortHeuristic(text,hasAttachments)
        val effort=if(autoSelected) chatDb.autoEffortHint(taskClass, baselineAutoEffort) else effortPref
        val reasoningMode=prefs.getString("reasoning_mode","standard") ?: "standard"
        var configUpdateEffort:String? = null
        val effectiveEffort:String
        if(reasoningMode=="standard"){
            val baseline=baselineEffort
            when {
                baseline==null -> { baselineEffort=effort; effectiveEffort=effort }
                baseline==effort -> effectiveEffort=baseline
                else -> { effectiveEffort=baseline; configUpdateEffort=effort }
            }
        } else {
            effectiveEffort=effort
        }
        val actualEffort = configUpdateEffort ?: effectiveEffort
        val verbosity=prefs.getString("verbosity","medium") ?: "medium"
        val summary=prefs.getString("reasoning_summary","auto") ?: "auto"
        val maxOut=prefs.getString("max_output","128000")?.toIntOrNull()?.coerceIn(256,128000) ?: 128000
        val backgroundEnabled = prefs.getBoolean("background_responses",true)
        val preflightWarning = buildPreflightWarning(text, knownFileBytes, maxOut, actualEffort)

        val sentUserMessage = JSONObject().put("role","user").put("text",text).put("request_id",localRequestId).apply {
            val fileIds=JSONArray(); requestFileIds.forEach{fileIds.put(it)}
            val fileNames=JSONArray(); requestFileNames.forEach{fileNames.put(it)}
            val fileSizes=JSONArray(); requestFileSizes.forEach{fileSizes.put(it)}
            val filePaths=JSONArray(); requestFilePaths.forEach{filePaths.put(it)}
            val fileMimes=JSONArray(); requestFileMimes.forEach{fileMimes.put(it)}
            if(fileIds.length()>0) put("file_ids",fileIds)
            if(fileNames.length()>0) put("file_names",fileNames)
            if(fileSizes.length()>0) put("file_sizes",fileSizes)
            if(filePaths.length()>0) put("file_paths",filePaths)
            if(fileMimes.length()>0) put("file_mimes",fileMimes)
            requestImagePath?.takeIf{File(it).isFile}?.let{path->put("image_path",path)}
            requestImageFileId?.let{fid->put("image_file_id",fid)}
            pendingImageName?.takeIf{it.isNotBlank()}?.let{put("image_name",it)}
            pendingImageMime?.takeIf{it.isNotBlank()}?.let{put("image_mime",it)}
            put("image_size",pendingImageSize.coerceAtLeast(0L))
        }

        if(!regenerate) {
            addBubble("Ты",text,true, message=sentUserMessage)
            chat.add(sentUserMessage)
            persistCurrentChat()
            input.setText("")
            clearComposerState(requestChatId)
        }

        lastUserText=text
        activeRequestRegenerate=regenerate
        chatDb.setActiveResponse(
            requestChatId,
            responseId = null,
            requestId = localRequestId,
            text = "",
            status = "starting",
            startedAt = System.currentTimeMillis(),
            effortPref = effortPref,
            effectiveEffort = actualEffort,
            autoSelected = autoSelected,
            promptChars = text.length,
            attachments = requestFileIds.size + if(requestImageFileId!=null) 1 else 0
        )
        setBusy(true)
        status.text=if(preflightWarning.isNullOrBlank()) "Astra думает…" else "⚠ $preflightWarning\nAstra думает…"
        toolStates.clear()
        renderToolActivity()
        diagnostics.log("request_start", mapOf(
            "chat_id" to requestChatId,
            "generation" to requestGeneration,
            "effort_pref" to effortPref,
            "effective_effort" to actualEffort,
            "auto" to autoSelected,
            "task_class" to taskClass,
            "auto_baseline" to baselineAutoEffort,
            "auto_effective" to effort,
            "attachments" to requestFileIds.size + if(requestImageFileId!=null) 1 else 0,
            "prompt_chars" to text.length,
            "model" to model
        ))

        val content=JSONArray()
        if(text.isNotBlank()) content.put(JSONObject().put("type","input_text").put("text",text))
        requestImageFileId?.let { content.put(JSONObject().put("type","input_image").put("file_id",it)) }
        requestFileIds.forEach { content.put(JSONObject().put("type","input_file").put("file_id",it)) }

        // Keep the request-level effort stable in standard mode; Astra's configuration_update
        // changes the conversation effort while preserving the original prompt prefix for caching.
        val reasoningObj=JSONObject().put("effort",effectiveEffort).put("mode",reasoningMode)
        if(summary!="null") reasoningObj.put("summary",summary)

        val textObj=JSONObject().put("verbosity",verbosity)
        val inputArr=JSONArray()
        configUpdateEffort?.let{ newEffort->
            inputArr.put(JSONObject().put("type","configuration_update").put("reasoning",JSONObject().put("effort",newEffort)))
            runOnUiThread{
                if(isRequestCurrent(requestChatId,requestGeneration)) toolLabel.text="⚙ мышление: $effectiveEffort → $newEffort (эта реплика)"
            }
        }
        inputArr.put(JSONObject().put("role","user").put("content",content))

        val body=JSONObject()
            .put("model",model)
            .put("input",inputArr)
            .put("stream",true)
            .put("store",true)
            .put("background",backgroundEnabled)
            .put("reasoning",reasoningObj)
            .put("max_output_tokens",maxOut)
            .put("parallel_tool_calls",prefs.getBoolean("parallel_tools",true))

        prefs.getString("instructions","")?.takeIf{it.isNotBlank()}?.let { body.put("instructions",it) }
        prefs.getString("cache_key","")?.takeIf{it.isNotBlank()}?.let { body.put("prompt_cache_key",it) }
        // OpenAI currently disallows combining configuration_update with automatic compaction.
        if (configUpdateEffort == null) {
            body.put("context_management", JSONArray().put(JSONObject().put("type","compaction").put("compact_threshold",800000)))
        }
        currentResponseId?.let { body.put("previous_response_id",it) }

        when(prefs.getString("text_format","text")){
            "json_object" -> textObj.put("format",JSONObject().put("type","json_object"))
            "json_schema" -> {
                val schemaText=prefs.getString("json_schema","")?.trim().orEmpty()
                if(schemaText.isNotBlank()){
                    try {
                        val schema=JSONObject(schemaText)
                        textObj.put("format",JSONObject().put("type","json_schema").put("name","astra_response").put("strict",true).put("schema",schema))
                    } catch(_:Exception){
                        runOnUiThread{ if(isRequestCurrent(requestChatId,requestGeneration)) status.text="Ошибка: JSON Schema недействительна" }
                    }
                } else {
                    runOnUiThread{ if(isRequestCurrent(requestChatId,requestGeneration)) status.text="Укажите JSON Schema в настройках для этого формата" }
                }
            }
        }
        body.put("text",textObj)

        val tools=JSONArray()
        if(prefs.getBoolean("web",true)) tools.put(JSONObject().put("type","web_search"))
        if(prefs.getBoolean("file_search",false)){
            val vs=prefs.getString("vector_store","")?.trim().orEmpty()
            if(vs.isNotBlank()) tools.put(JSONObject().put("type","file_search").put("vector_store_ids",JSONArray().put(vs)).put("max_num_results",20))
        }
        if(prefs.getBoolean("code_interpreter",false)){
            val c=JSONObject().put("type","auto").put("memory_limit","4g")
            if(requestFileIds.isNotEmpty()){
                val ids=JSONArray(); requestFileIds.forEach{ids.put(it)}; c.put("file_ids",ids)
            }
            tools.put(JSONObject().put("type","code_interpreter").put("container",c))
        }
        if(prefs.getBoolean("image_generation",false)) tools.put(JSONObject().put("type","image_generation"))
        if(tools.length()>0) body.put("tools",tools)

        val include=JSONArray()
        if(prefs.getBoolean("web",true)) include.put("web_search_call.action.sources")
        if(prefs.getBoolean("file_search",false)) include.put("file_search_call.results")
        if(prefs.getBoolean("code_interpreter",false)) include.put("code_interpreter_call.outputs")
        include.put("computer_call_output.output.image_url")
        body.put("include",include)

        clearAttachedSelection()
        toolLabel.text=""
        refreshAttachChips()

        val assistantView = addBubble("Astra","",false)
        activeAssistantBody = assistantView
        activeReasoningSummaryBody = null
        activeReasoningSummaryToggle = null
        val req=Request.Builder()
            .url("https://api.openai.com/v1/responses")
            .addHeader("Authorization","Bearer $key")
            .addHeader("Content-Type","application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        currentCall=client.newCall(req)
        val call=currentCall!!

        executor.execute {
            var requestId: String? = null
            var newId: String? = null
            var usage: JSONObject? = null
            var fileCites = emptyList<JSONObject>()
            var urlCites = emptyList<JSONObject>()
            var completionState = "in_progress"
            var completionError: String? = null
            var completedResponse: JSONObject? = null
            val toolItems=mutableListOf<String>()
            val full=StringBuilder()
            val reasoningSummary=StringBuilder()
            var lastUiUpdate=0L
            var lastActiveCheckpoint=0L
            try {
                call.execute().use { response ->
                    requestId=response.header("x-request-id")
                    chatDb.updateActiveResponse(requestChatId, text = "", status = "in_progress", expectedRequestId = localRequestId)
                    diagnostics.log("http_response", mapOf("request_id" to requestId, "status" to response.code, "chat_id" to requestChatId, "generation" to requestGeneration))
                    if(!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.body?.string().orEmpty()}")
                    val reader=response.body?.byteStream()?.bufferedReader(Charsets.UTF_8) ?: throw IOException("Пустой ответ")
                    val sseParser = SseEventParser()
                    var streamDone = false
                    var lastSequenceNumber = -1L

                    fun handleSseEvent(data:String) {
                        if(streamDone) return
                        if(data=="[DONE]"){
                            streamDone=true
                            return
                        }
                        try {
                            val ev=JSONObject(data)
                            val sequenceNumber=ev.optLong("sequence_number",-1L)
                            if(sequenceNumber>=0L){
                                when{
                                    sequenceNumber<=lastSequenceNumber -> {
                                        diagnostics.log("sse_event_duplicate_or_out_of_order",mapOf(
                                            "request_id" to requestId,
                                            "sequence_number" to sequenceNumber,
                                            "last_sequence_number" to lastSequenceNumber
                                        ))
                                        return
                                    }
                                    lastSequenceNumber>=0L && sequenceNumber>lastSequenceNumber+1L -> {
                                        diagnostics.log("sse_event_sequence_gap",mapOf(
                                            "request_id" to requestId,
                                            "expected_sequence_number" to (lastSequenceNumber+1L),
                                            "received_sequence_number" to sequenceNumber
                                        ))
                                    }
                                }
                                lastSequenceNumber=sequenceNumber
                            }
                            when(ev.optString("type")){
                                "response.created" -> {
                                    val r=ev.optJSONObject("response")
                                    newId=r?.optString("id")?.takeIf{it.isNotBlank()}
                                    if(newId!=null) {
                                        chatDb.updateActiveResponse(requestChatId, responseId = newId, status = "in_progress", expectedRequestId = localRequestId)
                                        diagnostics.log("response_created", mapOf("request_id" to requestId, "response_id" to newId, "chat_id" to requestChatId))
                                    }
                                }
                                "response.output_text.delta" -> {
                                    val delta=ev.optString("delta")
                                    if(delta.isNotEmpty()){
                                        full.append(delta)
                                        val now=SystemClock.elapsedRealtime()
                                        if(now-lastUiUpdate>=50L){
                                            lastUiUpdate=now
                                            val snapshot=full.toString()
                                            runOnUiThread{
                                                if(isRequestCurrent(requestChatId,requestGeneration)){
                                                    assistantView.text=markdown(snapshot)
                                                    activeAssistantBody=assistantView
                                                    scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}
                                                }
                                            }
                                        }
                                        if(now-lastActiveCheckpoint>=750L){
                                            lastActiveCheckpoint=now
                                            chatDb.updateActiveResponse(
                                                requestChatId,
                                                responseId = newId,
                                                text = full.toString(),
                                                status = "in_progress", expectedRequestId = localRequestId
                                            )
                                        }
                                    }
                                }
                                "response.reasoning_summary_text.delta" -> {
                                    val delta=ev.optString("delta")
                                    if(delta.isNotEmpty()){
                                        reasoningSummary.append(delta)
                                        runOnUiThread{
                                            if(isRequestCurrent(requestChatId,requestGeneration)){
                                                updateActiveReasoningSummary(reasoningSummary.toString())
                                            }
                                        }
                                    }
                                }
                                "response.reasoning_summary_text.done" -> {}
                                "response.web_search_call.completed" -> { toolItems.add("🌐 Web Search"); updateToolState("web_search","completed",requestChatId,requestGeneration) }
                                "response.file_search_call.completed" -> { toolItems.add("📚 File Search"); updateToolState("file_search","completed",requestChatId,requestGeneration) }
                                "response.code_interpreter_call.completed" -> { toolItems.add("🐍 Code Interpreter"); updateToolState("code_interpreter","completed",requestChatId,requestGeneration) }
                                "response.image_generation_call.completed" -> { toolItems.add("🎨 Image Generation"); updateToolState("image_generation","completed",requestChatId,requestGeneration) }
                                "response.computer_call.completed" -> { toolItems.add("🖥 Computer Use"); updateToolState("computer","completed",requestChatId,requestGeneration) }
                                "response.web_search_call.in_progress", "response.web_search_call.searching" -> updateToolState("web_search","running",requestChatId,requestGeneration)
                                "response.file_search_call.in_progress" -> updateToolState("file_search","running",requestChatId,requestGeneration)
                                "response.code_interpreter_call.in_progress" -> updateToolState("code_interpreter","running",requestChatId,requestGeneration)
                                "response.image_generation_call.in_progress" -> updateToolState("image_generation","running",requestChatId,requestGeneration)
                                "response.computer_call.in_progress" -> updateToolState("computer","running",requestChatId,requestGeneration)
                                "response.web_search_call.failed" -> updateToolState("web_search","failed",requestChatId,requestGeneration)
                                "response.file_search_call.failed" -> updateToolState("file_search","failed",requestChatId,requestGeneration)
                                "response.code_interpreter_call.failed" -> updateToolState("code_interpreter","failed",requestChatId,requestGeneration)
                                "response.image_generation_call.failed" -> updateToolState("image_generation","failed",requestChatId,requestGeneration)
                                "response.computer_call.failed" -> updateToolState("computer","failed",requestChatId,requestGeneration)
                                "response.incomplete" -> {
                                    val r=ev.optJSONObject("response")
                                    completionState="incomplete"
                                    newId=r?.optString("id")?.takeIf{it.isNotBlank()}
                                    usage=r?.optJSONObject("usage")
                                    completionError=r?.optJSONObject("incomplete_details")?.optString("reason")
                                    completedResponse=r
                                }
                                "response.failed" -> {
                                    val r=ev.optJSONObject("response")
                                    completionState="failed"
                                    newId=r?.optString("id")?.takeIf{it.isNotBlank()}
                                    usage=r?.optJSONObject("usage")
                                    completionError=r?.optJSONObject("error")?.optString("message")
                                        ?: r?.optString("error")
                                    completedResponse=r
                                }
                                "response.completed" -> {
                                    completionState="completed"
                                    val r=ev.optJSONObject("response")
                                    newId=r?.optString("id")?.takeIf{it.isNotBlank()}
                                    usage=r?.optJSONObject("usage")
                                    completedResponse=r
                                    parseTools(r?.optJSONArray("output"),toolItems,requestChatId,requestGeneration)
                                    newId?.let { chatDb.updateActiveResponse(requestChatId, responseId = it, text = full.toString(), status = "completed", expectedRequestId = localRequestId) }
                                    val annotations=extractAnnotations(r?.optJSONArray("output"))
                                    fileCites=annotations.first
                                    urlCites=annotations.second
                                    diagnostics.log("response_completed", mapOf(
                                        "request_id" to requestId,
                                        "response_id" to newId,
                                        "files" to fileCites.size,
                                        "urls" to urlCites.size,
                                        "latency_ms" to (SystemClock.elapsedRealtime()-startedAt)
                                    ))
                                }
                            }
                        } catch (parseError:Exception){
                            diagnostics.log("sse_event_parse_error", mapOf("message" to (parseError.message ?: "unknown"), "request_id" to requestId))
                        }
                    }

                    reader.forEachLine { line ->
                        if(streamDone) return@forEachLine
                        sseParser.acceptLine(line)?.let { handleSseEvent(it.data) }
                    }
                    sseParser.finish()?.let { handleSseEvent(it.data) }

                    if(completionState=="failed") {
                        throw IOException("Ответ Astra не завершился: ${completionError?.take(500) ?: "неизвестная ошибка"}")
                    }
                    val finalText=full.toString().ifBlank{if(completionState=="incomplete") "Ответ завершён не полностью." else "Готово. Ответ не содержит текста."}
                    val answerAnnotations=JSONArray()
                    fileCites.forEach{ answerAnnotations.put(JSONObject(it.toString())) }
                    urlCites.forEach{ answerAnnotations.put(JSONObject(it.toString())) }
                    val parsedModel = AstraResponseModel.fromResponse(
                        completedResponse,
                        finalText,
                        requestId,
                        completionState,
                        completionError
                    )
                    val streamedSummary=reasoningSummary.toString().trim()
                    val responseSummary=parsedModel.reasoningSummary?.takeIf{it.isNotBlank()}
                        ?: extractReasoningSummary(completedResponse).takeIf{it.isNotBlank()}
                        ?: streamedSummary.takeIf{it.isNotBlank()}
                    val responseModel = parsedModel.copy(
                        id = newId ?: parsedModel.id,
                        annotations = if(parsedModel.annotations.length()>0) parsedModel.annotations else answerAnnotations,
                        tools = (parsedModel.tools + toolItems).distinct(),
                        usage = usage ?: parsedModel.usage,
                        reasoningSummary = responseSummary
                    )
                    val assistantMessage=responseModel.toChatJson()
                    val savedGeneratedImages = saveGeneratedImages(responseModel.generatedImageResults)
                    if (savedGeneratedImages.length() > 0) assistantMessage.put("generated_images", savedGeneratedImages)

                    val latency=SystemClock.elapsedRealtime()-startedAt
                    val usageObj=usage
                    val inTok=usageObj?.optLong("input_tokens",0L) ?: 0L
                    val outTok=usageObj?.optLong("output_tokens",0L) ?: 0L
                    val cachedTok=usageObj?.optJSONObject("input_tokens_details")?.optLong("cached_tokens",0L) ?: 0L
                    val cacheWriteTok=usageObj?.optJSONObject("input_tokens_details")?.optLong("cache_write_tokens",0L) ?: 0L
                    val reasoningTok=usageObj?.optJSONObject("output_tokens_details")?.optLong("reasoning_tokens",0L) ?: 0L
                    val estimatedCost=estimateCost(inTok,cachedTok,cacheWriteTok,outTok)
                    chatDb.recordEffortMetric(requestId, requestChatId, taskClass, effortPref, actualEffort, autoSelected, text.length, requestFileIds.size + if(requestImageFileId!=null) 1 else 0, latency, inTok, cachedTok, cacheWriteTok, outTok, reasoningTok, completionState=="completed", if(completionState=="completed") null else completionState.ifBlank{"incomplete"}, estimatedCost)

                    runOnUiThread{
                        if(!isRequestCurrent(requestChatId,requestGeneration)) return@runOnUiThread
                        currentResponseId=newId ?: currentResponseId
                        baselineEffort=actualEffort
                        chat.add(assistantMessage)
                        chatDb.clearActiveResponse(requestChatId, expectedRequestId = localRequestId)
                        persistCurrentChat()

                        assistantView.text=RichTextRenderer.render(responseModel)
                        val bubbleCard=assistantView.parent as? LinearLayout
                        if(bubbleCard!=null){
                            responseModel.reasoningSummary?.takeIf{it.isNotBlank()}?.let{summaryText->
                                if(activeReasoningSummaryBody==null){
                                    activeReasoningSummaryBody=attachReasoningSummaryPanel(bubbleCard,summaryText,initiallyExpanded=false)
                                    activeReasoningSummaryToggle=(activeReasoningSummaryBody?.parent as? LinearLayout)?.getChildAt(0) as? TextView
                                }else{
                                    activeReasoningSummaryBody?.text=summaryText
                                    activeReasoningSummaryToggle?.text="🧠 Показать ход рассуждений ▼"
                                }
                            }
                            if (hasAssistantAssets(assistantMessage)) addAssetSectionHeader(bubbleCard)
                            renderGeneratedImages(assistantMessage,bubbleCard)
                            renderAnnotations(responseModel.annotations,bubbleCard,assistantMessage,requestChatId,requestGeneration)
                            addFeedbackRow(bubbleCard,assistantMessage,requestChatId)
                        }
                        status.text=if(usageObj!=null){
                            val cost=estimateCost(inTok,cachedTok,cacheWriteTok,outTok)
                            val reasoningPart=if(reasoningTok>0)" (${fmtTokens(reasoningTok)} думал)" else ""
                            val donePrefix=if(completionState=="incomplete") "Неполный ответ" else "Готово"
                            "$donePrefix · ${fmtTokens(inTok)} in · ${fmtTokens(outTok)} out$reasoningPart · ≈\$%.4f".format(cost)
                        } else if(completionState=="incomplete") "Ответ завершён не полностью" else "Готово"
                        if(toolItems.isNotEmpty()) toolLabel.text=toolItems.distinct().joinToString("  ")
                        setBusy(false)
                        activeAssistantBody=null
                        currentCall=null
                        activeRequestRegenerate=false
                        if(status.text=="Astra думает…") status.text="Готово"
                    }
                }
            } catch(e:Exception) {
                val cancelled=call.isCanceled()
                val userStopped = userStopRequestedGeneration == requestGeneration
                val canContinueInBackground = backgroundEnabled && newId != null && completionState == "in_progress" && !userStopped
                val latency=SystemClock.elapsedRealtime()-startedAt
                if(!canContinueInBackground && (!cancelled || userStopped)){
                    chatDb.recordEffortMetric(requestId ?: localRequestId, requestChatId, taskClass, effortPref, actualEffort, autoSelected, text.length, requestFileIds.size + if(requestImageFileId!=null) 1 else 0, latency, 0,0,0,0,0, false, if(cancelled) "cancelled" else e.javaClass.simpleName)
                }
                diagnostics.log(
                    when {
                        canContinueInBackground -> "stream_disconnected_background_continues"
                        cancelled && userStopped -> "request_cancelled"
                        cancelled -> "stream_disconnected"
                        else -> "request_failed"
                    },
                    mapOf("request_id" to requestId, "response_id" to newId, "chat_id" to requestChatId, "generation" to requestGeneration, "latency_ms" to latency, "error" to (e.message ?: e.toString()).take(512))
                )
                if(canContinueInBackground){
                    val active=try{chatDb.getActiveResponse(requestChatId)}catch(_:Exception){null}
                    if(!activityVisible && newId!=null){
                        BackgroundResponseWorkScheduler.enqueueImmediate(
                            applicationContext,
                            requestChatId,
                            newId!!,
                            localRequestId
                        )
                    } else if(activityVisible && active!=null){
                        runOnUiThread{
                            if(isRequestCurrent(requestChatId,requestGeneration)){
                                currentCall=null
                                status.text="Соединение прервалось — Astra продолжает работу…"
                                activeAssistantBody?.let{
                                    val partial=full.toString()
                                    if(partial.isNotBlank()) it.text=markdown(partial)
                                }
                                setBusy(true)
                            }
                        }
                        if(active!=null){
                            pollBackgroundResponse(active)
                        }
                    }
                    return@execute
                }
                runOnUiThread{
                    if(!isRequestCurrent(requestChatId,requestGeneration)) return@runOnUiThread
                    setBusy(false)
                    activeAssistantBody=null
                    currentCall=null
                    if(!cancelled && completionState=="incomplete" && full.isNotBlank() && isRequestCurrent(requestChatId,requestGeneration)) {
                        val partial=JSONObject().put("role","assistant").put("text",full.toString()).put("incomplete",true).put("status","incomplete")
                        newId?.let{partial.put("response_id",it)}
                        requestId?.let{partial.put("request_id",it)}
                        chat.add(partial)
                        persistCurrentChat()
                    }
                    if(cancelled && userStopped){
                        chatDb.clearActiveResponse(requestChatId, expectedRequestId = localRequestId)
                        if(chat.isNotEmpty() && chat.lastOrNull()?.optString("role")=="user" && !regenerate){
                            chat.removeAt(chat.lastIndex)
                            persistCurrentChat()
                        }
                        if(messages.childCount>0) messages.removeViewAt(messages.childCount-1)
                        if(!regenerate && messages.childCount>0) messages.removeViewAt(messages.childCount-1)
                        if(!regenerate){
                            input.setText(text); input.setSelection(input.text.length)
                        }
                        attachedFileIds.clear(); attachedFileIds.addAll(requestFileIds)
                        attachedFileNames.clear(); attachedFileNames.addAll(requestFileNames)
                        attachedFileSizes.clear(); attachedFileSizes.addAll(requestFileSizes)
                        attachedFilePaths.clear(); attachedFilePaths.addAll(requestFilePaths)
                        attachedFileMimes.clear(); attachedFileMimes.addAll(requestFileMimes)
                        while(attachedFilePaths.size<attachedFileIds.size)attachedFilePaths.add("")
                        while(attachedFileMimes.size<attachedFileIds.size)attachedFileMimes.add("")
                        pendingImagePath=requestImagePath
                        pendingImageName=sentUserMessage.optString("image_name").takeIf{it.isNotBlank()}
                        pendingImageMime=sentUserMessage.optString("image_mime").takeIf{it.isNotBlank()}
                        pendingImageSize=sentUserMessage.optLong("image_size",0L)
                        refreshAttachChips()
                        persistComposerStateNow()
                        status.text="Остановлено — сообщение и вложения возвращены в поле ввода"
                    } else {
                        chatDb.clearActiveResponse(requestChatId, expectedRequestId = localRequestId)
                        val friendly = friendlyApiError(e.message)
                        status.text="Ошибка"
                        addBubble("Ошибка",friendly,false)
                    }
                }
            }
        }
    }

    private fun JSONArray.toJsonArray(): JSONArray = JSONArray(toString())

    private fun friendlyApiError(raw:String?):String{
        val text=raw.orEmpty()
        if(text.isBlank()) return "Неизвестная ошибка API"
        val jsonStart=text.indexOf('{')
        if(jsonStart>=0){
            try{
                val obj=JSONObject(text.substring(jsonStart))
                val err=obj.optJSONObject("error")
                val msg=err?.optString("message")?.takeIf{it.isNotBlank()}
                if(msg!=null) return msg
            }catch(_:Exception){}
        }
        return text.lineSequence().firstOrNull()?.take(500) ?: "Ошибка запроса"
    }


    private fun buildPreflightWarning(text:String, totalFileBytes:Long, maxOut:Int, effort:String):String? {
        val warnings=mutableListOf<String>()
        val historyChars=chat.sumOf { it.optString("text").length }
        if(totalFileBytes > 40L*1024L*1024L) warnings += "вложения уже занимают ${(totalFileBytes/1024.0/1024.0).toInt()} МБ"
        if(text.length > 100_000) warnings += "очень большой текст (${text.length/1000} тыс. символов)"
        if(historyChars > 250_000) warnings += "история этого чата уже очень длинная — ответ может быть дольше и дороже"
        if(effort=="max" && (text.length > 40_000 || maxOut > 100_000)) warnings += "max + большой запрос может работать заметно дольше"
        return warnings.joinToString(" · ").takeIf{it.isNotBlank()}
    }

    private fun toolCaption(key:String):String = when(key){
        "web_search"->"🔎 Web Search"
        "file_search"->"📚 File Search"
        "code_interpreter"->"🐍 Code Interpreter"
        "image_generation"->"🎨 Image Generation"
        "computer"->"🖥 Computer Use"
        else->key
    }

    private fun updateToolState(key:String,state:String, chatId:String?=null, generation:Long?=null){
        if(chatId!=null && generation!=null && !isRequestCurrent(chatId,generation)) return
        toolStates[key]=state
        runOnUiThread {
            if(chatId==null || generation==null || isRequestCurrent(chatId,generation)) renderToolActivity()
        }
    }

    private fun renderToolActivity(){
        if(!::toolLabel.isInitialized)return
        if(toolStates.isEmpty()){toolLabel.text="";return}
        val text=toolStates.entries.joinToString("\n"){(key,state)->
            val suffix=when(state){"running"->"работает…";"completed"->"готово";"failed"->"ошибка";else->state}
            "${toolCaption(key)} — $suffix"
        }
        toolLabel.text=text
    }

    private fun handleToolEventType(type:String, chatId:String?=null, generation:Long?=null){
        val m=Regex("^response\\.([a-z_]+)_call\\.(.+)$").find(type) ?: return
        val key=when(m.groupValues[1]){
            "web_search"->"web_search"
            "file_search"->"file_search"
            "code_interpreter"->"code_interpreter"
            "image_generation"->"image_generation"
            "computer"->"computer"
            else->return
        }
        val state=m.groupValues[2]
        when {
            state.contains("in_progress") || state.contains("running") || state.contains("searching") || state.contains("queued") -> updateToolState(key,"running",chatId,generation)
            state.contains("completed") -> updateToolState(key,"completed",chatId,generation)
            state.contains("failed") -> updateToolState(key,"failed",chatId,generation)
        }
    }


    private fun resumeBackgroundResponses(){
        val active=try{chatDb.listActiveResponses()}catch(_:Exception){emptyList()}
        active.forEach { a ->
            val chatId=a.optString("chat_id")
            val requestId=a.optString("request_id").takeIf{it.isNotBlank() && it!="null"}
            val responseId=a.optString("response_id").takeIf{it.isNotBlank() && it!="null"}
            if(responseId==null){
                val startedAt=a.optLong("started_at",0L)
                val age=System.currentTimeMillis()-startedAt
                if(requestId!=null && startedAt>0L && age>120_000L){
                    chatDb.clearActiveResponse(chatId, expectedRequestId=requestId)
                    diagnostics.log("background_response_orphaned",mapOf("chat_id" to chatId,"request_id" to requestId,"age_ms" to age))
                }
                return@forEach
            }
            synchronized(activeResumeChats){
                if(!activeResumeChats.add(chatId)) return@forEach
            }
            if(chatId==currentChatId()) {
                val body=activeAssistantBody
                if(body!=null && a.optString("text").isNotBlank()) {
                    body.text=markdown(a.optString("text"))
                }
                runOnUiThread{
                    status.text="Astra продолжает ответ…"
                    toolStates.clear()
                    renderToolActivity()
                }
            }
            executor.execute{
                try{
                    pollBackgroundResponse(a)
                }finally{
                    synchronized(activeResumeChats){activeResumeChats.remove(chatId)}
                }
            }
        }
    }

    private fun pollBackgroundResponse(active:JSONObject){
        val chatId=active.optString("chat_id")
        val responseId=active.optString("response_id")
        val requestId=active.optString("request_id").takeIf{it.isNotBlank() && it!="null"}
        if(chatId.isBlank() || responseId.isBlank()) return

        val recoveryStartedAt=SystemClock.elapsedRealtime()
        val maxRecoveryMs=30L*60L*1000L
        var delay=1500L

        while(!executor.isShutdown){
            if(!activityVisible){
                BackgroundResponseWorkScheduler.enqueueImmediate(applicationContext,chatId,responseId,requestId ?: return)
                return
            }
            if(SystemClock.elapsedRealtime()-recoveryStartedAt>=maxRecoveryMs){
                diagnostics.log("background_resume_timeout",mapOf(
                    "chat_id" to chatId,
                    "response_id" to responseId,
                    "request_id" to requestId
                ))
                finishBackgroundRecovery(chatId,"Фоновое восстановление приостановлено — попробуйте открыть приложение позже",requestId)
                return
            }

            val r=try{
                retrieveResponse(responseId)
            }catch(e:ApiHttpException){
                when(e.statusCode){
                    401,403 -> {
                        diagnostics.log("background_resume_auth_failed",mapOf(
                            "chat_id" to chatId,
                            "response_id" to responseId,
                            "status" to e.statusCode
                        ))
                        finishBackgroundRecovery(chatId,"Не удалось восстановить ответ: проверьте API-ключ",requestId)
                        return
                    }
                    404,410 -> {
                        diagnostics.log("background_resume_response_gone",mapOf(
                            "chat_id" to chatId,
                            "response_id" to responseId,
                            "status" to e.statusCode
                        ))
                        finishBackgroundRecovery(chatId,"Фоновый ответ больше недоступен",requestId)
                        return
                    }
                    429 -> {
                        val waitMs=e.retryAfterMs?.coerceIn(1000L,300_000L)
                            ?: delay.coerceAtMost(60_000L)
                        diagnostics.log("background_resume_rate_limited",mapOf(
                            "chat_id" to chatId,
                            "response_id" to responseId,
                            "retry_after_ms" to waitMs
                        ))
                        if(!sleepBackgroundRetry(waitMs)) return
                        delay=(delay*2).coerceAtMost(30_000L)
                        continue
                    }
                    in 500..599 -> {
                        diagnostics.log("background_resume_server_error",mapOf(
                            "chat_id" to chatId,
                            "response_id" to responseId,
                            "status" to e.statusCode
                        ))
                    }
                    else -> {
                        diagnostics.log("background_resume_http_error",mapOf(
                            "chat_id" to chatId,
                            "response_id" to responseId,
                            "status" to e.statusCode
                        ))
                        finishBackgroundRecovery(chatId,"Не удалось восстановить ответ (HTTP ${e.statusCode})",requestId)
                        return
                    }
                }
                if(!sleepBackgroundRetry(delay)) return
                delay=(delay*2).coerceAtMost(30_000L)
                continue
            }catch(e:Exception){
                diagnostics.log("background_resume_poll_failed",mapOf(
                    "chat_id" to chatId,
                    "response_id" to responseId,
                    "error" to (e.message?:e.toString()).take(400)
                ))
                if(!sleepBackgroundRetry(delay)) return
                delay=(delay*2).coerceAtMost(30_000L)
                continue
            }

            val state=r.optString("status").ifBlank{"in_progress"}
            val text=extractResponseText(r).ifBlank{active.optString("text")}
            chatDb.updateActiveResponse(chatId,responseId=responseId,text=text,status=state,expectedRequestId=requestId)
            if(chatId==currentChatId()){
                runOnUiThread{
                    if(currentChatId()==chatId){
                        activeAssistantBody?.text=markdown(text)
                        activeAssistantBody?.let{scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}}
                        status.text=if(state=="queued")"Astra в очереди…" else "Astra продолжает ответ…"
                    }
                }
            }
            if(state in setOf("completed","failed","cancelled","incomplete")){
                finalizeBackgroundResponse(active,r)
                return
            }
            if(!sleepBackgroundRetry(delay)) return
            delay=1500L
        }
    }

    private fun sleepBackgroundRetry(baseDelayMs:Long):Boolean{
        val jitter=if(baseDelayMs<=1L) 0L else {
            val spread=(baseDelayMs/5L).coerceAtLeast(1L)
            kotlin.random.Random.nextLong(-spread,spread+1L)
        }
        val actual=(baseDelayMs+jitter).coerceAtLeast(250L)
        return try{
            Thread.sleep(actual)
            true
        }catch(_:InterruptedException){
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun finishBackgroundRecovery(chatId:String,message:String,requestId:String?){
        chatDb.clearActiveResponse(chatId,expectedRequestId=requestId)
        if(currentChatId()!=chatId) return
        runOnUiThread{
            if(currentChatId()!=chatId) return@runOnUiThread
            activeAssistantBody=null
            currentCall=null
            setBusy(false)
            status.text=message
        }
    }

    private fun retrieveResponse(responseId:String):JSONObject{
        val key=secure.getApiKey()
        if(key.isBlank()) throw IOException("API key is not configured")
        val req=Request.Builder()
            .url("https://api.openai.com/v1/responses/$responseId")
            .addHeader("Authorization","Bearer $key")
            .get().build()
        pollingClient.newCall(req).execute().use{resp->
            val raw=resp.body?.string().orEmpty()
            if(!resp.isSuccessful){
                val retryAfterMs=resp.header("Retry-After")?.trim()?.toLongOrNull()
                    ?.coerceIn(1L,300L)?.times(1000L)
                throw ApiHttpException(resp.code,retryAfterMs,raw.take(4096))
            }
            return JSONObject(raw)
        }
    }

    private fun extractResponseText(response:JSONObject?):String{
        val output=response?.optJSONArray("output") ?: return ""
        val out=StringBuilder()
        for(i in 0 until output.length()){
            val item=output.optJSONObject(i) ?: continue
            if(item.optString("type")!="message")continue
            val content=item.optJSONArray("content") ?: continue
            for(j in 0 until content.length()){
                val c=content.optJSONObject(j) ?: continue
                if(c.optString("type")=="output_text") out.append(c.optString("text"))
            }
        }
        return out.toString()
    }

    private fun finalizeBackgroundResponse(active:JSONObject,response:JSONObject){
        val chatId=active.optString("chat_id")
        val responseId=active.optString("response_id")
        val state=response.optString("status").ifBlank{active.optString("status")}
        val fallbackText=active.optString("text")
        val text=extractResponseText(response).ifBlank{fallbackText}
        val requestId=active.optString("request_id").takeIf{it.isNotBlank() && it!="null"}
        val model=AstraResponseModel.fromResponse(response,text,requestId,state,
            response.optJSONObject("error")?.optString("message")
                ?: response.optJSONObject("incomplete_details")?.optString("reason"))
        val savedImages=saveGeneratedImages(model.generatedImageResults)
        val assistant=model.toChatJson().apply{
            if(savedImages.length()>0)put("generated_images",savedImages)
        }
        val chatObj=chatDb.getChat(chatId) ?: return
        val arr=chatObj.optJSONArray("messages") ?: JSONArray()
        var already=false
        for(i in 0 until arr.length()){
            val m=arr.optJSONObject(i) ?: continue
            if(m.optString("response_id")==responseId){already=true;break}
        }
        val msgs=jsonArrayToMutableList(arr)
        if(!already && state!="cancelled") msgs.add(assistant)
        val effortPref=active.optString("effort_pref").ifBlank{"high"}
        val effectiveEffort=active.optString("effective_effort").ifBlank{"high"}
        val startedAt=active.optLong("started_at",System.currentTimeMillis())
        val latency=(System.currentTimeMillis()-startedAt).coerceAtLeast(0L)
        val usage=response.optJSONObject("usage")
        val inTok=usage?.optLong("input_tokens",0L) ?: 0L
        val outTok=usage?.optLong("output_tokens",0L) ?: 0L
        val cachedTok=usage?.optJSONObject("input_tokens_details")?.optLong("cached_tokens",0L) ?: 0L
        val cacheWriteTok=usage?.optJSONObject("input_tokens_details")?.optLong("cache_write_tokens",0L) ?: 0L
        val reasoningTok=usage?.optJSONObject("output_tokens_details")?.optLong("reasoning_tokens",0L) ?: 0L
        val success=state=="completed"
        val cost=estimateCost(inTok,cachedTok,cacheWriteTok,outTok)
        chatDb.recordEffortMetric(
            requestId,chatId,"resumed",
            effortPref,effectiveEffort,active.optBoolean("auto_selected",false),
            active.optInt("prompt_chars",0),active.optInt("attachments",0),
            latency,inTok,cachedTok,cacheWriteTok,outTok,reasoningTok,success,
            if(success)null else state.ifBlank{"background_failed"},cost
        )
        chatDb.upsertCurrentChat(
            chatId,
            chatObj.optString("title","Новый чат"),
            responseId.takeIf{it.isNotBlank()},
            effectiveEffort,
            msgs
        )
        chatDb.clearActiveResponse(chatId, expectedRequestId = requestId)
        diagnostics.log("background_response_finalized",mapOf(
            "chat_id" to chatId,
            "response_id" to responseId,
            "status" to state,
            "latency_ms" to latency,
            "estimated_cost_usd" to "%.6f".format(cost)
        ))
        runOnUiThread{
            if(currentChatId()==chatId){
                requestGeneration++
                currentResponseId=responseId.takeIf{it.isNotBlank()}
                baselineEffort=effectiveEffort
                activeAssistantBody=null
                restoreCurrentChat()
                setBusy(false)
                status.text=when{
                    state=="completed" && usage!=null -> "Готово · ${fmtTokens(inTok)} in · ${fmtTokens(outTok)} out · ≈$%.4f".format(cost)
                    state=="incomplete" -> "Ответ завершён не полностью"
                    state=="cancelled" -> "Ответ отменён"
                    else -> "Ошибка фонового ответа"
                }
            }
        }
    }

    private fun attachReasoningSummaryPanel(card:LinearLayout, summary:String, initiallyExpanded:Boolean=false):TextView {
         val box=LinearLayout(this).apply{
             orientation=LinearLayout.VERTICAL
             setPadding(0,dp(8),0,dp(2))
         }
         val toggle=TextView(this).apply{
             text=if(summary.isBlank())"🧠 Astra думает…" else if(initiallyExpanded)"🧠 Скрыть ход рассуждений ▲" else "🧠 Показать ход рассуждений ▼"
             textSize=12.5f
             setTextColor(Color.rgb(165,195,240))
             setPadding(0,dp(2),0,dp(4))
         }
         val body=TextView(this).apply{
             text=summary
             textSize=13f
             setTextColor(Color.rgb(205,210,220))
             setPadding(0,dp(2),0,dp(3))
             visibility=if(initiallyExpanded && summary.isNotBlank())View.VISIBLE else View.GONE
         }
         toggle.setOnClickListener{
             if(body.text.isNullOrBlank())return@setOnClickListener
             body.visibility=if(body.visibility==View.VISIBLE)View.GONE else View.VISIBLE
             toggle.text=if(body.visibility==View.VISIBLE)"🧠 Скрыть ход рассуждений ▲" else "🧠 Показать ход рассуждений ▼"
         }
         box.addView(toggle)
         box.addView(body)
         card.addView(box)
         return body
     }

     private fun updateActiveReasoningSummary(summary:String){
         val clean=summary.trim()
         if(activeReasoningSummaryBody==null){
             val card=activeAssistantBody?.parent as? LinearLayout ?: return
             activeReasoningSummaryBody=attachReasoningSummaryPanel(card,clean,initiallyExpanded=false)
             activeReasoningSummaryToggle=(activeReasoningSummaryBody?.parent as? LinearLayout)?.getChildAt(0) as? TextView
         }else{
             activeReasoningSummaryBody?.text=clean
             activeReasoningSummaryToggle?.text=if(clean.isBlank())"🧠 Astra думает…" else "🧠 Показать ход рассуждений ▼"
         }
     }

     private fun extractReasoningSummary(response:JSONObject?):String{
         val output=response?.optJSONArray("output") ?: return ""
         val parts=StringBuilder()
         for(i in 0 until output.length()){
             val item=output.optJSONObject(i) ?: continue
             if(item.optString("type")!="reasoning")continue
             val summary=item.optJSONArray("summary")
             if(summary!=null){
                 for(j in 0 until summary.length()){
                     val s=summary.optJSONObject(j) ?: continue
                     if(s.optString("type")=="summary_text"){
                         val t=s.optString("text").trim()
                         if(t.isNotBlank()){
                             if(parts.isNotEmpty())parts.append("\n\n")
                             parts.append(t)
                         }
                     }
                 }
             }
         }
         return parts.toString()
     }

    private fun parseTools(output:JSONArray?, items:MutableList<String>, chatId:String?=null, generation:Long?=null) {
        if(output==null)return
        for(i in 0 until output.length()){
            val o=output.optJSONObject(i)?:continue
            when(o.optString("type")){
                "web_search_call" -> { items.add("🌐 Web Search"); updateToolState("web_search","completed",chatId,generation) }
                "file_search_call" -> { items.add("📚 File Search"); updateToolState("file_search","completed",chatId,generation) }
                "code_interpreter_call" -> { items.add("🐍 Code Interpreter"); updateToolState("code_interpreter","completed",chatId,generation) }
                "image_generation_call" -> { items.add("🎨 Image Generation"); updateToolState("image_generation","completed",chatId,generation) }
                "computer_call" -> { items.add("🖥 Computer Use"); updateToolState("computer","completed",chatId,generation) }
            }
        }
    }

    private fun extractAnnotations(output:JSONArray?): Pair<List<JSONObject>,List<JSONObject>> {
        val files=mutableListOf<JSONObject>()
        val urls=mutableListOf<JSONObject>()
        if(output==null) return files to urls
        for(i in 0 until output.length()){
            val item=output.optJSONObject(i) ?: continue
            if(item.optString("type")!="message") continue
            val contentArr=item.optJSONArray("content") ?: continue
            for(j in 0 until contentArr.length()){
                val c=contentArr.optJSONObject(j) ?: continue
                if(c.optString("type")!="output_text") continue
                val annotations=c.optJSONArray("annotations") ?: continue
                for(k in 0 until annotations.length()){
                    val raw=annotations.optJSONObject(k) ?: continue
                    val a=JSONObject(raw.toString())
                    when(a.optString("type")){
                        "container_file_citation", "file_citation" -> files.add(a)
                        "url_citation" -> urls.add(a)
                    }
                }
            }
        }
        return files to urls
    }

    private fun renderAnnotations(
        annotations:JSONArray,
        card:LinearLayout,
        message:JSONObject,
        chatId:String,
        generation:Long
    ){
        val files=mutableListOf<JSONObject>()
        val urls=mutableListOf<JSONObject>()
        for(i in 0 until annotations.length()){
            val a=annotations.optJSONObject(i) ?: continue
            when(a.optString("type")){
                "container_file_citation", "file_citation" -> files.add(a)
                "url_citation" -> urls.add(a)
            }
        }
        files.distinctBy{it.optString("container_id")+"/"+it.optString("file_id")}.forEach{a->
            addGeneratedFileCard(a,card,message,chatId,generation)
        }
        if(urls.isNotEmpty()){
            val header=TextView(this).apply{
                text="Источники"
                textSize=12f
                setTypeface(null,Typeface.BOLD)
                setTextColor(Color.rgb(180,185,198))
                setPadding(0,dp(9),0,dp(2))
            }
            card.addView(header)
            urls.distinctBy{it.optString("url")}.take(10).forEach{u->
                val raw=u.optJSONObject("url_citation") ?: u
                val url=raw.optString("url").trim()
                if(url.isBlank()) return@forEach
                val sourceTitle=raw.optString("title").ifBlank{url}
                card.addView(TextView(this).apply{
                    text="🔗 $sourceTitle"
                    setTextColor(Color.rgb(138,180,248)); textSize=12.5f
                    isSingleLine=true; ellipsize=TextUtils.TruncateAt.END
                    setPadding(0,dp(2),0,dp(2))
                    setOnClickListener{
                        try{
                            val intent=Intent(Intent.ACTION_VIEW,Uri.parse(url)).apply{
                                addCategory(Intent.CATEGORY_BROWSABLE)
                            }
                            startActivity(intent)
                        }catch(_:Exception){status.text="Не удалось открыть ссылку"}
                    }
                })
            }
        }
    }

    private fun findLegacyGeneratedCache(filename:String):String?{
        val target=safeFileName(filename)
        val dir=File(filesDir,"generated")
        if(!dir.isDirectory)return null
        return dir.listFiles()?.asSequence()
            ?.filter{it.isFile && (it.name==target || it.name.endsWith("_$target"))}
            ?.maxByOrNull{it.lastModified()}
            ?.absolutePath
    }

    private fun addGeneratedFileCard(
        annotation:JSONObject,
        parent:LinearLayout,
        message:JSONObject,
        chatId:String,
        generation:Long
    ){
        val annotationType = annotation.optString("type").trim()
        val containerId=annotation.optString("container_id").trim()
        val fileId=annotation.optString("file_id").trim()
        val filename=safeDisplayName(annotation.optString("filename").ifBlank{"file"})
        val state=annotation.optString("state").ifBlank{"PENDING"}
        var path=annotation.optString("local_path").takeIf{it.isNotBlank()}
        // A successful download may have survived an app update while the annotation
        // itself was migrated with a stale EXPIRED/PENDING state. Always prefer a valid local copy.
        if(fileId.isNotBlank()){
            val directCachedPath=path?.takeIf{File(it).isFile}
            val cached=if(directCachedPath==null) try{chatDb.findLocalFileByFileId(fileId)}catch(_:Exception){null} else null
            val dbCachedPath=cached?.optString("path").orEmpty().takeIf{it.isNotBlank() && File(it).isFile}
            val legacyCachedPath=if(directCachedPath==null && dbCachedPath==null) findLegacyGeneratedCache(filename) else null
            val cachedPath=directCachedPath ?: dbCachedPath ?: legacyCachedPath
            if(cachedPath!=null && (state!="READY" || path!=cachedPath)){
                path=cachedPath
                annotation.put("state","READY").put("local_path",cachedPath)
                cached?.optString("mime_type")?.takeIf{it.isNotBlank()}?.let{annotation.put("mime_type",it)}
                val cachedSize=if(cached!=null) cached.optLong("size_bytes",File(cachedPath).length()) else File(cachedPath).length()
                annotation.put("size_bytes",cachedSize)
                chatDb.updateGeneratedFileState(chatId,fileId,"READY",cachedPath,annotation.optString("mime_type").takeIf{it.isNotBlank()},cachedSize,annotationType=annotationType)
                diagnostics.log("file_cache_restored",mapOf("chat_id" to chatId,"file_id" to fileId,"path" to cachedPath))
            }
        }
        if(path!=null && File(path).isFile && annotation.optString("state").ifBlank{"PENDING"}=="READY"){
            val box=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(8),dp(10),dp(8))
                background=rounded(Color.rgb(38,40,46),10)
            }
            parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            populateReadyFileCard(box,annotation,filename,path)
            return
        }

        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(10),dp(8),dp(10),dp(8))
            background=rounded(Color.rgb(38,40,46),10)
        }
        parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
        renderFileCardState(box,annotation,message,chatId,generation,filename)

        val canDownload = fileId.isNotBlank() && (containerId.isNotBlank() || annotationType == "file_citation")
        val shouldAutoDownload = canDownload && annotationType == "container_file_citation" && state !in setOf("FAILED","EXPIRED")
        if(shouldAutoDownload) {
            downloadCitedFile(annotation,box,message,chatId,generation)
        } else if(annotationType == "file_citation" && state == "PENDING" && canDownload) {
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(fileActionButton("Скачать"){
                annotation.put("state","DOWNLOADING").remove("error")
                persistCurrentChatFor(chatId)
                renderFileCardState(box,annotation,message,chatId,generation,filename)
                downloadCitedFile(annotation,box,message,chatId,generation)
            })
            box.addView(row)
        } else if(state=="FAILED") {
            addFileRetryButton(box,annotation,message,chatId,generation,filename)
        }
    }

    private fun renderFileCardState(
        box:LinearLayout,
        annotation:JSONObject,
        message:JSONObject,
        chatId:String,
        generation:Long,
        filename:String
    ){
        box.removeAllViews()
        val state=annotation.optString("state").ifBlank{"DOWNLOADING"}
        val size=annotation.optLong("size_bytes",0L)
        val mime=annotation.optString("mime_type").ifBlank{mimeForFilename(filename)}
        val icon=when(state){"READY"->"✅";"FAILED"->"⚠️";"EXPIRED"->"⌛";else->"📄"}
        val titleRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL}
        val titleView=TextView(this).apply{
            text="$icon $filename"
            textSize=14f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD)
        }
        titleRow.addView(titleView,LinearLayout.LayoutParams(0,-2,1f))
        box.addView(titleRow)
        val details=TextView(this).apply{
            text=when(state){
                "READY" -> "$mime · ${formatBytes(size)}"
                "FAILED" -> "Ошибка: ${annotation.optString("error").ifBlank{"неизвестная ошибка"}.take(180)}"
                "EXPIRED" -> if(annotation.optString("type") == "file_citation") "Исходный файл больше недоступен" else "Файл контейнера больше недоступен"
                "PENDING" -> if(annotation.optString("type") == "file_citation") "Файл найден в хранилище · нажмите «Скачать»" else "Подготовка…"
                else -> "Скачивание…"
            }
            textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(3),0,dp(4))
        }
        box.addView(details)
    }

    private fun populateReadyFileCard(box:LinearLayout,annotation:JSONObject,filename:String,path:String){
        box.removeAllViews()
        val mime=annotation.optString("mime_type").ifBlank{mimeForFilename(filename)}
        val size=annotation.optLong("size_bytes",File(path).length())
        if(mime.startsWith("image/")){
            val preview=ImageView(this).apply{
                adjustViewBounds=true
                scaleType=ImageView.ScaleType.FIT_CENTER
                background=rounded(Color.rgb(24,26,30),8)
                setOnClickListener{showImagePreviewFromFile(path,filename)}
            }
            loadThumbnailAsync(preview,path,900)
            box.addView(preview,LinearLayout.LayoutParams(-1,dp(220)))
        }else if(isTextLikeMime(mime,filename)){
            box.addView(TextView(this).apply{
                text="📄 Текстовый файл — нажмите «Просмотр», чтобы прочитать"
                textSize=12.5f;setTextColor(Color.rgb(180,185,198));setPadding(0,dp(4),0,dp(5))
            })
        }else if(mime.equals("application/pdf",true)){
            box.addView(TextView(this).apply{
                text="📕 PDF — можно открыть первый лист внутри Astra"
                textSize=12.5f;setTextColor(Color.rgb(180,185,198));setPadding(0,dp(4),0,dp(5))
            })
        }
        box.addView(TextView(this).apply{
            text="✅ $filename";textSize=14f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD)
        })
        box.addView(TextView(this).apply{
            text="$mime · ${formatBytes(size)}";textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(3),0,dp(6))
        })
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        when{
            mime.startsWith("image/") -> row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,filename)})
            isTextLikeMime(mime,filename) -> row.addView(fileActionButton("Просмотр"){showTextPreview(path,filename)})
            mime.equals("application/pdf",true) -> row.addView(fileActionButton("Просмотр"){showPdfPreview(path,filename)})
            else -> row.addView(fileActionButton("Открыть"){openLocalFile(path,filename,mime)})
        }
        row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,filename,mime)})
        row.addView(fileActionButton("Поделиться"){shareLocalFile(path,filename,mime)})
        box.addView(row)
    }

    private fun addFileRetryButton(box:LinearLayout,annotation:JSONObject,message:JSONObject,chatId:String,generation:Long,filename:String){
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        row.addView(fileActionButton("Повторить"){
            annotation.put("state","DOWNLOADING").remove("error")
            persistCurrentChatFor(chatId)
            renderFileCardState(box,annotation,message,chatId,generation,filename)
            downloadCitedFile(annotation,box,message,chatId,generation)
        })
        box.addView(row)
    }


    private fun fileActionButton(label:String,onClick:()->Unit)=TextView(this).apply{
        text=label;textSize=12.5f;setTextColor(Color.rgb(138,180,248))
        setPadding(dp(6),dp(6),dp(12),dp(6))
        setOnClickListener{onClick()}
    }

    private fun downloadCitedFile(annotation:JSONObject,box:LinearLayout,message:JSONObject,chatId:String,generation:Long){
        val annotationType = annotation.optString("type").trim()
        val containerId=annotation.optString("container_id").trim()
        val fileId=annotation.optString("file_id").trim()
        val filename=safeDisplayName(annotation.optString("filename").ifBlank{"file"})
        if(fileId.isBlank() || (containerId.isBlank() && annotationType != "file_citation")) return
        annotation.put("state","DOWNLOADING")
        chatDb.updateGeneratedFileState(chatId,fileId,"DOWNLOADING", annotationType=annotationType)
        if(currentChatId()==chatId) persistCurrentChatFor(chatId)
        renderFileCardState(box,annotation,message,chatId,generation,filename)
        diagnostics.log("file_download_start",mapOf("chat_id" to chatId,"type" to annotationType,"container_id" to containerId,"file_id" to fileId,"filename" to filename))
        val key=secure.getApiKey()
        executor.execute{
            try{
                val contentUrl = if (annotationType == "file_citation") {
                    "https://api.openai.com/v1/files/$fileId/content"
                } else {
                    "https://api.openai.com/v1/containers/$containerId/files/$fileId/content"
                }
                val req=Request.Builder()
                    .url(contentUrl)
                    .addHeader("Authorization","Bearer $key").get().build()
                client.newCall(req).execute().use{resp->
                    if(!resp.isSuccessful) throw FileDownloadException(resp.code)
                    val body=resp.body ?: throw IOException("Пустой ответ")
                    val expected=body.contentLength()
                    val maxBytes=512L*1024L*1024L
                    if(expected>maxBytes) throw IOException("Файл слишком большой для мобильного клиента (>${formatBytes(maxBytes)})")
                    val dir=File(filesDir,"generated").apply{mkdirs()}
                    val destination=File(dir,"${System.currentTimeMillis()}_${safeFileName(filename)}")
                    body.byteStream().use{input->destination.outputStream().use{output->
                        val buffer=ByteArray(64*1024)
                        var total=0L
                        while(true){
                            val n=input.read(buffer)
                            if(n<0) break
                            total+=n
                            if(total>maxBytes){destination.delete();throw IOException("Файл слишком большой для мобильного клиента (>${formatBytes(maxBytes)})")}
                            output.write(buffer,0,n)
                        }
                    }}
                    val mime=resp.header("Content-Type")?.substringBefore(';')?.trim().takeIf{!it.isNullOrBlank()} ?: mimeForFilename(filename)
                    chatDb.updateGeneratedFileState(chatId,fileId,"READY",destination.absolutePath,mime,destination.length(), annotationType=annotationType)
                    runOnUiThread{
                        if(!isRequestCurrent(chatId,generation)){ return@runOnUiThread }
                        annotation.put("state","READY")
                            .put("local_path",destination.absolutePath)
                            .put("mime_type",mime)
                            .put("size_bytes",destination.length())
                        persistCurrentChatFor(chatId)
                        populateReadyFileCard(box,annotation,filename,destination.absolutePath)
                        diagnostics.log("file_download_ready",mapOf("container_id" to containerId,"file_id" to fileId,"bytes" to destination.length()))
                    }
                }
            }catch(e:FileDownloadException){
                val expired=e.code==404 || e.code==410
                chatDb.updateGeneratedFileState(chatId,fileId,if(expired)"EXPIRED" else "FAILED",error="HTTP ${e.code}", annotationType=annotationType)
                runOnUiThread{
                    if(!isRequestCurrent(chatId,generation))return@runOnUiThread
                    annotation.put("state",if(expired)"EXPIRED" else "FAILED").put("error","HTTP ${e.code}")
                    persistCurrentChatFor(chatId)
                    renderFileCardState(box,annotation,message,chatId,generation,filename)
                    if(!expired) addFileRetryButton(box,annotation,message,chatId,generation,filename)
                    diagnostics.log("file_download_failed",mapOf("container_id" to containerId,"file_id" to fileId,"status" to e.code))
                }
            }catch(e:Exception){
                val friendly=friendlyApiError(e.message)
                chatDb.updateGeneratedFileState(chatId,fileId,"FAILED",error=friendly, annotationType=annotationType)
                runOnUiThread{
                    if(!isRequestCurrent(chatId,generation))return@runOnUiThread
                    annotation.put("state","FAILED").put("error",friendly)
                    persistCurrentChatFor(chatId)
                    renderFileCardState(box,annotation,message,chatId,generation,filename)
                    addFileRetryButton(box,annotation,message,chatId,generation,filename)
                    diagnostics.log("file_download_failed",mapOf("container_id" to containerId,"file_id" to fileId,"error" to (e.message ?: e.toString()).take(300)))
                }
            }
        }
    }

    private class FileDownloadException(val code:Int):IOException("HTTP $code")

    private fun saveGeneratedImages(results:List<String>):JSONArray {
        val out=JSONArray()
        if(results.isEmpty()) return out
        val maxEncodedBytes = 72L*1024L*1024L
        val maxDecodedBytes = 50L*1024L*1024L
        val dir=File(filesDir,"generated_images").apply{mkdirs()}
        results.take(4).forEachIndexed { index, encoded ->
            try{
                if(encoded.length.toLong() > maxEncodedBytes) throw IOException("Изображение Astra слишком большое")
                val bytes=Base64.decode(encoded,Base64.DEFAULT)
                if(bytes.size.toLong()>maxDecodedBytes) throw IOException("Изображение Astra слишком большое")
                if(bytes.size<8 || (bytes[0].toInt() and 0xFF)!=0x89 || (bytes[1].toInt() and 0xFF)!=0x50 || (bytes[2].toInt() and 0xFF)!=0x4E || (bytes[3].toInt() and 0xFF)!=0x47) throw IOException("Ожидался PNG")
                val file=File(dir,"astra_${System.currentTimeMillis()}_${System.nanoTime()}_$index.png")
                file.outputStream().use{it.write(bytes)}
                out.put(JSONObject().put("filename",file.name).put("local_path",file.absolutePath).put("mime_type","image/png").put("size_bytes",file.length()).put("state","READY"))
            }catch(e:Exception){
                diagnostics.log("generated_image_failed",mapOf("error" to (e.message ?: e.toString()).take(300)))
            }
        }
        return out
    }

    private fun renderGeneratedImages(message:JSONObject,parent:LinearLayout){
        val arr=message.optJSONArray("generated_images") ?: return
        for(i in 0 until arr.length()){
            val a=arr.optJSONObject(i) ?: continue
            val path=a.optString("local_path").takeIf{it.isNotBlank()}
            val filename=safeDisplayName(a.optString("filename").ifBlank{"astra_image.png"})
            val mime=a.optString("mime_type").ifBlank{"image/png"}
            if(path==null || !File(path).isFile) continue
            val box=LinearLayout(this).apply{
                orientation=LinearLayout.VERTICAL
                setPadding(dp(10),dp(8),dp(10),dp(8))
                background=rounded(Color.rgb(38,40,46),10)
            }
            parent.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            box.addView(ImageView(this).apply{
                loadThumbnailAsync(this,path,1200)
                adjustViewBounds=true
                scaleType=ImageView.ScaleType.FIT_CENTER
                setPadding(0,0,0,dp(6))
                setOnClickListener{showImagePreviewFromFile(path,filename)}
            },LinearLayout.LayoutParams(-1,dp(280)))
            box.addView(TextView(this).apply{
                text="🎨 $filename · ${formatBytes(File(path).length())}"
                textSize=12f;setTextColor(Color.rgb(165,170,180))
            })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,filename)})
            row.addView(fileActionButton("Открыть"){openLocalFile(path,filename,mime)})
            row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,filename,mime)})
            row.addView(fileActionButton("Поделиться"){shareLocalFile(path,filename,mime)})
            box.addView(row)
        }
    }

    private fun openLocalFile(path:String,filename:String,mime:String){
        try{
            val uri=FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.fileprovider",File(path))
            val intent=Intent(Intent.ACTION_VIEW).apply{
                setDataAndType(uri,mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,"Открыть $filename"))
        }catch(e:Exception){status.text="Нет приложения для открытия ${filename}: ${e.message ?: "неизвестная ошибка"}"}
    }

    private fun requestSaveLocalFile(path:String,filename:String,mime:String){
        pendingSaveSourcePath=path
        pendingSaveFilename=filename
        pendingSaveMime=mime
        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
            addCategory(Intent.CATEGORY_OPENABLE)
            type=mime
            putExtra(Intent.EXTRA_TITLE,filename)
        }
        try{startActivityForResult(i,pickSaveGenerated)}catch(e:Exception){status.text="Сохранение недоступно: ${e.message}"}
    }

    private fun shareLocalFile(path:String,filename:String,mime:String){
        try{
            val uri=FileProvider.getUriForFile(this,"${BuildConfig.APPLICATION_ID}.fileprovider",File(path))
            val intent=Intent(Intent.ACTION_SEND).apply{
                type=mime
                putExtra(Intent.EXTRA_STREAM,uri)
                putExtra(Intent.EXTRA_TEXT,filename)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent,"Поделиться $filename"))
        }catch(e:Exception){status.text="Не удалось поделиться файлом: ${e.message}"}
    }

    private fun safeFileName(name:String):String{
        val cleaned=name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]+"),"_").trim().ifBlank{"file"}
        return cleaned.take(180)
    }
    private fun safeDisplayName(name:String):String=safeFileName(name)
    private fun mimeForFilename(name:String):String{
        val ext=name.substringAfterLast('.').lowercase()
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when(ext){
            "json"->"application/json"; "csv"->"text/csv"; "md"->"text/markdown"; "txt"->"text/plain"; else->"application/octet-stream"
        }
    }
    private fun formatBytes(size:Long):String = when{
        size>=1024L*1024*1024->"%.1f GB".format(size/1024.0/1024.0/1024.0)
        size>=1024L*1024->"%.1f MB".format(size/1024.0/1024.0)
        size>=1024L->"%.1f KB".format(size/1024.0)
        else->"$size B"
    }

    private fun persistCurrentChatFor(chatId:String){
        if(currentChatId()!=chatId)return
        persistCurrentChat()
    }

    private fun showToolDetails() {
        if(toolLabel.text.isNullOrBlank()) return
        AlertDialog.Builder(this)
            .setTitle("Инструменты")
            .setMessage(toolLabel.text)
            .setPositiveButton("OK",null).show()
    }


    private fun showUsageStats(){
        executor.execute{
            try{
                val now=System.currentTimeMillis()
                val cal=java.util.Calendar.getInstance().apply{
                    set(java.util.Calendar.HOUR_OF_DAY,0); set(java.util.Calendar.MINUTE,0); set(java.util.Calendar.SECOND,0); set(java.util.Calendar.MILLISECOND,0)
                }
                val today=chatDb.usageTotals(cal.timeInMillis)
                val seven=chatDb.usageTotals(now-7L*86_400_000L)
                val all=chatDb.usageTotals(0L)
                val message=formatUsageStats(today,seven,all)
                runOnUiThread{
                    AlertDialog.Builder(this)
                        .setTitle("Расход и токены")
                        .setMessage(message)
                        .setPositiveButton("OK",null)
                        .show()
                }
            }catch(e:Exception){
                runOnUiThread{status.text="Не удалось загрузить статистику: ${e.message}"}
            }
        }
    }

    private fun formatUsageStats(today:JSONObject,seven:JSONObject,all:JSONObject):String{
        fun block(title:String,o:JSONObject):String{
            val requests=o.optLong("requests")
            val success=o.optLong("success")
            val input=o.optLong("input_tokens")
            val cached=o.optLong("cached_tokens")
            val writes=o.optLong("cache_write_tokens")
            val output=o.optLong("output_tokens")
            val reasoning=o.optLong("reasoning_tokens")
            val cost=o.optDouble("estimated_cost_usd",0.0)
            return buildString{
                append("$title\\n")
                append("Запросов: $requests (успешных $success)\\n")
                append("Input: ${fmtTokens(input)} · cache: ${fmtTokens(cached)}\\n")
                append("Cache write: ${fmtTokens(writes)}\\n")
                append("Output: ${fmtTokens(output)} · reasoning: ${fmtTokens(reasoning)}\\n")
                append("Оценка стоимости: $%.4f\\n".format(cost))
            }
        }
        return block("Сегодня",today)+"\\n"+block("Последние 7 дней",seven)+"\\n"+block("За всё время",all)+
            "\\nСтоимость приблизительная: учитывает текстовые token rates Astra и cache, но не отдельные tool-call сборы."
    }

    private fun showLocalFiles(){
        executor.execute{
            val files=try{chatDb.listLocalFiles()}catch(_:Exception){emptyList()}
            runOnUiThread{
                val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(14),dp(8),dp(14),dp(10))}
                if(files.isEmpty()){
                    box.addView(TextView(this).apply{ text="Пока нет локально сохранённых файлов Astra.";textSize=14f;setTextColor(Color.LTGRAY);setPadding(0,dp(10),0,dp(10))})
                }else{
                    files.forEach{f->
                        val row=LinearLayout(this).apply{
                            orientation=LinearLayout.VERTICAL
                            setPadding(0,dp(8),0,dp(8))
                        }
                        val name=TextView(this).apply{
                            text=(if(f.optString("kind")=="image")"🖼 " else "📄 ")+safeDisplayName(f.optString("filename"))
                            textSize=14f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD)
                        }
                        row.addView(name)
                        row.addView(TextView(this).apply{
                            text="${f.optString("chat_title","Чат")} · ${formatBytes(f.optLong("size_bytes",0L))}"
                            textSize=11.5f;setTextColor(Color.rgb(165,170,180))
                        })
                        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
                        val path=f.optString("path"); val fn=safeDisplayName(f.optString("filename")); val mime=f.optString("mime_type").ifBlank{mimeForFilename(fn)}
                        when{
                            mime.startsWith("image/") -> actions.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,fn)})
                            isTextLikeMime(mime,fn) -> actions.addView(fileActionButton("Просмотр"){showTextPreview(path,fn)})
                            mime.equals("application/pdf",true) -> actions.addView(fileActionButton("Просмотр"){showPdfPreview(path,fn)})
                            else -> actions.addView(fileActionButton("Открыть"){openLocalFile(path,fn,mime)})
                        }
                        actions.addView(fileActionButton("Сохранить"){requestSaveLocalFile(path,fn,mime)})
                        actions.addView(fileActionButton("Поделиться"){shareLocalFile(path,fn,mime)})
                        actions.addView(fileActionButton("Удалить"){
                            AlertDialog.Builder(this)
                                .setTitle("Удалить локальную копию?")
                                .setMessage(fn)
                                .setNegativeButton("Отмена",null)
                                .setPositiveButton("Удалить"){_,_->
                                    executor.execute{
                                        val ok=File(path).delete()
                                        chatDb.removeLocalFile(path)
                                        runOnUiThread{
                                            status.text=if(ok)"Локальная копия удалена" else "Запись удалена, но файл уже отсутствовал"
                                            showLocalFiles()
                                        }
                                    }
                                }.show()
                        })
                        row.addView(actions)
                        box.addView(row)
                        box.addView(View(this).apply{setBackgroundColor(Color.rgb(52,54,60));layoutParams=LinearLayout.LayoutParams(-1,dp(1))})
                    }
                }
                AlertDialog.Builder(this)
                    .setTitle("Локальные файлы Astra")
                    .setView(ScrollView(this).apply{addView(box)})
                    .setNegativeButton("Закрыть",null)
                    .show()
            }
        }
    }

    // ---------- attachments ----------
    private fun chooseAny() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="*/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
        }
        startActivityForResult(i,pickAny)
    }
    private fun chooseImage() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{
            type="image/*"; addCategory(Intent.CATEGORY_OPENABLE)
        }
        startActivityForResult(i,pickImage)
    }
    private fun chooseVoice() {
        val i=Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply{
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите…")
        }
        if(i.resolveActivity(packageManager)!=null){
            try{ startActivityForResult(i,pickVoice) }
            catch(e:Exception){ status.text="Голосовой ввод недоступен: ${e.message}" }
        } else {
            status.text="На устройстве не найден сервис распознавания речи"
        }
    }
    // ---------- vector store management ----------
    private fun vectorStoreId():String? = prefs.getString("vector_store","")?.trim()?.takeIf{it.isNotBlank()}

    private fun showVectorStoreManager(){
        val container=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(10),dp(20),dp(10))}
        val statusLine=TextView(this).apply{this.text="Загрузка…";setTextColor(Color.GRAY);textSize=12f}
        val listArea=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        container.addView(statusLine)
        container.addView(listArea)
        vsListArea=listArea
        vsStatusLine=statusLine

        val dialog=AlertDialog.Builder(this)
            .setTitle("Файлы Vector Store")
            .setView(ScrollView(this).apply{addView(container)})
            .setPositiveButton("+ Добавить файл",null)
            .setNegativeButton("Закрыть",null)
            .create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val existing=vectorStoreId()
                if(existing==null){
                    statusLine.text="Создаю Vector Store…"
                    executor.execute{
                        try{
                            val newId=createVectorStore("Astra Files")
                            prefs.edit().putString("vector_store",newId).apply()
                            runOnUiThread{ pickFileForVectorStore() }
                        }catch(e:Exception){ runOnUiThread{statusLine.text="Ошибка создания: ${e.message}"} }
                    }
                } else pickFileForVectorStore()
            }
        }
        dialog.show()
        val vsId=vectorStoreId()
        if(vsId==null) statusLine.text="Vector Store ещё не создан — нажмите «Добавить файл»"
        else renderVectorStoreList(vsId)
    }

    private fun renderVectorStoreList(vsId:String){
        vsStatusLine?.text="Загрузка списка файлов…"
        executor.execute{
            try{
                val files=listVectorStoreFiles(vsId)
                runOnUiThread{
                    val area=vsListArea ?: return@runOnUiThread
                    area.removeAllViews()
                    if(files.isEmpty()){
                        area.addView(TextView(this).apply{this.text="Файлов пока нет";setTextColor(Color.GRAY);textSize=13f})
                    }
                    files.forEach{(fid,name)->
                        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,dp(6))}
                        row.addView(TextView(this).apply{this.text=name;textSize=14f;setTextColor(Color.WHITE)}, LinearLayout.LayoutParams(0,-2,1f))
                        row.addView(TextView(this).apply{
                            this.text="✕";setTextColor(Color.rgb(210,120,120));setPadding(dp(12),dp(6),dp(6),dp(6))
                            setOnClickListener{
                                executor.execute{
                                    try{ removeFileFromVectorStore(vsId,fid) }catch(_:Exception){}
                                    renderVectorStoreList(vsId)
                                }
                            }
                        })
                        area.addView(row)
                    }
                    vsStatusLine?.text="Vector Store: $vsId"
                }
            }catch(e:Exception){
                runOnUiThread{ vsStatusLine?.text="Ошибка загрузки: ${e.message}" }
            }
        }
    }

    private fun pickFileForVectorStore(){
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply{addCategory(Intent.CATEGORY_OPENABLE);type="*/*"}
        startActivityForResult(i,pickVectorStoreFile)
    }

    private fun listVectorStoreFiles(vsId:String):List<Pair<String,String>>{
        val key=secure.getApiKey()
        val req=Request.Builder().url("https://api.openai.com/v1/vector_stores/$vsId/files?limit=100")
            .addHeader("Authorization","Bearer $key").get().build()
        client.newCall(req).execute().use{resp->
            val raw=resp.body?.string().orEmpty()
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: $raw")
            val arr=JSONObject(raw).optJSONArray("data") ?: JSONArray()
            val out=mutableListOf<Pair<String,String>>()
            for(i in 0 until arr.length()){
                val fid=arr.optJSONObject(i)?.optString("id") ?: continue
                val name=try{ fetchFileName(fid) }catch(_:Exception){ fid }
                out.add(fid to name)
            }
            return out
        }
    }
    private fun fetchFileName(fileId:String):String{
        val key=secure.getApiKey()
        val req=Request.Builder().url("https://api.openai.com/v1/files/$fileId")
            .addHeader("Authorization","Bearer $key").get().build()
        client.newCall(req).execute().use{resp->
            val raw=resp.body?.string().orEmpty()
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}")
            return JSONObject(raw).optString("filename",fileId)
        }
    }
    private fun createVectorStore(name:String):String{
        val key=secure.getApiKey()
        val reqBody=JSONObject().put("name",name).toString().toRequestBody("application/json".toMediaType())
        val req=Request.Builder().url("https://api.openai.com/v1/vector_stores")
            .addHeader("Authorization","Bearer $key").post(reqBody).build()
        client.newCall(req).execute().use{resp->
            val raw=resp.body?.string().orEmpty()
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: $raw")
            return JSONObject(raw).getString("id")
        }
    }
    private fun attachFileToVectorStore(vsId:String, fileId:String){
        val key=secure.getApiKey()
        val reqBody=JSONObject().put("file_id",fileId).toString().toRequestBody("application/json".toMediaType())
        val req=Request.Builder().url("https://api.openai.com/v1/vector_stores/$vsId/files")
            .addHeader("Authorization","Bearer $key").post(reqBody).build()
        client.newCall(req).execute().use{resp->
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: ${resp.body?.string()}")
        }
    }
    private fun removeFileFromVectorStore(vsId:String, fileId:String){
        val key=secure.getApiKey()
        val req=Request.Builder().url("https://api.openai.com/v1/vector_stores/$vsId/files/$fileId")
            .addHeader("Authorization","Bearer $key").delete().build()
        client.newCall(req).execute().use{resp->
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: ${resp.body?.string()}")
        }
    }
    private fun uploadFileForVectorStore(uri:Uri):Pair<String,String>{
        val key=secure.getApiKey()
        val temp=File(cacheDir,"upload_${System.nanoTime()}")
        val input=contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
        input.use{src->temp.outputStream().use{out->src.copyTo(out,64*1024)}}
        val mime=contentResolver.getType(uri) ?: "application/octet-stream"
        val name=queryName(uri) ?: "attachment"
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose","assistants")
            .addFormDataPart("file",name,temp.asRequestBody(mime.toMediaType()))
            .build()
        val req=Request.Builder().url("https://api.openai.com/v1/files").addHeader("Authorization","Bearer $key").post(body).build()
        try{
            client.newCall(req).execute().use{resp->
                val raw=resp.body?.string().orEmpty()
                if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: $raw")
                return JSONObject(raw).getString("id") to name
            }
        }finally{temp.delete()}
    }

    override fun onActivityResult(r:Int,c:Int,d:Intent?){
        super.onActivityResult(r,c,d)
        if(c!=RESULT_OK || d==null)return
        if(r==pickExportTxt){
            val uri=d.data ?: return
            val bytes=pendingExportBytes
            if(bytes==null){ status.text="Нечего сохранять"; return }
            try{
                val out=contentResolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл для записи")
                out.use{it.write(bytes)}
                status.text="Сохранено в файл"
            }catch(e:Exception){ status.text="Ошибка экспорта: ${e.message}" }
            pendingExportBytes=null
            return
        }
        if(r==pickSaveGenerated){
            val uri=d.data ?: return
            val source=pendingSaveSourcePath
            if(source.isNullOrBlank()){ status.text="Файл больше недоступен"; return }
            try{
                val sourceFile=File(source)
                if(!sourceFile.isFile) throw IOException("Локальная копия файла не найдена")
                val out=contentResolver.openOutputStream(uri) ?: throw IOException("Не удалось открыть файл для записи")
                sourceFile.inputStream().use{input->out.use{output->input.copyTo(output,64*1024)}}
                status.text="Сохранено в файл"
            }catch(e:Exception){status.text="Ошибка сохранения: ${e.message}"}
            pendingSaveSourcePath=null
            pendingSaveFilename=null
            pendingSaveMime=null
            return
        }
        if(r==pickVoice){
            val results=d.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val heard=results?.firstOrNull()
            if(!heard.isNullOrBlank()){
                val current=input.text.toString()
                input.setText(if(current.isBlank()) heard else "$current $heard")
                input.setSelection(input.text.length)
            }
            return
        }
        if(r==pickVectorStoreFile){
            val uri=d.data ?: return
            val vsId=vectorStoreId()
            if(vsId==null){ vsStatusLine?.text="Сначала создайте Vector Store"; return }
            vsStatusLine?.text="Загружаю файл…"
            executor.execute{
                try{
                    val (fileId,_)=uploadFileForVectorStore(uri)
                    attachFileToVectorStore(vsId,fileId)
                    runOnUiThread{ renderVectorStoreList(vsId) }
                }catch(e:Exception){
                    runOnUiThread{ vsStatusLine?.text="Ошибка: ${e.message}" }
                }
            }
            return
        }
        if(r==pickImage){
            val uri=d.data ?: return
            val uploadChatId=currentChatId()
            val key=attachmentKey(uri,"image")
            rememberUploadUri(uri,"image")
            refreshAttachChips()
            startPendingPreview(uri,"image")
            beginUpload()
            executor.execute{
                try{
                    reportUploadProgress(key,"Подготовка изображения",0L,100L)
                    val path=copyImageToLocal(uri){done,total->reportUploadProgress(key,"Подготовка",(done*45L/total.coerceAtLeast(1L)),100L)} ?: throw IOException("Не удалось сохранить изображение")
                    pendingLocalUploadPaths[key]=path
                    val name=queryName(uri) ?: "image.${File(path).extension}"
                    reportUploadProgress(key,"Загрузка",50L,100L)
                    val fileId=uploadImageFile(path,name,key){done,total->reportUploadProgress(key,"Загрузка",50L + (done*50L/total.coerceAtLeast(1L)),100L)}
                    runOnUiThread{
                        if(cancelledUploads.contains(key)) return@runOnUiThread
                        if(currentChatId()==uploadChatId){
                            pendingImagePath=path
                            pendingImageFileId=fileId
                            pendingImageName=name
                            pendingImageMime=contentResolver.getType(uri).orEmpty().ifBlank{mimeForFilename(name)}
                            pendingImageSize=File(path).length()
                            forgetUploadUri(uri,"image")
                            refreshAttachChips()
                            persistComposerStateNow()
                            status.text="Изображение готово к отправке"
                        }else{
                            saveUploadedImageToComposer(uploadChatId,path,fileId,uri.toString(),name,pendingImageMime ?: mimeForFilename(name),File(path).length())
                            status.text="Изображение загружено — оно сохранено в исходном чате"
                        }
                    }
                }catch(e:Exception){
                    if(!cancelledUploads.contains(key))runOnUiThread{status.text="Ошибка изображения: ${e.message}"}
                }finally{ endUpload(); uploadProgress.remove(key); pendingPreviewBitmaps.remove(key); pendingLocalUploadPaths.remove(key) }
            }
            return
        }

        val uris=mutableListOf<Uri>()
        d.data?.let{uris.add(it)}
        d.clipData?.let{clip->for(i in 0 until clip.itemCount)uris.add(clip.getItemAt(i).uri)}
        uris.distinct().forEach{uri->
            val name=queryName(uri) ?: "attachment"
            val ext=name.substringAfterLast('.',"").lowercase()
            val uploadChatId=currentChatId()
            val kind=if(ext=="zip")"zip" else "file"
            if(ext=="zip"){
                rememberUploadUri(uri,"zip")
                refreshAttachChips()
                status.text="ZIP добавлен. Он будет распакован при отправке."
                return@forEach
            }
            if(ext.isBlank() || ext !in supportedFileExtensions){
                runOnUiThread{status.text="Формат .$ext не поддерживается для чтения файла: $name"}
                return@forEach
            }
            val key=attachmentKey(uri,kind)
            rememberUploadUri(uri,"file")
            refreshAttachChips()
            if(contentResolver.getType(uri).orEmpty().startsWith("image/")) startPendingPreview(uri,"file")
            beginUpload()
            executor.execute{
                try{
                    val uploaded=uploadFile(uri,key,{done,total->reportUploadProgress(key,"Загрузка",done,total)}){localPath,localMime,localSize->
                        runOnUiThread{
                            if(!cancelledUploads.contains(key)){
                                pendingLocalUploadPaths[key]=localPath
                                showPendingLocalPreview(key,localPath,name,localMime)
                            }
                        }
                    }
                    runOnUiThread{
                        if(cancelledUploads.contains(key)) return@runOnUiThread
                        if(currentChatId()==uploadChatId){
                            attachedFileIds.add(uploaded.id)
                            attachedFileNames.add(uploaded.name)
                            attachedFileSizes.add(uploaded.size)
                            attachedFilePaths.add(uploaded.path)
                            attachedFileMimes.add(uploaded.mime)
                            forgetUploadUri(uri,"file")
                            refreshAttachChips()
                            persistComposerStateNow()
                            status.text="Вложение готово: ${uploaded.name}"
                        }else{
                            saveUploadedFileToComposer(uploadChatId,uploaded.id,uploaded.name,uploaded.size,uri.toString(),uploaded.path,uploaded.mime)
                            status.text="Вложение загружено — оно сохранено в исходном чате"
                        }
                    }
                }catch(e:Exception){if(!cancelledUploads.contains(key))runOnUiThread{status.text="Ошибка файла: ${e.message}"}}
                finally{ endUpload(); uploadProgress.remove(key); pendingLocalUploadPaths.remove(key) }
            }
        }
    }

    private val supportedFileExtensions = setOf(
        "art","bat","brf","c","cls","css","csv","diff","doc","docx","dot","eml","epub","es",
        "h","hs","htm","html","hwp","hwpx","ics","ifb","java","js","json","keynote","ksh",
        "ltx","mail","markdown","md","mht","mhtml","mjs","msg","nws","odp","ods","odt","otp",
        "ots","pages","patch","pdf","pl","pm","pot","potm","potx","ppa","pps","ppsm","ppsx",
        "ppt","pptm","pptx","pwz","py","rst","rtf","scala","sh","shtml","srt","sty","svg",
        "svgz","tex","text","txt","tsv","vcf","vtt","wiz","xla","xlb","xlc","xlm","xls","xlsx",
        "xlt","xlw","xml","yaml","yml"
    )

    private fun preparePendingZipsThenSend(regenerate:Boolean){
        if(preparingZipAttachments)return
        val zipItems=pendingUploadUris.filter{it.second=="zip"}.toList()
        if(zipItems.isEmpty())return
        val sourceChatId=currentChatId()
        preparingZipAttachments=true
        send.isEnabled=false
        status.text="Готовлю ZIP-вложение…"
        executor.execute{
            val uploaded=mutableListOf<LocalUploadedFile>()
            try{
                zipItems.forEachIndexed { index,(rawUri,_) ->
                    val uri=Uri.parse(rawUri)
                    val zipName=queryName(uri) ?: "archive.zip"
                    val key=attachmentKey(uri,"zip")
                    uploaded += extractZipForSend(uri,zipName,key){stage,pct->
                        runOnUiThread{uploadCards[key]?.details?.text="$stage · $pct%"}
                    }
                    diagnostics.log("zip_prepared",mapOf("chat_id" to sourceChatId,"filename" to zipName,"files_uploaded" to uploaded.size,"index" to index))
                }
                runOnUiThread{
                    if(sourceChatId==currentChatId()){
                        zipItems.forEach{(raw,kind)->pendingUploadUris.remove(raw to kind)}
                        uploaded.forEach{
                            attachedFileIds.add(it.id);attachedFileNames.add(it.name);attachedFileSizes.add(it.size);attachedFilePaths.add(it.path);attachedFileMimes.add(it.mime)
                        }
                        persistComposerStateNow()
                        preparingZipAttachments=false
                        refreshAttachChips()
                        status.text="ZIP подготовлен · файлов: ${uploaded.size}"
                        sendMessage(regenerate)
                    }else{
                        zipItems.forEach{(raw,kind)->pendingUploadUris.remove(raw to kind)}
                        uploaded.forEach{saveUploadedFileToComposer(sourceChatId,it.id,it.name,it.size,rawUri="",localPath=it.path,mime=it.mime)}
                        preparingZipAttachments=false
                        status.text="ZIP подготовлен в исходном чате"
                    }
                }
            }catch(e:Exception){
                preparingZipAttachments=false
                diagnostics.log("zip_prepare_failed",mapOf("chat_id" to sourceChatId,"error" to (e.message?:e.toString()).take(400)))
                runOnUiThread{refreshAttachChips();status.text="Не удалось подготовить ZIP: ${e.message}"}
            }
        }
    }

    private fun extractZipForSend(uri:Uri,zipName:String,zipKey:String,onProgress:(String,Int)->Unit):List<LocalUploadedFile>{
        val result=mutableListOf<LocalUploadedFile>()
        var entries=0
        var skipped=0
        var totalInflated=0L
        val maxEntryBytes=25_000_000L
        val maxTotalBytes=200L*1024L*1024L
        val maxEntries=100
        contentResolver.openInputStream(uri)?.use{stream->
            java.util.zip.ZipInputStream(java.io.BufferedInputStream(stream)).use{zin->
                var entry=zin.nextEntry
                while(entry!=null){
                    entries++
                    if(entries>maxEntries)throw IOException("ZIP содержит больше $maxEntries файлов")
                    val entryName=entry.name.substringAfterLast('/').trim()
                    val ext=entryName.substringAfterLast('.',"").lowercase()
                    if(!entry.isDirectory && entry.size>=0L && entry.size>maxEntryBytes)throw ZipSafetyException("entry_too_large")
                    if(!entry.isDirectory && entryName.isNotBlank() && !entryName.startsWith(".") && ext in supportedFileExtensions){
                        val local=File(File(filesDir,"attachments").apply{mkdirs()},"zip_${System.currentTimeMillis()}_${System.nanoTime()}_${safeFileName(entryName)}")
                        var entryBytes=0L
                        try{
                            local.outputStream().use{out->
                                val buffer=ByteArray(64*1024)
                                while(true){
                                    val n=zin.read(buffer);if(n<0)break
                                    entryBytes+=n;totalInflated+=n
                                    if(entryBytes>maxEntryBytes)throw ZipSafetyException("entry_too_large")
                                    if(totalInflated>maxTotalBytes)throw ZipSafetyException("archive_too_large")
                                    out.write(buffer,0,n)
                                    onProgress("Распаковка $entryName",(entryBytes.coerceAtMost(maxEntryBytes)*50L/maxEntryBytes).toInt())
                                }
                            }
                            if(entryBytes==0L){skipped++;local.delete()}
                            else{
                                val name=safeDisplayName(entryName)
                                val mime=mimeForFilename(name)
                                onProgress("Загрузка $name",50)
                                val uploaded=uploadLocalUserFile(local,name,mime,null){done,total->
                                    val pct=50 + (done*50L/total.coerceAtLeast(1L)).toInt()
                                    onProgress("Загрузка $name",pct.coerceIn(50,100))
                                }
                                result.add(uploaded)
                                onProgress("Готово $name",100)
                            }
                        }catch(e:ZipSafetyException){local.delete();throw e}
                        catch(_:Exception){skipped++;local.delete()}
                    }else if(!entry.isDirectory){
                        val buffer=ByteArray(64*1024)
                        var discarded=0L
                        while(true){
                            val n=zin.read(buffer);if(n<0)break
                            discarded+=n;totalInflated+=n
                            if(discarded>maxEntryBytes)throw ZipSafetyException("entry_too_large")
                            if(totalInflated>maxTotalBytes)throw ZipSafetyException("archive_too_large")
                        }
                        skipped++
                    }
                    zin.closeEntry()
                    entry=zin.nextEntry
                }
            }
        } ?: throw IOException("Не удалось открыть ZIP")
        if(result.isEmpty())throw IOException("В ZIP не найдено поддерживаемых файлов")
        return result
    }

    private class ZipSafetyException(val reason:String):IOException(reason)

    private fun uploadTempFile(temp:File,name:String,mime:String):Triple<String,String,Long>{
        val key=secure.getApiKey()
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose","user_data")
            .addFormDataPart("file",name,temp.asRequestBody(mime.toMediaType()))
            .build()
        val req=Request.Builder().url("https://api.openai.com/v1/files").addHeader("Authorization","Bearer $key").post(body).build()
        client.newCall(req).execute().use{resp->
            val raw=resp.body?.string().orEmpty()
            if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: $raw")
            return Triple(JSONObject(raw).getString("id"), name, temp.length())
        }
    }

    private fun uploadFileBytes(bytes:ByteArray, name:String):Triple<String,String,Long>{
        val temp=File(cacheDir,"upload_${System.nanoTime()}")
        temp.writeBytes(bytes)
        try{return uploadTempFile(temp,name,mimeForFilename(name))}finally{temp.delete()}
    }

    private fun uploadImageFile(path:String,name:String,key:String?=null,onProgress:(Long,Long)->Unit={_,_->}):String{
        val apiKey=secure.getApiKey()
        val file=File(path)
        if(!file.isFile) throw IOException("Изображение больше недоступно")
        val mime=mimeForFilename(name).takeIf{it.startsWith("image/")} ?: "image/jpeg"
        val delegate=file.asRequestBody(mime.toMediaType())
        val tracked=ProgressRequestBody(delegate){done,total->onProgress(done,total)}
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose","vision")
            .addFormDataPart("file",safeFileName(name),tracked)
            .build()
        val req=Request.Builder().url("https://api.openai.com/v1/files")
            .addHeader("Authorization","Bearer $apiKey").post(body).build()
        val call=client.newCall(req)
        if(key!=null)uploadCalls[key]=call
        try{
            call.execute().use{resp->
                val raw=resp.body?.string().orEmpty()
                if(!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $raw")
                onProgress(file.length(),file.length())
                return JSONObject(raw).getString("id")
            }
        }finally{ if(key!=null)uploadCalls.remove(key) }
    }

    private fun uploadLocalUserFile(file:File,name:String,mime:String,key:String?=null,onProgress:(Long,Long)->Unit={_,_->}):LocalUploadedFile{
        if(!file.isFile)throw IOException("Файл больше недоступен")
        val size=file.length()
        val max=50L*1024L*1024L
        if(size>max)throw IOException("Файл слишком большой для Responses API (максимум ${formatBytes(max)})")
        val apiKey=secure.getApiKey()
        val delegate=file.asRequestBody(mime.toMediaType())
        val tracked=ProgressRequestBody(delegate){done,total->onProgress(done,total)}
        val body=MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose","user_data")
            .addFormDataPart("file",safeFileName(name),tracked)
            .build()
        val req=Request.Builder().url("https://api.openai.com/v1/files")
            .addHeader("Authorization","Bearer $apiKey").post(body).build()
        val call=client.newCall(req)
        if(key!=null)uploadCalls[key]=call
        try{
            call.execute().use{resp->
                val raw=resp.body?.string().orEmpty()
                if(!resp.isSuccessful)throw IOException("HTTP ${resp.code}: $raw")
                onProgress(size,size)
                return LocalUploadedFile(JSONObject(raw).getString("id"),name,size,file.absolutePath,mime)
            }
        }finally{if(key!=null)uploadCalls.remove(key)}
    }

    private fun uploadFile(
        uri:Uri,
        key:String?=null,
        onProgress:(Long,Long)->Unit={_,_->},
        onLocalReady:(String,String,Long)->Unit={_,_,_->}
    ):LocalUploadedFile{
        val maxInputFileBytes=50L*1024L*1024L
        val reportedSize=querySize(uri)
        if(reportedSize>maxInputFileBytes){
            throw IOException("Файл слишком большой для Responses API (максимум ${formatBytes(maxInputFileBytes)})")
        }
        val name=queryName(uri) ?: "attachment"
        val mime=contentResolver.getType(uri).orEmpty().ifBlank{mimeForFilename(name)}
        val dir=File(filesDir,"attachments").apply{mkdirs()}
        val local=File(dir,"user_${System.currentTimeMillis()}_${System.nanoTime()}_${safeFileName(name)}")
        try{
            val input=contentResolver.openInputStream(uri) ?: throw IOException("Не удалось открыть файл")
            var copied=0L
            input.use{src->local.outputStream().use{out->
                val buffer=ByteArray(64*1024)
                while(true){
                    val n=src.read(buffer); if(n<0)break
                    copied+=n
                    if(copied>maxInputFileBytes)throw IOException("Файл слишком большой для Responses API (максимум ${formatBytes(maxInputFileBytes)})")
                    out.write(buffer,0,n)
                    val total=reportedSize.takeIf{it>0} ?: maxInputFileBytes
                    onProgress((copied.coerceAtMost(total)*45L/total.coerceAtLeast(1L)),100L)
                }
            }}
            onProgress(45L,100L)
            // The permanent local copy is ready before the network upload finishes.
            // Expose it to the composer so the user can preview/read the file immediately.
            try { onLocalReady(local.absolutePath,mime,local.length()) } catch (_:Exception) {}
            return uploadLocalUserFile(local,name,mime,key){done,total->
                // The upload is the second half of the progress indicator.
                val pctPart=50L + (done*50L/total.coerceAtLeast(1L))
                onProgress(pctPart,100L)
            }
        }catch(e:Exception){
            if(key==null || !cancelledUploads.contains(key)) local.delete()
            throw e
        }
    }

    private fun queryName(uri:Uri):String?{
        var n:String?=null
        contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->
            if(c.moveToFirst()) n=c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
        }
        return n
    }



    private fun saveUploadedFileToComposer(chatId:String,fileId:String,name:String,size:Long,rawUri:String,localPath:String?=null,mime:String?=null){
        if(chatId.isBlank()) return
        try{
            val key=composerPrefsKey(chatId)
            val o=try{JSONObject(prefs.getString(key,null).orEmpty())}catch(_:Exception){JSONObject()}
            val ids=o.optJSONArray("file_ids") ?: JSONArray()
            val names=o.optJSONArray("file_names") ?: JSONArray()
            val sizes=o.optJSONArray("file_sizes") ?: JSONArray()
            val paths=o.optJSONArray("file_paths") ?: JSONArray()
            val mimes=o.optJSONArray("file_mimes") ?: JSONArray()
            while(paths.length()<ids.length())paths.put("")
            while(mimes.length()<ids.length())mimes.put("")
            var exists=false
            for(i in 0 until ids.length()) if(ids.optString(i)==fileId) {
                exists=true
                if(localPath!=null)paths.put(i,localPath)
                if(!mime.isNullOrBlank())mimes.put(i,mime)
            }
            if(!exists){ ids.put(fileId); names.put(name); sizes.put(size); paths.put(localPath ?: ""); mimes.put(mime ?: mimeForFilename(name)) }
            val pending=JSONArray()
            o.optJSONArray("pending_uploads")?.let{a->
                for(i in 0 until a.length()){
                    val item=a.optJSONObject(i) ?: continue
                    if(item.optString("uri")==rawUri && item.optString("kind")=="file") continue
                    pending.put(item)
                }
            }
            o.put("file_ids",ids).put("file_names",names).put("file_sizes",sizes).put("file_paths",paths).put("file_mimes",mimes).put("pending_uploads",pending).put("saved_at",System.currentTimeMillis())
            prefs.edit().putString(key,o.toString()).apply()
        }catch(_:Exception){}
    }

    private fun saveUploadedImageToComposer(chatId:String,path:String,fileId:String,rawUri:String,name:String="image",mime:String="image/*",size:Long=0L){
        if(chatId.isBlank()) return
        try{
            val key=composerPrefsKey(chatId)
            val o=try{JSONObject(prefs.getString(key,null).orEmpty())}catch(_:Exception){JSONObject()}
            o.put("image_path",path).put("image_file_id",fileId).put("image_name",name).put("image_mime",mime).put("image_size",size)
            val pending=JSONArray()
            o.optJSONArray("pending_uploads")?.let{a->
                for(i in 0 until a.length()){
                    val item=a.optJSONObject(i) ?: continue
                    if(item.optString("uri")==rawUri && item.optString("kind")=="image") continue
                    pending.put(item)
                }
            }
            o.put("pending_uploads",pending).put("saved_at",System.currentTimeMillis())
            prefs.edit().putString(key,o.toString()).apply()
        }catch(_:Exception){}
    }

    private fun rememberUploadUri(uri:Uri, kind:String){
        val value=uri.toString() to kind
        cancelledUploads.remove(attachmentKey(uri,kind))
        if(pendingUploadUris.none{it.first==value.first && it.second==value.second}){
            try{
                contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }catch(_:Exception){}
            pendingUploadUris.add(value)
            persistComposerStateNow()
        }
    }

    private fun forgetUploadUri(uri:Uri, kind:String){
        pendingUploadUris.removeAll{it.first==uri.toString() && it.second==kind}
        persistComposerStateNow()
    }

    private fun resumePendingUploads(){
        val pending=pendingUploadUris.toList()
        val uploadChatId=currentChatId()
        pending.forEach { (rawUri,kind) ->
            if(kind=="zip") return@forEach // ZIP is intentionally prepared only when the user taps Send.
            val key="$kind|$rawUri"
            if(!activePendingUploadKeys.add(key)) return@forEach
            val uri=try{Uri.parse(rawUri)}catch(_:Exception){activePendingUploadKeys.remove(key);return@forEach}
            if(kind=="image"){
                beginUpload()
                startPendingPreview(uri,"image")
                executor.execute{
                    try{
                        val path=copyImageToLocal(uri){done,total->reportUploadProgress(key,"Подготовка",(done*45L/total.coerceAtLeast(1L)),100L)}
                            ?: throw IOException("Не удалось восстановить изображение")
                        pendingLocalUploadPaths[key]=path
                        val name=queryName(uri) ?: "image.${File(path).extension}"
                        val mime=contentResolver.getType(uri).orEmpty().ifBlank{mimeForFilename(name)}
                        val fileId=uploadImageFile(path,name,key){done,total->reportUploadProgress(key,"Загрузка",50L + (done*50L/total.coerceAtLeast(1L)),100L)}
                        runOnUiThread{
                            if(cancelledUploads.contains(key))return@runOnUiThread
                            if(currentChatId()==uploadChatId){
                                pendingImagePath=path
                                pendingImageFileId=fileId
                                pendingImageName=name
                                pendingImageMime=mime
                                pendingImageSize=File(path).length()
                                forgetUploadUri(uri,"image")
                                refreshAttachChips()
                                persistComposerStateNow()
                                status.text="Изображение восстановлено"
                            }else{
                                saveUploadedImageToComposer(uploadChatId,path,fileId,rawUri,name,mime,File(path).length())
                            }
                        }
                    }catch(e:Exception){
                        if(!cancelledUploads.contains(key)){
                            diagnostics.log("pending_image_resume_failed",mapOf("error" to (e.message?:e.toString()).take(300)))
                            runOnUiThread{status.text="Не удалось восстановить изображение: ${e.message}"}
                        }
                    }finally{
                        endUpload(); uploadProgress.remove(key); pendingPreviewBitmaps.remove(key); pendingLocalUploadPaths.remove(key); activePendingUploadKeys.remove(key)
                    }
                }
            }else{
                if(contentResolver.getType(uri).orEmpty().startsWith("image/")) startPendingPreview(uri,"file")
                beginUpload()
                executor.execute{
                    try{
                        val ext=(queryName(uri) ?: "").substringAfterLast('.', "").lowercase()
                        if(ext !in supportedFileExtensions)throw IOException("Формат .$ext больше не поддерживается")
                        val uploaded=uploadFile(uri,key,{done,total->reportUploadProgress(key,"Загрузка",done,total)}){localPath,localMime,localSize->
                            runOnUiThread{
                                if(!cancelledUploads.contains(key)){
                                    pendingLocalUploadPaths[key]=localPath
                                    showPendingLocalPreview(key,localPath,queryName(uri) ?: "attachment",localMime)
                                }
                            }
                        }
                        runOnUiThread{
                            if(cancelledUploads.contains(key))return@runOnUiThread
                            if(currentChatId()==uploadChatId){
                                attachedFileIds.add(uploaded.id)
                                attachedFileNames.add(uploaded.name)
                                attachedFileSizes.add(uploaded.size)
                                attachedFilePaths.add(uploaded.path)
                                attachedFileMimes.add(uploaded.mime)
                                forgetUploadUri(uri,"file")
                                refreshAttachChips()
                                persistComposerStateNow()
                                status.text="Вложение восстановлено: ${uploaded.name}"
                            }else{
                                saveUploadedFileToComposer(uploadChatId,uploaded.id,uploaded.name,uploaded.size,rawUri,uploaded.path,uploaded.mime)
                            }
                        }
                    }catch(e:Exception){
                        if(!cancelledUploads.contains(key)){
                            diagnostics.log("pending_file_resume_failed",mapOf("error" to (e.message?:e.toString()).take(300)))
                            runOnUiThread{status.text="Не удалось восстановить вложение: ${e.message}"}
                        }
                    }finally{
                        endUpload(); uploadProgress.remove(key); pendingLocalUploadPaths.remove(key); activePendingUploadKeys.remove(key)
                    }
                }
            }
        }
    }

    private fun composerPrefsKey(chatId:String = currentChatId()) = "composer_state_$chatId"

    private fun persistComposerStateDebounced(){
        draftSaveRunnable?.let(mainHandler::removeCallbacks)
        draftSaveRunnable=Runnable{persistComposerStateNow()}
        mainHandler.postDelayed(draftSaveRunnable!!,300L)
    }

    private fun persistComposerStateNow(){
        val id=currentChatId()
        if(id.isBlank() || !::input.isInitialized)return
        try{
            val obj=JSONObject()
                .put("text",input.text.toString())
                .put("saved_at",System.currentTimeMillis())
            val ids=JSONArray();attachedFileIds.forEach{ids.put(it)}
            val names=JSONArray();attachedFileNames.forEach{names.put(it)}
            val sizes=JSONArray();attachedFileSizes.forEach{sizes.put(it)}
            val paths=JSONArray();attachedFilePaths.forEach{paths.put(it)}
            val mimes=JSONArray();attachedFileMimes.forEach{mimes.put(it)}
            obj.put("file_ids",ids).put("file_names",names).put("file_sizes",sizes).put("file_paths",paths).put("file_mimes",mimes)
            pendingImagePath?.let{obj.put("image_path",it)}
            pendingImageFileId?.let{obj.put("image_file_id",it)}
            pendingImageName?.let{obj.put("image_name",it)}
            pendingImageMime?.let{obj.put("image_mime",it)}
            obj.put("image_size",pendingImageSize.coerceAtLeast(0L))
            val pending=JSONArray()
            pendingUploadUris.forEach { (uri,kind) ->
                pending.put(JSONObject().put("uri",uri).put("kind",kind))
            }
            obj.put("pending_uploads",pending)
            prefs.edit().putString(composerPrefsKey(id),obj.toString()).apply()
        }catch(_:Exception){}
    }

    private fun listComposerLocalFilePaths(chatId:String):List<String>{
        if(chatId.isBlank())return emptyList()
        val raw=prefs.getString(composerPrefsKey(chatId),null) ?: return emptyList()
        val out=linkedSetOf<String>()
        try{
            val obj=JSONObject(raw)
            obj.optJSONArray("file_paths")?.let{arr->
                for(i in 0 until arr.length()) arr.optString(i).takeIf{it.isNotBlank()}?.let(out::add)
            }
            obj.optString("image_path").takeIf{it.isNotBlank()}?.let(out::add)
        }catch(_:Exception){}
        return out.toList()
    }

    private fun deleteOwnedLocalFile(path:String){
        if(path.isBlank())return
        try{
            val file=File(path).canonicalFile
            val roots=listOf(filesDir,cacheDir).map{it.canonicalFile}
            if(roots.none{file.path==it.path || file.path.startsWith(it.path+File.separator)}){
                diagnostics.log("local_file_delete_skipped",mapOf("path" to path.take(300),"reason" to "outside_app_storage"))
                return
            }
            if(file.isFile && file.delete()){
                diagnostics.log("local_file_deleted",mapOf("path" to file.path.take(300)))
            }
        }catch(e:Exception){
            diagnostics.log("local_file_delete_failed",mapOf("path" to path.take(300),"error" to (e.message?:e.toString()).take(300)))
        }
    }

    private fun clearComposerState(chatId:String){
        if(chatId.isBlank())return
        prefs.edit().remove(composerPrefsKey(chatId)).apply()
    }

    private fun restoreComposerState(){
        val id=currentChatId()
        val raw=prefs.getString(composerPrefsKey(id),null)
        if(raw.isNullOrBlank())return
        try{
            val o=JSONObject(raw)
            restoringComposer=true
            input.setText(o.optString("text",""))
            input.setSelection(input.text.length)
            clearAttachedSelection()
            o.optJSONArray("file_ids")?.let{a->for(i in 0 until a.length())attachedFileIds.add(a.optString(i))}
            o.optJSONArray("file_names")?.let{a->for(i in 0 until a.length())attachedFileNames.add(a.optString(i))}
            o.optJSONArray("file_sizes")?.let{a->for(i in 0 until a.length())attachedFileSizes.add(a.optLong(i,0L))}
            o.optJSONArray("file_paths")?.let{a->for(i in 0 until a.length())attachedFilePaths.add(a.optString(i))}
            o.optJSONArray("file_mimes")?.let{a->for(i in 0 until a.length())attachedFileMimes.add(a.optString(i))}
            while(attachedFileSizes.size<attachedFileIds.size)attachedFileSizes.add(0L)
            while(attachedFilePaths.size<attachedFileIds.size)attachedFilePaths.add("")
            while(attachedFileMimes.size<attachedFileIds.size)attachedFileMimes.add("")
            pendingImagePath=o.optString("image_path").takeIf{it.isNotBlank() && File(it).isFile}
            pendingImageFileId=o.optString("image_file_id").takeIf{it.isNotBlank() && it!="null"}
            pendingImageName=o.optString("image_name").takeIf{it.isNotBlank()}
            pendingImageMime=o.optString("image_mime").takeIf{it.isNotBlank()}
            pendingImageSize=o.optLong("image_size",pendingImagePath?.let{File(it).length()} ?: 0L)
            pendingUploadUris.clear()
            o.optJSONArray("pending_uploads")?.let{a->
                for(i in 0 until a.length()){
                    val item=a.optJSONObject(i) ?: continue
                    val uri=item.optString("uri").takeIf{it.isNotBlank()} ?: continue
                    val kind=item.optString("kind").ifBlank{"file"}
                    pendingUploadUris.add(uri to kind)
                }
            }
            refreshAttachChips()
        }catch(_:Exception){}finally{
            restoringComposer=false
        }
    }

    // ---------- storage ----------
    private fun ensureChatStore(){
        val existing=currentChatId()
        if(existing.isBlank()){
            val id = newChatId()
            prefs.edit().putString("current_chat",id).apply()
            chatDb.ensureChat(id,"Новый чат")
        } else {
            chatDb.ensureChat(existing,"Новый чат")
        }
    }

    private fun currentChatTitleSafe(): String {
        val id=currentChatId()
        return chatDb.getChatTitle(id) ?: "Новый чат"
    }
    private fun currentChatId()=prefs.getString("current_chat","") ?: ""
    private fun currentChatTitle():String = currentChatTitleSafe()
    private fun currentChatObj():JSONObject = chatDb.getChat(currentChatId())
        ?: JSONObject().put("id",currentChatId()).put("title","Новый чат").put("messages",JSONArray())

    private fun ensureChatEntry(id:String,titleText:String){
        chatDb.ensureChat(id,titleText)
    }
    private fun loadAllChats():List<JSONObject> = chatDb.listChatSummaries()
    private fun saveAllChats(list:List<JSONObject>){
        // Compatibility helper for callers that still hand us a full list.
        list.forEach { c ->
            chatDb.upsertCurrentChat(
                c.optString("id"),
                c.optString("title","Новый чат"),
                c.optString("response_id").takeIf{it.isNotBlank() && it!="null"},
                c.optString("baseline_effort").takeIf{it.isNotBlank() && it!="null"},
                jsonArrayToList(c.optJSONArray("messages"))
            )
        }
    }
    private fun jsonArrayToList(arr:JSONArray?):List<JSONObject>{
         if(arr==null)return emptyList()
         val out=mutableListOf<JSONObject>()
         for(i in 0 until arr.length()) arr.optJSONObject(i)?.let{out.add(it)}
         return out
     }
    private fun jsonArrayToMutableList(arr:JSONArray?):MutableList<JSONObject>{
         val out=mutableListOf<JSONObject>()
         if(arr==null)return out
         for(i in 0 until arr.length()) arr.optJSONObject(i)?.let{out.add(it)}
         return out
     }
    private fun persistCurrentChat(){
        val id=currentChatId()
        if(id.isBlank())return
        var chatTitle=currentChatTitleSafe()
        if(chat.isNotEmpty() && chatTitle=="Новый чат"){
            val first=chat.firstOrNull{it.optString("role")=="user"}?.optString("text").orEmpty()
            chatTitle=first.take(36).ifBlank{"Новый чат"}
        }
        chatDb.upsertCurrentChat(id,chatTitle,currentResponseId,baselineEffort,chat)
        if(::title.isInitialized) title.text=chatTitle
    }
    private fun restoreCurrentChat(){
        messageAdapter.clearItems()
        chat.clear()
        clearAttachedSelection()
        refreshAttachChips()
        val c=currentChatObj()
        title.text=c.optString("title","Новый чат")
        currentResponseId=c.optString("response_id").takeIf{it.isNotBlank() && it!="null"}
        baselineEffort=c.optString("baseline_effort").takeIf{it.isNotBlank() && it!="null"}
        val arr=c.optJSONArray("messages") ?: JSONArray()
        for(i in 0 until arr.length()){
            val m=arr.optJSONObject(i)?:continue
            val role=m.optString("role")
            val t=m.optString("text")
            chat.add(m)
            addBubble(if(role=="user")"Ты" else "Astra",t,role=="user",null,m.optJSONArray("annotations"),m)
            if(role=="user")lastUserText=t
        }
        val restoredTools = chat.lastOrNull { it.optString("role") == "assistant" }
            ?.optJSONArray("tools")
        toolLabel.text = restoredTools?.let { arr ->
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.distinct().joinToString("  ")
        }.orEmpty()
        activeAssistantBody=null
        val active=chatDb.getActiveResponse(currentChatId())
        if(active!=null && active.optString("response_id").isNotBlank()){
            val partial=active.optString("text")
            activeAssistantBody=addBubble("Astra",partial,false)
            status.text=if(active.optString("status")=="queued")"Astra в очереди…" else "Astra продолжает ответ…"
            setBusy(true)
        }else{
            val restoredStatus = chat.lastOrNull { it.optString("role") == "assistant" }?.optString("status")
            status.text = if(restoredStatus == "incomplete") "Последний ответ был неполным" else "Готово"
            setBusy(false)
        }
        restoreComposerState()
    }

    private fun restoreAttachmentSelectionFromMessage(message:JSONObject){
        clearAttachedSelection()
        message.optJSONArray("file_ids")?.let{arr->for(i in 0 until arr.length())attachedFileIds.add(arr.optString(i))}
        message.optJSONArray("file_names")?.let{arr->for(i in 0 until arr.length())attachedFileNames.add(arr.optString(i))}
        message.optJSONArray("file_sizes")?.let{arr->for(i in 0 until arr.length())attachedFileSizes.add(arr.optLong(i,0L))}
        message.optJSONArray("file_paths")?.let{arr->for(i in 0 until arr.length())attachedFilePaths.add(arr.optString(i))}
        message.optJSONArray("file_mimes")?.let{arr->for(i in 0 until arr.length())attachedFileMimes.add(arr.optString(i))}
        while(attachedFileSizes.size<attachedFileIds.size)attachedFileSizes.add(0L)
        while(attachedFilePaths.size<attachedFileIds.size)attachedFilePaths.add("")
        while(attachedFileMimes.size<attachedFileIds.size)attachedFileMimes.add("")
        message.optString("image_path").takeIf{it.isNotBlank() && File(it).isFile}?.let{pendingImagePath=it}
        message.optString("image_file_id").takeIf{it.isNotBlank() && it!="null"}?.let{pendingImageFileId=it}
        pendingImageName=message.optString("image_name").takeIf{it.isNotBlank()}
        pendingImageMime=message.optString("image_mime").takeIf{it.isNotBlank()}
        pendingImageSize=message.optLong("image_size",pendingImagePath?.let{File(it).length()} ?: 0L)
    }

    private fun editMessage(idx:Int){
        if(idx<0 || idx>=chat.size) return
        if(chat[idx].optString("role")!="user") return
        requestGeneration++
        currentCall?.cancel()
        val message=chat[idx]
        val text=message.optString("text")

        // Restore attachments from the original user message into the composer.
        restoreAttachmentSelectionFromMessage(message)

        while(chat.size>idx) chat.removeAt(chat.size-1)
        while(messages.childCount>idx) messages.removeViewAt(messages.childCount-1)
        currentResponseId=chat.lastOrNull{it.optString("role")=="assistant"}
            ?.optString("response_id")?.takeIf{it.isNotBlank() && it!="null"}
        if(chat.isEmpty()) baselineEffort=null
        persistCurrentChat()
        input.setText(text)
        input.setSelection(input.text.length)
        refreshAttachChips()
        input.requestFocus()
        setBusy(false)
        status.text="Отредактируйте и отправьте снова"
    }

    private fun restoreLastUserAttachments(){
        val last=chat.lastOrNull{it.optString("role")=="user"} ?: return
        restoreAttachmentSelectionFromMessage(last)
    }

    private fun decodeThumbnailFromFile(path:String, maxDim:Int=640): Bitmap?{
        return try{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,bounds)
            if(bounds.outWidth<=0 || bounds.outHeight<=0) return null
            var sample=1
            while(bounds.outWidth/sample>maxDim || bounds.outHeight/sample>maxDim) sample*=2
            val opts=BitmapFactory.Options().apply{inSampleSize=sample; inPreferredConfig=Bitmap.Config.RGB_565}
            BitmapFactory.decodeFile(path,opts)
        }catch(_:Exception){null}
    }

    private fun copyImageToLocal(uri:Uri,onProgress:(Long,Long)->Unit={_,_->}):String?{
        val mime=contentResolver.getType(uri)?.lowercase() ?: "image/jpeg"
        val ext=when{
            mime=="image/png"->"png"
            mime=="image/webp"->"webp"
            mime=="image/gif"->"gif"
            mime=="image/jpeg"->"jpg"
            else->throw IOException("Формат изображения не поддерживается: $mime. Используйте PNG, JPEG, WEBP или GIF.")
        }
        val maxBytes=50L*1024L*1024L
        val reportedSize=querySize(uri)
        if(reportedSize>maxBytes)throw IOException("Изображение слишком большое (максимум ${formatBytes(maxBytes)})")
        val dir=File(filesDir,"attachments").apply{mkdirs()}
        val file=File(dir,"img_${System.currentTimeMillis()}_${System.nanoTime()}.$ext")
        var total=0L
        try{
            contentResolver.openInputStream(uri)?.use{input->
                file.outputStream().use{output->
                    val buffer=ByteArray(64*1024)
                    while(true){
                        val n=input.read(buffer)
                        if(n<0)break
                        total+=n
                        if(total>maxBytes)throw IOException("Изображение слишком большое (максимум ${formatBytes(maxBytes)})")
                        output.write(buffer,0,n)
                        val known=reportedSize.takeIf{it>0} ?: maxBytes
                        onProgress((total.coerceAtMost(known)*100L/known.coerceAtLeast(1L)),100L)
                    }
                }
            } ?: throw IOException("Не удалось открыть изображение")
            return file.absolutePath
        }catch(e:Exception){
            file.delete()
            throw e
        }
    }

    // ---------- attachment UI / previews ----------
    private fun attachmentKey(uri:Uri, kind:String) = "$kind|${uri}"

    private fun clearAttachedSelection(){
        attachedFileIds.clear()
        attachedFileNames.clear()
        attachedFileSizes.clear()
        attachedFilePaths.clear()
        attachedFileMimes.clear()
        pendingImagePath=null
        pendingImageFileId=null
        pendingImageName=null
        pendingImageMime=null
        pendingImageSize=0L
        // Pending composer attachments are chat-scoped too. The old chat has already
        // been persisted by callers before switching/deleting/creating another chat.
        pendingUploadUris.clear()
        uploadProgress.clear()
        pendingPreviewBitmaps.clear()
        uploadCards.clear()
    }

    private fun addAssetSectionHeader(parent:LinearLayout){
        parent.addView(View(this).apply{
            setBackgroundColor(Color.rgb(52,54,60))
            layoutParams=LinearLayout.LayoutParams(-1,dp(1)).apply{topMargin=dp(8);bottomMargin=dp(2)}
        })
    }

    private fun hasAssistantAssets(message:JSONObject):Boolean{
        if(message.optJSONArray("generated_images")?.length()?.let{it>0}==true)return true
        val anns=message.optJSONArray("annotations") ?: return false
        for(i in 0 until anns.length()){
            when(anns.optJSONObject(i)?.optString("type")){
                "container_file_citation","file_citation" -> return true
            }
        }
        return false
    }

    private fun cardBackground() = rounded(Color.rgb(38,40,46),10)

    private fun addCardDivider(parent:LinearLayout){
        parent.addView(View(this).apply{setBackgroundColor(Color.rgb(52,54,60));layoutParams=LinearLayout.LayoutParams(-1,dp(1))})
    }

    private fun createAttachmentCard():LinearLayout = LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL
        setPadding(dp(10),dp(8),dp(10),dp(8))
        background=cardBackground()
    }

    private val thumbnailCache = object : android.util.LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun thumbnailCacheKey(path:String,maxDim:Int):String{
        val file=File(path)
        return file.absolutePath+"|"+file.lastModified()+"|"+file.length()+"|"+maxDim
    }

    private fun loadThumbnailAsync(view:ImageView,path:String,maxDim:Int=640){
        view.setImageResource(android.R.drawable.ic_menu_gallery)
        val key=thumbnailCacheKey(path,maxDim)
        thumbnailCache.get(key)?.let{
            view.setImageBitmap(it)
            return
        }
        executor.execute{
            val bmp=decodeThumbnailFromFile(path,maxDim)
            if(bmp!=null)thumbnailCache.put(key,bmp)
            runOnUiThread{
                if(!isFinishing && !isDestroyed && bmp!=null) view.setImageBitmap(bmp)
            }
        }
    }

    private fun showImagePreviewFromFile(path:String,name:String){
        executor.execute{
            val key=thumbnailCacheKey(path,1800)
            val bmp=thumbnailCache.get(key) ?: decodeThumbnailFromFile(path,1800)?.also{thumbnailCache.put(key,it)}
            runOnUiThread{
                if(isFinishing)return@runOnUiThread
                if(bmp==null){status.text="Не удалось прочитать изображение";return@runOnUiThread}
                showImagePreviewFromBitmap(bmp,name)
            }
        }
    }

    private fun createThumbnailView(bitmap:Bitmap?):ImageView = ImageView(this).apply{
        if(bitmap!=null)setImageBitmap(bitmap) else setImageResource(android.R.drawable.ic_menu_gallery)
        scaleType=ImageView.ScaleType.CENTER_CROP
        adjustViewBounds=true
        background=rounded(Color.rgb(24,26,30),8)
        setPadding(0,0,0,0)
    }

    private fun formatAttachmentDetails(mime:String,size:Long):String{
        val type=mime.ifBlank{"application/octet-stream"}
        return if(size>0L) "$type · ${formatBytes(size)}" else type
    }

    private fun isTextLikeMime(mime:String,name:String):Boolean{
        val m=mime.lowercase()
        if(m.startsWith("text/"))return true
        return name.substringAfterLast('.',"").lowercase() in setOf(
            "txt","md","markdown","json","csv","tsv","xml","html","htm","css","js","ts","py","java","kt","kts","c","cpp","h","hpp","sh","sql","yaml","yml","log","diff","patch","srt","vtt"
        )
    }

    private fun userFileLocalPath(message:JSONObject,index:Int):String? = message.optJSONArray("file_paths")?.optString(index)?.takeIf{it.isNotBlank() && File(it).isFile}

    private fun userFileMime(message:JSONObject,index:Int,name:String):String{
        return message.optJSONArray("file_mimes")?.optString(index).orEmpty().ifBlank{mimeForFilename(name)}
    }

    private fun renderUserAttachments(message:JSONObject,card:LinearLayout){
        val fileIds=message.optJSONArray("file_ids") ?: JSONArray()
        val fileNames=message.optJSONArray("file_names") ?: JSONArray()
        val fileSizes=message.optJSONArray("file_sizes") ?: JSONArray()
        var shown=0
        val imagePath=message.optString("image_path").takeIf{it.isNotBlank() && File(it).isFile}
        val imageFileId=message.optString("image_file_id").trim()
        if(imagePath!=null || imageFileId.isNotBlank() || message.optString("image_name").isNotBlank()){
            val fallbackImageName = message.optString("image_path").takeIf{it.isNotBlank()}?.let{File(it).name}
            val name=message.optString("image_name").ifBlank{fallbackImageName ?: "Изображение"}
            val mime=message.optString("image_mime").ifBlank{if(imagePath!=null) mimeForFilename(name) else "image/*"}
            val box=createAttachmentCard()
            if(imagePath!=null){
                val preview=createThumbnailView(null)
                loadThumbnailAsync(preview,imagePath,900)
                preview.setOnClickListener{showImagePreviewFromFile(imagePath,name)}
                box.addView(preview,LinearLayout.LayoutParams(-1,dp(220)))
            }else{
                box.addView(TextView(this).apply{
                    text="🖼\nИзображение пока не скачано"
                    gravity=Gravity.CENTER
                    textSize=13f; setTextColor(Color.rgb(165,170,180))
                    background=rounded(Color.rgb(24,26,30),8)
                    setPadding(0,dp(22),0,dp(22))
                },LinearLayout.LayoutParams(-1,dp(130)))
            }
            box.addView(TextView(this).apply{ text="🖼 $name"; textSize=13.5f; setTextColor(Color.WHITE); setTypeface(null,Typeface.BOLD); setPadding(0,dp(6),0,dp(2)) })
            val size=message.optLong("image_size",imagePath?.let{File(it).length()} ?: 0L)
            box.addView(TextView(this).apply{ text=formatAttachmentDetails(mime,size); textSize=11.5f; setTextColor(Color.rgb(165,170,180)) })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(imagePath!=null){
                row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(imagePath,name)})
                row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(imagePath,name,mime)})
                row.addView(fileActionButton("Поделиться"){shareLocalFile(imagePath,name,mime)})
            }else if(imageFileId.isNotBlank()){
                row.addView(fileActionButton("Скачать и открыть"){downloadUserImageCopy(message,card,true)})
            }
            box.addView(row)
            card.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            shown++
        }
        for(i in 0 until maxOf(fileIds.length(),fileNames.length())){
            val fileId=fileIds.optString(i)
            val name=safeDisplayName(fileNames.optString(i).ifBlank{"attachment_${i+1}"})
            val size=fileSizes.optLong(i,0L)
            val localPath=userFileLocalPath(message,i)
            val mime=userFileMime(message,i,name)
            val box=createAttachmentCard()
            when{
                localPath!=null && mime.startsWith("image/") -> addCompactImagePreview(box,localPath,name)
                localPath!=null && isTextLikeMime(mime,name) -> addCompactTextPreview(box,localPath,name)
                localPath!=null && mime.equals("application/pdf",true) -> addCompactPdfPreview(box,localPath,name)
                else -> box.addView(TextView(this).apply{
                    text=when{mime.equals("application/pdf",true)->"📕";isTextLikeMime(mime,name)->"📄";mime.startsWith("image/")->"🖼";else->"📎"}
                    gravity=Gravity.CENTER;textSize=26f;setTextColor(Color.rgb(180,185,198))
                    background=rounded(Color.rgb(24,26,30),8);setPadding(0,dp(12),0,dp(12))
                },LinearLayout.LayoutParams(-1,dp(74)).apply{bottomMargin=dp(6)})
            }
            box.addView(TextView(this).apply{ text="📄 $name";textSize=13.5f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD) })
            box.addView(TextView(this).apply{ text=formatAttachmentDetails(mime,if(size>0)size else localPath?.let{File(it).length()} ?: 0L);textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(3),0,dp(4)) })
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(localPath!=null){
                if(isTextLikeMime(mime,name)) row.addView(fileActionButton("Просмотр"){showTextPreview(localPath,name)})
                else if(mime.equals("application/pdf",true)) row.addView(fileActionButton("Просмотр"){showPdfPreview(localPath,name)})
                else if(mime.startsWith("image/")) row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(localPath,name)})
                else row.addView(fileActionButton("Открыть"){openLocalFile(localPath,name,mime)})
                row.addView(fileActionButton("Сохранить"){requestSaveLocalFile(localPath,name,mime)})
                row.addView(fileActionButton("Поделиться"){shareLocalFile(localPath,name,mime)})
            }else if(fileId.isNotBlank()){
                row.addView(fileActionButton("Скачать и открыть"){downloadUserFileCopy(message,i,card,true)})
            }
            box.addView(row)
            card.addView(box,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(6)})
            shown++
        }
        if(shown>0){
            card.addView(TextView(this).apply{ text="Вложения"; textSize=11.5f; setTextColor(Color.rgb(145,150,160)); setPadding(0,dp(7),0,dp(0)) },2)
        }
    }

    private fun downloadUserFileCopy(message:JSONObject,index:Int,card:LinearLayout,openAfter:Boolean=false){
        val ids=message.optJSONArray("file_ids") ?: return
        val names=message.optJSONArray("file_names") ?: return
        val fileId=ids.optString(index).trim()
        if(fileId.isBlank())return
        val filename=safeDisplayName(names.optString(index).ifBlank{"attachment_${index+1}"})
        val chatId=message.optString("chat_id").ifBlank{currentChatId()}
        val requestId=message.optString("request_id").takeIf{it.isNotBlank() && it!="null"}.orEmpty()
        val mimeHint=userFileMime(message,index,filename)
        status.text="Скачиваю $filename…"
        executor.execute{
            var destination:File?=null
            try{
                val key=secure.getApiKey()
                val req=Request.Builder().url("https://api.openai.com/v1/files/$fileId/content")
                    .addHeader("Authorization","Bearer $key").get().build()
                destination=File(File(filesDir,"attachments").apply{mkdirs()},"user_${System.currentTimeMillis()}_${safeFileName(filename)}")
                client.newCall(req).execute().use{resp->
                    if(!resp.isSuccessful)throw FileDownloadException(resp.code)
                    val body=resp.body ?: throw IOException("Пустой ответ")
                    val maxBytes=512L*1024L*1024L
                    var total=0L
                    body.byteStream().use{input->destination!!.outputStream().use{output->
                        val buffer=ByteArray(64*1024)
                        while(true){
                            val n=input.read(buffer);if(n<0)break
                            total+=n
                            if(total>maxBytes){destination!!.delete();throw IOException("Файл слишком большой для мобильного клиента")}
                            output.write(buffer,0,n)
                        }
                    }}
                }
                val mime=if(mimeHint.isBlank())mimeForFilename(filename) else mimeHint
                val finalPath=destination!!.absolutePath
                val finalSize=destination!!.length()
                updateMessageFileLocalState(message,index,finalPath,mime,finalSize,chatId,requestId)
                diagnostics.log("user_file_cache_restored",mapOf("chat_id" to chatId,"request_id" to requestId,"file_id" to fileId,"filename" to filename,"bytes" to finalSize))
                runOnUiThread{
                    if(currentChatId()==chatId){
                        persistCurrentChatFor(chatId)
                        status.text="Файл доступен: $filename"
                        if(openAfter)openPreviewForLocalFile(finalPath,filename,mime) else restoreCurrentChat()
                    }
                }
            }catch(e:FileDownloadException){
                destination?.delete()
                runOnUiThread{status.text=if(e.code==404||e.code==410)"Исходный файл больше недоступен" else "Не удалось скачать $filename: HTTP ${e.code}"}
            }catch(e:Exception){
                destination?.delete()
                runOnUiThread{status.text="Не удалось скачать $filename: ${e.message}"}
            }
        }
    }

    private fun updateMessageFileLocalState(
        message:JSONObject,
        index:Int,
        localPath:String,
        mime:String,
        size:Long,
        chatId:String,
        requestId:String
    ){
        val ids=message.optJSONArray("file_ids") ?: JSONArray()
        val paths=message.optJSONArray("file_paths") ?: JSONArray()
        val mimes=message.optJSONArray("file_mimes") ?: JSONArray()
        val sizes=message.optJSONArray("file_sizes") ?: JSONArray()
        val targetLength=maxOf(index+1,ids.length(),paths.length(),mimes.length(),sizes.length())
        while(paths.length()<targetLength)paths.put("")
        while(mimes.length()<targetLength)mimes.put("")
        while(sizes.length()<targetLength)sizes.put(0L)
        paths.put(index,localPath)
        mimes.put(index,mime)
        sizes.put(index,size)
        message.put("file_paths",paths).put("file_mimes",mimes).put("file_sizes",sizes)
        if(requestId.isNotBlank()){
            chatDb.updateUserFileLocalPath(chatId,requestId,index,localPath,mime,size)
        }
    }

    private fun downloadUserImageCopy(message:JSONObject,card:LinearLayout,openAfter:Boolean=false){
        val fileId=message.optString("image_file_id").trim()
        if(fileId.isBlank()){
            status.text="Исходное изображение больше недоступно"
            return
        }
        val chatId=message.optString("chat_id").ifBlank{currentChatId()}
        val requestId=message.optString("request_id").takeIf{it.isNotBlank() && it!="null"}.orEmpty()
        val name=safeDisplayName(message.optString("image_name").ifBlank{"image.png"})
        status.text="Скачиваю $name…"
        executor.execute{
            var destination:File?=null
            try{
                val key=secure.getApiKey()
                val req=Request.Builder()
                    .url("https://api.openai.com/v1/files/$fileId/content")
                    .addHeader("Authorization","Bearer $key").get().build()
                val dir=File(filesDir,"attachments").apply{mkdirs()}
                destination=File(dir,"user_image_${System.currentTimeMillis()}_${safeFileName(name)}")
                var mime=message.optString("image_mime").ifBlank{mimeForFilename(name)}
                client.newCall(req).execute().use{resp->
                    if(!resp.isSuccessful)throw FileDownloadException(resp.code)
                    val body=resp.body ?: throw IOException("Пустой ответ")
                    resp.header("Content-Type")?.substringBefore(';')?.trim()?.takeIf{it.isNotBlank()}?.let{mime=it}
                    val maxBytes=512L*1024L*1024L
                    var total=0L
                    body.byteStream().use{input->destination!!.outputStream().use{output->
                        val buffer=ByteArray(64*1024)
                        while(true){
                            val n=input.read(buffer);if(n<0)break
                            total+=n
                            if(total>maxBytes){destination!!.delete();throw IOException("Файл слишком большой для мобильного клиента")}
                            output.write(buffer,0,n)
                        }
                    }}
                }
                val finalPath=destination!!.absolutePath
                val finalSize=destination!!.length()
                message.put("image_path",finalPath).put("image_mime",mime).put("image_size",finalSize)
                if(requestId.isNotBlank())chatDb.updateUserImageLocalPath(chatId,requestId,finalPath,mime,finalSize)
                diagnostics.log("user_image_cache_restored",mapOf("chat_id" to chatId,"request_id" to requestId,"file_id" to fileId,"bytes" to finalSize))
                runOnUiThread{
                    if(currentChatId()==chatId){
                        persistCurrentChatFor(chatId)
                        status.text="Изображение доступно: $name"
                        if(openAfter)showImagePreviewFromFile(finalPath,name) else restoreCurrentChat()
                    }
                }
            }catch(e:FileDownloadException){
                destination?.delete()
                runOnUiThread{status.text=if(e.code==404||e.code==410)"Исходное изображение больше недоступно" else "Не удалось скачать $name: HTTP ${e.code}"}
            }catch(e:Exception){
                destination?.delete()
                runOnUiThread{status.text="Не удалось скачать $name: ${e.message}"}
            }
        }
    }

    private fun showImagePreviewFromBitmap(bitmap:Bitmap,name:String=""){
        val image=ImageView(this).apply{
            setImageBitmap(bitmap)
            scaleType=ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.rgb(16,17,20))
        }
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=rounded(Color.rgb(16,17,20),12)}
        box.addView(image,LinearLayout.LayoutParams(-1,0,1f))
        if(name.isNotBlank()) box.addView(TextView(this).apply{ text=name;textSize=12f;setTextColor(Color.LTGRAY);setPadding(dp(4),dp(4),dp(4),dp(2));gravity=Gravity.CENTER })
        val dialog=AlertDialog.Builder(this).setView(box).setPositiveButton("Закрыть",null).create()
        dialog.setOnShowListener{ dialog.window?.setLayout(-1,-1) }
        dialog.show()
    }

    private fun showTextPreview(path:String,name:String){
        executor.execute{
            try{
                val maxPreview=2L*1024L*1024L
                val file=File(path)
                val limit=minOf(file.length(),maxPreview).toInt()
                val bytes=ByteArray(limit)
                var offset=0
                file.inputStream().use{input->
                    while(offset<bytes.size){
                        val n=input.read(bytes,offset,bytes.size-offset)
                        if(n<0)break
                        offset+=n
                    }
                }
                var text=String(bytes,0,offset,StandardCharsets.UTF_8)
                if(name.substringAfterLast('.',"").equals("json",true)){
                    text=try{JSONObject(text).toString(2)}catch(_:Exception){try{JSONArray(text).toString(2)}catch(_:Exception){text}}
                }
                val truncated=file.length()>maxPreview
                runOnUiThread{
                    val tv=TextView(this).apply{
                        this.text=if(truncated)"$text\n\n… Показана первая часть файла (${formatBytes(maxPreview)}). Полный файл можно открыть/сохранить."
                        else text
                        textSize=13f;setTextColor(Color.WHITE);typeface=Typeface.MONOSPACE;setPadding(dp(12),dp(10),dp(12),dp(10));setTextIsSelectable(true)
                    }
                    val box=ScrollView(this).apply{addView(tv)}
                    AlertDialog.Builder(this).setTitle(name).setView(box).setPositiveButton("Закрыть",null).setNeutralButton("Открыть файл"){_,_->openLocalFile(path,name,mimeForFilename(name))}.show()
                }
            }catch(e:Exception){runOnUiThread{status.text="Не удалось прочитать $name: ${e.message}"}}
        }
    }

    private fun showPdfPreview(path:String,name:String){
        executor.execute{
            try{
                val pfd=ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd).use{renderer->
                    if(renderer.pageCount<=0)throw IOException("PDF не содержит страниц")
                    renderer.openPage(0).use{page->
                        val scale=minOf(2f,1600f/maxOf(page.width,1).toFloat(),1600f/maxOf(page.height,1).toFloat())
                        val bmp=Bitmap.createBitmap(maxOf((page.width*scale).toInt(),1),maxOf((page.height*scale).toInt(),1),Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        runOnUiThread{
                            val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6))}
                            box.addView(ImageView(this).apply{setImageBitmap(bmp);scaleType=ImageView.ScaleType.FIT_CENTER;adjustViewBounds=true},LinearLayout.LayoutParams(-1,dp(540)))
                            AlertDialog.Builder(this).setTitle(name).setView(box).setPositiveButton("Закрыть",null).setNeutralButton("Открыть полностью"){_,_->openLocalFile(path,name,"application/pdf")}.show()
                        }
                    }
                }
                pfd.close()
            }catch(e:Exception){runOnUiThread{status.text="Не удалось показать PDF: ${e.message}"}}
        }
    }

    private fun addUploadProgressCard(uri:Uri,kind:String):UploadCardViews{
        val key=attachmentKey(uri,kind)
        val card=createAttachmentCard()
        val name=queryName(uri) ?: if(kind=="image")"image" else "attachment"
        val mime=contentResolver.getType(uri).orEmpty().ifBlank{mimeForFilename(name)}
        val preview=createThumbnailView(null)
        if(kind=="image" || mime.startsWith("image/")){
            preview.tag="attachment_local_preview"
            card.addView(preview,LinearLayout.LayoutParams(dp(180),dp(120)))
        }
        val nameView=TextView(this).apply{text=if(kind=="zip")"🗜 $name" else if(kind=="image")"🖼 $name" else "📄 $name";textSize=13.5f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD);setPadding(0,dp(6),0,dp(2))}
        val details=TextView(this).apply{
            text=if(kind=="zip")"Архив будет распакован только при отправке" else {
                val base=formatAttachmentDetails(mime,querySize(uri))
                val pct=uploadProgress[key]?.second ?: 0
                if(pct>0)"$base · подготовка $pct%" else "$base · подготовка…"
            }
            textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(2),0,dp(4))
        }
        val progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=100;progress=uploadProgress[key]?.second ?: 0}
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val action=TextView(this).apply{text="✕ Удалить";textSize=12.5f;setTextColor(Color.rgb(220,125,125));setPadding(0,dp(5),dp(10),dp(2));setOnClickListener{cancelPendingUpload(uri,kind)}}
        actions.addView(action)
        card.addView(nameView);card.addView(details);card.addView(progressBar);card.addView(actions)
        attachRow.addView(card,LinearLayout.LayoutParams(dp(240),-2).apply{rightMargin=dp(6)})
        val views=UploadCardViews(card,preview,nameView,details,progressBar,actions,action)
        uploadCards[key]=views
        pendingPreviewBitmaps[key]?.let { bm ->
            preview.setImageBitmap(bm)
            preview.setOnClickListener { showImagePreviewFromBitmap(bm,name) }
        }
        return views
    }

    private fun addCompactTextPreview(parent:LinearLayout,path:String,name:String){
        val preview=TextView(this).apply{
            tag="attachment_local_preview"
            text="Читаю файл…"
            textSize=11.5f
            setTextColor(Color.rgb(205,210,220))
            typeface=Typeface.MONOSPACE
            setPadding(dp(10),dp(8),dp(10),dp(8))
            setBackgroundColor(Color.rgb(24,26,30))
            maxLines=9
            ellipsize=TextUtils.TruncateAt.END
            setOnClickListener{showTextPreview(path,name)}
        }
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(118)).apply{bottomMargin=dp(6)})
        executor.execute{
            try{
                val maxPreview=48*1024
                val file=File(path)
                val buffer=ByteArray(maxPreview)
                var offset=0
                file.inputStream().use{input->
                    while(offset<buffer.size){
                        val n=input.read(buffer,offset,buffer.size-offset)
                        if(n<0)break
                        offset+=n
                    }
                }
                var text=String(buffer,0,offset,StandardCharsets.UTF_8)
                    .replace("\u0000", "")
                    .replace("\r\n","\n")
                if(name.substringAfterLast('.',"").equals("json",true)){
                    text=try{JSONObject(text).toString(2)}catch(_:Exception){try{JSONArray(text).toString(2)}catch(_:Exception){text}}
                }
                val lines=text.lineSequence().take(9).joinToString("\n")
                runOnUiThread{
                    preview.text=if(lines.isBlank())"(пустой файл)" else lines
                }
            }catch(e:Exception){
                runOnUiThread{preview.text="Не удалось прочитать файл. Нажмите «Просмотр»."}
            }
        }
    }

    private fun addCompactImagePreview(parent:LinearLayout,path:String,name:String){
        val preview=ImageView(this).apply{
            tag="attachment_local_preview"
            scaleType=ImageView.ScaleType.CENTER_CROP
            adjustViewBounds=true
            background=rounded(Color.rgb(24,26,30),8)
            setOnClickListener{showImagePreviewFromFile(path,name)}
        }
        loadThumbnailAsync(preview,path,720)
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
    }

    private fun addCompactPdfPreview(parent:LinearLayout,path:String,name:String){
        val preview=ImageView(this).apply{
            tag="attachment_local_preview"
            scaleType=ImageView.ScaleType.FIT_CENTER
            background=rounded(Color.rgb(24,26,30),8)
            setOnClickListener{showPdfPreview(path,name)}
        }
        parent.addView(preview,LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
        executor.execute{
            try{
                val pfd=ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY)
                PdfRenderer(pfd).use{renderer->
                    if(renderer.pageCount>0){
                        renderer.openPage(0).use{page->
                            val scale=minOf(1.5f,720f/maxOf(page.width,1).toFloat())
                            val w=maxOf((page.width*scale).toInt(),1)
                            val h=maxOf((page.height*scale).toInt(),1)
                            val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            runOnUiThread{preview.setImageBitmap(bmp)}
                        }
                    }
                }
                pfd.close()
            }catch(_:Exception){ }
        }
    }

    private fun openPreviewForLocalFile(path:String,name:String,mime:String){
        when{
            mime.startsWith("image/") -> showImagePreviewFromFile(path,name)
            isTextLikeMime(mime,name) -> showTextPreview(path,name)
            mime.equals("application/pdf",true) -> showPdfPreview(path,name)
            else -> openLocalFile(path,name,mime)
        }
    }

    private fun showPendingLocalPreview(key:String,path:String,name:String,mime:String){
        uploadCards[key]?.let{v->
            if(v.actions.childCount>1)v.actions.removeViews(0,v.actions.childCount-1)
            if(v.root.findViewWithTag<View>("attachment_local_preview")==null){
                when{
                    mime.startsWith("image/") -> {
                        val view=ImageView(this).apply{
                            tag="attachment_local_preview"
                            scaleType=ImageView.ScaleType.CENTER_CROP
                            adjustViewBounds=true
                            background=rounded(Color.rgb(24,26,30),8)
                            setOnClickListener{showImagePreviewFromFile(path,name)}
                        }
                        v.root.addView(view,v.root.indexOfChild(v.name),LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
                    }
                    isTextLikeMime(mime,name) -> {
                        val view=TextView(this).apply{
                            tag="attachment_local_preview"
                            text="Читаю файл…"
                            textSize=11.5f;setTextColor(Color.rgb(205,210,220));typeface=Typeface.MONOSPACE
                            setPadding(dp(10),dp(8),dp(10),dp(8));setBackgroundColor(Color.rgb(24,26,30));maxLines=8
                            ellipsize=TextUtils.TruncateAt.END;setOnClickListener{showTextPreview(path,name)}
                        }
                        v.root.addView(view,v.root.indexOfChild(v.name),LinearLayout.LayoutParams(-1,dp(118)).apply{bottomMargin=dp(6)})
                        executor.execute{
                            try{
                                val maxPreview=36*1024
                                val bytes=ByteArray(maxPreview)
                                var offset=0
                                File(path).inputStream().use{input->while(offset<bytes.size){val n=input.read(bytes,offset,bytes.size-offset);if(n<0)break;offset+=n}}
                                var text=String(bytes,0,offset,StandardCharsets.UTF_8).replace("\u0000","")
                                if(name.substringAfterLast(".","").equals("json",true)) text=try{JSONObject(text).toString(2)}catch(_:Exception){try{JSONArray(text).toString(2)}catch(_:Exception){text}}
                                val first=text.lineSequence().take(8).joinToString("\n")
                                runOnUiThread{view.text=if(first.isBlank())"(пустой файл)" else first}
                            }catch(_:Exception){runOnUiThread{view.text="Не удалось прочитать файл. Нажмите «Просмотр»."}}
                        }
                    }
                    mime.equals("application/pdf",true) -> {
                        val view=ImageView(this).apply{
                            tag="attachment_local_preview"
                            scaleType=ImageView.ScaleType.FIT_CENTER
                            background=rounded(Color.rgb(24,26,30),8)
                            setOnClickListener{showPdfPreview(path,name)}
                        }
                        v.root.addView(view,v.root.indexOfChild(v.name),LinearLayout.LayoutParams(-1,dp(180)).apply{bottomMargin=dp(6)})
                        executor.execute{
                            try{
                                val pfd=ParcelFileDescriptor.open(File(path),ParcelFileDescriptor.MODE_READ_ONLY)
                                PdfRenderer(pfd).use{renderer->
                                    if(renderer.pageCount>0){
                                        renderer.openPage(0).use{page->
                                            val scale=minOf(1.5f,720f/maxOf(page.width,1).toFloat())
                                            val w=maxOf((page.width*scale).toInt(),1);val h=maxOf((page.height*scale).toInt(),1)
                                            val bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);bmp.eraseColor(Color.WHITE)
                                            page.render(bmp,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                            runOnUiThread{view.setImageBitmap(bmp)}
                                        }
                                    }
                                }
                                pfd.close()
                            }catch(_:Exception){}
                        }
                    }
                }
            }
            val label=when{
                mime.startsWith("image/") -> "Просмотр"
                isTextLikeMime(mime,name) -> "Просмотр"
                mime.equals("application/pdf",true) -> "Просмотр"
                else -> "Открыть"
            }
            val button=fileActionButton(label){openPreviewForLocalFile(path,name,mime)}
            v.actions.addView(button,0)
            v.details.text="Локальная копия готова · загружаю на сервер…"
        }
    }

    private val pendingPreviewBitmaps = ConcurrentHashMap<String, Bitmap>()

    private fun startPendingPreview(uri:Uri,kind:String){
        if(kind!="image")return
        val key=attachmentKey(uri,kind)
        executor.execute{
            val bmp=decodeThumbnailFromUri(uri,640) ?: return@execute
            pendingPreviewBitmaps[key]=bmp
            runOnUiThread{
                uploadCards[key]?.let{v->
                    v.preview.setImageBitmap(bmp)
                    val displayName=v.name.text.toString().removePrefix("🖼 ")
                    v.preview.setOnClickListener{showImagePreviewFromBitmap(bmp,displayName)}
                }
            }
        }
    }

    private fun decodeThumbnailFromUri(uri:Uri,maxDim:Int):Bitmap?{
        return try{
            val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
            contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,bounds)}
            if(bounds.outWidth<=0 || bounds.outHeight<=0)return null
            var sample=1
            while(bounds.outWidth/sample>maxDim || bounds.outHeight/sample>maxDim)sample*=2
            val opts=BitmapFactory.Options().apply{inSampleSize=sample;inPreferredConfig=Bitmap.Config.RGB_565}
            contentResolver.openInputStream(uri)?.use{BitmapFactory.decodeStream(it,null,opts)}
        }catch(_:Exception){null}
    }

    private fun querySize(uri:Uri):Long{
        return contentResolver.query(uri,arrayOf(OpenableColumns.SIZE),null,null,null)?.use{c->if(c.moveToFirst()&&!c.isNull(0))c.getLong(0) else 0L} ?: 0L
    }

    private fun reportUploadProgress(key:String,stage:String,written:Long,total:Long){
        val pct=if(total>0L)(written.coerceAtMost(total)*100L/total).toInt().coerceIn(0,100) else 0
        val now=SystemClock.uptimeMillis()
        val last=uploadProgress[key]
        if(last!=null && last.second==pct && now-last.first<120L)return
        uploadProgress[key]=now to pct
        runOnUiThread{
            uploadCards[key]?.let{v->
                v.progress.visibility=View.VISIBLE
                if(total>0){v.progress.isIndeterminate=false;v.progress.progress=pct}
                v.details.text=if(total>0)"$stage · $pct%" else "$stage…"
            }
        }
    }

    private fun cancelPendingUpload(uri:Uri,kind:String){
        val key=attachmentKey(uri,kind)
        cancelledUploads.add(key)
        uploadCalls.remove(key)?.cancel()
        uploadProgress.remove(key)
        pendingUploadUris.removeAll{it.first==uri.toString() && it.second==kind}
        pendingPreviewBitmaps.remove(key)
        pendingLocalUploadPaths.remove(key)?.let{try{File(it).delete()}catch(_:Exception){}}
        persistComposerStateNow()
        refreshAttachChips()
        status.text="Вложение удалено"
    }

    private fun refreshAttachChips(){
        attachRow.removeAllViews()
        uploadCards.clear()
        for((rawUri,kind) in pendingUploadUris.toList()){
            val uri=try{Uri.parse(rawUri)}catch(_:Exception){continue}
            addUploadProgressCard(uri,kind)
        }
        if(pendingImagePath!=null){
            val path=pendingImagePath!!
            val name=pendingImageName?.ifBlank{null} ?: "Изображение"
            val mime=pendingImageMime?.ifBlank{null} ?: "image/*"
            val card=createAttachmentCard()
            val preview=createThumbnailView(null)
            loadThumbnailAsync(preview,path,640)
            preview.setOnClickListener{showImagePreviewFromFile(path,name)}
            card.addView(preview,LinearLayout.LayoutParams(dp(180),dp(120)))
            card.addView(TextView(this).apply{text="🖼 $name";textSize=13.5f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD);setPadding(0,dp(6),0,dp(2))})
            card.addView(TextView(this).apply{text=formatAttachmentDetails(mime,pendingImageSize.coerceAtLeast(File(path).length()));textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(2),0,dp(4))})
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            row.addView(fileActionButton("Просмотр"){showImagePreviewFromFile(path,name)})
            row.addView(fileActionButton("Удалить"){pendingImagePath=null;pendingImageFileId=null;pendingImageName=null;pendingImageMime=null;pendingImageSize=0L;refreshAttachChips();persistComposerStateNow()})
            card.addView(row)
            attachRow.addView(card,LinearLayout.LayoutParams(dp(220),-2).apply{rightMargin=dp(6)})
        }
        attachedFileNames.forEachIndexed{i,name->
            val path=attachedFilePaths.getOrNull(i).orEmpty().takeIf{it.isNotBlank() && File(it).isFile}
            val mime=attachedFileMimes.getOrNull(i).orEmpty().ifBlank{mimeForFilename(name)}
            val size=attachedFileSizes.getOrNull(i) ?: path?.let{File(it).length()} ?: 0L
            val card=createAttachmentCard()
            val title=TextView(this).apply{text="📄 ${safeDisplayName(name)}";textSize=13.5f;setTextColor(Color.WHITE);setTypeface(null,Typeface.BOLD)}
            card.addView(title)
            card.addView(TextView(this).apply{text=formatAttachmentDetails(mime,size);textSize=11.5f;setTextColor(Color.rgb(165,170,180));setPadding(0,dp(3),0,dp(4))})
            val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
            if(path!=null){
                if(isTextLikeMime(mime,name))row.addView(fileActionButton("Просмотр"){showTextPreview(path,name)})
                else if(mime.equals("application/pdf",true))row.addView(fileActionButton("Просмотр"){showPdfPreview(path,name)})
                else row.addView(fileActionButton("Открыть"){openLocalFile(path,name,mime)})
            }
            row.addView(fileActionButton("Удалить"){
                attachedFileIds.removeAt(i);attachedFileNames.removeAt(i);attachedFileSizes.removeAt(i);attachedFilePaths.removeAt(i);attachedFileMimes.removeAt(i)
                refreshAttachChips();persistComposerStateNow()
            })
            card.addView(row)
            attachRow.addView(card,LinearLayout.LayoutParams(dp(220),-2).apply{rightMargin=dp(6)})
        }
        attachScroll.visibility=if(attachRow.childCount>0)View.VISIBLE else View.GONE
        send.isEnabled = stop.visibility != View.VISIBLE && pendingUploads == 0 && !preparingZipAttachments
    }

    // ---------- utils ----------
    private fun setBusy(v:Boolean){
        send.isEnabled=!v && pendingUploads==0 && !preparingZipAttachments
        stop.visibility=if(v)View.VISIBLE else View.GONE
    }
    private fun beginUpload(){ runOnUiThread{ pendingUploads++; send.isEnabled=false } }
    private fun endUpload(){ runOnUiThread{
        pendingUploads=(pendingUploads-1).coerceAtLeast(0)
        if(pendingUploads==0 && stop.visibility!=View.VISIBLE) send.isEnabled=true
    } }
    private fun addBubble(
        label:String,
        text:String,
        user:Boolean,
        imageBitmap:Bitmap?=null,
        annotations:JSONArray?=null,
        message:JSONObject?=null
    ):TextView{
        val card=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(13),dp(10),dp(13),dp(10))
            background=rounded(if(user)Color.rgb(31,40,54) else Color.rgb(28,29,34),14)
        }
        val who=TextView(this).apply{
            this.text=label;textSize=12f
            setTextColor(if(user)Color.rgb(160,195,245) else Color.rgb(190,195,205))
            setTypeface(null,Typeface.BOLD)
        }
        card.addView(who)
        if (imageBitmap != null && !(user && message != null)) {
            imageBitmap.let{ bmp->
                card.addView(ImageView(this).apply{
                    setImageBitmap(bmp)
                    setAdjustViewBounds(true)
                    scaleType=ImageView.ScaleType.FIT_CENTER
                    setPadding(0,dp(6),0,0)
                    setOnClickListener { showImagePreviewFromBitmap(bmp, message?.optString("image_name").orEmpty()) }
                }, LinearLayout.LayoutParams(-1,dp(220)))
            }
        }
        val body=TextView(this).apply{
            this.text=if(!user && message!=null) RichTextRenderer.render(AstraResponseModel.fromChatJson(message)) else markdown(text, annotations)
            textSize=16f
            setTextColor(Color.WHITE)
            setLinkTextColor(Color.rgb(138,180,248))
            movementMethod=LinkMovementMethod.getInstance()
            highlightColor=Color.TRANSPARENT
            setPadding(0,dp(5),0,dp(0))
        }
        card.addView(body)
        if(user && message!=null){
            renderUserAttachments(message,card)
        }
        if(!user && message!=null){
            val savedSummary=message.optString("reasoning_summary").trim()
            if(savedSummary.isNotBlank()){
                attachReasoningSummaryPanel(card,savedSummary,initiallyExpanded=false)
            }
        }
        if(!user && message!=null && hasAssistantAssets(message)) addAssetSectionHeader(card)
        if(!user && message!=null){
            renderGeneratedImages(message,card)
        }
        if(!user && annotations!=null){
            renderAnnotations(annotations,card,message ?: findMessageForAnnotations(annotations) ?: JSONObject(),currentChatId(),requestGeneration)
        }
        if(!user && message!=null){
            addFeedbackRow(card,message,currentChatId())
        }
        val longPress=View.OnLongClickListener{
            val items=if(user)arrayOf("Копировать","Экспорт в TXT","Редактировать") else arrayOf("Копировать","Экспорт в TXT","Повторить последний запрос")
            AlertDialog.Builder(this).setItems(items){_,which->
                when(which){
                    0->{
                        val cm=getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("Astra",body.text?.toString().orEmpty()));status.text="Скопировано"
                    }
                    1->{
                        pendingExportBytes=body.text?.toString().orEmpty().toByteArray()
                        val i=Intent(Intent.ACTION_CREATE_DOCUMENT).apply{
                            addCategory(Intent.CATEGORY_OPENABLE)
                            type="text/plain"
                            putExtra(Intent.EXTRA_TITLE,"astra_message_${System.currentTimeMillis()}.txt")
                        }
                        try{ startActivityForResult(i,pickExportTxt) }
                        catch(e:Exception){ status.text="Экспорт недоступен: ${e.message}" }
                    }
                    2->if(user) editMessage(messages.indexOfChild(card)) else sendMessage(true)
                }
            }.show();true
        }
        card.setOnLongClickListener(longPress)
        body.setOnLongClickListener(longPress)
        who.setOnLongClickListener(longPress)
        messages.addView(card,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        scroll.post{scroll.fullScroll(View.FOCUS_DOWN)}
        return body
    }

    private fun addFeedbackRow(card:LinearLayout, message:JSONObject, chatId:String){
        val requestId=message.optString("request_id").takeIf{it.isNotBlank() && it!="null"} ?: return
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,0)}
        val current=message.optInt("feedback",0)
        fun feedbackButton(label:String,value:Int)=TextView(this).apply{
            text=if(current==value) "$label ✓" else label
            textSize=12f
            setTextColor(Color.rgb(150,155,165))
            setPadding(dp(6),dp(3),dp(10),dp(3))
            isEnabled=current==0
            setOnClickListener{
                if(message.optInt("feedback",0)!=0)return@setOnClickListener
                message.put("feedback",value)
                chatDb.recordFeedback(chatId,requestId,value)
                diagnostics.log("effort_feedback",mapOf("chat_id" to chatId,"request_id" to requestId,"value" to value))
                text="$label ✓"
                for(i in 0 until row.childCount) row.getChildAt(i).isEnabled=false
                status.text="Спасибо за оценку"
            }
        }
        row.addView(feedbackButton("👍",1))
        row.addView(feedbackButton("👎",-1))
        card.addView(row)
    }

    private fun findMessageForAnnotations(annotations:JSONArray):JSONObject?{
        return chat.lastOrNull{it.optString("role")=="assistant" && it.optJSONArray("annotations")?.toString()==annotations.toString()}
    }

    private fun markdown(s:String, annotations:JSONArray?=null):CharSequence = RichTextRenderer.render(s,annotations)

    private fun button(t:String,a:()->Unit)=Button(this).apply{
        text=t;textSize=16f;minWidth=0;minimumWidth=0
        setPadding(dp(10),dp(6),dp(10),dp(6))
        setTextColor(Color.rgb(210,214,222))
        background=rounded(Color.rgb(34,36,42))
        setOnClickListener{a()}
    }
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun rounded(fill:Int, radiusDp:Int=10): GradientDrawable = GradientDrawable().apply{
        cornerRadius = dp(radiusDp).toFloat()
        setColor(fill)
    }

}
