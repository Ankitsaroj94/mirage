package `in`.ankitsaroj.mirage.host

import `in`.ankitsaroj.mirage.core.InputEvent
import `in`.ankitsaroj.mirage.core.InputKind
import `in`.ankitsaroj.mirage.core.KeyMap
import `in`.ankitsaroj.mirage.core.Modifiers
import `in`.ankitsaroj.mirage.core.MouseButton
import `in`.ankitsaroj.mirage.host.AndroidInput as A

/**
 * Translates the Mac's [InputEvent]s (spec §4) into Android mouse/keyboard events for our
 * virtual display, as [InjectSpec] data. Pure and stateful (held buttons, held keys, last
 * position); not thread-safe — call from one thread (the link's reader).
 *
 * Mouse model (what a real USB mouse produces on Android, and what scrcpy injects):
 *  - move, no button → ACTION_HOVER_MOVE; move with a button → ACTION_MOVE
 *  - first button down → ACTION_DOWN then ACTION_BUTTON_PRESS (actionButton = that button);
 *    further buttons while one is held → ACTION_BUTTON_PRESS only
 *  - button up → ACTION_BUTTON_RELEASE; the last one also → ACTION_UP
 *  - wheel/trackpad → ACTION_SCROLL with AXIS_VSCROLL/AXIS_HSCROLL
 *
 * No hover enter/exit is synthesised: InputDispatcher derives those per window from the hover
 * stream itself. Injected events do not make Android draw a pointer (verified on One UI 8.5:
 * PointerChoreographer only draws for real devices), so the Mac's own cursor, which stays
 * visible over the video, is the pointer the user sees — positions match because both sides
 * use the same normalised coordinates.
 *
 * @param keyToAndroid macOS kVK → KEYCODE_* (-1 = unmapped); injectable for tests.
 * @param modifiersToMeta CGEventFlags → KeyEvent meta state.
 */
