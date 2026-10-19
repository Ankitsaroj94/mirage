package `in`.ankitsaroj.mirage.host.privileged

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.media.MediaCodecList
import android.os.Build
import `in`.ankitsaroj.mirage.host.AndroidInput
import `in`.ankitsaroj.mirage.host.InjectSpec
import java.io.File

/**
 * Phase 0 spike S0b, run without Shizuku straight from adb (we are shell there too):
 *
 * ```
 * adb push app-debug.apk /data/local/tmp/mirage.apk
 * adb shell CLASSPATH=/data/local/tmp/mirage.apk app_process / \
 *     `in`.ankitsaroj.mirage.host.privileged.SpikeMain [decor] [desktop] [home] [launch=pkg] [hold=8]
 * ```
 * Creates a trusted virtual display backed by an ImageReader, optionally with system
 * decorations and the desktop-mode settings, starts things on it, hovers an injected mouse,
 * saves what the display shows to /data/local/tmp/mirage-spike-*.png, prints the state of the
 * window manager, and restores every setting it touched.
 */
object SpikeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val opts = args.toSet()
        val hold = args.firstOrNull { it.startsWith("hold=") }?.substringAfter('=')?.toLong() ?: 6
        val launch = args.firstOrNull { it.startsWith("launch=") }?.substringAfter('=')
        val (w, h, dpi) = Triple(2880, 1800, 320)   // a 14" MacBook-ish panel at 2×

        println("build: ${Build.MANUFACTURER} ${Build.MODEL} sdk=${Build.VERSION.SDK_INT} ${Build.DISPLAY}")
        println("flags: ${VirtualDisplays.Flags.describe()}")
        printEncoders(w, h)

        args.firstOrNull { it.startsWith("encode=") }?.let {
            // encode=hevc|h264[,WxH[,fps]]: the real encoder format on a decorated display.
            val parts = it.substringAfter('=').split(',')
            val mime = if (parts[0] == "hevc") "video/hevc" else "video/avc"
            val (ew, eh) = parts.getOrNull(1)?.split('x')?.map(String::toInt) ?: listOf(w, h)
            encodeTest(mime, ew, eh, parts.getOrNull(2)?.toInt() ?: 60, hold)
            System.exit(0)
        }

        val settings = DesktopSettings()
        settings.restoreStaleBackup()
        println("settings before: ${settings.snapshot()}")
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: android.hardware.display.VirtualDisplay? = null
        try {
            if ("desktop" in opts) {
                settings.enable()
                println("settings during: ${settings.snapshot()}")
            }
            val flags = VirtualDisplays.Flags.desktop(systemDecorations = "decor" in opts)
            display = VirtualDisplays.create("Mirage spike", w, h, dpi, reader.surface, flags)
            val id = display.display.displayId
            println("display: id=$id flags=0x${flags.toString(16)} ${display.display}")
            Thread.sleep(1500)

            if ("home" in opts) println("launchHome: " + Launcher.launchHome(id))
            if (launch != null) println("launch $launch: " + Launcher.launch(launch, id, freeform = "freeform" in opts))
            Thread.sleep(2500)

            // Hover the pointer to the middle, then near a corner: does a cursor render?
            for (i in 0..20) {
                val spec = InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_HOVER_MOVE, x = w / 2f + i * 10, y = h / 2f + i * 10)
                if (!EventInjector.inject(spec, id)) println("inject failed at $i")
                Thread.sleep(16)
            }
            // click=x,y: a left click (down, press, release, up) to prove injection reaches the display.
            args.firstOrNull { it.startsWith("click=") }?.substringAfter('=')?.split(',')?.let { (cx, cy) ->
                val x = cx.toFloat(); val y = cy.toFloat(); val t = android.os.SystemClock.uptimeMillis()
                val p = AndroidInput.BUTTON_PRIMARY
                listOf(
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_HOVER_MOVE, x, y),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_DOWN, x, y, buttonState = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_BUTTON_PRESS, x, y, buttonState = p, actionButton = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_BUTTON_RELEASE, x, y, actionButton = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_UP, x, y, downTime = t),
                ).forEach { println("click ${it.action}: ${EventInjector.inject(it, id)}"); Thread.sleep(20) }
            }
            Thread.sleep(hold * 1000)
            save(reader, "/data/local/tmp/mirage-spike-${opts.filter { '=' !in it }.sorted().joinToString("-").ifEmpty { "plain" }}.png")

            println("---- dumpsys display (ours)")
            println(Shell.run("sh", "-c", "dumpsys display | grep -E 'Mirage spike' | head -5"))
            println("---- activities on display $id")
            println(Shell.run("sh", "-c", "dumpsys activity activities | grep -E -A12 '^Display #$id' | head -40"))
            println("---- windows on display $id")
            println(Shell.run("sh", "-c", "dumpsys window windows | grep -E 'Window #|mDisplayId=$id' | grep -B1 'mDisplayId=$id' | head -40"))
            println("---- display content $id")
            println(Shell.run("sh", "-c", "dumpsys window displays | grep -E -A25 'Display: mDisplayId=$id' | grep -i -E 'mDisplayId|windowing|decor|desktop|freeform|homeTask|taskbar|mFocused|mCurrentFocus' | head -20"))
            println("---- pointer")
            println(Shell.run("sh", "-c", "dumpsys input | grep -i -E -A6 'PointerChoreographer' | head -20"))
        } catch (t: Throwable) {
            println("FAILED: $t")
            t.printStackTrace(System.out)
        } finally {
            display?.release()
            reader.close()
            settings.restore()
            println("settings after: ${settings.snapshot()}")
        }
        System.exit(0)
    }

    /**
     * Encodes the decorated display for [seconds] with VideoEncoderAndroid's exact format,
     * clicking the app drawer open/closed to make motion, and reports fps, sizes, keyframe
     * response and composition→encoded latency (now − presentationTime). Exercises suspend.
     */
    private fun encodeTest(mime: String, w: Int, h: Int, fps: Int, seconds: Long) {
        val format = `in`.ankitsaroj.mirage.host.VideoEncoderAndroid.format(
            `in`.ankitsaroj.mirage.host.VideoEncoderAndroid.Config(mime, w, h, fps, 30_000),
        )
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        val codec = if (name != null) android.media.MediaCodec.createByCodecName(name) else android.media.MediaCodec.createEncoderByType(mime)
        println("encode: ${codec.name} $mime ${w}x$h@$fps (findEncoderForFormat=$name)")
        codec.configure(format, null, null, android.media.MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = codec.createInputSurface()
        codec.start()
        val display = VirtualDisplays.create("Mirage spike", w, h, 320, surface, VirtualDisplays.Flags.desktop(true))
        val id = display.display.displayId
        val info = android.media.MediaCodec.BufferInfo()
        var frames = 0; var keys = 0; var bytes = 0L; var configs = 0; var maxFrame = 0
        val latencies = ArrayList<Long>()
        var suspended = false
        var keyAskedAt = 0L; var keyLatencyMs = -1L
        val start = System.nanoTime()
        var lastClick = 0L; var clicks = 0
        while (System.nanoTime() - start < seconds * 1_000_000_000) {
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (elapsedMs - lastClick > 700) {   // toggle the app drawer → real redraws
                lastClick = elapsedMs; clicks++
                val x = w * 1361f / 2880; val y = h * 1771f / 1800; val t = android.os.SystemClock.uptimeMillis(); val p = AndroidInput.BUTTON_PRIMARY
                for (s in listOf(
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_DOWN, x, y, buttonState = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_BUTTON_PRESS, x, y, buttonState = p, actionButton = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_BUTTON_RELEASE, x, y, actionButton = p, downTime = t),
                    InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_UP, x, y, downTime = t),
                )) EventInjector.inject(s, id)
            }
            if (elapsedMs in 3000..3999 && !suspended) { suspended = true; codec.setParameters(android.os.Bundle().apply { putInt(android.media.MediaCodec.PARAMETER_KEY_SUSPEND, 1) }); println("suspend at ${elapsedMs}ms, frames=$frames") }
            if (elapsedMs >= 4000 && suspended) { suspended = false; codec.setParameters(android.os.Bundle().apply { putInt(android.media.MediaCodec.PARAMETER_KEY_SUSPEND, 0) }); println("resume at ${elapsedMs}ms, frames=$frames") }
            if (keyAskedAt == 0L && elapsedMs > 5000) { keyAskedAt = System.nanoTime(); codec.setParameters(android.os.Bundle().apply { putInt(android.media.MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
            val index = codec.dequeueOutputBuffer(info, 5_000)
            if (index < 0) continue
            if (info.flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                configs++
                println("codec config ${info.size} bytes")
            } else if (info.size > 0) {
                frames++; bytes += info.size; maxFrame = maxOf(maxFrame, info.size)
                if (info.flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                    keys++
                    if (keyAskedAt != 0L && keyLatencyMs < 0) keyLatencyMs = (System.nanoTime() - keyAskedAt) / 1_000_000
                }
                latencies += System.nanoTime() / 1000 - info.presentationTimeUs
            }
            codec.releaseOutputBuffer(index, false)
        }
        display.release()
        codec.stop(); codec.release()
        latencies.sort()
        val secs = seconds.toDouble()
        println("frames=$frames (${"%.1f".format(frames / secs)} fps incl. 1 s suspended) keyframes=$keys configs=$configs clicks=$clicks")
        println("avg ${bytes / maxOf(1, frames)} B/frame, max $maxFrame B, ${"%.1f".format(bytes * 8 / secs / 1e6)} Mbit/s")
        if (latencies.isNotEmpty()) println("compose→encoded latency µs: p50=${latencies[latencies.size / 2]} p90=${latencies[latencies.size * 9 / 10]} max=${latencies.last()}")
        println("keyframe after request: $keyLatencyMs ms")
    }

    private fun save(reader: ImageReader, path: String) {
        val image = reader.acquireLatestImage() ?: return println("no frame rendered (nothing on the display?)")
        image.use {
            val plane = it.planes[0]
            val bmp = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, it.height, Bitmap.Config.ARGB_8888)
            bmp.copyPixelsFromBuffer(plane.buffer)
            File(path).outputStream().use { out -> Bitmap.createBitmap(bmp, 0, 0, it.width, it.height).compress(Bitmap.CompressFormat.PNG, 100, out) }
            println("saved $path")
        }
    }

    private fun printEncoders(w: Int, h: Int) {
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (!info.isEncoder) continue
            for (type in info.supportedTypes) {
                if (type != "video/hevc" && type != "video/avc") continue
                val caps = info.getCapabilitiesForType(type)
                val v = caps.videoCapabilities
                val enc = caps.encoderCapabilities
                println(
                    "encoder ${info.name} $type hw=${info.isHardwareAccelerated} " +
                        "max=${v.supportedWidths.upper}x${v.supportedHeights.upper} " +
                        "${w}x$h@60=${v.areSizeAndRateSupported(w, h, 60.0)} 2560x1600@60=${v.areSizeAndRateSupported(2560, 1600, 60.0)} " +
                        "align=${v.widthAlignment}x${v.heightAlignment} bitrate=${v.bitrateRange} " +
                        "cbr=${enc.isBitrateModeSupported(2)} vbr=${enc.isBitrateModeSupported(1)} " +
                        "lowLatency=${caps.isFeatureSupported("low-latency")}",
                )
            }
        }
    }
}
