package com.voxturr.mirage.host.privileged

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Surface

/**
 * Trusted virtual displays, created from the shell uid (scrcpy `NewDisplayCapture` style).
 *
 * A normal app can only make an untrusted, private display that nothing else may launch on.
 * Shell holds ADD_TRUSTED_DISPLAY and ADD_ALWAYS_UNLOCKED_DISPLAY, which unlock the flags that
 * make the display behave like a real second monitor: other apps' activities may live there,
 * it gets its own focus and display group (so it is not mirrored and not blanked with the lock
 * screen), and it can host system decorations.
 */
@SuppressLint("PrivateApi")
object VirtualDisplays {

    /**
     * Hidden `VIRTUAL_DISPLAY_FLAG_*` values. Read reflectively so a platform that renumbers
     * them still gets the right bits; the fallbacks are the Android 13–16 values.
     */
    object Flags {
        val PUBLIC = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC                   // 1
        val PRESENTATION = DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION       // 2
        val OWN_CONTENT_ONLY = DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY // 8
        val SUPPORTS_TOUCH = hidden("VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH", 1 shl 6)
        val ROTATES_WITH_CONTENT = hidden("VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT", 1 shl 7)
        val DESTROY_CONTENT_ON_REMOVAL = hidden("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL", 1 shl 8)
        val SHOULD_SHOW_SYSTEM_DECORATIONS = hidden("VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS", 1 shl 9)
        val TRUSTED = hidden("VIRTUAL_DISPLAY_FLAG_TRUSTED", 1 shl 10)
        val OWN_DISPLAY_GROUP = hidden("VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP", 1 shl 11)
        val ALWAYS_UNLOCKED = hidden("VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED", 1 shl 12)
        val TOUCH_FEEDBACK_DISABLED = hidden("VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED", 1 shl 13)
        val OWN_FOCUS = hidden("VIRTUAL_DISPLAY_FLAG_OWN_FOCUS", 1 shl 14)

        /**
         * The "second monitor" set. OWN_FOCUS keeps keyboard focus on this display independent
         * of the tablet's own screen, so typing on the Mac never lands in a tablet app.
         * TOUCH_FEEDBACK_DISABLED: no tap ripples/vibration for injected mouse clicks.
         */
        fun desktop(systemDecorations: Boolean): Int {
            var f = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL or
                TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED or OWN_FOCUS
            if (systemDecorations) f = f or SHOULD_SHOW_SYSTEM_DECORATIONS
            return f
        }

        private fun hidden(name: String, fallback: Int): Int = try {
            DisplayManager::class.java.getDeclaredField(name).getInt(null)
        } catch (_: Throwable) {
            fallback
        }

        /** name=value for each flag, for spike/diagnostic logs. */
        fun describe(): String = listOf(
            "PUBLIC" to PUBLIC, "PRESENTATION" to PRESENTATION, "OWN_CONTENT_ONLY" to OWN_CONTENT_ONLY,
            "SUPPORTS_TOUCH" to SUPPORTS_TOUCH, "ROTATES_WITH_CONTENT" to ROTATES_WITH_CONTENT,
            "DESTROY_CONTENT_ON_REMOVAL" to DESTROY_CONTENT_ON_REMOVAL,
            "SHOULD_SHOW_SYSTEM_DECORATIONS" to SHOULD_SHOW_SYSTEM_DECORATIONS, "TRUSTED" to TRUSTED,
            "OWN_DISPLAY_GROUP" to OWN_DISPLAY_GROUP, "ALWAYS_UNLOCKED" to ALWAYS_UNLOCKED,
            "TOUCH_FEEDBACK_DISABLED" to TOUCH_FEEDBACK_DISABLED, "OWN_FOCUS" to OWN_FOCUS,
        ).joinToString(" ") { "${it.first}=${it.second}" }
    }

    /**
     * DisplayManager bound to [ShellContext]: the public constructor is hidden, and the
     * service checks the context's package name against our (shell) uid.
     */
    private val displayManager: DisplayManager by lazy {
        DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(ShellContext.instance)
    }

    fun create(name: String, width: Int, height: Int, densityDpi: Int, surface: Surface, flags: Int): VirtualDisplay =
        displayManager.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
            ?: error("createVirtualDisplay returned null")
}
