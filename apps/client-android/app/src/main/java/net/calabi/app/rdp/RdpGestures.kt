package net.calabi.app.rdp

import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/** Delay a single-finger press until movement/up so a two-finger tap never left-clicks. */
internal class RdpGestures(
    private val viewport: RdpViewport,
    private val slop: Float,
    private val wheelStep: Float,
    private val mouse: (Int, Int, Int) -> Unit,
    private val changed: () -> Unit,
    private val rightConsumed: () -> Unit,
    private val dragConsumed: () -> Unit = {},
) {
    var rightClick = false
    var panMode = false
    var trackpad = false
    var dragArmed = false
    var cursorX = 0f; private set
    var cursorY = 0f; private set
    private var cursorReady = false
    private var moved = false
    private var doubleTap = false
    private var lastTapAt = 0L
    private var lastTapX = 0f; private var lastTapY = 0f
    private var startX = 0f; private var startY = 0f
    private var lastX = 0f; private var lastY = 0f
    private var valid = false
    private var pressed = 0
    private var multi = false
    private var pairEnded = false
    private var multiAt = 0L
    private var startSpan = 0f; private var lastSpan = 0f
    private var centerX = 0f; private var centerY = 0f
    private var startCenterX = 0f; private var startCenterY = 0f
    private var kind = 0 // undecided, scroll, zoom, pan, cancelled
    private var pairTap = false
    private var firstX = 0f; private var firstY = 0f
    private var secondX = 0f; private var secondY = 0f
    private var scrollX = 0f; private var scrollY = 0f

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
    fun cancel(consumeArm: Boolean = true) {
        release(); valid = false; multi = false; pairEnded = true; kind = 4; scrollX = 0f; scrollY = 0f
        moved = false; doubleTap = false; lastTapAt = 0
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
        emit(0x0800, x, y)
        fun axis(value: Float, flag: Int) {
            if (!value.isFinite()) return
            var delta = (value.coerceIn(-8f, 8f) * 120).toInt()
            while (delta != 0) {
                val part = delta.coerceIn(-255, 255)
                emit(flag or (part and 0x1ff), x, y)
                delta -= part
            }
        }
        axis(vertical, 0x0200); axis(horizontal, 0x0400)
    }

    fun touch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val secondTap = lastTapAt != 0L && event.eventTime - lastTapAt <= 300 && hypot(event.x - lastTapX, event.y - lastTapY) <= slop * 2
                cancel(false); doubleTap = secondTap; pairEnded = false; kind = 0
                startX = event.x; startY = event.y; lastX = event.x; lastY = event.y
                valid = if (trackpad) prepareCursor() else viewport.remote(startX, startY) != null
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                pairTap = pressed == 0
                release()
                multi = true
                if (event.pointerCount != 2 || pairEnded) { kind = 4; return true }
                centerX = (event.getX(0) + event.getX(1)) / 2
                centerY = (event.getY(0) + event.getY(1)) / 2
                startCenterX = centerX; startCenterY = centerY
                firstX = event.getX(0); firstY = event.getY(0)
                secondX = event.getX(1); secondY = event.getY(1)
                startSpan = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
                lastSpan = startSpan; multiAt = event.eventTime; kind = 0
                valid = valid && (trackpad || viewport.remote(centerX, centerY) != null)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!valid || pairEnded || kind == 4) return true
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
                            scrollX = (scrollX - (cx - centerX)).coerceIn(-wheelStep * 8, wheelStep * 8)
                            scrollY = (scrollY + cy - centerY).coerceIn(-wheelStep * 8, wheelStep * 8)
                            val horizontal = (scrollX / wheelStep).toInt(); val vertical = (scrollY / wheelStep).toInt()
                            if (horizontal != 0 || vertical != 0) {
                                wheel(vertical.toFloat(), horizontal.toFloat(), cx, cy)
                                scrollX -= horizontal * wheelStep; scrollY -= vertical * wheelStep
                            }
                        }
                        2 -> if (lastSpan > 0 && span > 0) {
                            if (trackpad) { val at = viewport.local(cursorX, cursorY); viewport.scale(span / lastSpan, at.x, at.y) }
                            else viewport.scale(span / lastSpan, centerX, centerY, cx, cy)
                            changed()
                        }
                        3 -> { viewport.pan(cx - centerX, cy - centerY); changed() }
                    }
                    centerX = cx; centerY = cy; lastSpan = span
                } else if (!multi) {
                    if (hypot(event.x - startX, event.y - startY) > slop) moved = true
                    if (pressed == 0 && moved && (!trackpad || dragArmed || doubleTap)) {
                        pressed = if (rightClick) 0x2000 else 0x1000
                        emit(0x8000 or pressed, startX, startY)
                    }
                    if (trackpad && moved && viewport.pixelScale > 0) {
                        placeCursor(cursorX + (event.x - lastX) / viewport.pixelScale, cursorY + (event.y - lastY) / viewport.pixelScale)
                        viewport.reveal(cursorX, cursorY, slop * 2)
                        emit(0x0800, event.x, event.y)
                    } else if (pressed != 0) emit(0x0800, event.x, event.y)
                    if (moved) { lastX = event.x; lastY = event.y }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val still = event.pointerCount == 2 &&
                    hypot(event.getX(0) - firstX, event.getY(0) - firstY) <= slop &&
                    hypot(event.getX(1) - secondX, event.getY(1) - secondY) <= slop
                if (multi && !pairEnded && pairTap && still && kind == 0 && valid && event.eventTime - multiAt <= 300) {
                    click(true, startCenterX, startCenterY)
                }
                pairEnded = true
            }
            MotionEvent.ACTION_UP -> {
                val tap = !multi && valid && !moved && hypot(event.x - startX, event.y - startY) <= slop
                if (!multi && valid) {
                    lastX = event.x; lastY = event.y
                    if (pressed != 0) { release(); if (rightClick) { rightClick = false; rightConsumed() } }
                    else if (!trackpad || tap) click(rightClick, event.x, event.y)
                }
                cancel()
                if (tap) { lastTapAt = event.eventTime; lastTapX = startX; lastTapY = startY }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }
}
