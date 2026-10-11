package com.example.openaichat

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.abs

/** Ancestor gesture arbitration also receives swipes starting on a message TextView. */
class FingerDrawerLayout(context: Context) : FrameLayout(context) {
    var swipeSurface: View? = null
    var visibleFraction = 0f
        private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var startFraction = 0f
    private var eligible = false
    private var dragging = false
    private var outside = false
    private var horizontalChild: View? = null
    private var animator: ValueAnimator? = null
    private val scrim = Paint().apply { color = Color.BLACK }
    private val panel get() = if (childCount > 1) getChildAt(1) else null
    private val panelWidth get() = panel?.let { if (it.width > 0) it.width else it.layoutParams.width }?.coerceAtLeast(1) ?: 1
    init { clipChildren = true }
    fun isDrawerOpen(gravity: Int) = visibleFraction == 1f
    fun isDrawerVisible(gravity: Int) = visibleFraction > 0f
    fun openDrawer(gravity: Int, animate: Boolean = true) = settle(1f, animate)
    fun closeDrawer(gravity: Int, animate: Boolean = true) = settle(0f, animate)
    fun closeDrawers() = settle(0f)
    private fun move(fraction: Float) {
        visibleFraction = fraction.coerceIn(0f, 1f)
        panel?.apply {
            visibility = if (visibleFraction == 0f) INVISIBLE else VISIBLE
            translationX = -panelWidth * (1f - visibleFraction)
        }
        if (childCount > 0) getChildAt(0).importantForAccessibility = if (visibleFraction == 1f)
            IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS else IMPORTANT_FOR_ACCESSIBILITY_AUTO
        invalidate()
    }
    private fun settle(target: Float, animate: Boolean = true) {
        animator?.cancel()
        if (!animate || abs(target - visibleFraction) < 0.001f) { move(target); return }
        animator = ValueAnimator.ofFloat(visibleFraction, target).apply {
            duration = (180L * abs(target - visibleFraction)).toLong().coerceAtLeast(60L)
            addUpdateListener { move(it.animatedValue as Float) }
            start()
        }
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        move(visibleFraction)
    }
    override fun drawChild(canvas: Canvas, child: View, drawingTime: Long): Boolean {
        val drawn = super.drawChild(canvas, child, drawingTime)
        if (childCount > 0 && child === getChildAt(0) && visibleFraction > 0f) {
            scrim.alpha = (180f * visibleFraction).toInt()
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), scrim)
        }
        return drawn
    }
    private fun hit(view: View, x: Int, y: Int): View? {
        val bounds = Rect()
        if (!view.isShown || !view.getGlobalVisibleRect(bounds) || !bounds.contains(x, y)) return null
        if (view is ViewGroup) for (i in view.childCount - 1 downTo 0) hit(view.getChildAt(i), x, y)?.let { return it }
        return view
    }
    private fun canCapture(event: MotionEvent): Boolean {
        if (!eligible || event.pointerCount != 1 || event.eventTime - downAt >= ViewConfiguration.getLongPressTimeout()) return false
        val dx = event.x - downX; val dy = event.y - downY
        if (abs(dy) > slop && abs(dy) >= abs(dx)) { eligible = false; return false }
        if (abs(dx) <= slop || abs(dx) <= abs(dy) * 2.5f || (startFraction == 0f && dx <= 0f)) return false
        if (horizontalChild?.canScrollHorizontally(if (dx > 0f) -1 else 1) == true) { eligible = false; return false }
        return true
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                downX = event.x; downY = event.y; downAt = event.eventTime
                startFraction = visibleFraction; dragging = false
                outside = visibleFraction > 0f && event.x > panelWidth * visibleFraction
                var target = hit(this, event.rawX.toInt(), event.rawY.toInt())
                // Parent-owned coordinates stay valid when the touch began on
                // a nested TextView and raw screen coordinates differ in tests.
                val surface = swipeSurface
                eligible = visibleFraction > 0f || (surface != null && surface.isShown &&
                    surface.width > 0 && surface.height > 0 && run {
                        val rect = Rect(0, 0, surface.width, surface.height)
                        offsetDescendantRectToMyCoords(surface, rect)
                        rect.contains(event.x.toInt(), event.y.toInt())
                    })
                horizontalChild = null
                while (target != null && target !== this) {
                    if (target === swipeSurface) eligible = true
                    if (target.canScrollHorizontally(-1) || target.canScrollHorizontally(1)) horizontalChild = target
                    target = target.parent as? View
                }
                return outside
            }
            MotionEvent.ACTION_MOVE -> {
                if (canCapture(event)) {
                    dragging = true
                    // Android may deliver the capture MOVE only as CANCEL to the
                    // previous child. Update the panel here, not on the next MOVE.
                    move(startFraction + (event.x - downX) / panelWidth)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> eligible = false
        }
        return dragging
    }
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Keep seeing MOVE until its direction is known; vertical scrolling and long press still win.
        if (!disallowIntercept || !eligible) super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) { settle(if (visibleFraction >= 0.5f) 1f else 0f); eligible = false; dragging = false; return true }
                val dx = event.x - downX
                if (!dragging && canCapture(event)) dragging = true
                if (dragging || (outside && abs(dx) > slop)) {
                    dragging = true
                    move(startFraction + dx / panelWidth)
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging && event.pointerCount == 1) move(startFraction + (event.x - downX) / panelWidth)
                settle(if (outside && !dragging) 0f else if (visibleFraction >= 0.5f) 1f else 0f)
                eligible = false; dragging = false; outside = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                settle(if (visibleFraction >= 0.5f) 1f else 0f)
                eligible = false; dragging = false; outside = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
    override fun onSaveInstanceState(): Parcelable {
        val parentState = super.onSaveInstanceState()
        return Bundle().apply { putParcelable("parent", parentState); putBoolean("open", visibleFraction >= 0.5f) }
    }
    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) { super.onRestoreInstanceState(state.getParcelable("parent")); move(if (state.getBoolean("open")) 1f else 0f) }
        else super.onRestoreInstanceState(state)
    }
    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}
