package `in`.ankitsaroj.mirage.receiver

import `in`.ankitsaroj.mirage.core.InputEvent
import `in`.ankitsaroj.mirage.core.InputKind
import `in`.ankitsaroj.mirage.core.MouseButton
import kotlin.math.hypot

/**
 * Turns touch / S Pen / mouse / keyboard input into Mirage [InputEvent]s (spec §4).
 * Pure JVM logic — no android.view types — so every gesture is unit-tested; [InputCapture]
 * adapts MotionEvent/KeyEvent onto it. Coordinates in, pixels of the video view; out, 0…1
 * top-left. Times are milliseconds on one monotonic base (MotionEvent.eventTime).
 *
 * Direct-touch mode:
 *  - 1 finger: the cursor jumps to the finger at once; the button press is committed when the
 *    finger moves past the slop (drag, pressed at the original point), lifts (click) or holds
 *    500 ms still (right click). Deferring the press avoids a stray left click before a
 *    right-click or a two-finger scroll.
 *  - Double tap → second click carries clickCount 2 (triple → 3).
 *  - 2 fingers: pan = precise pixel scroll (positive deltaY = fingers move down), tap = right click.
 *  - 3 fingers held still 700 ms → [onMenuGesture] (local menu: disconnect / stats).
 * S Pen: hover moves the cursor, tip = left (immediate, no deferral), side button = right.
 * Mouse/touchpad: hover, buttons, wheel. Keyboard: mac keycodes + modifiers, modifiers as flagsChanged.
 * Pointer moves are coalesced to ≤240 Hz; the last skipped move is always flushed.
 */
