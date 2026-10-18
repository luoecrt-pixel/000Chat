package com.example.openaichat

import android.app.Activity
import android.os.Looper
import android.text.Selection
import android.text.Spannable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.TextView
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
class FingerDrawerLayoutTest {
    private class Fixture : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val drawer = FingerDrawerLayout(activity)
        val content = FrameLayout(activity)
        val panel = FrameLayout(activity)
        var downAt = 0L
        init {
            drawer.addView(content, FrameLayout.LayoutParams(300, 600))
            drawer.addView(panel, FrameLayout.LayoutParams(200, 600))
            drawer.swipeSurface = content
            activity.setContentView(drawer)
        }
        fun layout() {
            drawer.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            drawer.layout(0, 0, 300, 600)
        }
        fun touch(action: Int, x: Float, time: Long, y: Float = 50f) {
            if (action == MotionEvent.ACTION_DOWN) downAt = time
            val event = MotionEvent.obtain(downAt, time, action, x, y, 0)
            try { drawer.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun secondPointer(time: Long) {
            val properties = Array(2) { index -> MotionEvent.PointerProperties().apply { id = index; toolType = MotionEvent.TOOL_TYPE_FINGER } }
            val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply { x = 80f + 30f * index; y = 50f; pressure = 1f; size = 1f } }
            val event = MotionEvent.obtain(downAt, time,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0)
            try { drawer.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        fun finishAnimation() = Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(350))
        override fun close() { controller.pause().stop().destroy() }
    }

    private class TouchText(activity: Activity, selectable: Boolean = false) : TextView(activity) {
        val actions = mutableListOf<Int>()
        init {
            text = "A selectable response with enough words"
            if (selectable) setTextIsSelectable(true)
            isClickable = true
        }
        override fun onTouchEvent(event: MotionEvent): Boolean {
            actions += event.actionMasked
            super.onTouchEvent(event)
            return true
        }
    }

    @Test fun textGestureFollowsHeldFingerAndSmallSlowDragsWorkInBothDirections() = Fixture().use { f ->
        val text = TouchText(f.activity)
        f.content.addView(text, FrameLayout.LayoutParams(300, 150))
        f.panel.addView(TouchText(f.activity), FrameLayout.LayoutParams(200, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 30f, 50)
        assertEquals("The first intercepted move follows the finger", 0.10f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_MOVE, 60f, 300)
        assertEquals(0.25f, f.drawer.visibleFraction, 0.001f)
        assertTrue(text.actions.contains(MotionEvent.ACTION_CANCEL))
        f.finishAnimation()
        assertEquals("Holding never starts a settle animation", 0.25f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 60f, 800)
        f.finishAnimation()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)

        f.touch(MotionEvent.ACTION_DOWN, 150f, 1200)
        f.touch(MotionEvent.ACTION_MOVE, 125f, 1300)
        assertEquals(0.875f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_MOVE, 105f, 1500)
        f.touch(MotionEvent.ACTION_UP, 105f, 1800)
        f.finishAnimation()
        assertEquals("Slow 22.5% close needs no quick jerk", 0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun shortSlowMovementReturnsToOriginAndOutsideTapCloses() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 35f, 100)
        f.touch(MotionEvent.ACTION_UP, 35f, 600)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        f.drawer.openDrawer(Gravity.LEFT, false)
        f.touch(MotionEvent.ACTION_DOWN, 250f, 1000)
        f.touch(MotionEvent.ACTION_UP, 250f, 1050)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun horizontalCodeScrollOwnsGestureInMiddleAndAtBothEdges() = Fixture().use { f ->
        val code = HorizontalScrollView(f.activity)
        code.addView(TouchText(f.activity), FrameLayout.LayoutParams(900, 150))
        f.content.addView(code, FrameLayout.LayoutParams(300, 150))
        f.layout()
        for ((index, initialScroll) in listOf(0, 150, 600).withIndex()) {
            code.scrollTo(initialScroll, 0)
            val time = index * 1000L
            f.touch(MotionEvent.ACTION_DOWN, 40f, time)
            f.touch(MotionEvent.ACTION_MOVE, 80f, time + 50)
            f.touch(MotionEvent.ACTION_MOVE, 150f, time + 100)
            f.touch(MotionEvent.ACTION_UP, 150f, time + 400)
            f.finishAnimation()
            assertEquals("Code scroll at x=$initialScroll cannot become a drawer gesture", 0f, f.drawer.visibleFraction, 0.001f)
            if (initialScroll > 0) assertTrue("The code itself still scrolls", code.scrollX < initialScroll)
        }
    }

    @Test fun selectedTextAndLongPressDoNotOpenDrawerButOrdinaryTextStillDoes() = Fixture().use { f ->
        val text = TouchText(f.activity, selectable = true)
        f.content.addView(text, FrameLayout.LayoutParams(300, 150))
        f.layout()
        Selection.setSelection(text.text as Spannable, 2, 12)
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 150f, 80)
        f.touch(MotionEvent.ACTION_UP, 150f, 100)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        assertFalse(text.actions.contains(MotionEvent.ACTION_CANCEL))

        Selection.removeSelection(text.text as Spannable)
        text.actions.clear()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 1000)
        val afterLongPress = 1000L + ViewConfiguration.getLongPressTimeout() + 20
        f.touch(MotionEvent.ACTION_MOVE, 150f, afterLongPress)
        f.touch(MotionEvent.ACTION_UP, 150f, afterLongPress + 50)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        assertFalse(text.actions.contains(MotionEvent.ACTION_CANCEL))

        Selection.removeSelection(text.text as Spannable)
        f.touch(MotionEvent.ACTION_DOWN, 40f, 2000)
        f.touch(MotionEvent.ACTION_MOVE, 100f, 2100)
        f.touch(MotionEvent.ACTION_UP, 100f, 2400)
        f.finishAnimation()
        assertEquals("Selectable text keeps the ordinary horizontal swipe", 1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun childDisallowAndVerticalIntentLockOutDrawerForTheRestOfGesture() = Fixture().use { f ->
        val text = TouchText(f.activity)
        f.content.addView(text, FrameLayout.LayoutParams(300, 300))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0)
        text.parent.requestDisallowInterceptTouchEvent(true)
        f.touch(MotionEvent.ACTION_MOVE, 180f, 100)
        f.touch(MotionEvent.ACTION_UP, 180f, 150)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        assertFalse(text.actions.contains(MotionEvent.ACTION_CANCEL))
        text.actions.clear()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 42f, 1050, 90f)
        f.touch(MotionEvent.ACTION_MOVE, 180f, 1100, 90f)
        f.touch(MotionEvent.ACTION_UP, 180f, 1150, 90f)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        assertFalse(text.actions.contains(MotionEvent.ACTION_CANCEL))
    }