class MacInputMapper(
    private val commandAsCtrl: Boolean = true,
    private val keyToAndroid: (Int) -> Int = KeyMap::macToAndroid,
    private val modifiersToMeta: (Int) -> Int = { KeyMap.modifiersToMeta(it, commandAsCtrl) },
) {
    private var width = 1
    private var height = 1
    private var buttonState = 0
    private var gestureDownTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    /** Android keycode → its down time, for keys currently held (released on reset). */
    private val heldKeys = LinkedHashMap<Int, Long>()

    /** Display size in pixels; normalised coordinates map onto [0, size-1]. */
    fun configure(width: Int, height: Int) {
        this.width = maxOf(1, width)
        this.height = maxOf(1, height)
    }

    /** @param now SystemClock.uptimeMillis (the injector's event clock). */
    fun map(event: InputEvent, now: Long): List<InjectSpec> = when (event.kind) {
        InputKind.MOUSE_MOVE -> mouseMove(event)
        InputKind.MOUSE_DOWN -> mouseDown(event, now)
        InputKind.MOUSE_UP -> mouseUp(event)
        InputKind.SCROLL -> scroll(event)
        InputKind.KEY_DOWN -> key(event, down = true, now)
        InputKind.KEY_UP -> key(event, down = false, now)
        InputKind.FLAGS_CHANGED -> flagsChanged(event, now)
        else -> emptyList()
    }

    /** Releases everything still held — call when the link drops so nothing stays stuck. */
    fun releaseAll(): List<InjectSpec> {
        val out = ArrayList<InjectSpec>()
        if (buttonState != 0) {
            var remaining = buttonState
            for (bit in intArrayOf(A.BUTTON_PRIMARY, A.BUTTON_SECONDARY, A.BUTTON_TERTIARY)) {
                if (remaining and bit == 0) continue
                remaining = remaining and bit.inv()
                out += motion(A.ACTION_BUTTON_RELEASE, remaining, actionButton = bit, meta = 0)
            }
            out += motion(A.ACTION_UP, 0, meta = 0)
            buttonState = 0
        }
        for ((code, down) in heldKeys) {
            out += InjectSpec(InjectSpec.KIND_KEY, A.KEY_ACTION_UP, keyCode = code, downTime = down)
        }
        heldKeys.clear()
        // Ends any hover highlight under the (now departed) pointer.
        out += motion(A.ACTION_HOVER_EXIT, 0, meta = 0)
        return out
    }

    // MARK: mouse

    private fun position(e: InputEvent) {
        lastX = (e.x.coerceIn(0f, 1f) * width).coerceAtMost(width - 1f)
        lastY = (e.y.coerceIn(0f, 1f) * height).coerceAtMost(height - 1f)
    }

    private fun mouseMove(e: InputEvent): List<InjectSpec> {
        position(e)
        val action = if (buttonState == 0) A.ACTION_HOVER_MOVE else A.ACTION_MOVE
        return listOf(motion(action, buttonState, meta = modifiersToMeta(e.modifiers)))
    }

    private fun mouseDown(e: InputEvent, now: Long): List<InjectSpec> {
        position(e)
        val bit = buttonBit(e.button)
        if (buttonState and bit != 0) return emptyList()   // duplicate down; already held
        val meta = modifiersToMeta(e.modifiers)
        val out = ArrayList<InjectSpec>(2)
        val first = buttonState == 0
        buttonState = buttonState or bit
        if (first) {
            gestureDownTime = now
            out += motion(A.ACTION_DOWN, buttonState, meta = meta)
        }
        out += motion(A.ACTION_BUTTON_PRESS, buttonState, actionButton = bit, meta = meta)
        return out
    }

    private fun mouseUp(e: InputEvent): List<InjectSpec> {
        val bit = buttonBit(e.button)
        if (buttonState and bit == 0) return emptyList()    // never went down here
        // Up keeps the last pointer position, like a real mouse: the Mac may report the release
        // a hair off from the last move, and a jump at release reads as a tiny drag.
        val meta = modifiersToMeta(e.modifiers)
        buttonState = buttonState and bit.inv()
        val out = ArrayList<InjectSpec>(2)
        out += motion(A.ACTION_BUTTON_RELEASE, buttonState, actionButton = bit, meta = meta)
        if (buttonState == 0) out += motion(A.ACTION_UP, 0, meta = meta)
        return out
    }

    /**
     * Mac: positive deltaY = content moves down (spec §4). Android: AXIS_VSCROLL +1 = "scroll
     * up", which also moves content down — same sign. Horizontal is mirrored: Mac positive
     * deltaX moves content right; Android AXIS_HSCROLL +1 = "scroll right" moves it left.
     *
     * Units: Android scrolls `scaledScrollFactor` (64 dp) per 1.0 of axis. The virtual display's
     * density makes 1 dp == 1 Mac point, and precise Mac deltas are in points, so 64 points =
     * 1.0. Line deltas (a wheel) map one line to one notch, as a wheel on Android does.
     */
    private fun scroll(e: InputEvent): List<InjectSpec> {
        position(e)
        val scale = if (e.isPreciseScroll) 1f / POINTS_PER_NOTCH else 1f
        val v = e.deltaY * scale
        val h = -e.deltaX * scale
        if (v == 0f && h == 0f) return emptyList()
        return listOf(motion(A.ACTION_SCROLL, buttonState, meta = modifiersToMeta(e.modifiers)).copy(vScroll = v, hScroll = h))
    }

    private fun motion(action: Int, buttons: Int, actionButton: Int = 0, meta: Int) = InjectSpec(
        kind = InjectSpec.KIND_MOTION, action = action, x = lastX, y = lastY,
        buttonState = buttons, actionButton = actionButton, metaState = meta,
        // Only button gestures carry a down time; hover/scroll events stand alone.
        downTime = if (buttons != 0 || action == A.ACTION_UP || action == A.ACTION_BUTTON_RELEASE) gestureDownTime else 0,
    )

    private fun buttonBit(button: Int) = when (button) {
        MouseButton.RIGHT -> A.BUTTON_SECONDARY
        MouseButton.MIDDLE -> A.BUTTON_TERTIARY
        else -> A.BUTTON_PRIMARY
    }

    // MARK: keys

    private fun androidKey(macKey: Int): Int {
        val code = keyToAndroid(macKey)
        if (!commandAsCtrl) return code
        // ⌘ acts as Ctrl so ⌘C/⌘V/⌘Z do what a Mac user expects; ⌃ takes Meta's place.
        // Keep the key itself consistent with the meta-state mapping.
        return when (code) {
            A.KEYCODE_META_LEFT -> A.KEYCODE_CTRL_LEFT
            A.KEYCODE_META_RIGHT -> A.KEYCODE_CTRL_RIGHT
            A.KEYCODE_CTRL_LEFT -> A.KEYCODE_META_LEFT
            A.KEYCODE_CTRL_RIGHT -> A.KEYCODE_META_RIGHT
            else -> code
        }
    }

    private fun key(e: InputEvent, down: Boolean, now: Long): List<InjectSpec> {
        val code = androidKey(e.keyCode)
        if (code < 0) return emptyList()
        val meta = modifiersToMeta(e.modifiers)
        return if (down) {
            // The Mac sends auto-repeat as repeated keyDowns; Android wants a rising repeat count.
            val held = heldKeys[code]
            val repeat = if (held != null) 1 else 0
            if (held == null) heldKeys[code] = now
            listOf(InjectSpec(InjectSpec.KIND_KEY, A.KEY_ACTION_DOWN, keyCode = code, metaState = meta, repeatCount = repeat, downTime = held ?: now))
        } else {
            val downTime = heldKeys.remove(code) ?: now
            listOf(InjectSpec(InjectSpec.KIND_KEY, A.KEY_ACTION_UP, keyCode = code, metaState = meta, downTime = downTime))
        }
    }

    /**
     * Modifier keys arrive as flagsChanged with the key's code and the flags *after* the
     * change: the key went down if its flag is now set. Caps Lock toggles on each press, so it
     * becomes a full press. Fn has no Android equivalent and is dropped.
     */
    private fun flagsChanged(e: InputEvent, now: Long): List<InjectSpec> {
        val flag = MODIFIER_FLAG_FOR_KEY[e.keyCode] ?: return emptyList()
        if (flag == Modifiers.FN) return emptyList()
        if (flag == Modifiers.CAPS_LOCK) {
            val meta = modifiersToMeta(e.modifiers)
            return listOf(
                InjectSpec(InjectSpec.KIND_KEY, A.KEY_ACTION_DOWN, keyCode = A.KEYCODE_CAPS_LOCK, metaState = meta, downTime = now),
                InjectSpec(InjectSpec.KIND_KEY, A.KEY_ACTION_UP, keyCode = A.KEYCODE_CAPS_LOCK, metaState = meta, downTime = now),
            )
        }
        return key(e, down = e.modifiers and flag != 0, now)
    }

    companion object {
        /** Mac points per Android scroll notch (ViewConfiguration scroll factor, 64 dp). */
        const val POINTS_PER_NOTCH = 64f

        /** kVK codes of modifier keys → their CGEventFlags bit. */
        private val MODIFIER_FLAG_FOR_KEY = mapOf(
            0x37 to Modifiers.COMMAND, 0x36 to Modifiers.COMMAND,
            0x38 to Modifiers.SHIFT, 0x3C to Modifiers.SHIFT,
            0x3A to Modifiers.OPTION, 0x3D to Modifiers.OPTION,
            0x3B to Modifiers.CONTROL, 0x3E to Modifiers.CONTROL,
            0x39 to Modifiers.CAPS_LOCK,
            0x3F to Modifiers.FN,
        )
    }
}
