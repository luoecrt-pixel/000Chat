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
    @Test fun textGestureFollowsHeldFingerAndSnapsNearestOnRelease() {
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
        fun touch(action: Int, x: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, x, 50f, 0)
            try { drawer.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        try {
            touch(MotionEvent.ACTION_DOWN, 10f, 0)
            touch(MotionEvent.ACTION_MOVE, 80f, 20)
            assertEquals(0.35f, drawer.visibleFraction, 0.02f)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals("Holding must not start an animation", 0.35f, drawer.visibleFraction, 0.02f)
            touch(MotionEvent.ACTION_UP, 80f, 340)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(0f, drawer.visibleFraction, 0.001f)
            touch(MotionEvent.ACTION_DOWN, 10f, 700)
            touch(MotionEvent.ACTION_MOVE, 130f, 730)
            touch(MotionEvent.ACTION_UP, 130f, 750)
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            assertEquals(1f, drawer.visibleFraction, 0.001f)
        } finally { controller.pause().stop().destroy() }
    }
}
