package com.example.openaichat

import android.app.Application
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GravityCompat
import androidx.recyclerview.widget.RecyclerView
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
import java.time.Duration

/** The same Window dispatch route as the device gesture acceptance test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class DrawerActivityGestureRegressionTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private var controller: ActivityController<MainActivity>? = null
    private lateinit var activity: MainActivity
    private lateinit var drawer: FingerDrawerLayout
    private val trace = StringBuilder()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        context.getSharedPreferences("app", 0).edit().clear()
            .putString("provider", "openrouter").putString("model", "anthropic/claude-sonnet-5.5")
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", 0).edit().clear().commit()
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        activity = controller!!.get()
        drawer = field(activity, "drawerLayout") as FingerDrawerLayout
        activity.newChat()
        shadowOf(Looper.getMainLooper()).idle()
        layoutWindow()
    }

    @After fun after() {
        controller?.pause()?.stop()?.destroy()
        context.getSharedPreferences("app", 0).edit().clear().commit()
    }

    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    private fun layoutWindow() {
        activity.window.decorView.apply {
            measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY))
            layout(0, 0, 1080, 1920)
        }
    }

    private fun bounds(view: View): Rect {
        val rect = Rect()
        assertTrue("Gesture view must be visible: $view", view.getLocalVisibleRect(rect))
        val location = IntArray(2)
        view.getLocationInWindow(location)
        rect.offset(location[0], location[1])
        return rect
    }

    private fun record(step: Int, handled: Boolean) {
        trace.append("step=").append(step).append(" handled=").append(handled)
            .append(" fraction=").append(drawer.visibleFraction)
        for (name in listOf("eligible", "dragging", "aborted", "gesturePanelWidth", "gestureContainerWidth")) {
            trace.append(' ').append(name).append('=').append(field(drawer, name))
        }
        trace.append(" drawer=").append(bounds(drawer))
            .append(" surface=").append(bounds(field(activity, "scroll") as View)).append('\n')
    }

    private fun swipe(x0: Float, y0: Float, x1: Float, y1: Float) {
        val down = SystemClock.uptimeMillis()
        for (step in 0..12) {
            if (step > 0) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(25))
            val fraction = step / 12f
            val action = when (step) {
                0 -> MotionEvent.ACTION_DOWN
                12 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x0 + (x1 - x0) * fraction, y0 + (y1 - y0) * fraction, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            try { record(step, activity.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
    }

    @Test fun emptyNewChatVerticalThenHorizontalWindowGesturesOpenAndCloseDrawer() {
        val scroll = field(activity, "scroll") as RecyclerView
        assertEquals(0, scroll.adapter!!.itemCount)
        val chatBounds = bounds(scroll)
        assertTrue("Chat surface must be laid out", chatBounds.width() > 200)
        swipe(chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .25f,
            chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * .75f)
        assertFalse(trace.toString(), drawer.isDrawerVisible(GravityCompat.START))
        swipe(chatBounds.left + chatBounds.width() * .2f, chatBounds.exactCenterY(),
            chatBounds.left + chatBounds.width() * .9f, chatBounds.exactCenterY())
        assertTrue(trace.toString(), drawer.isDrawerOpen(GravityCompat.START))
        val panelBounds = bounds(drawer.getChildAt(1))
        swipe(panelBounds.left + panelBounds.width() * .85f, panelBounds.exactCenterY(),
            panelBounds.left + panelBounds.width() * .1f, panelBounds.exactCenterY())
        assertFalse(trace.toString(), drawer.isDrawerVisible(GravityCompat.START))
    }
}
