package com.voxturr.mirage.receiver

import com.voxturr.mirage.core.InputEvent
import com.voxturr.mirage.core.InputKind
import com.voxturr.mirage.core.Modifiers
import com.voxturr.mirage.core.MouseButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GestureEngineTest {

    private val events = mutableListOf<InputEvent>()
    private var menus = 0
    private lateinit var g: GestureEngine

    // Fake KeyMap: A (29) → kVK_ANSI_A (0), Shift-left (59) → kVK_Shift (56); else unmapped.
    private val keyMap: (Int) -> Int = { when (it) { 29 -> 0; 59 -> 56; else -> -1 } }
    private val metaMap: (Int) -> Int = { if (it and 1 != 0) Modifiers.SHIFT else 0 }

    @Before fun setUp() {
        g = GestureEngine(sink = { events += it }, touchSlopPx = 10f, keyMapper = keyMap, metaMapper = metaMap)
        g.viewWidth = 1000f; g.viewHeight = 500f
        g.pointsPerPixel = 0.5f
        g.onMenuGesture = { menus++ }
    }

    private fun kinds() = events.map { it.kind }
    private fun of(kind: Int) = events.filter { it.kind == kind }

    @Test fun tapIsLeftClickAtThePoint() {
        g.touchDown(500f, 250f, 1000)
        g.touchUp(502f, 251f, 1080)
        assertEquals(listOf(InputKind.MOUSE_MOVE, InputKind.MOUSE_DOWN, InputKind.MOUSE_UP), kinds())
        val down = of(InputKind.MOUSE_DOWN).single()
        assertEquals(MouseButton.LEFT, down.button)
        assertEquals(1, down.clickCount)
        assertEquals(0.5f, down.x, 1e-6f)
        assertEquals(0.5f, down.y, 1e-6f)
    }

    @Test fun doubleTapCarriesClickCountTwo() {
        g.touchDown(100f, 100f, 1000); g.touchUp(100f, 100f, 1050)
        g.touchDown(104f, 102f, 1200); g.touchUp(104f, 102f, 1250)
        assertEquals(listOf(1, 2), of(InputKind.MOUSE_DOWN).map { it.clickCount })
        assertEquals(listOf(1, 2), of(InputKind.MOUSE_UP).map { it.clickCount })
    }

    @Test fun slowSecondTapIsSingleClick() {
        g.touchDown(100f, 100f, 1000); g.touchUp(100f, 100f, 1050)
        g.touchDown(100f, 100f, 1600); g.touchUp(100f, 100f, 1650)
        assertEquals(listOf(1, 1), of(InputKind.MOUSE_DOWN).map { it.clickCount })
    }

    @Test fun dragPressesAtOriginThenMoves() {
        g.touchDown(100f, 100f, 1000)
        g.touchMove(1, 105f, 100f, 105f, 100f, 1010) // within slop: nothing
        assertTrue(of(InputKind.MOUSE_DOWN).isEmpty())
        g.touchMove(1, 130f, 100f, 130f, 100f, 1020)
        val down = of(InputKind.MOUSE_DOWN).single()
        assertEquals(0.1f, down.x, 1e-6f)
        g.touchMove(1, 200f, 100f, 200f, 100f, 1040)
        g.touchUp(210f, 100f, 1050)
        val up = of(InputKind.MOUSE_UP).single()
        assertEquals(0.21f, up.x, 1e-6f)
        assertEquals(InputKind.MOUSE_UP, events.last().kind)
        assertTrue(of(InputKind.MOUSE_MOVE).any { it.x == 0.2f })
    }

    @Test fun longPressIsRightClickOnly() {
        g.touchDown(300f, 300f, 1000)
        assertEquals(1500L, g.nextDeadline())
        g.onTimer(1499)
        assertTrue(of(InputKind.MOUSE_DOWN).isEmpty())
        g.onTimer(1500)
        g.touchUp(300f, 300f, 1700)
        val downs = of(InputKind.MOUSE_DOWN)
        assertEquals(listOf(MouseButton.RIGHT), downs.map { it.button })
        assertEquals(listOf(MouseButton.RIGHT), of(InputKind.MOUSE_UP).map { it.button })
    }

    @Test fun twoFingerPanScrollsInMacPoints() {
        g.touchDown(500f, 200f, 1000)
        g.touchPointerDown(2, 500f, 200f, 1005)
        var t = 1010L
        var y = 200f
        repeat(10) { y += 10f; t += 10; g.touchMove(2, 500f, y, 500f, y, t) }
        g.touchUp(500f, y, t + 10)
        assertTrue(of(InputKind.MOUSE_DOWN).isEmpty())
        val scrolls = of(InputKind.SCROLL)
        assertTrue(scrolls.isNotEmpty())
        assertTrue(scrolls.all { it.isPreciseScroll })
        // 100 px finger travel down × 0.5 pt/px = +50 pt (positive = fingers move down, spec §4)
        assertEquals(50f, scrolls.sumOf { it.deltaY.toDouble() }.toFloat(), 0.01f)
        assertEquals(0f, scrolls.sumOf { it.deltaX.toDouble() }.toFloat(), 0.01f)
    }

    @Test fun scrollDeltasAreIntegralWithRemainderCarried() {
        g.touchDown(500f, 200f, 1000)
        g.touchPointerDown(2, 500f, 200f, 1001)
        var y = 200f; var t = 1001L
        repeat(40) { y += 3f; t += 5; g.touchMove(2, 500f, y, 500f, y, t) } // 1.5 pt per step
        g.touchUp(500f, y, t + 5)
        val scrolls = of(InputKind.SCROLL)
        assertTrue(scrolls.all { it.deltaY == it.deltaY.toInt().toFloat() })
        assertEquals(60f, scrolls.sumOf { it.deltaY.toDouble() }.toFloat(), 1.0f)
    }

    @Test fun twoFingerTapIsRightClick() {
        g.touchDown(500f, 200f, 1000)
        g.touchPointerDown(2, 520f, 200f, 1010)
        g.touchPointerUp(1, 500f, 200f, 1100)
        g.touchUp(500f, 200f, 1110)
        assertEquals(listOf(MouseButton.RIGHT), of(InputKind.MOUSE_DOWN).map { it.button })
        assertEquals(1, of(InputKind.MOUSE_UP).size)
    }

    @Test fun threeFingerHoldOpensMenu() {
        g.touchDown(500f, 200f, 1000)
        g.touchPointerDown(2, 510f, 200f, 1010)
        g.touchPointerDown(3, 520f, 200f, 1020)
        g.onTimer(1500)
        assertEquals(0, menus)
        g.onTimer(1720)
        assertEquals(1, menus)
        g.touchUp(500f, 200f, 1800)
        assertTrue(of(InputKind.MOUSE_DOWN).isEmpty())
        assertTrue(of(InputKind.SCROLL).isEmpty())
    }

    @Test fun movesAreCoalescedTo240HzAndLastIsFlushed() {
        g.touchDown(0f, 0f, 1000)
        g.touchMove(1, 50f, 0f, 50f, 0f, 1001) // commits drag
        val before = of(InputKind.MOUSE_MOVE).size
        for (i in 1..100) g.touchMove(1, 50f + i, 0f, 50f + i, 0f, 1001L + i) // 1 kHz for 100 ms
        val sent = of(InputKind.MOUSE_MOVE).size - before
        assertTrue("sent $sent moves in 100 ms", sent in 20..25)
        g.touchUp(150f, 0f, 1102)
        val lastMove = of(InputKind.MOUSE_MOVE).last()
        assertEquals(0.15f, lastMove.x, 1e-6f)
    }

    @Test fun trailingHoverMoveFlushedByTimer() {
        g.mouseMove(10f, 10f, 1000)
        g.mouseMove(20f, 10f, 1001) // coalesced
        assertEquals(1, of(InputKind.MOUSE_MOVE).size)
        val d = g.nextDeadline()
        assertTrue(d in 1004..1006)
        g.onTimer(d)
        assertEquals(0.02f, of(InputKind.MOUSE_MOVE).last().x, 1e-6f)
    }

    @Test fun mouseMoveDeltasInPoints() {
        g.mouseMove(100f, 100f, 1000)
        g.mouseMove(120f, 90f, 1010)
        val m = of(InputKind.MOUSE_MOVE).last()
        assertEquals(10f, m.deltaX, 1e-6f)
        assertEquals(-5f, m.deltaY, 1e-6f)
    }

    @Test fun stylusTipIsImmediateLeftAndSideButtonIsRight() {
        g.stylusHover(100f, 100f, false, 1000)
        g.stylusDown(100f, 100f, false, 1010)
        assertEquals(MouseButton.LEFT, of(InputKind.MOUSE_DOWN).single().button)
        g.stylusMove(110f, 100f, 1020)
        g.stylusUp(110f, 100f, 1030)
        assertEquals(MouseButton.LEFT, of(InputKind.MOUSE_UP).single().button)
        events.clear()
        g.stylusDown(200f, 100f, true, 2000)
        g.stylusUp(200f, 100f, 2050)
        assertEquals(listOf(MouseButton.RIGHT), of(InputKind.MOUSE_DOWN).map { it.button })
        assertEquals(listOf(MouseButton.RIGHT), of(InputKind.MOUSE_UP).map { it.button })
    }

    @Test fun stylusSideButtonWhileHoveringIsRightClick() {
        g.stylusHover(100f, 100f, false, 1000)
        g.stylusHover(100f, 100f, true, 1010)
        g.stylusHover(100f, 100f, false, 1050)
        assertEquals(listOf(InputKind.MOUSE_MOVE, InputKind.MOUSE_DOWN, InputKind.MOUSE_UP), kinds())
        assertTrue(of(InputKind.MOUSE_DOWN).all { it.button == MouseButton.RIGHT })
    }

    @Test fun mouseButtonsAndDoubleClick() {
        g.mouseButton(MouseButton.LEFT, true, 100f, 100f, 1000)
        g.mouseButton(MouseButton.LEFT, false, 100f, 100f, 1050)
        g.mouseButton(MouseButton.LEFT, true, 101f, 100f, 1200)
        g.mouseButton(MouseButton.LEFT, false, 101f, 100f, 1250)
        g.mouseButton(MouseButton.LEFT, false, 101f, 100f, 1260) // duplicate up ignored
        assertEquals(listOf(1, 2), of(InputKind.MOUSE_DOWN).map { it.clickCount })
        assertEquals(2, of(InputKind.MOUSE_UP).size)
    }

    @Test fun wheelIsLineScrollWithMacSigns() {
        g.mouseWheel(100f, 100f, vscroll = 1f, hscroll = 0f, t = 1000) // wheel away = scroll up
        g.mouseWheel(100f, 100f, vscroll = 0f, hscroll = 1f, t = 1010)
        val s = of(InputKind.SCROLL)
        assertFalse(s[0].isPreciseScroll)
        assertEquals(1f, s[0].deltaY, 0f)
        assertEquals(-1f, s[1].deltaX, 0f)
    }

    @Test fun fractionalTouchpadScrollIsPrecise() {
        g.mouseWheel(100f, 100f, vscroll = 0.25f, hscroll = 0f, t = 1000)
        val s = of(InputKind.SCROLL).single()
        assertTrue(s.isPreciseScroll)
        assertEquals(6f, s.deltaY, 1e-6f)
    }

    @Test fun keyboardMapsKeysAndModifiers() {
        assertTrue(g.key(59, down = true, metaState = 1, isModifierKey = true))
        assertTrue(g.key(29, down = true, metaState = 1, isModifierKey = false))
        assertTrue(g.key(29, down = false, metaState = 1, isModifierKey = false))
        assertTrue(g.key(59, down = false, metaState = 0, isModifierKey = true))
        assertFalse(g.key(24, down = true, metaState = 0, isModifierKey = false)) // unmapped stays local
        assertEquals(listOf(InputKind.FLAGS_CHANGED, InputKind.KEY_DOWN, InputKind.KEY_UP, InputKind.FLAGS_CHANGED), kinds())
        assertEquals(listOf(56, 0, 0, 56), events.map { it.keyCode })
        assertEquals(listOf(Modifiers.SHIFT, Modifiers.SHIFT, Modifiers.SHIFT, 0), events.map { it.modifiers })
    }

    @Test fun modifiersApplyToClicks() {
        g.key(59, down = true, metaState = 1, isModifierKey = true)
        g.touchDown(10f, 10f, 1000); g.touchUp(10f, 10f, 1050)
        assertEquals(Modifiers.SHIFT, of(InputKind.MOUSE_DOWN).single().modifiers)
    }

    @Test fun releaseAllLetsGoOfEverything() {
        g.touchDown(10f, 10f, 1000)
        g.touchMove(1, 50f, 10f, 50f, 10f, 1010) // dragging
        g.mouseButton(MouseButton.RIGHT, true, 50f, 10f, 1020)
        g.key(59, down = true, metaState = 1, isModifierKey = true)
        events.clear()
        g.releaseAll()
        assertEquals(setOf(MouseButton.LEFT, MouseButton.RIGHT), of(InputKind.MOUSE_UP).map { it.button }.toSet())
        assertEquals(0, of(InputKind.FLAGS_CHANGED).single().modifiers)
    }

    @Test fun coordinatesAreClamped() {
        g.mouseMove(-20f, 900f, 1000)
        val m = events.single()
        assertEquals(0f, m.x, 0f)
        assertEquals(1f, m.y, 0f)
    }
}
