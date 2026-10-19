package com.example.openaichat

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/** Ordinary message swipes open the drawer; scrolling and text selection keep their gesture. */
class FingerDrawerLayout(context: Context) : FrameLayout(context) {
    companion object {
        const val OPEN_THRESHOLD_FRACTION = 0.20f
        const val FLING_VELOCITY_DP_PER_SEC = 280f
        private val SETTLE_INTERPOLATOR = PathInterpolator(0.2f, 0f, 0f, 1f)

        /** A fifth of the panel, or a deliberate short fling, works equally in either direction. */
        fun decideSettleTarget(
            startFraction: Float,
            currentFraction: Float,
            velocityXdpPerSec: Float,
            lastDeltaX: Float
        ): Float {
            val start = startFraction.coerceIn(0f, 1f)
            val current = currentFraction.coerceIn(0f, 1f)
            if (abs(current - start) >= 0.035f && abs(velocityXdpPerSec) >= FLING_VELOCITY_DP_PER_SEC) {
                if (velocityXdpPerSec > 0f && lastDeltaX > 0f) return 1f
                if (velocityXdpPerSec < 0f && lastDeltaX < 0f) return 0f
            }
            // A gesture can take over a settling animation at any visible position.
            if (start > 0.001f && start < 0.999f) return if (current >= 0.5f) 1f else 0f
            return if (start < 0.5f) {
                if (current >= start + OPEN_THRESHOLD_FRACTION) 1f else 0f
            } else {
                if (current <= start - OPEN_THRESHOLD_FRACTION) 0f else 1f
            }
        }
    }

    var swipeSurface: View? = null
    var visibleFraction = 0f
        private set
    private val configuration = ViewConfiguration.get(context)
    private val slop = configuration.scaledTouchSlop
    private val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastDeltaX = 0f
    private var startFraction = 0f
    private var gesturePanelWidth = 1
    private var gestureContainerWidth = 0
    private var gestureOpeningDirection = 1f
    private var eligible = false
    private var dragging = false
    private var outside = false
    private var aborted = false
    private var touchedText: TextView? = null
    private var settlingRecycler: RecyclerView? = null
    private var recyclerFlingClaim = false
    private var dispatchingDown = false
    private var interruptedTarget: Float? = null
    private var velocityTracker: VelocityTracker? = null
    private var animator: ValueAnimator? = null
    private var settleTarget = 0f
    private var accessibilityContent: View? = null
    private var previousContentAccessibility = IMPORTANT_FOR_ACCESSIBILITY_AUTO
    private val scrim = Paint().apply { color = Color.BLACK }
    private val panel get() = if (childCount > 1) getChildAt(1) else null
    private val panelWidth get() = panel?.let { if (it.width > 0) it.width else it.layoutParams.width }?.coerceAtLeast(1) ?: 1
    private val openingDirection: Float get() {
        val gravity = (panel?.layoutParams as? LayoutParams)?.gravity ?: Gravity.LEFT
        return if (Gravity.getAbsoluteGravity(gravity, layoutDirection) and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.RIGHT) -1f else 1f
    }

    init { clipChildren = true }

    fun isDrawerOpen(gravity: Int) = visibleFraction == 1f
    fun isDrawerVisible(gravity: Int) = visibleFraction > 0f
    fun openDrawer(gravity: Int, animate: Boolean = true) = command(1f, animate)
    fun closeDrawer(gravity: Int, animate: Boolean = true) = command(0f, animate)
    fun closeDrawers() = command(0f, true)

    private fun command(target: Float, animate: Boolean) {
        // A button, Back or chat switch also ends any gesture currently owning the panel.
        if (velocityTracker != null) aborted = true
        eligible = false; dragging = false; outside = false; interruptedTarget = null
        recyclerFlingClaim = false; settlingRecycler = null
        settle(target, animate)
    }

