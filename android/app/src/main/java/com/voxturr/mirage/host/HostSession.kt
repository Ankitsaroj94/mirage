package com.voxturr.mirage.host

import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.voxturr.mirage.core.ClientHello
import com.voxturr.mirage.core.ClientStats
import com.voxturr.mirage.core.FrameFlags
import com.voxturr.mirage.core.HostHelloAck
import com.voxturr.mirage.core.InputEvent
import com.voxturr.mirage.core.MessageType
import com.voxturr.mirage.core.PeerLink
import com.voxturr.mirage.core.Role
import com.voxturr.mirage.core.VideoParameterSets
import com.voxturr.mirage.host.privileged.DesktopSettings
import com.voxturr.mirage.host.privileged.EventInjector
import com.voxturr.mirage.host.privileged.VirtualDisplays
import java.net.InetAddress
import kotlin.math.roundToInt

/**
 * "Send my screen": this tablet is the host, a Mac running "Be a display" is the client.
 * Port of the Mac `HostSession`.
 *
 * **Runs as the shell user** — under `app_process` started by the Mac over adb
 * ([com.voxturr.mirage.host.privileged.ServerMain]) or inside the Shizuku UserService. That
 * is what lets it create a trusted virtual display and inject input directly; it takes no
 * Context (a shell process has none worth the name) and talks to its owner through [listener].
 *
 * Pipeline: MediaCodec encoder → its input Surface becomes the virtual display → AVCC frames
 * through [FramePumpAndroid] onto the [PeerLink]. Threads: control (connect, hello, teardown)
 * on one "mirage-host" HandlerThread; the hot paths never touch it — input, acks and keyframe
 * requests run on the link's reader thread, frames on the encoder thread.
 *
 * TODO(audio): Android → Mac audio is not implemented. The plan is a shell-uid AudioRecord on
 * REMOTE_SUBMIX (scrcpy's approach, possible here because we are shell), packetised exactly
 * like the Mac AudioSender (spec §7: 5 ms s16le over UDP to the client's audioUDPPort, playAt
 * = capture + playoutDelay), announced with audioConfig after helloAck. Until then helloAck
 * says audioAvailable = false and no audioConfig is sent.
 */
