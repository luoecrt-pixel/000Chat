package com.example.openaichat

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatMessageLayoutManagerTest {
    private class Fixture(safe: Boolean, editable: Boolean = false, touchMode: Boolean = true) : AutoCloseable {
        private val controller = run {
            Instrumentation().setInTouchMode(touchMode)
            Robolectric.buildActivity(Activity::class.java).setup()
        }
        val activity = controller.get()
        val list = RecyclerView(activity)
        val manager = if (safe) ChatMessageLayoutManager(activity) else LinearLayoutManager(activity)
        lateinit var target: TextView
        init {
            list.layoutManager = manager
            list.itemAnimator = null
            list.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                override fun getItemCount() = 3
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                    val box = LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = RecyclerView.LayoutParams(300, 1100)
                        addView(if (editable) EditText(activity).apply { setText("Editable field") }
                        else TextView(activity).apply { text = "A long model response"; setTextIsSelectable(true) },
                            LinearLayout.LayoutParams(300, 1100))
                    }
                    return object : RecyclerView.ViewHolder(box) { }
                }
                override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) { }
            }
            activity.setContentView(list)
            layout()
            manager.scrollToPositionWithOffset(1, -300)
            layout()
            target = (manager.findViewByPosition(1) as ViewGroup).getChildAt(0) as TextView
            list.descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
            list.isFocusableInTouchMode = true
            list.requestFocus()
            list.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            assertFalse("The test must acquire focus for the first time", target.hasFocus())
        }
        fun layout() {
            list.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY))
            list.layout(0, 0, 300, 500)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        }
        fun bubbleTop() = manager.findViewByPosition(1)!!.top
        override fun close() { controller.pause().stop().destroy() }
    }

    @Test fun firstTouchSelectionPreservesViewportWhileOrdinaryManagerReproducesJump() {
        Fixture(safe = false).use { baseline ->
            val top = baseline.bubbleTop()
            assertTrue(baseline.target.isInTouchMode)
            assertTrue(baseline.target.requestFocus())
            baseline.layout()
            assertNotEquals("Baseline must reproduce the first focus jump", top, baseline.bubbleTop())
        }
        Fixture(safe = true).use { fixed ->
            val top = fixed.bubbleTop()
            assertTrue(fixed.target.isInTouchMode)
            assertTrue(fixed.target.requestFocus())
            fixed.layout()
            assertTrue("Selection retains real focus", fixed.target.hasFocus())
            assertEquals(top, fixed.bubbleTop())
            fixed.target.clearFocus()
            assertTrue(fixed.target.requestFocus())
            fixed.layout()
            assertEquals(top, fixed.bubbleTop())
        }
    }

    @Test fun editableFocusAndExplicitRectangleScrollStillWork() {
        Fixture(safe = true, editable = true).use { f ->
            val top = f.bubbleTop()
            assertTrue(f.target.requestFocus())
            f.layout()
            assertTrue(f.target.onCheckIsTextEditor())
            assertNotEquals("Editable focus still scrolls for input", top, f.bubbleTop())
        }
        Fixture(safe = true).use { f ->
            assertTrue(f.target.requestFocus())
            val top = f.bubbleTop()
            f.target.requestRectangleOnScreen(Rect(0, 900, 100, 1000), true)
            f.layout()
            assertNotEquals("Explicit selection/keyboard rectangle requests still scroll", top, f.bubbleTop())
            f.manager.scrollToPositionWithOffset(1, -200)
            f.layout()
            assertEquals("Programmatic reading anchors still apply", -200, f.bubbleTop())
            f.list.scrollBy(0, 60)
            assertEquals("Ordinary scrolling remains available", -260, f.bubbleTop())
        }
    }

    @Test fun keyboardFocusRetainsAutomaticScrollIntoView() = Fixture(safe = true, touchMode = false).use { f ->
        val top = f.bubbleTop()
        assertFalse(f.target.isInTouchMode)
        assertTrue(f.target.requestFocus())
        f.layout()
        assertNotEquals("Keyboard navigation must still reveal focused text", top, f.bubbleTop())
    }
}
