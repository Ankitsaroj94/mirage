package `in`.ankitsaroj.mirage.core

import `in`.ankitsaroj.mirage.core.Vectors.hex
import `in`.ankitsaroj.mirage.core.Vectors.toHex
import `in`.ankitsaroj.mirage.core.Vectors.u64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class WireTest {
    @Test fun headersMatchVectors() {
        val headers = Vectors.root.getJSONArray("headers")
        assertEquals(3, headers.length())
        for (i in 0 until headers.length()) {
            val v = headers.getJSONObject(i)
            val header = MessageHeader(
                type = v.getInt("type"),
                flags = v.getInt("flags"),
                payloadLength = v.getInt("payloadLength"),
                timestampMicros = u64(v.getString("timestampMicros")),
            )
            assertEquals(v.getString("name"), v.getString("hex"), header.encoded().toHex())
            assertEquals(v.getString("name"), header, MessageHeader.decode(hex(v.getString("hex"))))
        }
    }

    @Test fun headerDecodesAtOffsetAndKeepsUnknownTypes() {
        val bytes = ByteArray(3) + MessageHeader(0x99, 0x80, 7, -1L).encoded()
        val h = MessageHeader.decode(bytes, 3)
        assertEquals(0x99, h.type)
        assertEquals(0x80, h.flags)
        assertEquals(7, h.payloadLength)
        assertEquals(-1L, h.timestampMicros)          // u64 max survives as the same bits
        assertFalse(MessageType.isKnown(0x99))
    }

    @Test fun inputEventsMatchVectors() {
        val events = Vectors.root.getJSONArray("inputEvents")
        assertEquals(4, events.length())
        for (i in 0 until events.length()) {
            val v = events.getJSONObject(i)
            val event = InputEvent(
                kind = v.getInt("kind"),
                button = v.getInt("button"),
                clickCount = v.getInt("clickCount"),
                x = v.getDouble("x").toFloat(),
                y = v.getDouble("y").toFloat(),
                deltaX = v.getDouble("deltaX").toFloat(),
                deltaY = v.getDouble("deltaY").toFloat(),
                keyCode = v.getInt("keyCode"),
                modifiers = v.getInt("modifiers"),
                isPreciseScroll = v.getBoolean("isPreciseScroll"),
            )
            assertEquals(v.getString("name"), v.getString("hex"), event.encoded().toHex())
            assertEquals(v.getString("name"), event, InputEvent.decode(hex(v.getString("hex"))))
        }
    }

    @Test fun inputEventRejectsShortOrUnknown() {
        assertNull(InputEvent.decode(ByteArray(31)))
        assertNull(InputEvent.decode(ByteArray(32)))                       // kind 0
        assertNull(InputEvent.decode(ByteArray(32).also { it[0] = 8 }))
    }

    @Test fun inputEventFullRangeFields() {
        val e = InputEvent(InputKind.KEY_DOWN, button = 2, clickCount = 255, keyCode = 0xFFFF, modifiers = Modifiers.COMMAND or Modifiers.FN)
        assertEquals(e, InputEvent.decode(e.encoded()))
    }
}
