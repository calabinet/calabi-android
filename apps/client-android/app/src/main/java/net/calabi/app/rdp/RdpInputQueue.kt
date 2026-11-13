package net.calabi.app.rdp

import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/** Preserve UI event order across IME commits, control keys and pointer events. */
internal class RdpInputQueue(private val emit: (Int, Int, Int, Int) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false
    @Volatile private var enabled = true
    @Volatile private var generation = 0L

    fun setEnabled(value: Boolean) { generation++; enabled = value }

    private fun enqueue(action: () -> Unit) {
        if (closed || !enabled) return
        val ticket = generation
        try { executor.execute { if (!closed && enabled && generation == ticket) action() } }
        catch (_: RejectedExecutionException) { /* Session closed while dispatching. */ }
    }

    fun mouse(flags: Int, x: Int, y: Int) = enqueue { emit(0, flags, x, y) }
    private fun sendKey(scan: Int, down: Boolean) {
        emit(1, (if (down) 0 else 0x8000) or (if (scan > 255) 0x0100 else 0), scan and 255, 0)
    }
    fun key(scan: Int, down: Boolean) = enqueue { sendKey(scan, down) }
    fun press(scan: Int) = enqueue { sendKey(scan, true); sendKey(scan, false) }
    fun chord(modifiers: IntArray, scan: Int) = enqueue {
        modifiers.forEach { sendKey(it, true) }
        sendKey(scan, true); sendKey(scan, false)
        modifiers.reversed().forEach { sendKey(it, false) }
    }
    fun text(text: String) {
        if (text.length > 4096) return
        val ticket = generation
        enqueue {
            for (character in text) {
                if (closed || !enabled || generation != ticket) break
                emit(2, 0, character.code, 0)
                emit(2, 0x8000, character.code, 0)
                Thread.sleep(2) // Keep the bounded native queue below its capacity.
            }
        }
    }

    fun close() { closed = true; executor.shutdown() }
}