class GestureEngine(
    private val sink: (InputEvent) -> Unit,
    private val touchSlopPx: Float = 16f,
    /** Android KEYCODE → mac kVK, -1 if unmapped (KeyMap.androidToMac in production). */
    private val keyMapper: (Int) -> Int,
    /** KeyEvent meta state → Modifiers bits (KeyMap.metaToModifiers in production). */
    private val metaMapper: (Int) -> Int,
) {
    var viewWidth = 1f
    var viewHeight = 1f
    /** Mac points per view pixel (host pointWidth / pixelWidth; 0.5 for Crisp). */
    var pointsPerPixel = 0.5f
    var onMenuGesture: (() -> Unit)? = null

    /** Current keyboard modifiers, applied to pointer events too (⌘-click etc.). */
    var modifiers = 0
        private set

    // ---- pointer move coalescing -------------------------------------------------------------
    private var lastMoveAt = Long.MIN_VALUE / 2
    private var pendingMove = false
    private var pendingX = 0f
    private var pendingY = 0f
    private var lastSentX = Float.NaN
    private var lastSentY = Float.NaN

    // ---- touch state -------------------------------------------------------------------------
    private enum class Touch { IDLE, PENDING, DRAGGING, SCROLLING, MENU, IGNORE }
    private var touch = Touch.IDLE
    private var downX = 0f; private var downY = 0f; private var downAt = 0L
    private var clickCount = 1
    private var lastTapUpAt = Long.MIN_VALUE / 2
    private var lastTapX = 0f; private var lastTapY = 0f; private var lastTapCount = 0
    private var scrollAnchorX = 0f; private var scrollAnchorY = 0f
    private var scrollStartX = 0f; private var scrollStartY = 0f
    private var scrollStartAt = 0L
    private var scrollMoved = false
    private var scrollAccX = 0f; private var scrollAccY = 0f
    private var lastScrollAt = Long.MIN_VALUE / 2
    private var menuAt = 0L; private var menuX = 0f; private var menuY = 0f

    // ---- stylus / mouse ----------------------------------------------------------------------
    private var stylusButton = -1        // button held by the pen tip, -1 none
    private var stylusHoverRight = false // side button held while hovering
    private var mouseButtons = 0         // bitmask of mac buttons held
    private var lastClickAt = Long.MIN_VALUE / 2
    private var lastClickButton = -1
    private var lastClickX = 0f; private var lastClickY = 0f
    private var lastClickCount = 0
    private var wheelAccX = 0f; private var wheelAccY = 0f

    // =========================================================================================
    // Touch (fingers)
    // =========================================================================================

    fun touchDown(x: Float, y: Float, t: Long) {
        if (touch == Touch.DRAGGING) release(MouseButton.LEFT, x, y)
        touch = Touch.PENDING
        downX = x; downY = y; downAt = t
        clickCount = if (t - lastTapUpAt <= DOUBLE_TAP_MS &&
            hypot(x - lastTapX, y - lastTapY) <= touchSlopPx * 3
        ) minOf(lastTapCount + 1, 3) else 1
        moveTo(x, y, t, force = true)
    }

    /** Another finger went down; [count] = fingers now down, ([cx],[cy]) = their centroid. */
    fun touchPointerDown(count: Int, cx: Float, cy: Float, t: Long) {
        when (touch) {
            Touch.PENDING -> if (count >= 2) {
                touch = Touch.SCROLLING
                scrollAnchorX = cx; scrollAnchorY = cy
                scrollStartX = cx; scrollStartY = cy
                scrollStartAt = t; scrollMoved = false
                scrollAccX = 0f; scrollAccY = 0f
                moveTo(cx, cy, t, force = true)
            }
            Touch.SCROLLING -> if (count >= 3 && !scrollMoved) {
                touch = Touch.MENU; menuAt = t; menuX = cx; menuY = cy
            } else {
                scrollAnchorX = cx; scrollAnchorY = cy
            }
            else -> Unit // a drag keeps its single finger; extra fingers are ignored
        }
    }

    fun touchMove(count: Int, x: Float, y: Float, cx: Float, cy: Float, t: Long) {
        when (touch) {
            Touch.PENDING -> if (hypot(x - downX, y - downY) > touchSlopPx) {
                touch = Touch.DRAGGING
                press(MouseButton.LEFT, downX, downY, clickCount)
                moveTo(x, y, t, force = true)
            }
            Touch.DRAGGING -> moveTo(x, y, t, force = false)
            Touch.SCROLLING -> if (count >= 2) {
                val dx = cx - scrollAnchorX
                val dy = cy - scrollAnchorY
                scrollAnchorX = cx; scrollAnchorY = cy
                if (!scrollMoved && hypot(cx - scrollStartX, cy - scrollStartY) > touchSlopPx) scrollMoved = true
                scrollAccX += dx * pointsPerPixel
                scrollAccY += dy * pointsPerPixel
                if (scrollMoved && t - lastScrollAt >= MOVE_INTERVAL_MS) flushScroll(cx, cy, t)
            }
            Touch.MENU -> if (hypot(cx - menuX, cy - menuY) > touchSlopPx * 4) touch = Touch.IGNORE
            else -> Unit
        }
    }

    /** A finger lifted but [remaining] (≥1) are still down; ([cx],[cy]) = their centroid. */
    fun touchPointerUp(remaining: Int, cx: Float, cy: Float, t: Long) {
        when (touch) {
            Touch.SCROLLING -> { scrollAnchorX = cx; scrollAnchorY = cy; if (remaining < 2) maybeTwoFingerTap(t) }
            Touch.MENU -> touch = Touch.IGNORE
            else -> Unit
        }
    }

    fun touchUp(x: Float, y: Float, t: Long) {
        when (touch) {
            Touch.PENDING -> {
                press(MouseButton.LEFT, downX, downY, clickCount)
                release(MouseButton.LEFT, downX, downY, clickCount)
                lastTapUpAt = t; lastTapX = downX; lastTapY = downY; lastTapCount = clickCount
            }
            Touch.DRAGGING -> {
                moveTo(x, y, t, force = true)
                release(MouseButton.LEFT, x, y, clickCount)
                lastTapUpAt = Long.MIN_VALUE / 2
            }
            Touch.SCROLLING -> {
                if (scrollMoved) flushScroll(scrollAnchorX, scrollAnchorY, t) else maybeTwoFingerTap(t)
            }
            else -> Unit
        }
        touch = Touch.IDLE
    }

    fun touchCancel() {
        if (touch == Touch.DRAGGING) release(MouseButton.LEFT, lastSentX.orZero(), lastSentY.orZero())
        touch = Touch.IDLE
    }

    private fun maybeTwoFingerTap(t: Long) {
        if (touch != Touch.SCROLLING) return
        if (!scrollMoved && t - scrollStartAt <= TAP_MS) {
            press(MouseButton.RIGHT, scrollStartX, scrollStartY, 1)
            release(MouseButton.RIGHT, scrollStartX, scrollStartY, 1)
        }
        touch = Touch.IGNORE
    }

    private fun flushScroll(cx: Float, cy: Float, t: Long) {
        val ix = scrollAccX.toInt().toFloat()
        val iy = scrollAccY.toInt().toFloat()
        if (ix == 0f && iy == 0f) return
        scrollAccX -= ix; scrollAccY -= iy
        lastScrollAt = t
        emit(InputKind.SCROLL, 0, 1, cx, cy, ix, iy, precise = true)
    }

    // =========================================================================================
    // Timers (long press, 3-finger menu, trailing move flush)
    // =========================================================================================

    /** Earliest time [onTimer] needs to run, or -1. */
    fun nextDeadline(): Long {
        var d = -1L
        fun take(v: Long) { if (d < 0 || v < d) d = v }
        if (touch == Touch.PENDING) take(downAt + LONG_PRESS_MS)
        if (touch == Touch.MENU) take(menuAt + MENU_HOLD_MS)
        if (pendingMove) take(lastMoveAt + MOVE_INTERVAL_MS.toLong() + 1)
        return d
    }

    fun onTimer(t: Long) {
        if (touch == Touch.PENDING && t - downAt >= LONG_PRESS_MS) {
            press(MouseButton.RIGHT, downX, downY, 1)
            release(MouseButton.RIGHT, downX, downY, 1)
            touch = Touch.IGNORE
        }
        if (touch == Touch.MENU && t - menuAt >= MENU_HOLD_MS) {
            touch = Touch.IGNORE
            onMenuGesture?.invoke()
        }
        if (pendingMove && t - lastMoveAt >= MOVE_INTERVAL_MS) flushMove(t)
    }

    // =========================================================================================
    // Stylus (S Pen)
    // =========================================================================================

    /** Hover move; [sideButton] = BUTTON_STYLUS_PRIMARY held. */
    fun stylusHover(x: Float, y: Float, sideButton: Boolean, t: Long) {
        if (sideButton != stylusHoverRight) {
            stylusHoverRight = sideButton
            if (sideButton) press(MouseButton.RIGHT, x, y, nextClickCount(MouseButton.RIGHT, x, y, t))
            else release(MouseButton.RIGHT, x, y)
            return
        }
        moveTo(x, y, t, force = false)
    }

    fun stylusDown(x: Float, y: Float, sideButton: Boolean, t: Long) {
        flushMove(t)
        if (stylusHoverRight) { stylusButton = MouseButton.RIGHT; return } // already pressed while hovering
        val b = if (sideButton) MouseButton.RIGHT else MouseButton.LEFT
        stylusButton = b
        moveTo(x, y, t, force = true)
        press(b, x, y, nextClickCount(b, x, y, t))
    }

    fun stylusMove(x: Float, y: Float, t: Long) = moveTo(x, y, t, force = false)

    fun stylusUp(x: Float, y: Float, t: Long) {
        moveTo(x, y, t, force = true)
        if (stylusButton >= 0) release(stylusButton, x, y, lastClickCount.coerceAtLeast(1))
        stylusButton = -1
        stylusHoverRight = false
    }

    /** Pen left hover range: release a side-button press that never got its "up". */
    fun stylusHoverExit(t: Long) {
        flushMove(t)
        if (stylusHoverRight) { release(MouseButton.RIGHT, lastSentX.orZero(), lastSentY.orZero()); stylusHoverRight = false }
    }

    // =========================================================================================
    // Mouse / touchpad
    // =========================================================================================

    fun mouseMove(x: Float, y: Float, t: Long) = moveTo(x, y, t, force = false)

    /** [button] is a [MouseButton] value. */
    fun mouseButton(button: Int, down: Boolean, x: Float, y: Float, t: Long) {
        moveTo(x, y, t, force = true)
        val bit = 1 shl button
        if (down) {
            if (mouseButtons and bit != 0) return
            mouseButtons = mouseButtons or bit
            press(button, x, y, nextClickCount(button, x, y, t))
        } else {
            if (mouseButtons and bit == 0) return
            mouseButtons = mouseButtons and bit.inv()
            release(button, x, y, lastClickCount.coerceAtLeast(1))
        }
    }

    /**
     * Android AXIS_VSCROLL/HSCROLL (+v = wheel away from user = content moves down; +h = right).
     * Whole-notch values go out as line scroll; fractional (touchpad) values as precise pixels.
     */
    fun mouseWheel(x: Float, y: Float, vscroll: Float, hscroll: Float, t: Long) {
        moveTo(x, y, t, force = true)
        val fractional = vscroll != vscroll.toInt().toFloat() || hscroll != hscroll.toInt().toFloat()
        if (fractional) {
            emit(InputKind.SCROLL, 0, 1, x, y, -hscroll * TOUCHPAD_PT_PER_UNIT, vscroll * TOUCHPAD_PT_PER_UNIT, precise = true)
            return
        }
        wheelAccX += -hscroll; wheelAccY += vscroll
        val ix = wheelAccX.toInt().toFloat(); val iy = wheelAccY.toInt().toFloat()
        if (ix == 0f && iy == 0f) return
        wheelAccX -= ix; wheelAccY -= iy
        emit(InputKind.SCROLL, 0, 1, x, y, ix, iy, precise = false)
    }

    // =========================================================================================
    // Keyboard
    // =========================================================================================

    /** Returns true if consumed (forwarded). Unmapped keys (volume, back…) stay local. */
    fun key(androidKeyCode: Int, down: Boolean, metaState: Int, isModifierKey: Boolean): Boolean {
        val mac = keyMapper(androidKeyCode)
        if (mac < 0) return false
        modifiers = metaMapper(metaState)
        val kind = when {
            isModifierKey -> InputKind.FLAGS_CHANGED
            down -> InputKind.KEY_DOWN
            else -> InputKind.KEY_UP
        }
        sink(InputEvent(kind = kind, keyCode = mac, modifiers = modifiers,
            x = norm(lastSentX.orZero(), viewWidth), y = norm(lastSentY.orZero(), viewHeight)))
        return true
    }

    /** Focus lost / link reset: release whatever is held so the Mac has no stuck buttons. */
    fun releaseAll() {
        val x = lastSentX.orZero(); val y = lastSentY.orZero()
        if (touch == Touch.DRAGGING) release(MouseButton.LEFT, x, y)
        touch = Touch.IDLE
        if (stylusButton >= 0) release(stylusButton, x, y)
        stylusButton = -1
        if (stylusHoverRight) release(MouseButton.RIGHT, x, y)
        stylusHoverRight = false
        for (b in 0..2) if (mouseButtons and (1 shl b) != 0) release(b, x, y)
        mouseButtons = 0
        if (modifiers != 0) {
            modifiers = 0
            sink(InputEvent(kind = InputKind.FLAGS_CHANGED, modifiers = 0))
        }
    }

    // =========================================================================================
    // Emission helpers
    // =========================================================================================

    private fun nextClickCount(button: Int, x: Float, y: Float, t: Long): Int {
        val n = if (button == lastClickButton && t - lastClickAt <= DOUBLE_CLICK_MS &&
            hypot(x - lastClickX, y - lastClickY) <= touchSlopPx
        ) minOf(lastClickCount + 1, 3) else 1
        lastClickAt = t; lastClickButton = button; lastClickX = x; lastClickY = y; lastClickCount = n
        return n
    }

    private fun moveTo(x: Float, y: Float, t: Long, force: Boolean) {
        if (!force && t - lastMoveAt < MOVE_INTERVAL_MS) {
            pendingMove = true; pendingX = x; pendingY = y
            return
        }
        sendMove(x, y, t)
    }

    private fun flushMove(t: Long) {
        if (pendingMove) sendMove(pendingX, pendingY, t)
    }

    private fun sendMove(x: Float, y: Float, t: Long) {
        pendingMove = false
        if (x == lastSentX && y == lastSentY) return
        val dx = if (lastSentX.isNaN()) 0f else (x - lastSentX) * pointsPerPixel
        val dy = if (lastSentY.isNaN()) 0f else (y - lastSentY) * pointsPerPixel
        lastMoveAt = t
        emit(InputKind.MOUSE_MOVE, 0, 1, x, y, dx, dy, precise = false)
    }

    private fun press(button: Int, x: Float, y: Float, count: Int = 1) {
        if (pendingMove) sendMove(pendingX, pendingY, lastMoveAt)
        emit(InputKind.MOUSE_DOWN, button, count, x, y, 0f, 0f, precise = false)
    }

    private fun release(button: Int, x: Float, y: Float, count: Int = 1) {
        if (pendingMove) sendMove(pendingX, pendingY, lastMoveAt)
        emit(InputKind.MOUSE_UP, button, count, x, y, 0f, 0f, precise = false)
    }

    private fun emit(kind: Int, button: Int, count: Int, x: Float, y: Float, dx: Float, dy: Float, precise: Boolean) {
        if (kind == InputKind.MOUSE_MOVE || kind == InputKind.MOUSE_DOWN || kind == InputKind.MOUSE_UP) {
            lastSentX = x; lastSentY = y
        }
        sink(InputEvent(kind = kind, button = button, clickCount = count,
            x = norm(x, viewWidth), y = norm(y, viewHeight),
            deltaX = dx, deltaY = dy, modifiers = modifiers, isPreciseScroll = precise))
    }

    private fun norm(v: Float, size: Float): Float = if (size <= 0f) 0f else (v / size).coerceIn(0f, 1f)
    private fun Float.orZero() = if (isNaN()) 0f else this

    companion object {
        const val MOVE_INTERVAL_MS = 1000.0 / 240.0
        const val LONG_PRESS_MS = 500L
        const val MENU_HOLD_MS = 700L
        const val TAP_MS = 300L
        const val DOUBLE_TAP_MS = 300L
        const val DOUBLE_CLICK_MS = 400L
        /** Touchpad ACTION_SCROLL units → Mac points (roughly one text line). */
        const val TOUCHPAD_PT_PER_UNIT = 24f
    }
}
