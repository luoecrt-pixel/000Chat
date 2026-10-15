package com.example.openaichat

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
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
    @Test fun textGestureFollowsHeldFingerAndUses30PercentReleaseThreshold() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val drawer = FingerDrawerLayout(activity)
        val content = FrameLayout(activity)
        content.addView(TextView(activity).apply { text = "Clickable message"; isClickable = true }, FrameLayout.LayoutParams(300, 150))
        drawer.addView(content, FrameLayout.LayoutParams(300, 600))
        drawer.addView(FrameLayout(activity), FrameLayout.LayoutParams(200, 600))
        drawer.swipeSurface = content
        activity.setContentView(drawer)
        drawer.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        drawer.layout(0, 0, 300, 600)
        var downAt = 0L
        fun touch(action: Int, x: Float, time: Long) {
            if (action == MotionEvent.ACTION_DOWN) downAt = time
            val event = MotionEvent.obtain(downAt, time, action, x, 50f, 0)
            try { drawer.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        try {
            touch(MotionEvent.ACTION_DOWN, 10f, 0)
            for (step in 1..10) touch(MotionEvent.ACTION_MOVE, 10f + step * 5f, step * 50L)
            assertEquals(0.25f, drawer.visibleFraction, 0.02f)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals("Holding must not start an animation", 0.25f, drawer.visibleFraction, 0.02f)
            touch(MotionEvent.ACTION_UP, 60f, 800)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(0f, drawer.visibleFraction, 0.001f)
            touch(MotionEvent.ACTION_DOWN, 10f, 1200)
            touch(MotionEvent.ACTION_MOVE, 130f, 1230)
            touch(MotionEvent.ACTION_UP, 130f, 1250)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(1f, drawer.visibleFraction, 0.001f)
        } finally { controller.pause().stop().destroy() }
    }

    @org.junit.Test
    fun decideSettleTargetOpensAt30PercentAndHonorsFlingAndReversal() {
        // Slow drag from closed (0f): 29% closes, 30% and 31% open.
        org.junit.Assert.assertEquals(0f, FingerDrawerLayout.decideSettleTarget(0f, 0.29f, 0f, 1f), 0.001f)
        org.junit.Assert.assertEquals(1f, FingerDrawerLayout.decideSettleTarget(0f, 0.30f, 0f, 1f), 0.001f)
        org.junit.Assert.assertEquals(1f, FingerDrawerLayout.decideSettleTarget(0f, 0.31f, 0f, 1f), 0.001f)

        // Slow drag from open (1f): 71% stays open (29% closed), 70% and 69% close (>=30% closed).
        org.junit.Assert.assertEquals(1f, FingerDrawerLayout.decideSettleTarget(1f, 0.71f, 0f, -1f), 0.001f)
        org.junit.Assert.assertEquals(0f, FingerDrawerLayout.decideSettleTarget(1f, 0.70f, 0f, -1f), 0.001f)
        org.junit.Assert.assertEquals(0f, FingerDrawerLayout.decideSettleTarget(1f, 0.69f, 0f, -1f), 0.001f)

        // Short fast flick right (<30% travel, e.g. 12%) opens; fast flick left at 88% closes.
        org.junit.Assert.assertEquals(1f, FingerDrawerLayout.decideSettleTarget(0f, 0.12f, 650f, 8f), 0.001f)
        org.junit.Assert.assertEquals(0f, FingerDrawerLayout.decideSettleTarget(1f, 0.88f, -650f, -8f), 0.001f)

        // Reversal: dragged right to 75%, then flicked left before release -> closes.
        org.junit.Assert.assertEquals(0f, FingerDrawerLayout.decideSettleTarget(0f, 0.75f, -600f, -12f), 0.001f)
    }
}