class HostSession(
    val options: Options = Options(),
    private val hostName: String = Build.MODEL,
) {

    data class Options(
        val targetBitrateKbps: Int = 30_000,
        val minimumBitrateKbps: Int = 6_000,
        val adaptiveBitrate: Boolean = true,
        val maxFps: Int = 60,
        val preferHevc: Boolean = true,
        val forceH264: Boolean = false,
        val acceptRemoteInput: Boolean = true,
        /** ⌘ drives Android's Ctrl shortcuts (copy/paste/undo). */
        val commandAsCtrl: Boolean = true,
        /**
         * Also flip the AOSP desktop-windowing settings while streaming. Off by default: on
         * One UI 8.5 they changed nothing (spike S0b) — the DeX launcher appears through the
         * display's system-decorations flag alone — and settings are the one thing that can
         * outlive a crash.
         */
        val desktopModeSettings: Boolean = false,
    )

    sealed interface State {
        data object Idle : State
        data class Connecting(val address: String) : State
        /** Linked; waiting for the Mac's hello. */
        data object Waiting : State
        data class Running(val name: String, val width: Int, val height: Int, val codec: String, val fps: Int, val displayId: Int) : State
        data class Failed(val message: String) : State
        /** The Mac said bye or the session was stopped. */
        data object Ended : State
    }

    data class Stats(
        val fps: Int = 0,
        val sentKbps: Int = 0,
        val targetKbps: Int = 0,
        val roundTripMs: Double = 0.0,
        val unacknowledged: Int = 0,
        val droppedEncoded: Int = 0,
        val suspensions: Int = 0,
        val remoteDecodeMs: Double = 0.0,
        val remoteDropped: Int = 0,
    )

    interface Listener {
        fun onState(state: State)
        fun onStats(stats: Stats) {}
    }

    var listener: Listener? = null

    @Volatile var state: State = State.Idle
        private set(value) { field = value; listener?.onState(value) }
    @Volatile var stats = Stats()
        private set

    private val thread = HandlerThread("mirage-host").apply { start() }
    private val control = Handler(thread.looper)
    private val desktopSettings = DesktopSettings()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile private var link: PeerLink? = null
    @Volatile private var pipeline: Pipeline? = null
    @Volatile private var remote = ClientStats(0, 0, 0.0, 0.0, 0)

    // MARK: connect / stop

    /** Connects to a Mac offering itself as a display. Non-blocking. */
    fun connect(host: String, port: Int, pairingCode: String?) {
        control.post {
            closeLinkLocked(sendBye = true)
            state = State.Connecting("$host:$port")
            val link = try {
                PeerLink.connect(InetAddress.getByName(host), port, Role.INITIATOR, pairingCode?.takeIf { it.isNotBlank() })
            } catch (t: Throwable) {
                state = State.Failed("could not reach $host:$port: ${t.message ?: t}")
                return@post
            }
            this.link = link
            link.onReady = { control.post { if (this.link === link && pipeline == null) state = State.Waiting } }
            link.onFailure = { error ->
                control.post {
                    if (this.link !== link) return@post
                    teardownVideo()
                    this.link = null
                    state = State.Failed(error?.message ?: "the Mac disconnected")
                }
            }
            link.onMessage = { type, _, _, payload -> onMessage(link, type, payload) }
            link.start()
            // The Mac sends its hello as soon as the link is up; nothing means a wrong port or
            // a pairing-code mismatch on the other side.
            control.postDelayed({
                if (this.link === link && pipeline == null && state !is State.Failed) {
                    closeLinkLocked(sendBye = true)
                    state = State.Failed("the Mac never described its display")
                }
            }, HELLO_TIMEOUT_MS)
        }
    }

    /** Ends the session (bye to the Mac, display gone, settings restored). Safe to call twice. */
    fun stop() {
        control.post {
            val wasActive = link != null || pipeline != null
            closeLinkLocked(sendBye = true)
            desktopSettings.restore()
            if (wasActive || state !is State.Failed) state = State.Ended
        }
    }

    /** [stop], then waits up to [timeoutMs] for the teardown (for process exit paths). */
    fun stopAndWait(timeoutMs: Long = 2_000) {
        if (!closed.compareAndSet(false, true)) return
        stop()
        val done = java.util.concurrent.CountDownLatch(1)
        control.post { done.countDown() }
        done.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        thread.quitSafely()
    }

    private fun closeLinkLocked(sendBye: Boolean) {
        teardownVideo()
        val link = link ?: return
        this.link = null
        if (sendBye) runCatching { link.send(MessageType.BYE) }
        runCatching { link.close() }
    }

    // MARK: messages

    /** Reader thread. Hot messages are handled right here; the rest hop to the control thread. */
    private fun onMessage(link: PeerLink, type: Int, payload: ByteArray) {
        when (type) {
            MessageType.INPUT -> pipeline?.inject(payload)
            MessageType.FRAME_ACK -> pipeline?.pump?.acknowledgeFrame()
            MessageType.REQUEST_KEYFRAME -> pipeline?.pump?.requestKeyframe()
            MessageType.STATS -> runCatching { remote = ClientStats.fromJson(payload) }
            MessageType.HELLO, MessageType.RECONFIGURE -> {
                val hello = runCatching { ClientHello.fromJson(payload) }.getOrNull() ?: return
                control.post { if (this.link === link) startPipeline(link, hello) }
            }
            MessageType.BYE -> control.post {
                if (this.link !== link) return@post
                closeLinkLocked(sendBye = false)
                desktopSettings.restore()
                state = State.Ended
            }
            else -> Unit   // unknown types are ignored (spec §2)
        }
    }

    // MARK: pipeline

    /**
     * A repeated hello or a reconfigure rebuilds display + encoder on the same link: the Mac
     * keeps its window and resets its renderer on the new parameter sets.
     */
    private fun startPipeline(link: PeerLink, hello: ClientHello) {
        teardownVideo()
        if (hello.protocolVersion !in 1..2) {
            return reject(link, "Version mismatch: the Mac is running an incompatible build of Mirage.")
        }
        val plan = plan(hello, options.maxFps)
        val fps = plan.fps
        val mime = VideoEncoderAndroid.choose(
            plan.width, plan.height,
            clientHevc = hello.supportsHEVC && options.preferHevc && !options.forceH264,
            clientH264 = hello.supportsH264,
            supportsSize = VideoEncoderAndroid::hardwareSupportsSize,
        )
        val p = Pipeline(link, plan, mime)
        try {
            p.start(hello)
        } catch (t: Throwable) {
            Log.e(TAG, "pipeline failed", t)
            p.stop()
            return reject(link, t.message ?: t.toString())
        }
        pipeline = p
        if (options.desktopModeSettings) desktopSettings.enable()

        val ack = HostHelloAck(
            accepted = true, hostName = hostName, codec = p.codecName,
            pixelWidth = plan.width, pixelHeight = plan.height,
            pointWidth = hello.pointWidth, pointHeight = hello.pointHeight,
            fps = fps, bitrateKbps = options.targetBitrateKbps,
            isHiDPI = plan.width > hello.pointWidth,
            platform = "android", audioAvailable = false,
        )
        link.send(MessageType.HELLO_ACK, payload = ack.toJson())
        // Frames that came out before the ack were not sent; start the stream with an IDR.
        p.acked = true
        p.pump.requestKeyframe()
        state = State.Running(hello.deviceName, plan.width, plan.height, p.codecName, fps, p.displayId)
        sampleStats(p)
    }

    private fun reject(link: PeerLink, reason: String) {
        Log.w(TAG, "rejecting: $reason")
        state = State.Failed(reason)
        val ack = HostHelloAck(
            accepted = false, reason = reason, hostName = hostName, codec = "h264",
            pixelWidth = 0, pixelHeight = 0, pointWidth = 0, pointHeight = 0, fps = 0, bitrateKbps = 0,
            isHiDPI = false, platform = "android",
        )
        runCatching { link.send(MessageType.HELLO_ACK, payload = ack.toJson()) }
    }

    private fun teardownVideo() {
        val p = pipeline ?: return
        pipeline = null
        p.stop()
        stats = Stats()
    }

    private fun sampleStats(p: Pipeline) {
        var lastFrames = p.framesSent
        var lastBytes = p.bytesSent
        control.postDelayed(object : Runnable {
            override fun run() {
                if (pipeline !== p) return
                val frames = p.framesSent
                val bytes = p.bytesSent
                val snap = p.pump.snapshot()
                val r = remote
                stats = Stats(
                    fps = ((frames - lastFrames) * 1000 / STATS_INTERVAL_MS).toInt(),
                    sentKbps = ((bytes - lastBytes) * 8 / STATS_INTERVAL_MS).toInt(),
                    targetKbps = snap.bitrateKbps,
                    roundTripMs = runCatching { link?.roundTripMs ?: 0.0 }.getOrDefault(0.0),
                    unacknowledged = snap.unacknowledged,
                    droppedEncoded = snap.droppedEncoded,
                    suspensions = snap.suspensions,
                    remoteDecodeMs = r.decodeMsAverage,
                    remoteDropped = r.framesDropped,
                )
                listener?.onStats(stats)
                lastFrames = frames
                lastBytes = bytes
                control.postDelayed(this, STATS_INTERVAL_MS)
            }
        }, STATS_INTERVAL_MS)
    }

    /**
     * One display + encoder + pump. Replaced wholesale on reconfigure; [stopped] fences off
     * callbacks from an old encoder that are still in flight.
     */
    private inner class Pipeline(
        val link: PeerLink,
        val plan: DisplayPlan,
        mime: String,
    ) : VideoEncoderAndroid.Listener, FramePumpAndroid.EncoderControl {

        private val encoderConfig = VideoEncoderAndroid.Config(mime, plan.width, plan.height, plan.fps, options.targetBitrateKbps)
        val codecName = encoderConfig.codecName
        val encoder = VideoEncoderAndroid(encoderConfig, this)
        val pump = FramePumpAndroid(
            control = this,
            pendingBytes = { link.pendingBytes },
            config = FramePumpAndroid.Config(
                targetKbps = options.targetBitrateKbps,
                minimumKbps = options.minimumBitrateKbps,
                adaptive = options.adaptiveBitrate,
            ),
            clockMs = { SystemClock.elapsedRealtime() },
        )
        private val mapper = MacInputMapper(commandAsCtrl = options.commandAsCtrl)
        private var display: VirtualDisplay? = null
        @Volatile var displayId = -1
            private set
        private var acceptsInput = false
        @Volatile var acked = false
        @Volatile private var stopped = false
        @Volatile var framesSent = 0L
            private set
        @Volatile var bytesSent = 0L
            private set
        private var paramsJson: ByteArray? = null
        private var paramsFor: VideoParameterSets? = null

        fun start(hello: ClientHello) {
            encoder.start()
            // System decorations are what make One UI put its DeX secondary launcher, taskbar
            // and wallpaper on the display (spike S0b). Without them it is a black rectangle.
            val vd = VirtualDisplays.create(
                "${hello.deviceName} (Mirage)", plan.width, plan.height, plan.densityDpi,
                encoder.inputSurface, VirtualDisplays.Flags.desktop(systemDecorations = true),
            )
            display = vd
            displayId = vd.display.displayId
            Log.i(TAG, "display $displayId ${plan.width}x${plan.height} @ ${plan.densityDpi} dpi, $codecName ${plan.fps} fps")
            mapper.configure(plan.width, plan.height)
            acceptsInput = options.acceptRemoteInput && hello.wantsInputForwarding
            scheduleTick()
        }

        /** The pump's clock: once per frame interval, on the encoder thread. */
        private fun scheduleTick() {
            val period = (1000L / plan.fps).coerceAtLeast(4)
            encoder.handler.postDelayed(object : Runnable {
                override fun run() {
                    if (stopped) return
                    pump.tick()
                    encoder.handler.postDelayed(this, period)
                }
            }, period)
        }

        fun stop() {
            stopped = true
            pump.stop()
            if (displayId >= 0) {
                val release = synchronized(mapper) { mapper.releaseAll() }
                for (spec in release) runCatching { EventInjector.inject(spec, displayId) }
            }
            // Display first: it renders into the encoder's surface.
            display?.release()
            display = null
            encoder.stop()
        }

        /** Reader thread. A Mac click becomes two Android events (DOWN + BUTTON_PRESS). */
        fun inject(payload: ByteArray) {
            if (!acceptsInput || stopped) return
            val event = InputEvent.decode(payload) ?: return
            synchronized(mapper) {
                for (spec in mapper.map(event, SystemClock.uptimeMillis())) {
                    runCatching { EventInjector.inject(spec, displayId) }
                        .onFailure { Log.w(TAG, "inject failed", it) }
                }
            }
        }

        // VideoEncoderAndroid.Listener — encoder thread

        override fun onParameterSets(sets: VideoParameterSets) {
            Log.i(TAG, "parameter sets: ${sets.codec} ${sets.parameterSets.size} NALs")
        }

        override fun onFrame(avcc: ByteArray, isKeyframe: Boolean, captureMicros: Long) {
            if (stopped || !acked) return
            if (!pump.shouldSend(isKeyframe)) return
            if (isKeyframe) {
                // The Mac only takes parameter sets from videoParams, and after a reconnect or
                // reconfigure needs them before the IDR — so they go ahead of every keyframe.
                val sets = encoder.parameterSets ?: return
                if (sets != paramsFor) { paramsJson = sets.toJson(); paramsFor = sets }
                link.send(MessageType.VIDEO_PARAMS, payload = paramsJson!!)
            }
            link.send(MessageType.VIDEO_FRAME, if (isKeyframe) FrameFlags.KEYFRAME else 0, avcc, captureMicros)
            framesSent++
            bytesSent += avcc.size
        }

        override fun onError(error: Throwable) {
            control.post {
                if (pipeline !== this) return@post
                closeLinkLocked(sendBye = true)
                state = State.Failed("the video encoder failed: ${error.message}")
            }
        }

        // FramePumpAndroid.EncoderControl

        override fun setSuspended(suspended: Boolean) = encoder.setSuspended(suspended)
        override fun setBitrate(kbps: Int) = encoder.setBitrate(kbps)
        override fun requestKeyframe() = encoder.requestKeyframe()
    }

    /** What the virtual display will be. */
    data class DisplayPlan(val width: Int, val height: Int, val densityDpi: Int, val fps: Int)

    companion object {
        private const val TAG = "Mirage"
        private const val HELLO_TIMEOUT_MS = 10_000L
        private const val STATS_INTERVAL_MS = 1_000L

        /**
         * Spec §5, Android host: the display is the Mac panel's exact pixels (no scaling
         * anywhere), and density = 160 × pixels / points, so 1 dp == 1 Mac point — a Retina
         * Mac gets 320 dpi and desktop-sized UI, a 1× display 160 dpi. Even dimensions for the
         * encoder; fps capped at what we encode (a 120 Hz ProMotion panel still gets 60).
         */
        fun plan(hello: ClientHello, maxFps: Int): DisplayPlan {
            val w = hello.pixelWidth and 1.inv()
            val h = hello.pixelHeight and 1.inv()
            val scale = if (hello.pointWidth > 0) hello.pixelWidth.toDouble() / hello.pointWidth else 2.0
            val dpi = (160 * scale).roundToInt().coerceIn(120, 640)
            val fps = hello.refreshHz.coerceIn(30, maxFps.coerceAtLeast(30))
            return DisplayPlan(maxOf(2, w), maxOf(2, h), dpi, fps)
        }

        /** One stdout/status line per state — the contract the Mac's AdbLauncher parses. */
        fun statusLine(state: State): String = when (state) {
            State.Idle -> "IDLE"
            is State.Connecting -> "CONNECTING ${state.address}"
            State.Waiting -> "CONNECTED"
            is State.Running -> "OK ${state.displayId} ${state.width}x${state.height} ${state.codec} ${state.fps}"
            is State.Failed -> "ERR ${state.message.replace('\n', ' ')}"
            State.Ended -> "BYE"
        }

        fun statsLine(s: Stats): String =
            "STATS fps=${s.fps} kbps=${s.sentKbps} target=${s.targetKbps} rtt=${"%.1f".format(s.roundTripMs)} " +
                "unacked=${s.unacknowledged} dropped=${s.droppedEncoded} suspended=${s.suspensions} " +
                "decodeMs=${"%.1f".format(s.remoteDecodeMs)} remoteDropped=${s.remoteDropped}"
    }
}
