package com.voxturr.mirage.host

import com.voxturr.mirage.core.InputEvent
import com.voxturr.mirage.core.InputKind
import com.voxturr.mirage.core.Modifiers
import com.voxturr.mirage.core.MouseButton
import com.voxturr.mirage.host.AndroidInput as A
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** KeyMap (core) is replaced by tiny fakes so these tests depend only on the mapper. */
class MacInputMapperTest {

    private val keyTable = mapOf(0x00 to 29 /* A */, 0x37 to A.KEYCODE_META_LEFT, 0x3B to A.KEYCODE_CTRL_LEFT, 0x38 to 59 /* SHIFT_LEFT */)
    private lateinit var mapper: MacInputMapper

    @Before
    fun setUp() {
        mapper = MacInputMapper(
            commandAsCtrl = true,
            keyToAndroid = { keyTable[it] ?: -1 },
            modifiersToMeta = { m -> if (m and Modifiers.COMMAND != 0) 0x1000 /* META_CTRL_ON */ else 0 },
        )
        mapper.configure(2880, 1800)
    }

    private fun ev(kind: Int, x: Float = 0.5f, y: Float = 0.5f, button: Int = MouseButton.LEFT, dx: Float = 0f, dy: Float = 0f,
                   key: Int = 0, mods: Int = 0, precise: Boolean = false) =
        InputEvent(kind = kind, button = button, x = x, y = y, deltaX = dx, deltaY = dy, keyCode = key, modifiers = mods, isPreciseScroll = precise)

    @Test
    fun hoverMoveMapsNormalisedToPixels() {
        val out = mapper.map(ev(InputKind.MOUSE_MOVE, x = 0.25f, y = 0.5f), 100)
        assertEquals(1, out.size)
        assertEquals(A.ACTION_HOVER_MOVE, out[0].action)
        assertEquals(720f, out[0].x, 0.001f)
        assertEquals(900f, out[0].y, 0.001f)
        assertEquals(0, out[0].buttonState)
    }

    @Test
    fun edgesClampInsideTheDisplay() {
        val out = mapper.map(ev(InputKind.MOUSE_MOVE, x = 1.2f, y = -0.1f), 0)
        assertEquals(2879f, out[0].x, 0.001f)
        assertEquals(0f, out[0].y, 0.001f)
    }

    @Test
    fun leftClickIsDownPressReleaseUp() {
        val down = mapper.map(ev(InputKind.MOUSE_DOWN, x = 0.1f, y = 0.1f), 1000)
        assertEquals(listOf(A.ACTION_DOWN, A.ACTION_BUTTON_PRESS), down.map { it.action })
        assertEquals(A.BUTTON_PRIMARY, down[0].buttonState)
        assertEquals(A.BUTTON_PRIMARY, down[1].actionButton)
        assertTrue(down.all { it.downTime == 1000L })

        val drag = mapper.map(ev(InputKind.MOUSE_MOVE, x = 0.2f, y = 0.1f), 1010)
        assertEquals(A.ACTION_MOVE, drag.single().action)
        assertEquals(A.BUTTON_PRIMARY, drag.single().buttonState)

        val up = mapper.map(ev(InputKind.MOUSE_UP, x = 0.9f, y = 0.9f), 1020)
        assertEquals(listOf(A.ACTION_BUTTON_RELEASE, A.ACTION_UP), up.map { it.action })
        assertEquals(0, up[0].buttonState)
        assertEquals(A.BUTTON_PRIMARY, up[0].actionButton)
        // Release stays where the pointer last was, not where the up event claims.
        assertEquals(576f, up[1].x, 0.001f)
        assertEquals(1000L, up[1].downTime)

        assertEquals(A.ACTION_HOVER_MOVE, mapper.map(ev(InputKind.MOUSE_MOVE), 1030).single().action)
    }

    @Test
    fun rightClickUsesSecondaryButton() {
        val down = mapper.map(ev(InputKind.MOUSE_DOWN, button = MouseButton.RIGHT), 0)
        assertEquals(A.BUTTON_SECONDARY, down[0].buttonState)
        assertEquals(A.BUTTON_SECONDARY, down[1].actionButton)
    }

    @Test
    fun secondButtonWhileHeldIsPressOnly() {
        mapper.map(ev(InputKind.MOUSE_DOWN), 0)
        val second = mapper.map(ev(InputKind.MOUSE_DOWN, button = MouseButton.MIDDLE), 5)
        assertEquals(A.ACTION_BUTTON_PRESS, second.single().action)
        assertEquals(A.BUTTON_PRIMARY or A.BUTTON_TERTIARY, second.single().buttonState)
        val firstUp = mapper.map(ev(InputKind.MOUSE_UP), 10)
        assertEquals(A.ACTION_BUTTON_RELEASE, firstUp.single().action)   // middle still held: no UP yet
        assertEquals(A.BUTTON_TERTIARY, firstUp.single().buttonState)
        assertEquals(A.ACTION_UP, mapper.map(ev(InputKind.MOUSE_UP, button = MouseButton.MIDDLE), 20).last().action)
    }

