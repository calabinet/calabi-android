package net.calabi.app.rdp

import android.view.MotionEvent

/** Absolute physical mouse coordinates stay independent of touchpad gestures. */
internal class RdpMouse(
    private val viewport: RdpViewport,
    private val send: (Int, Int, Int) -> Unit,
    private val modifiers: () -> Unit,
    private val released: () -> Unit,
    private val cursor: (Float, Float) -> Unit,
    private val focus: () -> Unit,
    private val wheel: (Float, Float, Float, Float) -> Unit,
) {
    private var buttons = 0
    private var x = 0; private var y = 0
    fun release() {
        for (flag in intArrayOf(0x1000, 0x2000, 0x4000)) if (buttons and flag != 0) send(flag, x, y)
        buttons = 0; released()
    }
    fun event(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_CANCEL) { release(); return true }
        if (event.actionMasked in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS)) focus()
        val point = viewport.remote(event.x, event.y, buttons != 0) ?: return true
        x = point.x.toInt(); y = point.y.toInt(); cursor(point.x, point.y)
        val next = (if (event.buttonState and MotionEvent.BUTTON_PRIMARY != 0) 0x1000 else 0) or
            (if (event.buttonState and MotionEvent.BUTTON_SECONDARY != 0) 0x2000 else 0) or
            (if (event.buttonState and MotionEvent.BUTTON_TERTIARY != 0) 0x4000 else 0)
        if (next != 0) modifiers()
        for (flag in intArrayOf(0x1000, 0x2000, 0x4000)) if ((next and flag) != (buttons and flag))
            send(flag or (if (next and flag != 0) 0x8000 else 0), x, y)
        val releasedLastButton = buttons != 0 && next == 0
        buttons = next
        if (releasedLastButton) released()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> send(0x0800, x, y)
            MotionEvent.ACTION_SCROLL -> wheel(event.getAxisValue(MotionEvent.AXIS_VSCROLL), event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.x, event.y)
        }
        return true
    }
}
