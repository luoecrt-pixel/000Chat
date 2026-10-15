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
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Ancestor gesture arbitration also receives swipes starting on a message TextView. */
class FingerDrawerLayout(context: Context) : FrameLayout(context) {
    companion object {
        const val OPEN_THRESHOLD_FRACTION = 0.30f
        const val FLING_VELOCITY_DP_PER_SEC = 420f
        private val SETTLE_INTERPOLATOR = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)

        /**
         * Pure release decision policy (Task 4):
         * 1. A deliberate horizontal fling (>= FLING_VELOCITY_DP_PER_SEC) in the direction
         *    of the final finger motion settles to that direction's target.
         * 2. A slow drag settles to the new state once it crosses 30% of travel from
         *    startFraction (>= 0.30 when opening from closed; <= 0.70 when closing from open).
         */
        fun decideSettleTarget(
            startFraction: Float,
            currentFraction: Float,
            velocityXdpPerSec: Float,
            lastDeltaX: Float
        ): Float {
            val clampedStart = startFraction.coerceIn(0f, 1f)
            val clampedCurrent = currentFraction.coerceIn(0f, 1f)
            if (abs(velocityXdpPerSec) >= FLING_VELOCITY_DP_PER_SEC) {
                if (velocityXdpPerSec > 0f && lastDeltaX >= -0.5f) return 1f
                if (velocityXdpPerSec < 0f && lastDeltaX <= 0.5f) return 0f
            }
            if ( abs(lastDeltaX) > 2f && abs(velocityXdpPerSec) >= FLING_VELOCITY_DP_PER_SEC * 0.6f) {
                return if (lastDeltaX > 0f) 1f else 0f
            }
            return if (clampedStart < 0.5f) {
                if (clampedCurrent - clampedStart >= OPEN_THRESHOLD_FRACTION) 1f else 0f
            } else {
                if (clampedStart - clampedCurrent >= OPEN_THRESHOLD_FRACTION) 0f else 1f
            }
        }
    }

    var swipeSurface: View? = null
    var visibleFraction = 0f
        private set
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = context.resources.displayMetrics.density.coerceAtLeast(1f)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastDeltaX = 0f
    private var startFraction = 0f
    private var eligible = false
    private var dragging = false
    private var outside = false
    private var horizontalChild: View? = null
    private var velocityTracker: VelocityTracker? = null
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

    private fun settle(target: Float, animate: Boolean = true, velocityXdpPerSec: Float = 0f) {
        animator?.cancel()
        val distance = abs(target - visibleFraction)
        if (!animate || distance < 0.001f) { move(target); return }
        val baseDuration = (250f * distance).toLong()
        val speedBoost = (abs(velocityXdpPerSec) / 2200f).coerceIn(0f, 0.45f)
        val durationMs = ((baseDuration * (1f - speedBoost)).toLong()).coerceIn(95L, 260L)
        animator = ValueAnimator.ofFloat(visibleFraction, target).apply {
            duration = durationMs
            interpolator = SETTLE_INTERPOLATOR
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

    private fun trackMovement(event: MotionEvent) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain()
        velocityTracker?.addMovement(event)
        val step = event.x - lastX
        if (abs(step) > 0.5f) lastDeltaX = step
        lastX = event.x
    }

    private fun computeVelocityDpPerSec(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
        return tracker.xVelocity / density
    }

    private fun recycleVelocity() {
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private fun canCapture(event: MotionEvent): Boolean {
        if (!eligible || event.pointerCount != 1) return false
        val dx = event.x - downX
        val dy = event.y - downY
        if (abs(dy) > slop && abs(dy) >= abs(dx)) { eligible = false; return false }
        if (abs(dx) <= slop || abs(dx) <= abs(dy) * 1.8f || (startFraction == 0f && dx <= 0f)) return false
        if (horizontalChild?.canScrollHorizontally(if (dx > 0f) -1 else 1) == true) { eligible = false; return false }
        return true
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                animator?.cancel()
                recycleVelocity()
                downX = event.x; downY = event.y; lastX = event.x; lastDeltaX = 0f
                trackMovement(event)
                startFraction = visibleFraction; dragging = false
                outside = visibleFraction > 0f && event.x > panelWidth * visibleFraction
                val selfGlobal = Rect()
                var current = if (getGlobalVisibleRect(selfGlobal)) {
                    hit(this, selfGlobal.left + event.x.toInt(), selfGlobal.top + event.y.toInt())
                } else {
                    hit(this, event.rawX.toInt(), event.rawY.toInt())
                }
                val surface = swipeSurface
                eligible = visibleFraction > 0f || (surface != null && surface.isShown &&
                    surface.width > 0 && surface.height > 0 && run {
                        val rect = Rect(0, 0, surface.width, surface.height)
                        offsetDescendantRectToMyCoords(surface, rect)
                        rect.contains(event.x.toInt(), event.y.toInt())
                    })
                horizontalChild = null
                while (current != null && current !== this) {
                    if (current === swipeSurface) eligible = true
                    if (current.canScrollHorizontally(-1) || current.canScrollHorizontally(1)) horizontalChild = current
                    current = current.parent as? View
                }
                return outside
            }
            MotionEvent.ACTION_MOVE -> {
                trackMovement(event)
                if (canCapture(event)) {
                    dragging = true
                    move(startFraction + (event.x - downX) / panelWidth)
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> {
                eligible = false
                if (!dragging) recycleVelocity()
            }
        }
        return dragging
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        if (!disallowIntercept || !eligible) super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                trackMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount != 1) {
                    val vx = computeVelocityDpPerSec()
                    settle(decideSettleTarget(startFraction, visibleFraction, vx, lastDeltaX), true, vx)
                    eligible = false; dragging = false; outside = false
                    recycleVelocity()
                    return true
                }
                trackMovement(event)
                val dx = event.x - downX
                if (!dragging && canCapture(event)) dragging = true
                if (dragging || (outside && abs(dx) > slop)) {
                    dragging = true
                    move(startFraction + dx / panelWidth)
                }
            }
            MotionEvent.ACTION_UP -> {
                trackMovement(event)
                if (dragging && event.pointerCount == 1) move(startFraction + (event.x - downX) / panelWidth)
                val vx = computeVelocityDpPerSec()
                val target = if (outside && !dragging) 0f
                else decideSettleTarget(startFraction, visibleFraction, vx, lastDeltaX)
                settle(target, true, vx)
                eligible = false; dragging = false; outside = false
                recycleVelocity()
                parent?.requestDisallowInterceptTouchEvent(false)
            }
            MotionEvent.ACTION_CANCEL -> {
                val vx = computeVelocityDpPerSec()
                settle(decideSettleTarget(startFraction, visibleFraction, vx, lastDeltaX), true, vx)
                eligible = false; dragging = false; outside = false
                recycleVelocity()
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

    override fun onDetachedFromWindow() {
        animator?.cancel()
        recycleVelocity()
        super.onDetachedFromWindow()
    }
}