    @Test fun editableTextKeepsItsSelectionAndNeverBecomesDrawerSwipe() = Fixture().use { f ->
        f.content.addView(EditText(f.activity).apply { setText("Editable input") }, FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 180f, 100)
        f.touch(MotionEvent.ACTION_UP, 180f, 150)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun ordinaryVerticalScrollStillMovesContent() = Fixture().use { f ->
        val scroll = ScrollView(f.activity)
        scroll.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 1400))
        f.content.addView(scroll, FrameLayout.LayoutParams(300, 600))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0, 400f)
        f.touch(MotionEvent.ACTION_MOVE, 42f, 50, 350f)
        f.touch(MotionEvent.ACTION_MOVE, 44f, 100, 250f)
        f.touch(MotionEvent.ACTION_UP, 44f, 400, 250f)
        f.finishAnimation()
        assertTrue("Nested content still receives and performs the vertical scroll", scroll.scrollY > 50)
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun scrimDragClosesSmoothlyEvenWhenCodeIsUnderTheFinger() = Fixture().use { f ->
        val code = HorizontalScrollView(f.activity)
        code.addView(TouchText(f.activity), FrameLayout.LayoutParams(900, 150))
        f.content.addView(code, FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.drawer.openDrawer(Gravity.LEFT, false)
        f.touch(MotionEvent.ACTION_DOWN, 250f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 205f, 100)
        assertEquals(0.775f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 205f, 400)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun cancelSettlesByPositionWithoutCommittingVelocityAndNextGestureWorks() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 80f, 20)
        assertEquals(0.35f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_CANCEL, 80f, 21)
        f.finishAnimation()
        assertEquals("Cancel cannot commit the preceding fast opening fling", 0f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 150f, 1100)
        f.touch(MotionEvent.ACTION_UP, 150f, 1300)
        f.finishAnimation()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun secondPointerAbortsCaptureUntilAllPointersFinish() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 80f, 50)
        f.secondPointer(60)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_MOVE, 200f, 100)
        f.touch(MotionEvent.ACTION_UP, 200f, 120)
        f.finishAnimation()
        assertEquals("Lifting one pointer cannot resume the cancelled drag", 0f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 150f, 1100)
        f.touch(MotionEvent.ACTION_UP, 150f, 1300)
        f.finishAnimation()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun interruptedSettleFollowsNewFingerAndTapDoesNotFreezePanelHalfOpen() = Fixture().use { f ->
        f.panel.addView(TouchText(f.activity), FrameLayout.LayoutParams(200, 150))
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.drawer.openDrawer(Gravity.LEFT)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        val fraction = f.drawer.visibleFraction
        assertTrue(fraction > 0f && fraction < 1f)
        f.touch(MotionEvent.ACTION_DOWN, 20f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 50f, 1050)
        assertEquals((fraction + 0.15f).coerceAtMost(1f), f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 50f, 1300)
        f.finishAnimation()
        assertTrue(f.drawer.visibleFraction == 0f || f.drawer.visibleFraction == 1f)

        f.drawer.closeDrawer(Gravity.LEFT, false)
        f.drawer.openDrawer(Gravity.LEFT)
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(64))
        f.touch(MotionEvent.ACTION_DOWN, 20f, 2000)
        f.touch(MotionEvent.ACTION_UP, 20f, 2050)
        f.finishAnimation()
        assertEquals("A tap resumes the interrupted settle", 1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun programmaticCloseDuringDragCannotBeUndoneByOldPointer() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 120f, 100)
        f.drawer.closeDrawer(Gravity.LEFT, false)
        f.touch(MotionEvent.ACTION_MOVE, 200f, 150)
        f.touch(MotionEvent.ACTION_UP, 200f, 180)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun releasePolicyHasSymmetricSlowThresholdAndRespectsReversal() {
        assertEquals(0f, FingerDrawerLayout.decideSettleTarget(0f, 0.19f, 0f, 1f), 0.001f)
        assertEquals(1f, FingerDrawerLayout.decideSettleTarget(0f, 0.20f, 0f, 1f), 0.001f)
        assertEquals(1f, FingerDrawerLayout.decideSettleTarget(1f, 0.81f, 0f, -1f), 0.001f)
        assertEquals(0f, FingerDrawerLayout.decideSettleTarget(1f, 0.80f, 0f, -1f), 0.001f)
        assertEquals(1f, FingerDrawerLayout.decideSettleTarget(0f, 0.12f, 350f, 8f), 0.001f)
        assertEquals(0f, FingerDrawerLayout.decideSettleTarget(1f, 0.88f, -350f, -8f), 0.001f)
        assertEquals(0f, FingerDrawerLayout.decideSettleTarget(0f, 0.75f, -350f, -8f), 0.001f)
        assertEquals("Velocity alone cannot turn sub-slop noise into an opening",
            0f, FingerDrawerLayout.decideSettleTarget(0f, 0.01f, 1000f, 1f), 0.001f)
    }
}
