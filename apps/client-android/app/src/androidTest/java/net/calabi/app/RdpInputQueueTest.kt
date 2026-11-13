package net.calabi.app

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.KeyCharacterMap
import android.view.KeyEvent
import net.calabi.app.rdp.RdpInputQueue
import net.calabi.app.rdp.RdpKeyboard
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RdpInputQueueTest : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val results = Bundle()
        try {
            val keys = mutableListOf<Pair<Int, Boolean>>()
            val typed = StringBuilder()
            val keyboard = RdpKeyboard({ scan, down -> keys.add(scan to down) }, { typed.append(it) })
            val mixed = "ORDER_0123456789-Calabi"
            KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(mixed.toCharArray())!!.forEach {
                check(keyboard.dispatch(it))
            }
            check(typed.toString() == mixed)
            check(keys.isEmpty()) { "Translated Unicode must not toggle the remote IME through synthetic Shift" }
            keyboard.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
            keyboard.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_SHIFT_ON))
            keyboard.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_SHIFT_ON))
            keyboard.dispatch(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SHIFT_LEFT, 0))
            check(keys == listOf(0x2a to true, 0x14b to true, 0x14b to false, 0x2a to false))
            val events = mutableListOf<List<Int>>()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = CountDownLatch(1)
            val queue = RdpInputQueue { kind, flags, x, y ->
                if (events.isEmpty()) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                events.add(listOf(kind, flags, x, y))
                if (kind == 0) finished.countDown()
            }
            val text = "中文输入_" + "0123456789".repeat(30)
            try {
                queue.text(text)
                check(entered.await(5, TimeUnit.SECONDS))
                // Submit controls while the first character is still blocked in flight.
                queue.press(0x1c)
                queue.press(0x0e)
                queue.mouse(0x1000, 123, 456)
                release.countDown()
                check(finished.await(10, TimeUnit.SECONDS))
                check(events.size == text.length * 2 + 5)
                text.forEachIndexed { i, character ->
                    check(events[i * 2] == listOf(2, 0, character.code, 0))
                    check(events[i * 2 + 1] == listOf(2, 0x8000, character.code, 0))
                }
                check(events.takeLast(5) == listOf(
                    listOf(1, 0, 0x1c, 0), listOf(1, 0x8000, 0x1c, 0),
                    listOf(1, 0, 0x0e, 0), listOf(1, 0x8000, 0x0e, 0), listOf(0, 0x1000, 123, 456)))
            } finally { release.countDown(); queue.close() }
            queue.text("must-not-send")
            queue.press(0x1c)
            results.putString("stream", "\nSynthetic Shift/Unicode, Shift-selection, and blocked text/control/pointer ordering passed\n")
            finish(Activity.RESULT_OK, results)
        } catch (error: Throwable) {
            results.putString("stream", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
