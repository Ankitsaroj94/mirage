package `in`.ankitsaroj.mirage.receiver

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/** spec/PROTOCOL.md §5: how much the host scales the virtual display's points. */
enum class ScalePreference(val wire: String, val factor: Double, val label: String) {
    CRISP("crisp", 1.0, "Crisp"),
    LARGER("larger", 0.85, "Larger text"),
    MORE("more", 1.15, "More space"),
    MOST("most", 1.30, "Most space");

    /** The virtual display size in points ("looks like W×H"), even-rounded like the host. */
    fun looksLike(panelPxWidth: Int, panelPxHeight: Int): Pair<Int, Int> =
        evenRound(panelPxWidth / 2.0 * factor) to evenRound(panelPxHeight / 2.0 * factor)

    companion object {
        fun fromWire(value: String?): ScalePreference = entries.firstOrNull { it.wire == value } ?: CRISP
        fun evenRound(v: Double): Int {
            val r = Math.round(v).toInt()
            return if (r % 2 == 0) r else r - 1
        }
    }
}

/** Receiver preferences, persisted in SharedPreferences. */
class ReceiverSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("mirage.receiver", Context.MODE_PRIVATE)
    private val defaultName: String =
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL

    var deviceName: String
        get() = prefs.getString(KEY_NAME, null)?.takeIf { it.isNotBlank() } ?: defaultName
        set(v) = prefs.edit().putString(KEY_NAME, v.trim()).apply()

    /** Empty = no pairing code (unencrypted). */
    var pairingCode: String
        get() = prefs.getString(KEY_CODE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_CODE, v.trim()).apply()

    var scalePreference: ScalePreference
        get() = ScalePreference.fromWire(prefs.getString(KEY_SCALE, null))
        set(v) = prefs.edit().putString(KEY_SCALE, v.wire).apply()

    var showStats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(v) = prefs.edit().putBoolean(KEY_STATS, v).apply()

    /** Map the keyboard's Ctrl key to ⌘ (handy on Android keyboards without a Meta key). */
    var ctrlAsCommand: Boolean
        get() = prefs.getBoolean(KEY_CTRL_CMD, false)
        set(v) = prefs.edit().putBoolean(KEY_CTRL_CMD, v).apply()

    val pairingCodeOrNull: String? get() = pairingCode.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_NAME = "deviceName"
        const val KEY_CODE = "pairingCode"
        const val KEY_SCALE = "scalePreference"
        const val KEY_STATS = "showStats"
        const val KEY_CTRL_CMD = "ctrlAsCommand"
    }
}
