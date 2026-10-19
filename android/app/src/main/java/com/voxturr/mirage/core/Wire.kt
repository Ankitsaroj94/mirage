package com.voxturr.mirage.core

// Wire format — see spec/PROTOCOL.md. Byte-for-byte compatible with Sources/Mirage/Core/Protocol.swift.

object MessageType {
    const val HELLO = 0x01
    const val HELLO_ACK = 0x02
    const val VIDEO_PARAMS = 0x10
    const val VIDEO_FRAME = 0x11
    const val RECONFIGURE = 0x12
    const val INPUT = 0x20
    const val STATS = 0x30
    const val REQUEST_KEYFRAME = 0x31
    const val FRAME_ACK = 0x32
    const val PING = 0x40
    const val PONG = 0x41
    const val BYE = 0x50
    const val KEY_EXCHANGE = 0x60
    const val AUDIO_CONFIG = 0x70
    const val AUDIO_STOP = 0x71
}

object FrameFlags {
    const val KEYFRAME = 1
}

const val PROTOCOL_VERSION = 2

/** 16-byte little-endian header. */
data class MessageHeader(
    val type: Int,
    val flags: Int = 0,
    val payloadLength: Int,
    val timestampMicros: Long = MonotonicClock.micros(),
) {
    fun encoded(): ByteArray = TODO("agent 4")

    companion object {
        const val BYTE_COUNT = 16
        fun decode(bytes: ByteArray, offset: Int = 0): MessageHeader = TODO("agent 4")
    }
}

object InputKind {
    const val MOUSE_MOVE = 1
    const val MOUSE_DOWN = 2
    const val MOUSE_UP = 3
    const val SCROLL = 4
    const val KEY_DOWN = 5
    const val KEY_UP = 6
    const val FLAGS_CHANGED = 7
}

object MouseButton {
    const val LEFT = 0
    const val RIGHT = 1
    const val MIDDLE = 2
}

/** CGEventFlags bits carried in InputEvent.modifiers. */
object Modifiers {
    const val CAPS_LOCK = 0x10000
    const val SHIFT = 0x20000
    const val CONTROL = 0x40000
    const val OPTION = 0x80000
    const val COMMAND = 0x100000
    const val NUMERIC_PAD = 0x200000
    const val FN = 0x800000
}

/** 32-byte packed input event. x/y are 0..1, top-left origin. keyCode is a macOS kVK code. */
data class InputEvent(
    val kind: Int,
    val button: Int = 0,
    val clickCount: Int = 1,
    val x: Float = 0f,
    val y: Float = 0f,
    val deltaX: Float = 0f,
    val deltaY: Float = 0f,
    val keyCode: Int = 0,
    val modifiers: Int = 0,
    val isPreciseScroll: Boolean = false,
) {
    fun encoded(): ByteArray = TODO("agent 4")

    companion object {
        const val BYTE_COUNT = 32
        fun decode(bytes: ByteArray, offset: Int = 0): InputEvent? = TODO("agent 4")
    }
}

object MonotonicClock {
    private val base = System.nanoTime()
    /** Microseconds on this device's monotonic clock. Never compare raw values across devices; use ClockSync. */
    fun micros(): Long = (System.nanoTime() - base) / 1_000
    /** Convert a micros() value back to the System.nanoTime() timebase (for AudioTrack / Choreographer). */
    fun toNanoTime(micros: Long): Long = base + micros * 1_000
    fun fromNanoTime(nanos: Long): Long = (nanos - base) / 1_000
}
