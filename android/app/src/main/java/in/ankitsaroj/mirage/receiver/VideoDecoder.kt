package `in`.ankitsaroj.mirage.receiver

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PerformanceHintManager
import android.os.Process
import android.util.Log
import android.view.Surface
import `in`.ankitsaroj.mirage.core.FrameFlags
import `in`.ankitsaroj.mirage.core.Nal
import `in`.ankitsaroj.mirage.core.VideoParameterSets
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Low-latency MediaCodec decode straight onto a SurfaceView's Surface (HWC overlay, no GPU pass).
 *
 * Threads:
 *  - network reader thread: [submitFrame] rewrites AVCC→Annex-B in place and queues into a free
 *    codec input buffer right there (no hop); [setParameters] blocks it until the codec is rebuilt,
 *    so the IDR that follows new params is never lost.
 *  - "mirage-decode" HandlerThread (URGENT_DISPLAY): codec callbacks + every lifecycle change.
 *  - main thread: [setSurface] (blocks until the codec is torn down when the surface goes away).
 * Output buffers are released for render immediately. No per-frame allocation here: the payload
 * array from the link is rewritten in place and copied once into the codec's own buffer.
 */
class VideoDecoder(
    context: Context,
    private val stats: ReceiverStats,
    private val listener: Listener,
) {
    interface Listener {
        /** Frame queued to the codec or dropped — either way it gets its frameAck. */
        fun onFrameDone(ptsUs: Long)
        fun requestKeyframe()
        /** The frame captured at host time [ptsUs] reached the display at [renderNanos] (System.nanoTime). */
        fun onFrameRendered(ptsUs: Long, renderNanos: Long)
    }

    private val thread = HandlerThread("mirage-decode", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val hintManager: PerformanceHintManager? = context.getSystemService(PerformanceHintManager::class.java)
    private var hintSession: PerformanceHintManager.Session? = null
    private var hintHasReader = false
    @Volatile private var decodeTid = 0
    @Volatile private var readerTid = 0

    // Owned by the decode thread (codec is also read by the reader thread under the queue lock).
    @Volatile private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private var params: VideoParameterSets? = null
    @Volatile private var hevc = true

    // queue time per pts, for decode-time stats (small ring; benign races only affect stats).
    private val qPts = LongArray(32)
    private val qNanos = LongArray(32)
    private var qNext = 0

    private val sink = object : FrameQueue.Sink {
        override fun queue(index: Int, data: ByteArray, length: Int, ptsUs: Long, isKeyframe: Boolean): Boolean {
            val c = codec ?: return false
            try {
                val buf: ByteBuffer = c.getInputBuffer(index) ?: return false
                if (length > buf.capacity()) {
                    Log.w(TAG, "frame of $length bytes exceeds input buffer ${buf.capacity()}")
                    return false
                }
                buf.clear()
                buf.put(data, 0, length)
                c.queueInputBuffer(index, 0, length, ptsUs, if (isKeyframe) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            } catch (e: Exception) {
                Log.w(TAG, "queueInputBuffer failed", e)
                return false
            }
            val s = qNext; qNext = (s + 1) and 31
            qPts[s] = ptsUs; qNanos[s] = System.nanoTime()
            listener.onFrameDone(ptsUs)
            return true
        }

        override fun dropped(ptsUs: Long) {
            stats.framesDropped++
            listener.onFrameDone(ptsUs)
        }

        override fun requestKeyframe() = listener.requestKeyframe()
    }

    private val queue = FrameQueue(capacity = 3, sink = sink)

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(c: MediaCodec, index: Int) {
            if (c === codec) queue.inputAvailable(index)
        }

        override fun onOutputBufferAvailable(c: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (c !== codec) return
            try {
                c.releaseOutputBuffer(index, true)
            } catch (e: IllegalStateException) {
                return
            }
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
            stats.framesDecoded++
            val now = System.nanoTime()
            val pts = info.presentationTimeUs
            for (i in 0 until 32) {
                if (qPts[i] == pts && qNanos[i] != 0L) {
                    val dt = now - qNanos[i]
                    qNanos[i] = 0L
                    stats.decodeMs = ewma(stats.decodeMs, dt / 1e6)
                    reportHint(dt)
                    break
                }
            }
        }

        override fun onError(c: MediaCodec, e: MediaCodec.CodecException) {
            if (c !== codec) return
            Log.w(TAG, "codec error (transient=${e.isTransient}, recoverable=${e.isRecoverable})", e)
            restartCodec(requestKeyframe = true)
        }

        override fun onOutputFormatChanged(c: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "output format: $format")
        }
    }

    private val renderedListener = MediaCodec.OnFrameRenderedListener { c, ptsUs, nanoTime ->
        if (c === codec) listener.onFrameRendered(ptsUs, nanoTime)
    }

    init {
        handler.post { decodeTid = Process.myTid() }
    }

    // ---------------------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------------------

    /** Network reader thread. [payload] is consumed (rewritten in place). */
    fun submitFrame(payload: ByteArray, flags: Int, ptsUs: Long) {
        if (readerTid == 0) readerTid = Process.myTid()
        stats.bytesReceived += payload.size
        val key = flags and FrameFlags.KEYFRAME != 0
        val isRef = NalInspect.isReferenceFrame(payload, 0, payload.size, hevc)
        if (!Nal.avccToAnnexBInPlace(payload, 0, payload.size)) {
            queue.corrupt(ptsUs)
            return
        }
        queue.submit(payload, payload.size, ptsUs, key, isRef)
    }

    /** Network reader thread; blocks until the codec is rebuilt (only if the sets changed). */
    fun setParameters(sets: VideoParameterSets) = runSync {
        if (sets == params && codec != null) return@runSync
        params = sets
        hevc = sets.codec.equals("hevc", ignoreCase = true)
        stats.codec = sets.codec
        stats.streamWidth = sets.width
        stats.streamHeight = sets.height
        // The host sends params right before an IDR, so no keyframe request here.
        restartCodec(requestKeyframe = false)
    }

    /** Main thread. null = surface destroyed: returns only after the codec stopped using it. */
    fun setSurface(s: Surface?) = runSync {
        if (s === surface && (s == null || codec != null)) return@runSync
        surface = s
        if (s == null) releaseCodec() else restartCodec(requestKeyframe = true)
    }

    fun release() {
        runSync {
            releaseCodec()
            hintSession?.close(); hintSession = null
        }
        thread.quitSafely()
    }

    // ---------------------------------------------------------------------------------------
    // Codec lifecycle (decode thread only)
    // ---------------------------------------------------------------------------------------

    private fun restartCodec(requestKeyframe: Boolean) {
        releaseCodec()
        val p = params ?: return
        val s = surface ?: return
        if (!s.isValid) return
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val info = Codecs.pickDecoder(mime)
        // Attempts, best first. Exynos accepts an unbounded KEY_OPERATING_RATE at configure()
        // and then fails start() with NO_MEMORY, because it sizes its resources from that rate —
        // so ask for a realistic rate (twice the panel's top refresh), and if even that fails,
        // retry with nothing but the essentials. Each attempt is configure *and* start.
        val operatingRates = listOf(OPERATING_RATE, null)
        for ((attempt, rate) in operatingRates.withIndex()) {
            var c: MediaCodec? = null
            try {
                c = if (info != null) MediaCodec.createByCodecName(info.name) else MediaCodec.createDecoderByType(mime)
                configure(c, buildFormat(p, mime, info, rate), s)
                c.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                val started = c
                queue.withLock {
                    codec = started
                    queue.reset(ready = true, requestKeyframe = requestKeyframe)
                    started.start()
                }
                Log.i(TAG, "decoder ${c.name} started: ${p.codec} ${p.width}x${p.height} operatingRate=${rate ?: "default"}")
                ensureHintSession()
                return
            } catch (e: Exception) {
                val last = attempt == operatingRates.lastIndex
                if (last) Log.e(TAG, "could not start decoder", e)
                else Log.w(TAG, "decoder start failed with operating rate $rate, retrying: ${e.message}")
                queue.withLock { codec = null; queue.reset(ready = false, requestKeyframe = false) }
                try { c?.release() } catch (_: Exception) {}
            }
        }
    }

    private fun configure(c: MediaCodec, format: MediaFormat, s: Surface) {
        c.setCallback(callback, handler)
        c.setOnFrameRenderedListener(renderedListener, handler)
        c.configure(format, s, null, 0)
    }

    private fun releaseCodec() {
        val c = codec ?: return
        queue.withLock {
            codec = null
            queue.reset(ready = false, requestKeyframe = false)
        }
        try { c.stop() } catch (_: Exception) {}
        try { c.release() } catch (_: Exception) {}
    }

    private fun buildFormat(p: VideoParameterSets, mime: String, info: MediaCodecInfo?, operatingRate: Int?): MediaFormat {
        val f = MediaFormat.createVideoFormat(mime, p.width, p.height)
        if (hevc) {
            f.setByteBuffer("csd-0", ByteBuffer.wrap(Nal.withStartCodes(p.parameterSets)))
        } else {
            val sps = p.parameterSets.firstOrNull { it.isNotEmpty() && Nal.h264Type(it) == 7 } ?: p.parameterSets.first()
            val pps = p.parameterSets.firstOrNull { it.isNotEmpty() && Nal.h264Type(it) == 8 } ?: p.parameterSets.last()
            f.setByteBuffer("csd-0", ByteBuffer.wrap(Nal.withStartCodes(listOf(sps))))
            f.setByteBuffer("csd-1", ByteBuffer.wrap(Nal.withStartCodes(listOf(pps))))
        }
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(p.width * p.height * 3 / 2, 2 shl 20))
        val caps = try { info?.getCapabilitiesForType(mime) } catch (_: Exception) { null }
        if (caps?.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency) == true) {
            f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        f.setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
        if (operatingRate != null) f.setInteger(MediaFormat.KEY_OPERATING_RATE, operatingRate)
        return f
    }

    private fun ensureHintSession() {
        val hm = hintManager ?: return
        val reader = readerTid
        if (hintSession != null && (hintHasReader || reader == 0)) return
        val tids = if (reader != 0) intArrayOf(decodeTid, reader) else intArrayOf(decodeTid)
        try {
            val existing = hintSession
            if (existing != null && Build.VERSION.SDK_INT >= 34) {
                existing.setThreads(tids)
            } else {
                existing?.close()
                hintSession = hm.createHintSession(tids, TARGET_WORK_NANOS)
            }
            hintHasReader = reader != 0
        } catch (e: Exception) {
            Log.w(TAG, "performance hint session unavailable", e)
        }
    }

    private fun reportHint(workNanos: Long) {
        val h = hintSession ?: return
        if (!hintHasReader && readerTid != 0) ensureHintSession()
        try { h.reportActualWorkDuration(workNanos.coerceAtLeast(1)) } catch (_: Exception) {}
    }

    private fun runSync(block: () -> Unit) {
        if (Looper.myLooper() === thread.looper) { block(); return }
        val latch = CountDownLatch(1)
        val posted = handler.post { try { block() } finally { latch.countDown() } }
        if (posted) latch.await(3, TimeUnit.SECONDS)
    }

    private companion object {
        const val TAG = "Mirage"
        /** Twice the Tab S9 FE's 90 Hz: headroom to clear a burst, without asking for more than the hardware has. */
        const val OPERATING_RATE = 180
        const val TARGET_WORK_NANOS = 8_000_000L // a frame should be through decode well within 8 ms
    }
}
