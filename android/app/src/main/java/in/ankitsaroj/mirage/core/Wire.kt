package `in`.ankitsaroj.mirage.core

// Wire format — see spec/PROTOCOL.md. Byte-for-byte compatible with Sources/Mirage/Core/Protocol.swift.

import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    /**
     * Types this build understands. Anything else is read, opened (so the cipher counter stays
     * in step) and dropped — that is the whole forward-compatibility contract (spec §2).
     */
    fun isKnown(type: Int): Boolean = when (type) {
        HELLO, HELLO_ACK, VIDEO_PARAMS, VIDEO_FRAME, RECONFIGURE, INPUT, STATS, REQUEST_KEYFRAME,
        FRAME_ACK, PING, PONG, BYE, KEY_EXCHANGE, AUDIO_CONFIG, AUDIO_STOP -> true
        else -> false
    }
}

object FrameFlags {
    const val KEYFRAME = 1
}

const val PROTOCOL_VERSION = 2

/**
 * 16-byte little-endian header. [type] is kept as a raw byte so an unknown (newer) type decodes
 * instead of failing; [timestampMicros] is a u64 on the wire, carried as the same 64 bits.
 */
data class MessageHeader(
    val type: Int,
    val flags: Int = 0,
    val payloadLength: Int,
    val timestampMicros: Long = MonotonicClock.micros(),
) {
    fun encoded(): ByteArray = ByteArray(BYTE_COUNT).also { encodeInto(it, 0) }

    /** Writes the 16 bytes at [offset] — lets PeerLink build header + body in one array. */
    fun encodeInto(dst: ByteArray, offset: Int) {
        ByteBuffer.wrap(dst, offset, BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
            .put(type.toByte())
            .put(flags.toByte())
            .putShort(0)
            .putInt(payloadLength)
            .putLong(timestampMicros)
    }

    companion object {
        const val BYTE_COUNT = 16

        /** @throws IllegalArgumentException if fewer than 16 bytes are available. */
        fun decode(bytes: ByteArray, offset: Int = 0): MessageHeader {
            require(bytes.size - offset >= BYTE_COUNT) { "header needs $BYTE_COUNT bytes" }
            val b = ByteBuffer.wrap(bytes, offset, BYTE_COUNT).order(ByteOrder.LITTLE_ENDIAN)
            return MessageHeader(
                type = b.get(offset).toInt() and 0xff,
                flags = b.get(offset + 1).toInt() and 0xff,
                payloadLength = b.getInt(offset + 4),
                timestampMicros = b.getLong(offset + 8),
            )
        }
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
    fun encoded(): ByteArray {
        val out = ByteArray(BYTE_COUNT)
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
            .put(kind.toByte())
            .put(button.toByte())
            .put(clickCount.toByte())
            .put(if (isPreciseScroll) 1 else 0)
            .putFloat(x)
            .putFloat(y)
            .putFloat(deltaX)
            .putFloat(deltaY)
            .putShort(keyCode.toShort())
            .putShort(0)                     // padding
            .putInt(modifiers)              // bytes 28..31 stay zero
        return out
    }

    companion object {
        const val BYTE_COUNT = 32

        /** Null if too short or the kind is unknown — the Mac host drops those too. */
        fun decode(bytes: ByteArray, offset: Int = 0): InputEvent? {
            if (bytes.size - offset < BYTE_COUNT) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val kind = bytes[offset].toInt() and 0xff
            if (kind !in InputKind.MOUSE_MOVE..InputKind.FLAGS_CHANGED) return null
            return InputEvent(
                kind = kind,
                button = bytes[offset + 1].toInt() and 0xff,
                clickCount = bytes[offset + 2].toInt() and 0xff,
                isPreciseScroll = bytes[offset + 3].toInt() != 0,
                x = b.getFloat(offset + 4),
                y = b.getFloat(offset + 8),
                deltaX = b.getFloat(offset + 12),
                deltaY = b.getFloat(offset + 16),
                keyCode = b.getShort(offset + 20).toInt() and 0xffff,
                modifiers = b.getInt(offset + 24),
            )
        }
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