    private fun move(fraction: Float) {
        visibleFraction = fraction.coerceIn(0f, 1f)
        panel?.apply {
            visibility = if (visibleFraction == 0f) INVISIBLE else VISIBLE
            translationX = -openingDirection * panelWidth * (1f - visibleFraction)
        }
        val content = if (childCount > 0) getChildAt(0) else null
        if (content != null && visibleFraction > 0f) {
            if (accessibilityContent !== content) {
                restoreContentAccessibility()
                previousContentAccessibility = content.importantForAccessibility
                accessibilityContent = content
            }
            content.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else restoreContentAccessibility()
        invalidate()
    }

    private fun restoreContentAccessibility() {
        accessibilityContent?.importantForAccessibility = previousContentAccessibility
        accessibilityContent = null
    }

    private fun restingTarget(): Float = interruptedTarget ?: animator?.let { settleTarget }
        ?: if (visibleFraction >= 0.5f) 1f else 0f

    private fun settle(target: Float, animate: Boolean = true, velocityXdpPerSec: Float = 0f) {
        animator?.cancel()
        animator = null
        settleTarget = target
        val distance = abs(target - visibleFraction)
        if (!animate || distance < 0.001f) { move(target); return }
        val speedBoost = (abs(velocityXdpPerSec) / 2200f).coerceIn(0f, 0.35f)
        val next = ValueAnimator.ofFloat(visibleFraction, target).apply {
            duration = (260f * distance * (1f - speedBoost)).toLong().coerceIn(110L, 260L)
            interpolator = SETTLE_INTERPOLATOR
            addUpdateListener { move(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (animator === animation) animator = null
                }
            })
        }
        // Register before start(): disabled system animations may finish synchronously.
        animator = next
        next.start()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (velocityTracker != null && (eligible || dragging || outside || recyclerFlingClaim || interruptedTarget != null) &&
            (gesturePanelWidth != panelWidth || gestureContainerWidth != width || gestureOpeningDirection != openingDirection)) {
            val target = restingTarget()
            eligible = false; dragging = false; outside = false; aborted = true; interruptedTarget = null
            recyclerFlingClaim = false; settlingRecycler = null
            settle(target)
            parent?.requestDisallowInterceptTouchEvent(false)
        }
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

    private fun beginGesture(event: MotionEvent) {
        // A new DOWN can replace a stream whose UP/CANCEL was lost. Preserve its pending
        // settle before resetting ownership, rather than leaving a paused panel between states.
        val previousTarget = if (velocityTracker != null) restingTarget() else null
        resetGesture()
        downX = event.x; downY = event.y; lastX = event.x; downTime = event.eventTime
        startFraction = visibleFraction
        gesturePanelWidth = panelWidth; gestureContainerWidth = width; gestureOpeningDirection = openingDirection
        outside = visibleFraction > 0f && panel?.let {
            event.x < it.left + it.translationX || event.x >= it.right + it.translationX ||
                event.y < it.top + it.translationY || event.y >= it.bottom + it.translationY
        } == true
        val surface = swipeSurface
        eligible = visibleFraction > 0f || (surface != null && surface.isShown &&
            surface.width > 0 && surface.height > 0 && run {
                val rect = Rect(0, 0, surface.width, surface.height)
                offsetDescendantRectToMyCoords(surface, rect)
                rect.contains(event.x.toInt(), event.y.toInt())
            })
        val selfGlobal = Rect()
        var current = if (getGlobalVisibleRect(selfGlobal)) {
            hit(this, selfGlobal.left + event.x.toInt(), selfGlobal.top + event.y.toInt())
        } else hit(this, event.rawX.toInt(), event.rawY.toInt())
        while (!outside && current != null && current !== this) {
            if (current is TextView && touchedText == null) touchedText = current
            // Reserve the entire code/table gesture, including a swipe at its scroll boundary.
            if (current is HorizontalScrollView || current is WebView || current is EditText ||
                current.canScrollHorizontally(-1) || current.canScrollHorizontally(1)) eligible = false
            current = current.parent as? View
        }
        if (touchedText?.hasSelection() == true) eligible = false
        // RecyclerView claims a DOWN that stops its previous fling before marking
        // itself DRAGGING. Keep taps with it, but arbitrate a new horizontal drag.
        if (eligible && startFraction == 0f && surface is RecyclerView &&
            surface.scrollState == RecyclerView.SCROLL_STATE_SETTLING) settlingRecycler = surface
        if (eligible || outside) {
            interruptedTarget = animator?.let { settleTarget } ?: previousTarget
            animator?.cancel()
        } else if (animator == null && previousTarget != null) {
            settle(previousTarget)
        }
        velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
    }

    /** Track each dispatch once, including events delivered to a child before capture. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) beginGesture(event)
        else {
            velocityTracker?.addMovement(event)
            val step = event.x - lastX
            if (abs(step) > 0.5f) lastDeltaX = step
            lastX = event.x
            if (event.pointerCount != 1) { eligible = false; recyclerFlingClaim = false }
        }
        if (event.actionMasked == MotionEvent.ACTION_MOVE && recyclerFlingClaim) {
            eligible = true
            if (canCapture(event)) {
                recyclerFlingClaim = false
                // This clears only the previous-fling claim. Current child requests
                // cancel the candidate in requestDisallowInterceptTouchEvent below.
                super.requestDisallowInterceptTouchEvent(false)
            } else {
                if (!eligible || aborted) recyclerFlingClaim = false
                eligible = false
                // RV is already DRAGGING after stopping its fling. Delivering tiny
                // jitter now would scroll a pixel and renew its claim before either
                // direction reaches touchSlop. Leave the tap/next deliberate move
                // with its native target; all samples still reach our velocity tracker.
                if (recyclerFlingClaim && abs(event.x - downX) <= slop &&
                    abs(event.y - downY) <= slop) return true
            }
        }
        dispatchingDown = event.actionMasked == MotionEvent.ACTION_DOWN
        val handled = try { super.dispatchTouchEvent(event) } finally { dispatchingDown = false }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && recyclerFlingClaim) {
            // Keep ownership in our pending policy, not cached ViewGroup flags:
            // otherwise an intermediate parent can swallow a later real child
            // requestDisallow(true) as a duplicate before we ever observe it.
            settlingRecycler?.parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            // A child may own the whole sequence, including an interrupted animation's tap.
            interruptedTarget?.let { settle(it) }
            resetGesture()
        }
        return handled
    }

    private fun computeVelocityDpPerSec(): Float {
        val tracker = velocityTracker ?: return 0f
        tracker.computeCurrentVelocity(1000, configuration.scaledMaximumFlingVelocity.toFloat())
        return tracker.xVelocity / density * gestureOpeningDirection
    }

    private fun resetGesture() {
        velocityTracker?.recycle(); velocityTracker = null
        eligible = false; dragging = false; outside = false; aborted = false
        lastDeltaX = 0f; touchedText = null; interruptedTarget = null
        settlingRecycler = null; recyclerFlingClaim = false
        super.requestDisallowInterceptTouchEvent(false)
    }

    private fun canCapture(event: MotionEvent): Boolean {
        if (!eligible || aborted || event.pointerCount != 1) return false
        if (touchedText?.hasSelection() == true ||
            (touchedText?.isTextSelectable == true &&
                event.eventTime - downTime >= ViewConfiguration.getLongPressTimeout())) {
            eligible = false
            return false
        }
        val dx = (event.x - downX) * gestureOpeningDirection
        val dy = event.y - downY
        if (abs(dy) > slop && abs(dy) >= abs(dx)) { eligible = false; return false }
        return abs(dx) > slop && abs(dx) > abs(dy) * 1.8f && (startFraction > 0f || dx > 0f)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return outside
        if (event.actionMasked == MotionEvent.ACTION_MOVE && canCapture(event)) {
            dragging = true
            interruptedTarget = null
            move(startFraction + (event.x - downX) * gestureOpeningDirection / gesturePanelWidth)
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        return dragging
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Selection handles, long press and nested scroll are real owners of their stream.
        if (disallowIntercept && !dragging) {
            recyclerFlingClaim = dispatchingDown && eligible &&
                settlingRecycler?.scrollState == RecyclerView.SCROLL_STATE_SETTLING
            eligible = false
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (aborted) return true
        if (event.pointerCount != 1 || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (dragging || outside) {
                interruptedTarget = null
                settle(if (visibleFraction >= 0.5f) 1f else 0f)
            }
            eligible = false; dragging = false; outside = false; aborted = true
            parent?.requestDisallowInterceptTouchEvent(false)
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && canCapture(event)) { dragging = true; interruptedTarget = null }
                if (dragging) move(startFraction + (event.x - downX) * gestureOpeningDirection / gesturePanelWidth)
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) move(startFraction + (event.x - downX) * gestureOpeningDirection / gesturePanelWidth)
                if (dragging || outside) {
                    val vx = computeVelocityDpPerSec()
                    val target = if (outside && !dragging) 0f
                    else decideSettleTarget(startFraction, visibleFraction, vx, lastDeltaX * gestureOpeningDirection)
                    interruptedTarget = null
                    settle(target, true, vx)
                }
            }
        }
        return true
    }

    override fun onSaveInstanceState(): Parcelable {
        val parentState = super.onSaveInstanceState()
        return Bundle().apply { putParcelable("parent", parentState); putBoolean("open", restingTarget() == 1f) }
    }

    @Suppress("DEPRECATION")
    override fun onRestoreInstanceState(state: Parcelable?) {
        if (state is Bundle) {
            super.onRestoreInstanceState(state.getParcelable("parent"))
            command(if (state.getBoolean("open")) 1f else 0f, false)
        } else super.onRestoreInstanceState(state)
    }

    override fun onDetachedFromWindow() {
        val target = restingTarget()
        animator?.cancel()
        animator = null
        resetGesture()
        move(target)
        super.onDetachedFromWindow()
    }
}
