package com.voxturr.mirage.receiver

import android.content.Context
import android.util.Log
import com.voxturr.mirage.core.AudioConfig
import com.voxturr.mirage.core.ClientStats
import com.voxturr.mirage.core.HostHelloAck
import com.voxturr.mirage.core.InputEvent
import com.voxturr.mirage.core.MessageType
import com.voxturr.mirage.core.MonotonicClock
import com.voxturr.mirage.core.PeerLink
import com.voxturr.mirage.core.VideoParameterSets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * One connected host — the Kotlin counterpart of ClientSession.swift's per-link half.
 * Video frames go from the link's reader thread straight into [VideoDecoder] (no hop);
 * control messages are handled inline (they are tiny and rare).
 */
class ReceiverSession(
    private val context: Context,
    val link: PeerLink,
    private val settings: ReceiverSettings,
    private val audio: AudioReceiver,
    private val callbacks: Callbacks,
) {
    interface Callbacks {
        fun onAccepted(session: ReceiverSession, ack: HostHelloAck)
        fun onRejected(session: ReceiverSession, reason: String)
        fun onEnded(session: ReceiverSession, error: Throwable?)
    }

    val stats = ReceiverStats()
    @Volatile var ack: HostHelloAck? = null; private set
    @Volatile private var ended = false
    private var timer: ScheduledExecutorService? = null
    private var bytesAtLastReport = 0L

    private val clockAdapter = object : AudioReceiver.HostClock {
        override val ready: Boolean get() = link.clock.hasEstimate
        override fun hostMicrosToLocalNanos(hostMicros: Long): Long =
            MonotonicClock.toNanoTime(link.clock.remoteToLocal(hostMicros))
    }

    val decoder = VideoDecoder(context, stats, object : VideoDecoder.Listener {
        override fun onFrameDone(ptsUs: Long) {
            // Like ClientSession.swift: the host keeps ≤3 frames un-acked, which bounds latency.
            link.send(MessageType.FRAME_ACK, timestampMicros = ptsUs)
        }

        override fun requestKeyframe() {
            link.send(MessageType.REQUEST_KEYFRAME)
        }

        override fun onFrameRendered(ptsUs: Long, renderNanos: Long) {
            val clock = link.clock
            if (!clock.hasEstimate) return
            val latencyUs = MonotonicClock.fromNanoTime(renderNanos) - clock.remoteToLocal(ptsUs)
            if (latencyUs in 0..2_000_000) stats.glassLatencyMs = ewma(stats.glassLatencyMs, latencyUs / 1000.0)
        }
    })

    /** Host's points per stream pixel, for scroll / mouse deltas. */
    val pointsPerPixel: Float
        get() = ack?.let { if (it.pixelWidth > 0) it.pointWidth.toFloat() / it.pixelWidth else null } ?: 0.5f

    fun start() {
        audio.stats = stats
        link.onReady = { sendHello(MessageType.HELLO, Panel.probe(context)) }
        link.onMessage = { type, flags, ts, payload -> onMessage(type, flags, ts, payload) }
        link.onFailure = { error -> end(sendBye = false, error = error) }
        link.start()
    }

    private fun onMessage(type: Int, flags: Int, timestamp: Long, payload: ByteArray) {
        when (type) {
            MessageType.VIDEO_FRAME -> decoder.submitFrame(payload, flags, timestamp)
            MessageType.VIDEO_PARAMS -> {
                val sets = try { VideoParameterSets.fromJson(payload) } catch (e: Exception) {
                    Log.w(TAG, "bad videoParams", e); return
                }
                decoder.setParameters(sets)
            }
            MessageType.HELLO_ACK -> {
                val a = try { HostHelloAck.fromJson(payload) } catch (e: Exception) {
                    Log.w(TAG, "bad helloAck", e); return
                }
                if (!a.accepted) {
                    callbacks.onRejected(this, a.reason ?: "The Mac refused the connection.")
                    end(sendBye = false, error = null)
                    return
                }
                ack = a
                startTimers()
                callbacks.onAccepted(this, a)
            }
            MessageType.AUDIO_CONFIG -> {
                val cfg = try { AudioConfig.fromJson(payload) } catch (e: Exception) {
                    Log.w(TAG, "bad audioConfig", e); return
                }
                audio.start(cfg, clockAdapter, link.remoteAddress) { link.channel }
            }
            MessageType.AUDIO_STOP -> audio.stopPlayback()
            MessageType.BYE -> end(sendBye = false, error = null)
            else -> Unit // unknown types are ignored (spec §2)
        }
    }

    private fun sendHello(type: Int, panel: Panel) {
        val hello = buildHello(panel, settings, audio.port)
        link.send(type, payload = hello.toJson())
    }

    /**
     * Rotation (or scale change): send the full, updated ClientHello as `reconfigure`. The host
     * rebuilds its virtual display and sends new videoParams + IDR. v2 hosts only (they set
     * `platform` in the ack); a v1 host would choke on the unknown type.
     */
    fun sendReconfigure(panel: Panel) {
        val a = ack ?: return
        if (a.platform == null) return
        sendHello(MessageType.RECONFIGURE, panel)
    }

    fun sendInput(event: InputEvent) {
        if (ended) return
        link.send(MessageType.INPUT, payload = event.encoded())
    }

    private fun startTimers() {
        if (timer != null) return
        val t = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mirage-session").apply { isDaemon = true } }
        timer = t
        var tick = 0
        t.scheduleWithFixedDelay({
            try {
                stats.roundTripMs = link.roundTripMs
                stats.sample(System.nanoTime())
                if (++tick % 4 == 0) {
                    val bytes = stats.bytesReceived
                    val report = ClientStats(
                        framesDecoded = stats.framesDecoded.toInt(),
                        framesDropped = stats.framesDropped.toInt(),
                        averageLatencyMs = stats.roundTripMs,
                        decodeMsAverage = stats.decodeMs,
                        bytesReceived = (bytes - bytesAtLastReport).toInt(),
                    )
                    bytesAtLastReport = bytes
                    link.send(MessageType.STATS, payload = report.toJson())
                }
            } catch (e: Exception) {
                Log.w(TAG, "stats tick failed", e)
            }
        }, 500, 500, TimeUnit.MILLISECONDS)
    }

    /** Idempotent. Safe from any thread. */
    fun end(sendBye: Boolean, error: Throwable? = null) {
        synchronized(this) {
            if (ended) return
            ended = true
        }
        timer?.shutdownNow()
        if (sendBye) try { link.send(MessageType.BYE) } catch (_: Exception) {}
        audio.stopPlayback()
        decoder.release()
        try { link.close() } catch (_: Exception) {}
        callbacks.onEnded(this, error)
    }

    val isEnded: Boolean get() = ended

    private companion object { const val TAG = "Mirage" }
}
