package dev.narze.pood

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Root of the overlay window. Drags move the window using raw screen coordinates (Compose's local coordinates shift as
 * the window moves under the finger); taps pass through to the Compose content, and so do sideways drags that start on
 * a row [canSwipe] claims, so the row can be swiped.
 */
@SuppressLint("ViewConstructor")
class OverlayFrame(
    context: Context,
    private val onDragStart: () -> Unit,
    private val onDrag: (dx: Float, dy: Float) -> Unit,
    private val onDragEnd: () -> Unit,
    private val canSwipe: (x: Float, y: Float) -> Boolean = { _, _ -> false },
) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var onSwipeRow = false

    /** Set once this gesture has been left to the content (a row swipe); the window then stays put until the finger lifts. */
    private var yielded = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; lastX = ev.rawX; lastY = ev.rawY
                dragging = false
                yielded = false
                onSwipeRow = canSwipe(ev.x, ev.y)
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && !yielded) {
                val dx = abs(ev.rawX - downX)
                val dy = abs(ev.rawY - downY)
                if (onSwipeRow && dx > slop && dx > dy) yielded = true
                else if (dx > slop || dy > slop) {
                    dragging = true
                    lastX = ev.rawX; lastY = ev.rawY
                    onDragStart()
                }
            }
        }
        return dragging
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; lastX = ev.rawX; lastY = ev.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) {
                    dragging = true
                    onDragStart()
                }
                if (dragging) {
                    onDrag(ev.rawX - lastX, ev.rawY - lastY)
                    lastX = ev.rawX; lastY = ev.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) onDragEnd()
                dragging = false
            }
        }
        return true
    }
}
