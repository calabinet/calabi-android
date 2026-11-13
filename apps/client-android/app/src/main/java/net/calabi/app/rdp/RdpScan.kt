package net.calabi.app.rdp

import android.view.KeyEvent

internal object RdpScan {
    private val letters = intArrayOf(0x1e, 0x30, 0x2e, 0x20, 0x12, 0x21, 0x22, 0x23, 0x17, 0x24, 0x25, 0x26, 0x32,
        0x31, 0x18, 0x19, 0x10, 0x13, 0x1f, 0x14, 0x16, 0x2f, 0x11, 0x2d, 0x15, 0x2c)
    fun key(code: Int): Int = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> letters[code - KeyEvent.KEYCODE_A]
        in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 -> 0x02 + code - KeyEvent.KEYCODE_1
        KeyEvent.KEYCODE_0 -> 0x0b
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F10 -> 0x3b + code - KeyEvent.KEYCODE_F1
        KeyEvent.KEYCODE_F11 -> 0x57
        KeyEvent.KEYCODE_F12 -> 0x58
        KeyEvent.KEYCODE_ENTER -> 0x1c
        KeyEvent.KEYCODE_NUMPAD_ENTER -> 0x11c
        KeyEvent.KEYCODE_NUMPAD_0 -> 0x152
        KeyEvent.KEYCODE_NUMPAD_1 -> 0x14f
        KeyEvent.KEYCODE_NUMPAD_2 -> 0x150
        KeyEvent.KEYCODE_NUMPAD_3 -> 0x151
        KeyEvent.KEYCODE_NUMPAD_4 -> 0x14b
        KeyEvent.KEYCODE_NUMPAD_5 -> 0x4c
        KeyEvent.KEYCODE_NUMPAD_6 -> 0x14d
        KeyEvent.KEYCODE_NUMPAD_7 -> 0x147
        KeyEvent.KEYCODE_NUMPAD_8 -> 0x148
        KeyEvent.KEYCODE_NUMPAD_9 -> 0x149
        KeyEvent.KEYCODE_NUMPAD_DOT -> 0x153
        KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 0x135
        KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 0x37
        KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 0x4a
        KeyEvent.KEYCODE_NUMPAD_ADD -> 0x4e
        KeyEvent.KEYCODE_DEL -> 0x0e
        KeyEvent.KEYCODE_FORWARD_DEL -> 0x153
        KeyEvent.KEYCODE_TAB -> 0x0f
        KeyEvent.KEYCODE_ESCAPE -> 0x01
        KeyEvent.KEYCODE_DPAD_LEFT -> 0x14b
        KeyEvent.KEYCODE_DPAD_RIGHT -> 0x14d
        KeyEvent.KEYCODE_DPAD_UP -> 0x148
        KeyEvent.KEYCODE_DPAD_DOWN -> 0x150
        KeyEvent.KEYCODE_MOVE_HOME -> 0x147
        KeyEvent.KEYCODE_MOVE_END -> 0x14f
        KeyEvent.KEYCODE_PAGE_UP -> 0x149
        KeyEvent.KEYCODE_PAGE_DOWN -> 0x151
        KeyEvent.KEYCODE_INSERT -> 0x152
        KeyEvent.KEYCODE_SPACE -> 0x39
        KeyEvent.KEYCODE_MINUS -> 0x0c
        KeyEvent.KEYCODE_EQUALS -> 0x0d
        KeyEvent.KEYCODE_LEFT_BRACKET -> 0x1a
        KeyEvent.KEYCODE_RIGHT_BRACKET -> 0x1b
        KeyEvent.KEYCODE_BACKSLASH -> 0x2b
        KeyEvent.KEYCODE_SEMICOLON -> 0x27
        KeyEvent.KEYCODE_APOSTROPHE -> 0x28
        KeyEvent.KEYCODE_GRAVE -> 0x29
        KeyEvent.KEYCODE_COMMA -> 0x33
        KeyEvent.KEYCODE_PERIOD -> 0x34
        KeyEvent.KEYCODE_SLASH -> 0x35
        else -> 0
    }
    fun character(value: Char): Int = when (value) {
        in 'a'..'z' -> letters[value - 'a']
        in 'A'..'Z' -> letters[value - 'A']
        in '1'..'9' -> 0x02 + (value - '1')
        '0' -> 0x0b
        else -> 0
    }
    fun modifier(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT -> 1
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> 2
        KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> 4
        KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> 8
        else -> 0
    }
    val modifierScans = listOf(1 to 0x1d, 2 to 0x38, 4 to 0x2a, 8 to 0x15b)
}
