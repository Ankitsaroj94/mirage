package `in`.ankitsaroj.mirage.host

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One Android input event to synthesise, as plain data.
 *
 * [MacInputMapper] produces these in the app process (pure Kotlin, unit-testable); the
 * privileged service turns them into real MotionEvent/KeyEvent objects and injects them. The
 * split keeps every decision testable on the JVM and leaves the shell side a dumb converter.
 *
 * Constants mirror android.view.MotionEvent/KeyEvent/InputDevice values (stable public API),
 * duplicated here because those classes are stubs in JVM unit tests.
 */
data class InjectSpec(
    val kind: Int,
    val action: Int,
    val x: Float = 0f,
    val y: Float = 0f,
    val buttonState: Int = 0,
    val actionButton: Int = 0,
    val vScroll: Float = 0f,
    val hScroll: Float = 0f,
    val metaState: Int = 0,
    val keyCode: Int = 0,
    val repeatCount: Int = 0,
    /** SystemClock.uptimeMillis of the gesture's/key's down event. */
    val downTime: Long = 0,
) {
    companion object {
        const val KIND_MOTION = 0
        const val KIND_KEY = 1

        /** Fixed record, little-endian: kind+3 pad, ten 4-byte fields, an 8-byte time, 4 pad. */
        const val BYTES = 56

        fun encode(specs: List<InjectSpec>): ByteArray {
            val buf = ByteBuffer.allocate(specs.size * BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (s in specs) {
                val start = buf.position()
                buf.put(s.kind.toByte()).put(0).put(0).put(0)
                buf.putInt(s.action).putFloat(s.x).putFloat(s.y)
                buf.putInt(s.buttonState).putInt(s.actionButton)
                buf.putFloat(s.vScroll).putFloat(s.hScroll)
                buf.putInt(s.metaState).putInt(s.keyCode).putInt(s.repeatCount)
                buf.putLong(s.downTime)
                buf.position(start + BYTES)
            }
            return buf.array()
        }

        fun decode(bytes: ByteArray): List<InjectSpec> {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val out = ArrayList<InjectSpec>(bytes.size / BYTES)
            while (buf.remaining() >= BYTES) {
                val start = buf.position()
                val kind = buf.get().toInt(); buf.position(start + 4)
                out += InjectSpec(
                    kind = kind, action = buf.int, x = buf.float, y = buf.float,
                    buttonState = buf.int, actionButton = buf.int,
                    vScroll = buf.float, hScroll = buf.float,
                    metaState = buf.int, keyCode = buf.int, repeatCount = buf.int,
                    downTime = buf.long,
                )
                buf.position(start + BYTES)
            }
            return out
        }
    }
}

/** android.view.MotionEvent / KeyEvent / InputDevice constants used by the mapper. */
object AndroidInput {
    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
    const val ACTION_MOVE = 2
    const val ACTION_CANCEL = 3
    const val ACTION_HOVER_MOVE = 7
    const val ACTION_SCROLL = 8
    const val ACTION_HOVER_ENTER = 9
    const val ACTION_HOVER_EXIT = 10
    const val ACTION_BUTTON_PRESS = 11
    const val ACTION_BUTTON_RELEASE = 12

    const val BUTTON_PRIMARY = 1
    const val BUTTON_SECONDARY = 2
    const val BUTTON_TERTIARY = 4

    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    const val SOURCE_MOUSE = 0x00002002
    const val SOURCE_KEYBOARD = 0x00000101
    const val TOOL_TYPE_MOUSE = 3

    const val KEYCODE_CAPS_LOCK = 115
    const val KEYCODE_CTRL_LEFT = 113
    const val KEYCODE_CTRL_RIGHT = 114
    const val KEYCODE_META_LEFT = 117
    const val KEYCODE_META_RIGHT = 118
}
