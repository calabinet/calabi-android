package net.calabi.app.rdp

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Pure pointer acceleration and inertial scroll decay math (JVM-testable). */
internal object RdpPointerMath {
    /** Velocity-aware trackpad gain: fine control at slow speed, fast cross-screen travel on flicks. */
    fun trackpadGain(dxPx: Float, dyPx: Float, dtMs: Long, density: Float): Float {
        val safeDensity = density.coerceAtLeast(1f)
        val dt = dtMs.coerceIn(4L, 64L).toFloat()
        val speedDpPerMs = hypot(dxPx, dyPx) / safeDensity / dt
        return when {
            speedDpPerMs < 0.18f -> 0.65f + (speedDpPerMs / 0.18f) * 0.35f
            speedDpPerMs < 0.75f -> 1.0f + ((speedDpPerMs - 0.18f) / 0.57f) * 0.55f
            else -> (1.55f + ((speedDpPerMs - 0.75f) / 1.25f) * 0.85f).coerceAtMost(2.4f)
        }
    }

    /** Exponential decay factor for smooth scroll fling. */
    fun flingDecay(dtMs: Long): Float = exp(-dtMs.coerceIn(1L, 64L) / 240f)
}

/** Delay a single-finger press until movement/up so a two-finger tap never left-clicks. */
internal class RdpGestures(
    private val viewport: RdpViewport,
    private val slop: Float,
    private val wheelStep: Float,
    private val density: Float,
    private val mouse: (Int, Int, Int) -> Unit,
    private val touchOut: (Int, Int, Int, Int) -> Unit,
    private val changed: () -> Unit,
    private val rightConsumed: () -> Unit,
    private val dragConsumed: () -> Unit = {},
    private val scheduleLongPress: (Long, Runnable) -> Unit = { _, _ -> },
    private val cancelLongPress: (Runnable) -> Unit = {},
    private val onTapFeedback: (Float, Float, Boolean) -> Unit = { _, _, _ -> },
    private val onFlingChanged: (Boolean) -> Unit = {},
) {
    companion object {
        const val LONG_PRESS_MS = 450L
        const val TOUCH_BEGIN = 0
        const val TOUCH_UPDATE = 1
        const val TOUCH_END = 2
        const val TOUCH_CANCEL = 3
        private const val FINE_WHEEL_DELTA = 40
    }

    var rightClick = false
    var panMode = false
    var trackpad = false
    var nativeTouch = false
    var hasModifiers = false
    var dragArmed = false
    var cursorX = 0f; private set
    var cursorY = 0f; private set
    val isPressed: Boolean get() = pressed != 0
    private var cursorReady = false
    private var moved = false
    private var doubleTap = false
    private var lastTapAt = 0L
    private var lastTapX = 0f; private var lastTapY = 0f
    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var lastMoveAt = 0L
    private var valid = false
    private var pressed = 0
    private var multi = false
    private var pairEnded = false
    private var multiAt = 0L
    private var startSpan = 0f; private var lastSpan = 0f
    private var centerX = 0f; private var centerY = 0f
    private var startCenterX = 0f; private var startCenterY = 0f
    private var kind = 0 // 0=undecided, 1=scroll, 2=zoom+pan, 3=pan, 4=cancelled, 5=three-finger pan
    private var pairTap = false
    private var firstX = 0f; private var firstY = 0f
    private var secondX = 0f; private var secondY = 0f
    private var scrollX = 0f; private var scrollY = 0f
    private var scrollVelX = 0f; private var scrollVelY = 0f
    private var lastScrollAt = 0L
    private var flingActive = false
    private var flingVelX = 0f; private var flingVelY = 0f
    private var flingX = 0f; private var flingY = 0f
    private var longPressFired = false
    private var rdpeiActive = false
    private var rdpeiPointerId = -1
    private var rdpeiRemoteX = 0
    private var rdpeiRemoteY = 0
    private var threeX = 0f; private var threeY = 0f

    private val longPressTask = Runnable {
        if (!valid || multi || moved || pressed != 0 || rdpeiActive || rightClick || dragArmed) return@Runnable
        longPressFired = true
        click(true, startX, startY)
        val feedbackX = if (trackpad) viewport.localX(cursorX) else startX
        val feedbackY = if (trackpad) viewport.localY(cursorY) else startY
        onTapFeedback(feedbackX, feedbackY, true)
    }

    private fun shouldUseNativeTouch(): Boolean =
        !trackpad && nativeTouch && !rightClick && !panMode && !hasModifiers

    private fun emit(flags: Int, x: Float, y: Float, clamp: Boolean = true) {
        val point = if (trackpad) {
            if (!prepareCursor()) return
            android.graphics.PointF(cursorX, cursorY)
        } else viewport.remote(x, y, clamp)
        point?.let { placeCursor(it.x, it.y); mouse(flags, it.x.toInt(), it.y.toInt()) }
    }

    fun prepareCursor(): Boolean {
        if (viewport.remoteWidth <= 0 || viewport.remoteHeight <= 0) return false
        if (!cursorReady) placeCursor(viewport.remoteWidth / 2f, viewport.remoteHeight / 2f)
        else if (cursorX >= viewport.remoteWidth || cursorY >= viewport.remoteHeight) placeCursor(cursorX, cursorY)
        return true
    }

    fun placeCursor(x: Float, y: Float) {
        val nextX = x.coerceIn(0f, maxOf(0, viewport.remoteWidth - 1).toFloat())
        val nextY = y.coerceIn(0f, maxOf(0, viewport.remoteHeight - 1).toFloat())
        if (cursorReady && nextX == cursorX && nextY == cursorY) return
        cursorX = nextX; cursorY = nextY
        cursorReady = viewport.remoteWidth > 0
        changed()
    }

    private fun release() {
        if (pressed != 0) emit(pressed, lastX, lastY)
        pressed = 0
    }

    private fun cancelRdpei() {
        if (!rdpeiActive) return
        val id = rdpeiPointerId
        rdpeiActive = false
        rdpeiPointerId = -1
        if (id >= 0) touchOut(TOUCH_CANCEL, id, rdpeiRemoteX, rdpeiRemoteY)
    }

    fun stopFling() {
        if (!flingActive) return
        flingActive = false
        flingVelX = 0f; flingVelY = 0f
        scrollX = 0f; scrollY = 0f
        onFlingChanged(false)
    }

    /** Advance inertial scroll by dtMs; returns true while fling should keep running. */
    fun stepFling(dtMs: Long): Boolean {
        if (!flingActive) return false
        val dt = dtMs.coerceIn(1L, 48L).toFloat()
        scrollX = (scrollX - flingVelX * dt).coerceIn(-wheelStep * 8, wheelStep * 8)
        scrollY = (scrollY + flingVelY * dt).coerceIn(-wheelStep * 8, wheelStep * 8)
        flushFineScroll(flingX, flingY)
        val decay = RdpPointerMath.flingDecay(dtMs)
        flingVelX *= decay
        flingVelY *= decay
        if (hypot(flingVelX, flingVelY) < 0.04f) {
            stopFling()
            return false
        }
        return true
    }

    fun cancel(consumeArm: Boolean = true) {
        cancelLongPress(longPressTask)
        cancelRdpei()
        stopFling()
        release()
        valid = false; multi = false; pairEnded = true; kind = 4; scrollX = 0f; scrollY = 0f
        scrollVelX = 0f; scrollVelY = 0f; lastScrollAt = 0L
        moved = false; doubleTap = false; lastTapAt = 0; longPressFired = false
        if (consumeArm && dragArmed) { dragArmed = false; dragConsumed() }
    }

    private fun click(right: Boolean, x: Float, y: Float) {
        if (if (trackpad) !prepareCursor() else viewport.remote(x, y) == null) return
        val button = if (right) 0x2000 else 0x1000
        emit(0x8000 or button, x, y); emit(button, x, y)
        if (rightClick) { rightClick = false; rightConsumed() }
    }

    /** RDP uses a signed nine-bit wheel delta; keep each event in range. */
    fun wheel(vertical: Float, horizontal: Float, x: Float, y: Float) {
        wheelDelta(
            if (vertical.isFinite()) (vertical.coerceIn(-8f, 8f) * 120).toInt() else 0,
            if (horizontal.isFinite()) (horizontal.coerceIn(-8f, 8f) * 120).toInt() else 0,
            x, y,
        )
    }

    private fun wheelDelta(verticalDelta: Int, horizontalDelta: Int, x: Float, y: Float) {
        if (verticalDelta == 0 && horizontalDelta == 0) return
        emit(0x0800, x, y)
        fun axis(initial: Int, flag: Int) {
            var delta = initial
            while (delta != 0) {
                val part = delta.coerceIn(-255, 255)
                emit(flag or (part and 0x1ff), x, y)
                delta -= part
            }
        }
        axis(verticalDelta, 0x0200); axis(horizontalDelta, 0x0400)
    }

    private fun flushFineScroll(x: Float, y: Float) {
        val horizontal = (scrollX / wheelStep).toInt()
        val vertical = (scrollY / wheelStep).toInt()
        if (horizontal != 0 || vertical != 0) {
            wheelDelta(vertical * FINE_WHEEL_DELTA, horizontal * FINE_WHEEL_DELTA, x, y)
            scrollX -= horizontal * wheelStep
            scrollY -= vertical * wheelStep
        }
    }

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                val secondTap = lastTapAt != 0L && event.eventTime - lastTapAt <= 300 && hypot(event.x - lastTapX, event.y - lastTapY) <= slop * 2
                cancel(false); doubleTap = secondTap; pairEnded = false; kind = 0
                startX = event.x; startY = event.y; lastX = event.x; lastY = event.y
                lastMoveAt = event.eventTime
                longPressFired = false
                if (shouldUseNativeTouch()) {
                    val point = viewport.remote(startX, startY)
                    valid = point != null
                    if (point != null) {
                        rdpeiActive = true
                        rdpeiPointerId = event.getPointerId(0)
                        rdpeiRemoteX = point.x.toInt()
                        rdpeiRemoteY = point.y.toInt()
                        placeCursor(point.x, point.y)
                        touchOut(TOUCH_BEGIN, rdpeiPointerId, rdpeiRemoteX, rdpeiRemoteY)
                        onTapFeedback(startX, startY, false)
                    }
                } else {
                    valid = if (trackpad) prepareCursor() else viewport.remote(startX, startY) != null
                    if (valid && !rightClick && !dragArmed && !secondTap) {
                        scheduleLongPress(LONG_PRESS_MS, longPressTask)
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelLongPress(longPressTask)
                val wasRdpei = rdpeiActive
                cancelRdpei()
                pairTap = pressed == 0 && !longPressFired && !wasRdpei || (wasRdpei && !moved)
                release()
                multi = true
                if (event.pointerCount == 3) {
                    kind = 5
                    threeX = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                    threeY = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                    return true
                }
                if (event.pointerCount != 2 || pairEnded) { kind = 4; return true }
                centerX = (event.getX(0) + event.getX(1)) / 2
                centerY = (event.getY(0) + event.getY(1)) / 2
                startCenterX = centerX; startCenterY = centerY
                firstX = event.getX(0); firstY = event.getY(0)
                secondX = event.getX(1); secondY = event.getY(1)
                startSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                lastSpan = startSpan; multiAt = event.eventTime; kind = 0
                scrollVelX = 0f; scrollVelY = 0f; lastScrollAt = event.eventTime
                valid = valid && (trackpad || viewport.remote(centerX, centerY) != null)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!valid || pairEnded || kind == 4) return true
                if (kind == 5 && event.pointerCount == 3) {
                    val tx = (event.getX(0) + event.getX(1) + event.getX(2)) / 3f
                    val ty = (event.getY(0) + event.getY(1) + event.getY(2)) / 3f
                    viewport.pan(tx - threeX, ty - threeY)
                    threeX = tx; threeY = ty
                    changed()
                    return true
                }
                if (multi && event.pointerCount == 2) {
                    val cx = (event.getX(0) + event.getX(1)) / 2
                    val cy = (event.getY(0) + event.getY(1)) / 2
                    val span = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                    if (hypot(event.getX(0) - firstX, event.getY(0) - firstY) > slop ||
                        hypot(event.getX(1) - secondX, event.getY(1) - secondY) > slop) pairTap = false
                    if (kind == 0) {
                        kind = when {
                            abs(span - startSpan) > maxOf(slop * 2, startSpan * 0.06f) -> 2
                            hypot(cx - startCenterX, cy - startCenterY) > slop -> if (panMode) 3 else 1
                            else -> 0
                        }
                        if (kind == 0) return true
                    }
                    when (kind) {
                        1 -> {
                            val dx = cx - centerX
                            val dy = cy - centerY
                            val dt = (event.eventTime - lastScrollAt).coerceAtLeast(1L).toFloat()
                            scrollVelX = scrollVelX * 0.55f + (dx / dt) * 0.45f
                            scrollVelY = scrollVelY * 0.55f + (dy / dt) * 0.45f
                            lastScrollAt = event.eventTime
                            scrollX = (scrollX - dx).coerceIn(-wheelStep * 8, wheelStep * 8)
                            scrollY = (scrollY + dy).coerceIn(-wheelStep * 8, wheelStep * 8)
                            flushFineScroll(cx, cy)
                        }
                        2 -> if (lastSpan > 0 && span > 0) {
                            viewport.scale(span / lastSpan, centerX, centerY, cx, cy)
                            changed()
                        }
                        3 -> { viewport.pan(cx - centerX, cy - centerY); changed() }
                    }
                    centerX = cx; centerY = cy; lastSpan = span
                } else if (!multi) {
                    if (hypot(event.x - startX, event.y - startY) > slop) {
                        moved = true
                        cancelLongPress(longPressTask)
                    }
                    if (rdpeiActive) {
                        val idx = event.findPointerIndex(rdpeiPointerId)
                        if (idx >= 0) {
                            val point = viewport.remote(event.getX(idx), event.getY(idx), clamp = true)
                            if (point != null) {
                                rdpeiRemoteX = point.x.toInt()
                                rdpeiRemoteY = point.y.toInt()
                                placeCursor(point.x, point.y)
                                touchOut(TOUCH_UPDATE, rdpeiPointerId, rdpeiRemoteX, rdpeiRemoteY)
                            }
                        }
                        lastX = event.x; lastY = event.y
                        return true
                    }
                    if (longPressFired) return true
                    if (pressed == 0 && moved && (!trackpad || dragArmed || doubleTap)) {
                        pressed = if (rightClick) 0x2000 else 0x1000
                        emit(0x8000 or pressed, startX, startY)
                    }
                    if (trackpad && moved && viewport.pixelScale > 0) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        val gain = RdpPointerMath.trackpadGain(dx, dy, event.eventTime - lastMoveAt, density)
                        placeCursor(cursorX + (dx * gain) / viewport.pixelScale, cursorY + (dy * gain) / viewport.pixelScale)
                        viewport.reveal(cursorX, cursorY, slop * 2)
                        emit(0x0800, event.x, event.y)
                    } else if (pressed != 0) emit(0x0800, event.x, event.y)
                    if (moved) { lastX = event.x; lastY = event.y; lastMoveAt = event.eventTime }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (kind == 5) {
                    pairEnded = true
                    return true
                }
                val still = event.pointerCount == 2 &&
                    hypot(event.getX(0) - firstX, event.getY(0) - firstY) <= slop &&
                    hypot(event.getX(1) - secondX, event.getY(1) - secondY) <= slop
                if (multi && !pairEnded && pairTap && still && kind == 0 && valid && event.eventTime - multiAt <= 300) {
                    click(true, startCenterX, startCenterY)
                    val feedbackX = if (trackpad) viewport.localX(cursorX) else startCenterX
                    val feedbackY = if (trackpad) viewport.localY(cursorY) else startCenterY
                    onTapFeedback(feedbackX, feedbackY, true)
                } else if (multi && kind == 1 && !pairEnded && event.eventTime - lastScrollAt <= 80 && hypot(scrollVelX, scrollVelY) > 0.14f) {
                    flingActive = true
                    flingVelX = scrollVelX.coerceIn(-4f, 4f)
                    flingVelY = scrollVelY.coerceIn(-4f, 4f)
                    flingX = centerX; flingY = centerY
                    onFlingChanged(true)
                }
                pairEnded = true
            }
            MotionEvent.ACTION_UP -> {
                cancelLongPress(longPressTask)
                if (rdpeiActive) {
                    val idx = event.findPointerIndex(rdpeiPointerId)
                    val px = if (idx >= 0) event.getX(idx) else event.x
                    val py = if (idx >= 0) event.getY(idx) else event.y
                    val point = viewport.remote(px, py, clamp = true)
                    if (point != null) {
                        rdpeiRemoteX = point.x.toInt()
                        rdpeiRemoteY = point.y.toInt()
                        placeCursor(point.x, point.y)
                    }
                    val id = rdpeiPointerId
                    rdpeiActive = false
                    rdpeiPointerId = -1
                    if (id >= 0) touchOut(TOUCH_END, id, rdpeiRemoteX, rdpeiRemoteY)
                    cancel(false)
                    return true
                }
                val tap = !multi && valid && !moved && !longPressFired && hypot(event.x - startX, event.y - startY) <= slop
                if (!multi && valid && !longPressFired) {
                    lastX = event.x; lastY = event.y
                    if (pressed != 0) { release(); if (rightClick) { rightClick = false; rightConsumed() } }
                    else if (!trackpad || tap) {
                        val wasRight = rightClick
                        click(rightClick, event.x, event.y)
                        if (!trackpad) onTapFeedback(event.x, event.y, wasRight)
                    }
                }
                val keepFling = flingActive
                if (keepFling) {
                    // Preserve active fling while resetting pointer tracking state.
                    valid = false; multi = false; pairEnded = true; kind = 4
                    moved = false; doubleTap = false; longPressFired = false
                } else {
                    cancel()
                }
                if (tap) { lastTapAt = event.eventTime; lastTapX = startX; lastTapY = startY }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
}
