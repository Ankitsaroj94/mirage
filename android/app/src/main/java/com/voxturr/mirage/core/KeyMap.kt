package com.voxturr.mirage.core

/**
 * Android KeyEvent.KEYCODE_* ↔ macOS kVK_* virtual keycodes, plus meta-state ↔ CGEventFlags.
 * Single source for both the receiver (Android keyboard → Mac) and the host (Mac → Android).
 */
object KeyMap {
    /** -1 if unmapped. */
    fun androidToMac(androidKeyCode: Int): Int = TODO("agent 4")
    /** -1 if unmapped. */
    fun macToAndroid(macKeyCode: Int): Int = TODO("agent 4")
    /** KeyEvent meta state → Modifiers bits. metaAsCommand: Android META (and Ctrl if ctrlAsCommand) → COMMAND. */
    fun metaToModifiers(metaState: Int, ctrlAsCommand: Boolean = false): Int = TODO("agent 4")
    /** Modifiers bits → KeyEvent meta state. commandAs: KeyEvent.META_CTRL_ON or META_META_ON. */
    fun modifiersToMeta(modifiers: Int, commandAsCtrl: Boolean = true): Int = TODO("agent 4")
}