    @Test
    fun strayUpAndDuplicateDownAreIgnored() {
        assertTrue(mapper.map(ev(InputKind.MOUSE_UP), 0).isEmpty())
        mapper.map(ev(InputKind.MOUSE_DOWN), 0)
        assertTrue(mapper.map(ev(InputKind.MOUSE_DOWN), 1).isEmpty())
    }

    @Test
    fun preciseScrollSignsAndUnits() {
        // Mac +deltaY = content moves down = Android VSCROLL positive. 64 points = one notch.
        val s = mapper.map(ev(InputKind.SCROLL, dx = 32f, dy = 128f, precise = true), 0).single()
        assertEquals(A.ACTION_SCROLL, s.action)
        assertEquals(2f, s.vScroll, 0.0001f)
        // Mac +deltaX = content moves right = Android HSCROLL negative ("scroll left").
        assertEquals(-0.5f, s.hScroll, 0.0001f)
    }

    @Test
    fun wheelLinesAreNotches() {
        val s = mapper.map(ev(InputKind.SCROLL, dy = -3f), 0).single()
        assertEquals(-3f, s.vScroll, 0.0001f)
        assertTrue(mapper.map(ev(InputKind.SCROLL), 0).isEmpty())
    }

    @Test
    fun keysMapWithMetaAndRepeat() {
        val down = mapper.map(ev(InputKind.KEY_DOWN, key = 0x00, mods = Modifiers.COMMAND), 50).single()
        assertEquals(InjectSpec.KIND_KEY, down.kind)
        assertEquals(A.KEY_ACTION_DOWN, down.action)
        assertEquals(29, down.keyCode)
        assertEquals(0x1000, down.metaState)
        assertEquals(0, down.repeatCount)
        val repeat = mapper.map(ev(InputKind.KEY_DOWN, key = 0x00), 90).single()
        assertEquals(1, repeat.repeatCount)
        assertEquals(50L, repeat.downTime)
        val up = mapper.map(ev(InputKind.KEY_UP, key = 0x00), 120).single()
        assertEquals(A.KEY_ACTION_UP, up.action)
        assertEquals(50L, up.downTime)
        assertTrue(mapper.map(ev(InputKind.KEY_DOWN, key = 0x7F), 0).isEmpty())   // unmapped
    }

    @Test
    fun commandKeyBecomesCtrlViaFlagsChanged() {
        val down = mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x37, mods = Modifiers.COMMAND), 0).single()
        assertEquals(A.KEY_ACTION_DOWN, down.action)
        assertEquals(A.KEYCODE_CTRL_LEFT, down.keyCode)
        val up = mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x37, mods = 0), 10).single()
        assertEquals(A.KEY_ACTION_UP, up.action)
        assertEquals(A.KEYCODE_CTRL_LEFT, up.keyCode)
        // ⌃ takes Meta's place so both remain reachable.
        assertEquals(A.KEYCODE_META_LEFT, mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x3B, mods = Modifiers.CONTROL), 0).single().keyCode)
    }

    @Test
    fun capsLockIsATogglePressAndFnIsDropped() {
        val caps = mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x39, mods = Modifiers.CAPS_LOCK), 0)
        assertEquals(listOf(A.KEY_ACTION_DOWN, A.KEY_ACTION_UP), caps.map { it.action })
        assertTrue(caps.all { it.keyCode == A.KEYCODE_CAPS_LOCK })
        assertTrue(mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x3F, mods = Modifiers.FN), 0).isEmpty())
    }

    @Test
    fun releaseAllLetsGoOfButtonsAndKeys() {
        mapper.map(ev(InputKind.MOUSE_DOWN), 0)
        mapper.map(ev(InputKind.MOUSE_DOWN, button = MouseButton.RIGHT), 0)
        mapper.map(ev(InputKind.FLAGS_CHANGED, key = 0x38, mods = Modifiers.SHIFT), 0)
        val out = mapper.releaseAll()
        val actions = out.map { it.kind to it.action }
        assertEquals(
            listOf(
                InjectSpec.KIND_MOTION to A.ACTION_BUTTON_RELEASE,
                InjectSpec.KIND_MOTION to A.ACTION_BUTTON_RELEASE,
                InjectSpec.KIND_MOTION to A.ACTION_UP,
                InjectSpec.KIND_KEY to A.KEY_ACTION_UP,
                InjectSpec.KIND_MOTION to A.ACTION_HOVER_EXIT,
            ),
            actions,
        )
        assertEquals(0, out[2].buttonState)
        // Nothing held any more: a second release only ends the hover.
        assertEquals(listOf(A.ACTION_HOVER_EXIT), mapper.releaseAll().map { it.action })
    }
}
