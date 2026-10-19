package com.voxturr.mirage.receiver

import android.content.Context
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.view.Display
import com.voxturr.mirage.core.ClientHello
import kotlin.math.roundToInt

/** The physical panel as the host should see it (spec §3: current orientation, never the window). */
data class Panel(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val refreshHz: Int,
    val dpi: Int,
    /** Display.Mode id of the best (highest refresh) mode at the current resolution. */
    val bestModeId: Int,
) {
    val landscape: Boolean get() = pixelWidth >= pixelHeight
    val orientation: String get() = if (landscape) "landscape" else "portrait"

    companion object {
        /**
         * Rotates a Display.Mode size to the current orientation and even-rounds it (the Mac only
         * synthesises a HiDPI mode for even sizes). Independent of the panel's natural orientation.
         */
        fun oriented(modeWidth: Int, modeHeight: Int, landscape: Boolean): Pair<Int, Int> {
            val long = maxOf(modeWidth, modeHeight)
            val short = minOf(modeWidth, modeHeight)
            val (w, h) = if (landscape) long to short else short to long
            return (w and 1.inv()) to (h and 1.inv())
        }

        fun probe(context: Context, display: Display? = null): Panel {
            val d = display ?: context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            val mode = d.mode
            val best = d.supportedModes
                .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                .maxByOrNull { it.refreshRate } ?: mode
            val orientation = context.resources.configuration.orientation
            val landscape = when (orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> true
                Configuration.ORIENTATION_PORTRAIT -> false
                else -> context.resources.displayMetrics.let { it.widthPixels >= it.heightPixels }
            }
            val (w, h) = oriented(mode.physicalWidth, mode.physicalHeight, landscape)
            val xdpi = context.resources.displayMetrics.xdpi
            return Panel(w, h, best.refreshRate.roundToInt(), xdpi.roundToInt(), best.modeId)
        }
    }
}

object Codecs {
    /** A hardware HEVC decoder exists (the Tab S9 FE's Exynos has one). */
    val hasHardwareHevcDecoder: Boolean by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            !info.isEncoder && info.isHardwareAccelerated &&
                info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
        }
    }

    /** Best decoder for [mime]: hardware first. */
    fun pickDecoder(mime: String): MediaCodecInfo? {
        val all = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        return all.firstOrNull { it.isHardwareAccelerated && !it.isSoftwareOnly } ?: all.firstOrNull()
    }
}

/** Builds the v2 ClientHello for this tablet. */
fun buildHello(panel: Panel, settings: ReceiverSettings, audioPort: Int): ClientHello {
    val pref = settings.scalePreference
    val (ptW, ptH) = pref.looksLike(panel.pixelWidth, panel.pixelHeight)
    return ClientHello(
        deviceName = settings.deviceName,
        pixelWidth = panel.pixelWidth,
        pixelHeight = panel.pixelHeight,
        pointWidth = ptW,
        pointHeight = ptH,
        refreshHz = panel.refreshHz,
        supportsHEVC = Codecs.hasHardwareHevcDecoder,
        supportsH264 = true,
        wantsInputForwarding = true,
        platform = "android",
        physicalDPI = panel.dpi.toDouble(),
        scalePreference = pref.wire,
        supportsAudio = audioPort > 0,
        audioUDPPort = audioPort.takeIf { it > 0 },
        orientation = panel.orientation,
    )
}

/** DNS-SD TXT record (spec §1). */
fun advertisementTxt(panel: Panel, locked: Boolean, audio: Boolean): Map<String, String> = mapOf(
    "px" to "${panel.pixelWidth}x${panel.pixelHeight}",
    "pt" to "${panel.pixelWidth / 2}x${panel.pixelHeight / 2}",
    "hz" to panel.refreshHz.toString(),
    "lock" to if (locked) "1" else "0",
    "v" to "2",
    "os" to "android",
    "dpi" to panel.dpi.toString(),
    "audio" to if (audio) "1" else "0",
)
