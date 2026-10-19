package `in`.ankitsaroj.mirage.receiver

import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import `in`.ankitsaroj.mirage.core.MouseButton

/**
 * Feeds a view's MotionEvents and the activity's KeyEvents into a [GestureEngine]. All of the
 * gesture logic lives in the engine (unit-tested); this class only classifies the device
 * (finger / S Pen / mouse-touchpad), extracts coordinates and drives the engine's timer.
 *
 * TODO trackpad mode (relative cursor, tap-to-click) as an alternative to direct touch.
 */
class InputCapture(private val view: View, private val engine: GestureEngine) :
    View.OnTouchListener, View.OnGenericMotionListener {

    private var mouseButtons = 0
    private val timer = Runnable {
        engine.onTimer(SystemClock.uptimeMillis())
        schedule()
    }

    fun attach() {
        view.setOnTouchListener(this)
        view.setOnGenericMotionListener(this)
        view.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            engine.viewWidth = v.width.toFloat()
            engine.viewHeight = v.height.toFloat()
        }
    }

    fun detach() {
        view.removeCallbacks(timer)
        view.setOnTouchListener(null)
        view.setOnGenericMotionListener(null)
        engine.releaseAll()
    }

    override fun onTouch(v: View, e: MotionEvent): Boolean {
        val t = e.eventTime
        when {
            isStylus(e) -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> engine.stylusDown(e.x, e.y, sideButton(e), t)
                MotionEvent.ACTION_MOVE -> engine.stylusMove(e.x, e.y, t)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> engine.stylusUp(e.x, e.y, t)
            }
            e.isFromSource(InputDevice.SOURCE_MOUSE) -> {
                if (e.actionMasked == MotionEvent.ACTION_MOVE) engine.mouseMove(e.x, e.y, t)
                syncMouseButtons(e)
            }
            else -> fingers(e, t)
        }
        schedule()
        return true
    }

    override fun onGenericMotion(v: View, e: MotionEvent): Boolean {
        val t = e.eventTime
        val handled = when {
            isStylus(e) -> when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> { engine.stylusHover(e.x, e.y, sideButton(e), t); true }
                MotionEvent.ACTION_HOVER_EXIT -> { engine.stylusHoverExit(t); true }
                else -> false
            }
            e.isFromSource(InputDevice.SOURCE_MOUSE) -> {
                when (e.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> engine.mouseMove(e.x, e.y, t)
                    MotionEvent.ACTION_SCROLL -> engine.mouseWheel(e.x, e.y,
                        e.getAxisValue(MotionEvent.AXIS_VSCROLL), e.getAxisValue(MotionEvent.AXIS_HSCROLL), t)
                }
                syncMouseButtons(e)
                true
            }
            else -> false
        }
        schedule()
        return handled
    }

    /**
     * Forwards hardware-keyboard keys. System keys (back, volume, home…) are never forwarded so
     * the tablet stays controllable. Returns true if consumed.
     */
    fun onKey(e: KeyEvent): Boolean {
        if (e.keyCode in LOCAL_KEYS) return false
        val down = when (e.action) {
            KeyEvent.ACTION_DOWN -> true
            KeyEvent.ACTION_UP -> false
            else -> return false
        }
        return engine.key(e.keyCode, down, e.metaState, KeyEvent.isModifierKey(e.keyCode))
    }

    private fun fingers(e: MotionEvent, t: Long) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> engine.touchDown(e.x, e.y, t)
            MotionEvent.ACTION_POINTER_DOWN -> engine.touchPointerDown(e.pointerCount, cx(e, -1), cy(e, -1), t)
            MotionEvent.ACTION_MOVE -> engine.touchMove(e.pointerCount, e.getX(0), e.getY(0), cx(e, -1), cy(e, -1), t)
            MotionEvent.ACTION_POINTER_UP -> engine.touchPointerUp(e.pointerCount - 1, cx(e, e.actionIndex), cy(e, e.actionIndex), t)
            MotionEvent.ACTION_UP -> engine.touchUp(e.x, e.y, t)
            MotionEvent.ACTION_CANCEL -> engine.touchCancel()
        }
    }

    /** Button-state diffing works whether the device reports BUTTON_PRESS, DOWN/UP or both. */
    private fun syncMouseButtons(e: MotionEvent) {
        val now = e.buttonState
        if (now == mouseButtons) return
        for ((androidBit, mac) in BUTTONS) {
            val was = mouseButtons and androidBit != 0
            val isDown = now and androidBit != 0
            if (was != isDown) engine.mouseButton(mac, isDown, e.x, e.y, e.eventTime)
        }
        mouseButtons = now
    }

    private fun schedule() {
        view.removeCallbacks(timer)
        val d = engine.nextDeadline()
        if (d >= 0) view.postDelayed(timer, (d - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    private fun isStylus(e: MotionEvent): Boolean {
        val tool = e.getToolType(e.actionIndex)
        return tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun sideButton(e: MotionEvent) = e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0

    private fun cx(e: MotionEvent, skip: Int): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getX(i); n++ }
        return if (n == 0) e.x else s / n
    }

    private fun cy(e: MotionEvent, skip: Int): Float {
        var s = 0f; var n = 0
        for (i in 0 until e.pointerCount) if (i != skip) { s += e.getY(i); n++ }
        return if (n == 0) e.y else s / n
    }

    private companion object {
        val BUTTONS = arrayOf(
            MotionEvent.BUTTON_PRIMARY to MouseButton.LEFT,
            MotionEvent.BUTTON_SECONDARY to MouseButton.RIGHT,
            MotionEvent.BUTTON_TERTIARY to MouseButton.MIDDLE,
        )
        val LOCAL_KEYS = setOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_APP_SWITCH,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST,
        )
    }
}
