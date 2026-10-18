package com.example.openaichat

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Looper
import android.os.Parcel
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class MainActivityScrollLifecycleTest {
    private lateinit var prefs: SharedPreferences
    private var controller: ActivityController<MainActivity>? = null
    private val parserGates = mutableListOf<ParserGate>()

    @Before fun before() {
        RuntimeEnvironment.setQualifiers("w1080dp-h1800dp-mdpi")
        val context = RuntimeEnvironment.getApplication()
        try {
            androidx.work.WorkManager.getInstance(context)
        } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("response_notifications", false)
            .putString("provider", "openai").putString("model", "gpt-6-astra").commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun after() {
        RichResponsePanel.beforeAsyncParseForTests = null
        parserGates.forEach { it.release.countDown() }
        controller?.pause()?.stop()?.destroy()
        controller = null
        awaitParserExecutorIdle()
        shadowOf(Looper.getMainLooper()).idle()
        prefs.edit().clear().commit()
    }

    @Test fun savedStateBoundsTransientAnswerSignatureAndManyAnchorsAndLocksWithoutChangingSource() {
        ChatDatabase(RuntimeEnvironment.getApplication()).use { it.ensureChat("bounded", "Bounded") }
        prefs.edit().putString("current_chat", "bounded").commit()
        val activity = launch()
        val source = "Complete transient answer with original Unicode 😀 and separator \u001f. ".repeat(12_000)
        assertTrue(source.length > 512 * 1024)
        val itemId = activity.addBubble("Assistant", source, false, itemId = "active-transient-answer")
        awaitRendered(activity)
        val adapter = field<ChatMessageAdapter>(activity, "messageAdapter")
        val item = adapter.snapshot().single { it.id == itemId }
        assertNull("Exercise the transient active-answer fingerprint path", item.message)
        assertTrue("An active answer must have a bounded signature before saving", item.contentSignature.length <= 64)
        val anchors = anchors(activity)
        // The oversized source uses paged UI; its final page can be short. Capture its actual
        // settled follow anchor here; the separate lifecycle tests establish deep reading positions.
        val anchor = anchors.getValue("bounded")
        assertEquals(itemId, anchor.messageId)
        assertEquals(item.contentSignature, anchor.contentSignature)
        val locks = locks(activity)
        // Valid, individually modest IDs still exceed the total Bundle budget when saved without a cap.
        repeat(1_024) { index ->
            val chatId = "history-$index-" + "c".repeat(120)
            anchors[chatId] = anchor.copy(messageId = "message-$index-" + "m".repeat(180),
                contentSignature = "old-signature-$index", mode = ChatScrollMode.READING_LOCKED)
            locks.add(chatId)
        }
        // The former independent lock-ID array could bypass an otherwise bounded anchor serializer.
        repeat(1_024) { locks.add("lock-only-$it-" + "l".repeat(1_024)) }
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        val (roundTripped, bytes) = parcelRoundTrip(saved)
        assertTrue("Saved Activity Bundle was $bytes bytes", bytes < 256 * 1024)
        val savedAnchors = ChatScrollPolicy.decodeAnchorsJson(roundTripped.getString("per_chat_scroll_anchors_v1"))
        val savedCurrent = savedAnchors.getValue("bounded")
        assertEquals(itemId, savedCurrent.messageId)
        assertEquals(anchor.topOffsetPx, savedCurrent.topOffsetPx)
        assertEquals(anchor.mode, savedCurrent.mode)
        assertTrue(savedAnchors.size < anchors.size)
        assertEquals("Saving derived metadata must preserve the complete active answer", source,
            adapter.snapshot().single { it.id == itemId }.text)
        assertEquals(item.contentSignature, adapter.snapshot().single { it.id == itemId }.contentSignature)
        assertEquals("The live anchor set must survive serialization intact", 1_025, anchors.size)
        assertTrue("Saving must not trim live reading locks", locks.size >= 2_048)
    }

    @Test fun forcedFollowDuringPendingAsyncRestoreSavesFollowModeAndReachesBottomAfterRestart() {
        seedAsyncChats()
        val activity = launch()
        awaitRendered(activity)
        readDeepIntoAnswer(activity)
        switchChat(activity, "short")
        awaitRendered(activity)
        val gate = pauseParsers()
        switchChat(activity, "async")
        awaitPausedParser(activity, gate)
        assertEquals(ChatScrollMode.READING_LOCKED, anchors(activity).getValue("async").mode)
        MainActivity::class.java.getDeclaredMethod("scrollToLatest", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, true)
        assertEquals("async", field<String?>(activity, "pendingScrollRestoreChatId"))
        assertNull("Force-follow must replace the pending reading anchor", field<ChatScrollAnchor?>(activity, "pendingScrollRestoreAnchor"))
        MainActivity::class.java.getDeclaredMethod("applyTheme").apply { isAccessible = true }.invoke(activity)
        assertNull("Theme rebinding must preserve the intentional pending follow mode",
            field<ChatScrollAnchor?>(activity, "pendingScrollRestoreAnchor"))
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        val savedAnchor = ChatScrollPolicy.decodeAnchorsJson(parcelRoundTrip(saved).first
            .getString("per_chat_scroll_anchors_v1")).getValue("async")
        assertEquals("Saving must preserve the new follow intent instead of the old reading mode",
            ChatScrollMode.FOLLOW_LATEST, savedAnchor.mode)
        assertEquals(0, savedAnchor.distanceFromBottomPx)
        controller!!.pause().stop()
        assertEquals(ChatScrollMode.FOLLOW_LATEST, anchors(activity).getValue("async").mode)
        controller!!.start().resume().visible()
        assertEquals("async", field<String?>(activity, "pendingScrollRestoreChatId"))
        assertNull(field<ChatScrollAnchor?>(activity, "pendingScrollRestoreAnchor"))
        gate.release.countDown()
        awaitRendered(activity)
        val distance = MainActivity::class.java.getDeclaredMethod("pixelDistanceFromBottom")
            .apply { isAccessible = true }.invoke(activity) as Int
        assertEquals("The completed asynchronous answer must finish at its real bottom", 0, distance)
        assertEquals(ChatScrollMode.FOLLOW_LATEST, anchors(activity).getValue("async").mode)
        assertEquals(0, anchors(activity).getValue("async").distanceFromBottomPx)
        assertFalse("async" in locks(activity))
        assertNull(field<String?>(activity, "pendingScrollRestoreChatId"))
    }

    @Test fun stoppedActivityRetainsPendingDeepAnchorAndRestoresItWhenSameActivityStartsAgain() {
        val source = seedAsyncChats()
        val activity = launch()
        awaitRendered(activity)
        val deepAnchor = readDeepIntoAnswer(activity)
        switchChat(activity, "short")
        awaitRendered(activity)
        val gate = pauseParsers()
        switchChat(activity, "async")
        awaitPausedParser(activity, gate)
        val generationBeforeStop = field<Long>(activity, "scrollRestoreGeneration")
        controller!!.pause().stop()
        val stoppedGeneration = field<Long>(activity, "scrollRestoreGeneration")
        assertTrue("Stopping must invalidate queued restore callbacks", stoppedGeneration > generationBeforeStop)
        assertEquals("async", field<String?>(activity, "pendingScrollRestoreChatId"))
        assertEquals(deepAnchor, field<ChatScrollAnchor>(activity, "pendingScrollRestoreAnchor"))
        gate.release.countDown()
        awaitParserExecutorIdle()
        shadowOf(Looper.getMainLooper()).idle()
        layout(activity)
        shadowOf(Looper.getMainLooper()).idle()
        layout(activity)
        assertFalse("The real parser must complete while the Activity is stopped", panel(activity)!!.isAsyncParsePending())
        assertEquals("Stopped layout must not commit a pending scroll restore", "async",
            field<String?>(activity, "pendingScrollRestoreChatId"))
        assertEquals(deepAnchor, field<ChatScrollAnchor>(activity, "pendingScrollRestoreAnchor"))
        assertEquals(deepAnchor, anchors(activity).getValue("async"))
        assertEquals(stoppedGeneration, field<Long>(activity, "scrollRestoreGeneration"))
        controller!!.start().resume().visible()
        assertSame(activity, controller!!.get())
        awaitRendered(activity)
        assertEquals("Restarting the same Activity must restore the exact deep position", deepAnchor.topOffsetPx,
            layoutManager(activity).findViewByPosition(0)!!.top - recycler(activity).paddingTop)
        assertNull(field<String?>(activity, "pendingScrollRestoreChatId"))
        assertTrue("async" in locks(activity))
        assertEquals(source, field<ChatMessageAdapter>(activity, "messageAdapter").snapshot().single().text)
    }

    @Test fun destroyedActivityRejectsQueuedRestoreAndPausedParserCompletionWithoutUiOrAnchorMutation() {
        val source = seedAsyncChats()
        val activity = launch()
        awaitRendered(activity)
        val deepAnchor = readDeepIntoAnswer(activity)
        switchChat(activity, "short")
        awaitRendered(activity)
        val gate = pauseParsers()
        switchChat(activity, "async")
        awaitPausedParser(activity, gate)
        val oldPanel = panel(activity)!!
        assertEquals(deepAnchor, field<ChatScrollAnchor>(activity, "pendingScrollRestoreAnchor"))
        // Queue the real restore continuation, then destroy before the main looper can execute it.
        MainActivity::class.java.getDeclaredMethod("applyRestoredScrollState", String::class.java,
            ChatScrollAnchor::class.java, Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(activity, "async", deepAnchor, field<Long>(activity, "scrollRestoreGeneration"))
        destroyCurrent()
        assertTrue(activity.isDestroyed)
        assertNull(field<String?>(activity, "pendingScrollRestoreChatId"))
        assertNull(field<ChatScrollAnchor?>(activity, "pendingScrollRestoreAnchor"))
        val anchorsAfterDestroy = anchors(activity).toMap()
        val locksAfterDestroy = locks(activity).toSet()
        val generationAfterDestroy = field<Long>(activity, "scrollRestoreGeneration")
        val childrenAfterDestroy = oldPanel.childCount
        val oldJumpButton = field<TextView>(activity, "jumpToBottomBtn")
        oldJumpButton.visibility = View.INVISIBLE
        gate.release.countDown()
        awaitParserExecutorIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("A detached parser must not install children in the destroyed Activity",
            childrenAfterDestroy, oldPanel.childCount)
        assertEquals(View.INVISIBLE, oldJumpButton.visibility)
        assertEquals(anchorsAfterDestroy, anchors(activity))
        assertEquals(locksAfterDestroy, locks(activity))
        assertEquals(generationAfterDestroy, field<Long>(activity, "scrollRestoreGeneration"))
        assertNull(field<String?>(activity, "pendingScrollRestoreChatId"))
        assertEquals(deepAnchor, anchors(activity).getValue("async"))
        ChatDatabase(RuntimeEnvironment.getApplication()).use { db ->
            assertEquals(source, db.getChat("async")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
        }
    }

    @Test fun pendingDeepAnchorSurvivesParcelAndFreshActivityWithDatabaseReopenBeforeParserCompletes() {
        val source = seedAsyncChats()
        val oldActivity = launch()
        awaitRendered(oldActivity)
        val deepAnchor = readDeepIntoAnswer(oldActivity)
        switchChat(oldActivity, "short")
        awaitRendered(oldActivity)
        val gate = pauseParsers(expectedStarts = 2)
        switchChat(oldActivity, "async")
        awaitPausedParser(oldActivity, gate)
        val saved = Bundle()
        controller!!.saveInstanceState(saved)
        val restoredState = parcelRoundTrip(saved).first
        val savedAnchor = ChatScrollPolicy.decodeAnchorsJson(restoredState.getString("per_chat_scroll_anchors_v1"))
            .getValue("async")
        assertEquals("Saving a pending empty card must retain the prior deep position", deepAnchor, savedAnchor)
        val oldDatabase = field<ChatDatabase>(oldActivity, "chatDb")
        destroyCurrent()
        // Process-recreation simulation: Parcel state plus fresh Activity/SQLite helper objects in this JVM.
        // This does not claim to kill an Android OS process or execute a real process-death boundary.
        ChatDatabase(RuntimeEnvironment.getApplication()).use { reopened ->
            assertEquals(source, reopened.getChat("async")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
        }
        val recreated = launch(restoredState)
        assertNotSame(oldDatabase, field<ChatDatabase>(recreated, "chatDb"))
        awaitPausedParser(recreated, gate)
        assertTrue("Both old and recreated Activities must reach the asynchronous parser", gate.allEntered.await(5, TimeUnit.SECONDS))
        assertEquals(savedAnchor, anchors(recreated).getValue("async"))
        assertEquals(savedAnchor, field<ChatScrollAnchor>(recreated, "pendingScrollRestoreAnchor"))
        assertEquals(source, field<ChatMessageAdapter>(recreated, "messageAdapter").snapshot().single().text)
        val oldAnchors = anchors(oldActivity).toMap()
        gate.release.countDown()
        awaitRendered(recreated)
        val card = layoutManager(recreated).findViewByPosition(0)!!
        assertEquals("Measured content must restore the saved deep offset", savedAnchor.topOffsetPx,
            card.top - recycler(recreated).paddingTop)
        assertTrue("The restored position must remain inside the answer", card.top - recycler(recreated).paddingTop < -300)
        assertTrue("Reading mode must survive the new Activity", "async" in locks(recreated))
        assertEquals(View.VISIBLE, field<TextView>(recreated, "jumpToBottomBtn").visibility)
        assertNull(field<String?>(recreated, "pendingScrollRestoreChatId"))
        assertEquals(savedAnchor.topOffsetPx, anchors(recreated).getValue("async").topOffsetPx)
        assertEquals(source, field<ChatMessageAdapter>(recreated, "messageAdapter").snapshot().single().text)
        awaitParserExecutorIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("The old parser cannot change the destroyed Activity's retained anchor", oldAnchors, anchors(oldActivity))
    }

    private fun seedAsyncChats(): String {
        val source = (1..650).joinToString("\n\n") { "Paragraph $it: a detailed explanation with enough real paragraphs to retain a deep reading position." }
        assertTrue("Fixture must take the >32k asynchronous parser path", source.length > 32_000)
        assertTrue("Keep a single native prose block rather than the >100k paging branch", source.length < 100_000)
        ChatDatabase(RuntimeEnvironment.getApplication()).use { db ->
            db.ensureChat("async", "Async")
            db.upsertCurrentChat("async", "Async", null, null, listOf(JSONObject().put("role", "assistant")
                .put("request_id", "async-answer").put("status", "completed").put("text", source)))
            db.ensureChat("short", "Short")
            db.upsertCurrentChat("short", "Short", null, null, listOf(JSONObject().put("role", "assistant")
                .put("request_id", "short-answer").put("status", "completed").put("text", "Short completed answer")))
        }
        prefs.edit().putString("current_chat", "async").commit()
        return source
    }

    private fun launch(savedState: Bundle? = null): MainActivity {
        val launched = if (savedState == null) Robolectric.buildActivity(MainActivity::class.java).setup()
        else Robolectric.buildActivity(MainActivity::class.java).create(savedState).start()
            .postCreate(savedState).resume().visible()
        controller = launched
        return launched.get()
    }

    private fun destroyCurrent() {
        controller!!.pause().stop().destroy()
        controller = null
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T

    private fun anchors(activity: MainActivity) = field<MutableMap<String, ChatScrollAnchor>>(activity, "perChatScrollAnchors")
    private fun locks(activity: MainActivity) = field<MutableSet<String>>(activity, "userScrollLockedChats")
    private fun recycler(activity: MainActivity) = field<RecyclerView>(activity, "scroll")
    private fun layoutManager(activity: MainActivity) = recycler(activity).layoutManager as LinearLayoutManager

    @Suppress("DEPRECATION")
    private fun layout(activity: MainActivity) {
        val display = activity.windowManager.defaultDisplay
        if (display.width != 1080) shadowOf(display).setWidth(1080)
        if (display.height != 1800) shadowOf(display).setHeight(1800)
        val view = activity.window.decorView
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 1080, 1800)
    }

    private fun descendants(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun panel(activity: MainActivity): RichResponsePanel? = layoutManager(activity).findViewByPosition(0)
        ?.let { descendants(it).filterIsInstance<RichResponsePanel>().firstOrNull() }

    private fun awaitRendered(activity: MainActivity) {
        awaitCondition("Rich answer must finish parsing, measurable layout and pending scroll restore") {
            shadowOf(Looper.getMainLooper()).idle()
            layout(activity)
            panel(activity)?.let { !it.isAsyncParsePending() && it.height > 0 } == true &&
                field<String?>(activity, "pendingScrollRestoreChatId") == null
        }
        shadowOf(Looper.getMainLooper()).idle()
        layout(activity)
        assertNull(field<String?>(activity, "pendingScrollRestoreChatId"))
    }

    private fun readDeepIntoAnswer(activity: MainActivity): ChatScrollAnchor {
        val view = recycler(activity)
        val card = layoutManager(activity).findViewByPosition(0)!!
        val bottomOffset = card.top - view.paddingTop
        assertTrue("Cold launch must open the tall answer at its bottom", bottomOffset < -900)
        view.scrollBy(0, bottomOffset + 900)
        layout(activity)
        val anchor = anchors(activity).getValue(activity.currentChatIdForTesting())
        assertEquals(card.top - view.paddingTop, anchor.topOffsetPx)
        assertTrue("A real upward scroll must create a deep anchor", anchor.topOffsetPx < -300)
        assertTrue("The anchor must point into content", anchor.charOffset > 0)
        assertEquals(ChatScrollMode.READING_LOCKED, anchor.mode)
        return anchor
    }

    private fun switchChat(activity: MainActivity, chatId: String) {
        MainActivity::class.java.getDeclaredMethod("switchChat", String::class.java)
            .apply { isAccessible = true }.invoke(activity, chatId)
    }

    private fun pauseParsers(expectedStarts: Int = 1): ParserGate = ParserGate(expectedStarts).also { gate ->
        parserGates.add(gate)
        RichResponsePanel.beforeAsyncParseForTests = { gate.block() }
    }

    private fun awaitPausedParser(activity: MainActivity, gate: ParserGate) {
        awaitCondition("Activity must bind the pending rich answer") {
            shadowOf(Looper.getMainLooper()).idle()
            layout(activity)
            panel(activity)?.isAsyncParsePending() == true && field<String?>(activity, "pendingScrollRestoreChatId") == "async"
        }
        assertTrue("The background parser must reach its latch", gate.firstEntered.await(5, TimeUnit.SECONDS))
        assertFalse("The parser must still be paused", gate.release.await(0, TimeUnit.SECONDS))
    }

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (System.nanoTime() < deadline) {
            if (condition()) return
            Thread.sleep(5)
        }
        fail(message)
    }

    private fun parcelRoundTrip(saved: Bundle): Pair<Bundle, Int> {
        val parcel = Parcel.obtain()
        return try {
            parcel.writeBundle(saved)
            val bytes = parcel.dataSize()
            parcel.setDataPosition(0)
            (parcel.readBundle(MainActivity::class.java.classLoader)!! to bytes)
        } finally { parcel.recycle() }
    }

    private class ParserGate(expectedStarts: Int) {
        val firstEntered = CountDownLatch(1)
        val allEntered = CountDownLatch(expectedStarts)
        val release = CountDownLatch(1)
        fun block() {
            firstEntered.countDown()
            allEntered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            // Keep the test gate closed across Future.cancel(true), so destruction happens before completion.
            while (release.count > 0 && System.nanoTime() < deadline) {
                try { if (release.await(100, TimeUnit.MILLISECONDS)) return }
                catch (_: InterruptedException) { /* Detached state still cancels the real parser after release. */ }
            }
            check(release.count == 0L) { "Test did not release asynchronous parser" }
        }
    }

    private fun awaitParserExecutorIdle() {
        val executor = RichResponsePanel::class.java.getDeclaredField("parsers").apply { isAccessible = true }
            .get(null) as ExecutorService
        val bothWorkersReachedBarrier = CountDownLatch(2)
        // Both fixed-pool workers must rendezvous; one free worker cannot overtake an older canceled parse.
        val barriers = (1..2).map {
            executor.submit {
                bothWorkersReachedBarrier.countDown()
                check(bothWorkersReachedBarrier.await(10, TimeUnit.SECONDS)) { "Parser executor did not drain" }
            }
        }
        barriers.forEach { it.get(15, TimeUnit.SECONDS) }
    }
}
