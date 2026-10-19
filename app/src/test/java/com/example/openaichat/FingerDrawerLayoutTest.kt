package com.example.openaichat

import android.app.Activity
import android.animation.ValueAnimator
import android.content.pm.ApplicationInfo
import android.os.Looper
import android.os.Parcelable
import android.util.SparseArray
import android.text.Selection
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
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
    private class Fixture(direction: Int = View.LAYOUT_DIRECTION_LTR) : AutoCloseable {
        private val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val drawer = FingerDrawerLayout(activity)
        val content = FrameLayout(activity)
        val panel = FrameLayout(activity)
        val host = FrameLayout(activity)
        var downAt = 0L
        init {
            drawer.id = 0x1000101
            if (direction == View.LAYOUT_DIRECTION_RTL) {
                activity.applicationInfo.flags = activity.applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
            }
            drawer.layoutDirection = direction
            drawer.addView(content, FrameLayout.LayoutParams(300, 600))
            drawer.addView(panel, FrameLayout.LayoutParams(200, 600).apply { gravity = Gravity.START })
            drawer.swipeSurface = content
            // Keep explicit fixture geometry stable when the real window performs its first
            // layout; tests that need a resize change the panel/host geometry deliberately.
            host.addView(drawer, FrameLayout.LayoutParams(300, 600))
            activity.setContentView(host)
        }
        fun layout() {
            host.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
            host.layout(0, 0, 300, 600)
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
        fun seekAnimation(time: Long) {
            val animator = FingerDrawerLayout::class.java.getDeclaredField("animator").apply { isAccessible = true }.get(drawer) as ValueAnimator
            animator.currentPlayTime = time
        }
        fun saveState() = SparseArray<Parcelable>().also { drawer.saveHierarchyState(it) }
        fun restoreState(state: SparseArray<Parcelable>) = drawer.restoreHierarchyState(state)
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
        // ScrollView measures its child with UNSPECIFIED height, ignoring lp.height.
        scroll.addView(TouchText(f.activity).apply { minimumHeight = 1400 }, FrameLayout.LayoutParams(300, 1400))
        f.content.addView(scroll, FrameLayout.LayoutParams(300, 600))
        f.layout()
        assertEquals("The fixture must actually contain scrollable content", 1400, scroll.getChildAt(0).height)
        assertEquals("The viewport must match dispatched coordinates", 600, scroll.height)
        assertTrue("The vertical gesture needs a real downward scroll range", scroll.canScrollVertically(1))
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0, 400f)
        f.touch(MotionEvent.ACTION_MOVE, 42f, 50, 350f)
        f.touch(MotionEvent.ACTION_MOVE, 44f, 100, 250f)
        f.touch(MotionEvent.ACTION_UP, 44f, 400, 250f)
        f.finishAnimation()
        assertTrue("Nested content still receives and performs the vertical scroll (scrollY=${scroll.scrollY}, child=${scroll.getChildAt(0).height}, viewport=${scroll.height})", scroll.scrollY > 50)
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
        // Advance the actual animator deterministically; looper idling can also run the
        // window's first frame and finish its animation before the intended interruption.
        f.seekAnimation(64)
        val fraction = f.drawer.visibleFraction
        assertTrue("The test must interrupt a real partial frame: $fraction", fraction > 0f && fraction < 1f)
        f.touch(MotionEvent.ACTION_DOWN, 20f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 50f, 1050)
        assertEquals((fraction + 0.15f).coerceAtMost(1f), f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 50f, 1300)
        f.finishAnimation()
        assertTrue(f.drawer.visibleFraction == 0f || f.drawer.visibleFraction == 1f)

        f.drawer.closeDrawer(Gravity.LEFT, false)
        f.drawer.openDrawer(Gravity.LEFT)
        f.seekAnimation(64)
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

    @Test fun startDrawerInRtlFollowsLeftwardOpeningAndRightwardClosing() = Fixture(View.LAYOUT_DIRECTION_RTL).use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.panel.addView(TouchText(f.activity), FrameLayout.LayoutParams(200, 150))
        f.layout()
        assertEquals("START is the right edge in RTL", 100, f.panel.left)
        f.touch(MotionEvent.ACTION_DOWN, 250f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 200f, 100)
        assertEquals(0.25f, f.drawer.visibleFraction, 0.001f)
        assertEquals("A quarter of the panel enters from the right", 250f, f.panel.left + f.panel.translationX, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 200f, 400)
        f.finishAnimation()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_DOWN, 180f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 230f, 1100)
        assertEquals(0.75f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_UP, 230f, 1400)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        f.drawer.openDrawer(Gravity.START, false)
        f.touch(MotionEvent.ACTION_DOWN, 30f, 2000)
        f.touch(MotionEvent.ACTION_UP, 30f, 2050)
        f.finishAnimation()
        assertEquals("RTL scrim is to the left of the panel", 0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test @Config(qualifiers = "ldpi")
    fun shortFlingUsesActualDensityBelowOne() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        assertTrue(f.activity.resources.displayMetrics.density < 1f)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 20f, 40)
        f.touch(MotionEvent.ACTION_MOVE, 30f, 80)
        f.touch(MotionEvent.ACTION_UP, 30f, 81)
        f.finishAnimation()
        assertEquals("250 px/s is over 280 dp/s at ldpi", 1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun savingDuringAnimationRestoresRequestedStateInsteadOfEarlyFrame() {
        Fixture().use { f ->
            f.layout()
            f.drawer.openDrawer(Gravity.START)
            f.seekAnimation(24)
            assertTrue("The regression needs an early opening frame", f.drawer.visibleFraction in 0.001f..0.49f)
            val state = f.saveState()
            Fixture().use { restored ->
                restored.layout()
                restored.restoreState(state)
                assertEquals("An opening command survives recreation", 1f, restored.drawer.visibleFraction, 0.001f)
            }
        }
        Fixture().use { f ->
            f.layout()
            f.drawer.openDrawer(Gravity.START, false)
            f.drawer.closeDrawer(Gravity.START)
            f.seekAnimation(24)
            assertTrue("The regression needs an early closing frame", f.drawer.visibleFraction in 0.51f..0.999f)
            val state = f.saveState()
            Fixture().use { restored ->
                restored.layout()
                restored.restoreState(state)
                assertEquals("A closing command survives recreation", 0f, restored.drawer.visibleFraction, 0.001f)
            }
        }
    }

    @Test fun openingIsModalForAccessibilityAndClosingRestoresOriginalImportance() = Fixture().use { f ->
        f.layout()
        f.content.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        f.drawer.openDrawer(Gravity.START)
        f.seekAnimation(24)
        assertTrue(f.drawer.visibleFraction > 0f)
        assertEquals("A visible modal panel hides content even while animating",
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, f.content.importantForAccessibility)
        f.drawer.openDrawer(Gravity.START, false)
        f.drawer.closeDrawer(Gravity.START, false)
        assertEquals("Closing preserves the content's original accessibility policy",
            View.IMPORTANT_FOR_ACCESSIBILITY_YES, f.content.importantForAccessibility)
    }

    @Test fun resizingPanelDuringDragCancelsOldPointerAndNextGestureWorks() = Fixture().use { f ->
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 10f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 80f, 100)
        assertEquals(0.35f, f.drawer.visibleFraction, 0.001f)
        f.panel.layoutParams = (f.panel.layoutParams as FrameLayout.LayoutParams).apply { width = 240 }
        f.layout()
        f.finishAnimation()
        assertEquals("Changing geometry ends the old gesture at a stable endpoint", 0f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_MOVE, 200f, 150)
        f.touch(MotionEvent.ACTION_UP, 200f, 180)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 1000)
        f.touch(MotionEvent.ACTION_MOVE, 100f, 1100)
        f.touch(MotionEvent.ACTION_UP, 100f, 1400)
        f.finishAnimation()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun aNewDownAfterMissingUpCannotFreezeAnInterruptedAnimation() = Fixture().use { f ->
        f.panel.addView(TouchText(f.activity), FrameLayout.LayoutParams(200, 150))
        f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.drawer.openDrawer(Gravity.START)
        f.seekAnimation(24)
        assertTrue(f.drawer.visibleFraction in 0.001f..0.49f)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 1000)
        // Android permits a new DOWN after an interrupted input stream without a matching UP.
        f.touch(MotionEvent.ACTION_DOWN, 10f, 2000)
        f.touch(MotionEvent.ACTION_UP, 10f, 2050)
        f.finishAnimation()
        assertEquals("The original open command must still settle", 1f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun responseLinkMovementKeepsOrdinarySwipeAndLinkTap() = Fixture().use { f ->
        var linkClicks = 0
        val text = TouchText(f.activity, selectable = true).apply {
            this.text = SpannableString("Link then the rest of the response").apply {
                setSpan(object : ClickableSpan() {
                    override fun onClick(widget: View) { linkClicks++ }
                }, 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            movementMethod = LinkMovementMethod.getInstance()
        }
        f.content.addView(text, FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 40f, 0)
        f.touch(MotionEvent.ACTION_MOVE, 100f, 100)
        f.touch(MotionEvent.ACTION_UP, 100f, 400)
        f.finishAnimation()
        assertEquals("Actual response movement method preserves ordinary swipes", 1f, f.drawer.visibleFraction, 0.001f)
        f.drawer.closeDrawer(Gravity.START, false)
        f.touch(MotionEvent.ACTION_DOWN, 10f, 1000, 10f)
        f.touch(MotionEvent.ACTION_UP, 10f, 1050, 10f)
        f.finishAnimation()
        assertEquals("Clickable response spans still receive a tap", 1, linkClicks)
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun actualEditorLongPressSelectionOwnsSubsequentMovement() = Fixture().use { f ->
        val text = TouchText(f.activity, selectable = true).apply {
            movementMethod = LinkMovementMethod.getInstance()
        }
        f.content.addView(text, FrameLayout.LayoutParams(300, 150))
        f.layout()
        f.touch(MotionEvent.ACTION_DOWN, 50f, 1000, 10f)
        assertTrue("The real TextView Editor must accept long press", text.performLongClick())
        assertTrue("The runtime test needs actual selected text", text.hasSelection())
        text.actions.clear()
        f.touch(MotionEvent.ACTION_MOVE, 150f, 1600, 10f)
        f.touch(MotionEvent.ACTION_UP, 150f, 1650, 10f)
        f.finishAnimation()
        assertEquals(0f, f.drawer.visibleFraction, 0.001f)
        assertFalse("Drawer must not cancel the Editor's selected-text stream", text.actions.contains(MotionEvent.ACTION_CANCEL))
    }

    @Test fun detachAndReattachPreservePendingOpeningAndClosingIntent() = Fixture().use { f ->
        f.layout()
        f.drawer.openDrawer(Gravity.START)
        f.seekAnimation(24)
        assertTrue(f.drawer.visibleFraction in 0.001f..0.49f)
        f.host.removeView(f.drawer)
        assertEquals("Detaching commits the pending opening state", 1f, f.drawer.visibleFraction, 0.001f)
        f.host.addView(f.drawer, FrameLayout.LayoutParams(300, 600))
        f.layout()
        assertEquals(1f, f.drawer.visibleFraction, 0.001f)
        f.drawer.closeDrawer(Gravity.START)
        f.seekAnimation(24)
        assertTrue(f.drawer.visibleFraction in 0.51f..0.999f)
        f.host.removeView(f.drawer)
        assertEquals("Detaching commits the pending closing state", 0f, f.drawer.visibleFraction, 0.001f)
        f.host.addView(f.drawer, FrameLayout.LayoutParams(300, 600))
        f.layout()
        f.finishAnimation()
        assertEquals("Detached animator cannot later reopen the attached view", 0f, f.drawer.visibleFraction, 0.001f)
    }

    @Test fun disabledSystemAnimationsLeaveNoStaleAnimatorOrHalfOpenState() {
        val durationScale = ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        try {
            durationScale.invoke(null, 0f)
            Fixture().use { f ->
                f.content.addView(TouchText(f.activity), FrameLayout.LayoutParams(300, 150))
                f.layout()
                f.drawer.openDrawer(Gravity.START)
                f.finishAnimation()
                assertEquals(1f, f.drawer.visibleFraction, 0.001f)
                f.drawer.closeDrawer(Gravity.START)
                f.finishAnimation()
                assertEquals(0f, f.drawer.visibleFraction, 0.001f)
                val animator = FingerDrawerLayout::class.java.getDeclaredField("animator").apply { isAccessible = true }.get(f.drawer)
                assertNull("A completed zero-scale animator must not remain registered", animator)
                f.touch(MotionEvent.ACTION_DOWN, 10f, 1000)
                f.touch(MotionEvent.ACTION_MOVE, 60f, 1100)
                f.touch(MotionEvent.ACTION_UP, 60f, 1400)
                f.finishAnimation()
                assertEquals("Gesture release still settles when animations are disabled", 1f, f.drawer.visibleFraction, 0.001f)
            }
        } finally { durationScale.invoke(null, 1f) }
    }
}
