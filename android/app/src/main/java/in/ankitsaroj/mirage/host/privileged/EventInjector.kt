package `in`.ankitsaroj.mirage.host.privileged

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import `in`.ankitsaroj.mirage.host.AndroidInput
import `in`.ankitsaroj.mirage.host.InjectSpec
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Turns [InjectSpec]s into real events and injects them with the hidden
 * `InputManagerGlobal.injectInputEvent` (INJECT_EVENTS, which shell holds), targeted at one
 * display via the hidden `setDisplayId`. Single-threaded: binder one-way calls arrive in order
 * on one thread, so the reused pointer arrays are safe.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi")
internal object EventInjector {
    /** InputManager.INJECT_INPUT_EVENT_MODE_ASYNC: never wait for the target app to consume. */
    private const val MODE_ASYNC = 0

    private val manager: Any
    private val inject: Method
    private val motionSetDisplayId: Method
    private val keySetDisplayId: Method
    private val setActionButton: Method

    init {
        HiddenApiBypass.addHiddenApiExemptions("L")
        // Android 14 moved the instance to InputManagerGlobal; InputManager.getInstance() went away.
        val global = runCatching { Class.forName("android.hardware.input.InputManagerGlobal") }.getOrNull()
        val cls = global ?: Class.forName("android.hardware.input.InputManager")
        manager = cls.getDeclaredMethod("getInstance").invoke(null)!!
        inject = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        motionSetDisplayId = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        keySetDisplayId = KeyEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        setActionButton = MotionEvent::class.java.getMethod("setActionButton", Int::class.javaPrimitiveType)
    }

    private val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE })
    private val coords = arrayOf(MotionEvent.PointerCoords())

    fun inject(spec: InjectSpec, displayId: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        val event: InputEvent = if (spec.kind == InjectSpec.KIND_KEY) {
            KeyEvent(
                if (spec.downTime > 0) spec.downTime else now, now, spec.action, spec.keyCode,
                spec.repeatCount, spec.metaState, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                AndroidInput.SOURCE_KEYBOARD,
            ).also { keySetDisplayId.invoke(it, displayId) }
        } else {
            val c = coords[0]
            c.clear()
            c.x = spec.x
            c.y = spec.y
            if (spec.action == AndroidInput.ACTION_SCROLL) {
                c.setAxisValue(MotionEvent.AXIS_VSCROLL, spec.vScroll)
                c.setAxisValue(MotionEvent.AXIS_HSCROLL, spec.hScroll)
            }
            // Pressure 1 while a button is down, like a real mouse; 0 while hovering.
            c.pressure = if (spec.buttonState != 0) 1f else 0f
            MotionEvent.obtain(
                if (spec.downTime > 0) spec.downTime else now, now, spec.action, 1, props, coords,
                spec.metaState, spec.buttonState, 1f, 1f, 0, 0, AndroidInput.SOURCE_MOUSE, 0,
            ).also {
                motionSetDisplayId.invoke(it, displayId)
                // The dispatcher rejects BUTTON_PRESS/RELEASE without an action button.
                if (spec.actionButton != 0) setActionButton.invoke(it, spec.actionButton)
            }
        }
        return try {
            inject.invoke(manager, event, MODE_ASYNC) as Boolean
        } finally {
            if (event is MotionEvent) event.recycle()
        }
    }
}
