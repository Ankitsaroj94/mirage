package `in`.ankitsaroj.mirage.core

import android.view.KeyEvent

/**
 * Android KeyEvent.KEYCODE_* ↔ macOS kVK_* virtual keycodes, plus meta-state ↔ CGEventFlags.
 * Single source for both the receiver (Android keyboard → Mac) and the host (Mac → Android).
 *
 * The wire always carries kVK codes (spec §4), which are *positions* on an ANSI Mac keyboard,
 * not characters — so the table is position-to-position and the host's keyboard layout decides
 * what gets typed, exactly as with a USB keyboard. kVK values are from Carbon's Events.h.
 * The table is a bijection: every mapped Android key has its own Mac key and back.
 */
object KeyMap {
    private val pairs: Array<IntArray> = arrayOf(
        // Letters
        intArrayOf(KeyEvent.KEYCODE_A, 0x00), intArrayOf(KeyEvent.KEYCODE_S, 0x01),
        intArrayOf(KeyEvent.KEYCODE_D, 0x02), intArrayOf(KeyEvent.KEYCODE_F, 0x03),
        intArrayOf(KeyEvent.KEYCODE_H, 0x04), intArrayOf(KeyEvent.KEYCODE_G, 0x05),
        intArrayOf(KeyEvent.KEYCODE_Z, 0x06), intArrayOf(KeyEvent.KEYCODE_X, 0x07),
        intArrayOf(KeyEvent.KEYCODE_C, 0x08), intArrayOf(KeyEvent.KEYCODE_V, 0x09),
        intArrayOf(KeyEvent.KEYCODE_B, 0x0B), intArrayOf(KeyEvent.KEYCODE_Q, 0x0C),
        intArrayOf(KeyEvent.KEYCODE_W, 0x0D), intArrayOf(KeyEvent.KEYCODE_E, 0x0E),
        intArrayOf(KeyEvent.KEYCODE_R, 0x0F), intArrayOf(KeyEvent.KEYCODE_Y, 0x10),
        intArrayOf(KeyEvent.KEYCODE_T, 0x11), intArrayOf(KeyEvent.KEYCODE_O, 0x1F),
        intArrayOf(KeyEvent.KEYCODE_U, 0x20), intArrayOf(KeyEvent.KEYCODE_I, 0x22),
        intArrayOf(KeyEvent.KEYCODE_P, 0x23), intArrayOf(KeyEvent.KEYCODE_L, 0x25),
        intArrayOf(KeyEvent.KEYCODE_J, 0x26), intArrayOf(KeyEvent.KEYCODE_K, 0x28),
        intArrayOf(KeyEvent.KEYCODE_N, 0x2D), intArrayOf(KeyEvent.KEYCODE_M, 0x2E),
        // Digit row (kVK order is not numeric: 6 comes before 5, 9 before 7)
        intArrayOf(KeyEvent.KEYCODE_1, 0x12), intArrayOf(KeyEvent.KEYCODE_2, 0x13),
        intArrayOf(KeyEvent.KEYCODE_3, 0x14), intArrayOf(KeyEvent.KEYCODE_4, 0x15),
        intArrayOf(KeyEvent.KEYCODE_6, 0x16), intArrayOf(KeyEvent.KEYCODE_5, 0x17),
        intArrayOf(KeyEvent.KEYCODE_9, 0x19), intArrayOf(KeyEvent.KEYCODE_7, 0x1A),
        intArrayOf(KeyEvent.KEYCODE_8, 0x1C), intArrayOf(KeyEvent.KEYCODE_0, 0x1D),
        // Punctuation
        intArrayOf(KeyEvent.KEYCODE_EQUALS, 0x18), intArrayOf(KeyEvent.KEYCODE_MINUS, 0x1B),
        intArrayOf(KeyEvent.KEYCODE_RIGHT_BRACKET, 0x1E), intArrayOf(KeyEvent.KEYCODE_LEFT_BRACKET, 0x21),
        intArrayOf(KeyEvent.KEYCODE_APOSTROPHE, 0x27), intArrayOf(KeyEvent.KEYCODE_SEMICOLON, 0x29),
        intArrayOf(KeyEvent.KEYCODE_BACKSLASH, 0x2A), intArrayOf(KeyEvent.KEYCODE_COMMA, 0x2B),
        intArrayOf(KeyEvent.KEYCODE_SLASH, 0x2C), intArrayOf(KeyEvent.KEYCODE_PERIOD, 0x2F),
        intArrayOf(KeyEvent.KEYCODE_GRAVE, 0x32),
        // Editing / whitespace
        intArrayOf(KeyEvent.KEYCODE_ENTER, 0x24), intArrayOf(KeyEvent.KEYCODE_TAB, 0x30),
        intArrayOf(KeyEvent.KEYCODE_SPACE, 0x31), intArrayOf(KeyEvent.KEYCODE_DEL, 0x33),        // backspace
        intArrayOf(KeyEvent.KEYCODE_ESCAPE, 0x35), intArrayOf(KeyEvent.KEYCODE_FORWARD_DEL, 0x75),
        // Modifiers
        intArrayOf(KeyEvent.KEYCODE_META_RIGHT, 0x36), intArrayOf(KeyEvent.KEYCODE_META_LEFT, 0x37),
        intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, 0x38), intArrayOf(KeyEvent.KEYCODE_CAPS_LOCK, 0x39),
        intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, 0x3A), intArrayOf(KeyEvent.KEYCODE_CTRL_LEFT, 0x3B),
        intArrayOf(KeyEvent.KEYCODE_SHIFT_RIGHT, 0x3C), intArrayOf(KeyEvent.KEYCODE_ALT_RIGHT, 0x3D),
        intArrayOf(KeyEvent.KEYCODE_CTRL_RIGHT, 0x3E), intArrayOf(KeyEvent.KEYCODE_FUNCTION, 0x3F),
        // Function row
        intArrayOf(KeyEvent.KEYCODE_F1, 0x7A), intArrayOf(KeyEvent.KEYCODE_F2, 0x78),
        intArrayOf(KeyEvent.KEYCODE_F3, 0x63), intArrayOf(KeyEvent.KEYCODE_F4, 0x76),
        intArrayOf(KeyEvent.KEYCODE_F5, 0x60), intArrayOf(KeyEvent.KEYCODE_F6, 0x61),
        intArrayOf(KeyEvent.KEYCODE_F7, 0x62), intArrayOf(KeyEvent.KEYCODE_F8, 0x64),
        intArrayOf(KeyEvent.KEYCODE_F9, 0x65), intArrayOf(KeyEvent.KEYCODE_F10, 0x6D),
        intArrayOf(KeyEvent.KEYCODE_F11, 0x67), intArrayOf(KeyEvent.KEYCODE_F12, 0x6F),
        // PC keys with no Mac legend, mapped where Mac keyboards put them: Print Screen,
        // Scroll Lock and Pause sit where F13–F15 are; Insert where Help is.
        intArrayOf(KeyEvent.KEYCODE_SYSRQ, 0x69), intArrayOf(KeyEvent.KEYCODE_SCROLL_LOCK, 0x6B),
        intArrayOf(KeyEvent.KEYCODE_BREAK, 0x71), intArrayOf(KeyEvent.KEYCODE_INSERT, 0x72),
        // Navigation
        intArrayOf(KeyEvent.KEYCODE_MOVE_HOME, 0x73), intArrayOf(KeyEvent.KEYCODE_PAGE_UP, 0x74),
        intArrayOf(KeyEvent.KEYCODE_MOVE_END, 0x77), intArrayOf(KeyEvent.KEYCODE_PAGE_DOWN, 0x79),
        intArrayOf(KeyEvent.KEYCODE_DPAD_LEFT, 0x7B), intArrayOf(KeyEvent.KEYCODE_DPAD_RIGHT, 0x7C),
        intArrayOf(KeyEvent.KEYCODE_DPAD_DOWN, 0x7D), intArrayOf(KeyEvent.KEYCODE_DPAD_UP, 0x7E),
        // Numpad (Num Lock sits where the Mac keypad has Clear)
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_DOT, 0x41), intArrayOf(KeyEvent.KEYCODE_NUMPAD_MULTIPLY, 0x43),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_ADD, 0x45), intArrayOf(KeyEvent.KEYCODE_NUM_LOCK, 0x47),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_DIVIDE, 0x4B), intArrayOf(KeyEvent.KEYCODE_NUMPAD_ENTER, 0x4C),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, 0x4E), intArrayOf(KeyEvent.KEYCODE_NUMPAD_EQUALS, 0x51),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_0, 0x52), intArrayOf(KeyEvent.KEYCODE_NUMPAD_1, 0x53),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_2, 0x54), intArrayOf(KeyEvent.KEYCODE_NUMPAD_3, 0x55),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_4, 0x56), intArrayOf(KeyEvent.KEYCODE_NUMPAD_5, 0x57),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_6, 0x58), intArrayOf(KeyEvent.KEYCODE_NUMPAD_7, 0x59),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_8, 0x5B), intArrayOf(KeyEvent.KEYCODE_NUMPAD_9, 0x5C),
        intArrayOf(KeyEvent.KEYCODE_NUMPAD_COMMA, 0x5F),
        // Media
        intArrayOf(KeyEvent.KEYCODE_VOLUME_UP, 0x48), intArrayOf(KeyEvent.KEYCODE_VOLUME_DOWN, 0x49),
        intArrayOf(KeyEvent.KEYCODE_VOLUME_MUTE, 0x4A),
        // JIS
        intArrayOf(KeyEvent.KEYCODE_YEN, 0x5D), intArrayOf(KeyEvent.KEYCODE_RO, 0x5E),
        intArrayOf(KeyEvent.KEYCODE_EISU, 0x66), intArrayOf(KeyEvent.KEYCODE_KANA, 0x68),
    )

    private val toMac = HashMap<Int, Int>(pairs.size * 2).apply { pairs.forEach { put(it[0], it[1]) } }
    private val toAndroid = HashMap<Int, Int>(pairs.size * 2).apply { pairs.forEach { put(it[1], it[0]) } }

    /** Every mapped (android, mac) pair — for tests and diagnostics. */
    val mappedPairs: List<Pair<Int, Int>> get() = pairs.map { it[0] to it[1] }

    /** -1 if unmapped. */
    fun androidToMac(androidKeyCode: Int): Int = toMac[androidKeyCode] ?: -1
    /** -1 if unmapped. */
    fun macToAndroid(macKeyCode: Int): Int = toAndroid[macKeyCode] ?: -1

    /**
     * KeyEvent meta state → Modifiers bits. Android META (the Windows/Search key) → COMMAND,
     * since it sits where Command sits; with [ctrlAsCommand] Ctrl → COMMAND too, so a PC
     * habit of Ctrl+C copies on the Mac.
     */
    fun metaToModifiers(metaState: Int, ctrlAsCommand: Boolean = false): Int {
        var m = 0
        if (metaState and KeyEvent.META_SHIFT_ON != 0) m = m or Modifiers.SHIFT
        if (metaState and KeyEvent.META_ALT_ON != 0) m = m or Modifiers.OPTION
        if (metaState and KeyEvent.META_CTRL_ON != 0) m = m or (if (ctrlAsCommand) Modifiers.COMMAND else Modifiers.CONTROL)
        if (metaState and KeyEvent.META_META_ON != 0) m = m or Modifiers.COMMAND
        if (metaState and KeyEvent.META_CAPS_LOCK_ON != 0) m = m or Modifiers.CAPS_LOCK
        if (metaState and KeyEvent.META_FUNCTION_ON != 0) m = m or Modifiers.FN
        return m
    }

    /**
     * Modifiers bits → KeyEvent meta state. Command → CTRL by default ([commandAsCtrl]): Android
     * shortcuts are Ctrl-based, so a Mac user's ⌘C should copy on the tablet. Each modifier
     * sets both the generic and the left-side bit, as a real left-hand key press would.
     */
    fun modifiersToMeta(modifiers: Int, commandAsCtrl: Boolean = true): Int {
        val ctrl = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        var s = 0
        if (modifiers and Modifiers.SHIFT != 0) s = s or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (modifiers and Modifiers.OPTION != 0) s = s or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (modifiers and Modifiers.CONTROL != 0) s = s or ctrl
        if (modifiers and Modifiers.COMMAND != 0) s = s or (if (commandAsCtrl) ctrl else meta)
        if (modifiers and Modifiers.CAPS_LOCK != 0) s = s or KeyEvent.META_CAPS_LOCK_ON
        if (modifiers and Modifiers.FN != 0) s = s or KeyEvent.META_FUNCTION_ON
        return s
    }
}
