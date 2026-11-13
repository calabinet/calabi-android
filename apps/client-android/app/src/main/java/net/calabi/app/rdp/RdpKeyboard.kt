package net.calabi.app.rdp

import android.view.KeyEvent
import android.view.KeyCharacterMap

/** Unicode for ordinary text; scan codes for shortcuts, without toggling the remote IME. */
internal class RdpKeyboard(private val key: (Int, Boolean) -> Unit, private val text: (String) -> Unit) {
    var latch = 0; private set
    var onLatchChanged: ((Int) -> Unit)? = null
    private var accent = 0
    fun setLatch(mask: Int) { latch = mask and 15; onLatchChanged?.invoke(latch) }
    private fun consumeLatch() { if (latch != 0) { setLatch(0); syncModifiers(heldMask()) } }
    private val held = mutableSetOf<Int>()
    private val pressed = mutableSetOf<Int>()
    private var sentModifiers = 0
    private fun heldMask() = held.fold(0) { mask, code -> mask or RdpScan.modifier(code) }
    private fun syncModifiers(mask: Int) {
        RdpScan.modifierScans.reversed().forEach { (bit, scan) ->
            if (sentModifiers and bit != 0 && mask and bit == 0) key(scan, false)
        }
        RdpScan.modifierScans.forEach { (bit, scan) ->
            if (sentModifiers and bit == 0 && mask and bit != 0) key(scan, true)
        }
        sentModifiers = mask
    }
    fun pointerModifiers() { syncModifiers(heldMask() or latch) }
    fun pointerReleased() { consumeLatch() }
    fun press(scan: Int) { syncModifiers(heldMask() or latch); key(scan, true); key(scan, false); consumeLatch() }
    fun releaseAll() {
        pressed.forEach { key(it, false) }; pressed.clear()
        syncModifiers(0); held.clear(); accent = 0; setLatch(0)
    }
    fun commit(value: String) {
        val mask = heldMask() or latch
        val scan = value.singleOrNull()?.let(RdpScan::character) ?: 0
        if (mask != 0 && scan != 0) {
            syncModifiers(mask); key(scan, true); key(scan, false)
        } else { syncModifiers(0); text(value) }
        consumeLatch()
    }
    fun dispatch(event: KeyEvent): Boolean {
        if (event.action !in setOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) return false
        val down = event.action == KeyEvent.ACTION_DOWN
        if (RdpScan.modifier(event.keyCode) != 0) {
            if (down) held.add(event.keyCode) else { held.remove(event.keyCode); syncModifiers(sentModifiers and heldMask()) }
            return true
        }
        val mask = heldMask() or latch or (if (event.isCtrlPressed) 1 else 0) or (if (event.isAltPressed) 2 else 0) or
            (if (event.isShiftPressed) 4 else 0) or (if (event.isMetaPressed) 8 else 0)
        val scan = RdpScan.key(event.keyCode)
        if (!down && scan != 0 && pressed.remove(scan)) { key(scan, false); consumeLatch(); return true }
        val unicode = event.unicodeChar
        if (unicode and KeyCharacterMap.COMBINING_ACCENT != 0) {
            if (down) accent = unicode and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        val printable = unicode in 0x20..0x10ffff
        if (printable && (mask and 11 == 0 || KeyEvent.KEYCODE_ALT_RIGHT in held)) {
            if (down) {
                if (latch != 0 && scan != 0) { syncModifiers(mask); key(scan, true); pressed.add(scan); return true }
                syncModifiers(0)
                val combined = if (accent != 0) KeyCharacterMap.getDeadChar(accent, unicode) else 0
                if (accent != 0 && combined == 0) text(String(Character.toChars(accent)))
                text(String(Character.toChars(if (combined != 0) combined else unicode))); accent = 0; consumeLatch()
            }
            return true
        }
        if (scan != 0 && (mask and 11 != 0 || !printable || latch != 0)) {
            if (down) { syncModifiers(mask); key(scan, true); pressed.add(scan) }
            return true
        }
        return false
    }
}
