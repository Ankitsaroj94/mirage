package com.voxturr.mirage.host

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Surface
import com.voxturr.mirage.core.MonotonicClock
import com.voxturr.mirage.core.Nal
import com.voxturr.mirage.core.VideoParameterSets

/**
 * Hardware HEVC/H.264 encoder fed by a Surface (the virtual display renders straight into it,
 * GPU → encoder, no copies), producing exactly what the Mac's `VideoRenderer` already eats:
 * a `VideoParameterSets` JSON plus 4-byte-length-prefixed frames.
 *
 * Tuned like the Mac's VideoToolbox session: real-time priority, no B-frames, latency 1, a
 * very long GOP (the client asks for IDRs when it needs one), BT.709 limited range tagged in
 * the SPS VUI so the Mac renders the colours the Mac encoder would have produced.
 *
 * Callbacks arrive on the encoder's own URGENT_DISPLAY HandlerThread, in output order.
 */
class VideoEncoderAndroid(private val config: Config, private val listener: Listener) {

    data class Config(
        val mime: String,           // MediaFormat.MIMETYPE_VIDEO_HEVC or _AVC
        val width: Int,
        val height: Int,
        val fps: Int = 60,
        val bitrateKbps: Int = 30_000,
        /** VBR by default: Exynos CBR pads static screens and makes IDRs no smaller. */
        val bitrateMode: Int = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR,
    ) {
        val codecName: String get() = if (mime == MediaFormat.MIMETYPE_VIDEO_HEVC) "hevc" else "h264"
    }

    interface Listener {
        /** New parameter sets (first output, and whenever the encoder changes them). */
        fun onParameterSets(sets: VideoParameterSets)
        /** One access unit, AVCC framed. [captureMicros] is on MonotonicClock. */
        fun onFrame(avcc: ByteArray, isKeyframe: Boolean, captureMicros: Long)
        fun onError(error: Throwable)
    }

    private val thread = HandlerThread("mirage-encoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    /** Also used by HostSession for the pump's ticks, so they never race the output callback. */
    val handler = Handler(thread.looper)
    private var codec: MediaCodec? = null
    lateinit var inputSurface: Surface
        private set

    @Volatile private var latestParams: VideoParameterSets? = null
    val parameterSets: VideoParameterSets? get() = latestParams

    /** Reused copy-out buffer: one allocation per frame is left, the AVCC payload itself. */
    private var scratch = ByteArray(256 * 1024)

    fun start() {
        val format = format(config)
        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        val codec = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(config.mime)
        Log.i(TAG, "encoder ${codec.name} ${config.codecName} ${config.width}x${config.height}@${config.fps} ${config.bitrateKbps} kbps")
        codec.setCallback(callback, handler)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            // Some encoders reject optional keys (profile, max-fps); retry with the essentials.
            Log.w(TAG, "configure failed ($e); retrying without profile")
            format.removeKey(MediaFormat.KEY_PROFILE)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        this.codec = codec
    }

    fun requestKeyframe() = setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
    fun setBitrate(kbps: Int) = setParameter(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, kbps * 1000)
    /** Discards display frames before they are encoded (see FramePumpAndroid). */
    fun setSuspended(suspended: Boolean) = setParameter(MediaCodec.PARAMETER_KEY_SUSPEND, if (suspended) 1 else 0)

    private fun setParameter(key: String, value: Int) {
        val codec = codec ?: return
        try {
            codec.setParameters(Bundle().apply { putInt(key, value) })
        } catch (e: IllegalStateException) {
            // Released concurrently by stop(); nothing to do.
        }
    }

    fun stop() {
        val codec = codec ?: return
        this.codec = null
        handler.post {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface.release() }
            thread.quitSafely()
        }
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit   // Surface input

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            if (this@VideoEncoderAndroid.codec !== codec) return
            try {
                val size = info.size
                if (size > 0) {
                    val buffer = codec.getOutputBuffer(index) ?: return
                    if (scratch.size < size) scratch = ByteArray(size + size / 2)
                    buffer.position(info.offset)
                    buffer.get(scratch, 0, size)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        handleParameterSets(Nal.splitAnnexB(scratch, 0, size))
                    } else {
                        val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        // presentationTimeUs on Surface input is the display buffer's timestamp,
                        // CLOCK_MONOTONIC = System.nanoTime() / 1000.
                        val capture = MonotonicClock.fromNanoTime(info.presentationTimeUs * 1_000)
                        listener.onFrame(frameToAvcc(size, key), key, capture)
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (e: IllegalStateException) {
                // Codec stopped under us.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
            listener.onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) = Unit
    }

    /**
     * Keyframes from some encoders carry VPS/SPS/PPS in-band even with
     * PREPEND_HEADER_TO_SYNC_FRAMES = 0. The Mac takes parameter sets only from `videoParams`,
     * so strip them out of the frame and forward them if they changed. P-frames (the hot path)
     * skip the split entirely.
     */
    private fun frameToAvcc(size: Int, keyframe: Boolean): ByteArray {
        if (!keyframe) return Nal.annexBToAvcc(scratch, 0, size)
        val nals = Nal.splitAnnexB(scratch, 0, size)
        val (params, slices) = nals.partition { isParameterSet(it) }
        if (params.isNotEmpty()) handleParameterSets(params)
        if (params.isEmpty()) return Nal.annexBToAvcc(scratch, 0, size)
        val out = ByteArray(slices.sumOf { 4 + it.size })
        var p = 0
        for (nal in slices) {
            val n = nal.size
            out[p] = (n ushr 24).toByte(); out[p + 1] = (n ushr 16).toByte()
            out[p + 2] = (n ushr 8).toByte(); out[p + 3] = n.toByte()
            System.arraycopy(nal, 0, out, p + 4, n)
            p += 4 + n
        }
        return out
    }

    private fun isParameterSet(nal: ByteArray): Boolean = nal.isNotEmpty() && if (isHevc) {
        Nal.hevcType(nal) in HEVC_VPS..HEVC_PPS
    } else {
        Nal.h264Type(nal) == H264_SPS || Nal.h264Type(nal) == H264_PPS
    }

    private val isHevc get() = config.mime == MediaFormat.MIMETYPE_VIDEO_HEVC

    /** Orders the sets the way VideoToolbox wants them: HEVC VPS,SPS,PPS; H.264 SPS,PPS. */
    private fun handleParameterSets(nals: List<ByteArray>) {
        val ordered = parameterSetOrder(nals, isHevc)
        if (ordered == null) {
            Log.w(TAG, "incomplete parameter sets: ${nals.size} NALs")
            return
        }
        val sets = VideoParameterSets(config.codecName, config.width, config.height, ordered)
        if (sets == latestParams) return
        latestParams = sets
        listener.onParameterSets(sets)
    }

    companion object {
        private const val TAG = "Mirage"
        private const val HEVC_VPS = 32
        private const val HEVC_SPS = 33
        private const val HEVC_PPS = 34
        private const val H264_SPS = 7
        private const val H264_PPS = 8

        /** The encoder format: low-latency real-time settings, see the class comment. */
        fun format(config: Config): MediaFormat = MediaFormat.createVideoFormat(config.mime, config.width, config.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, config.bitrateKbps * 1000)
            setInteger(MediaFormat.KEY_BITRATE_MODE, config.bitrateMode)
            setInteger(MediaFormat.KEY_FRAME_RATE, config.fps)
            // Cap what the buffer source feeds the encoder; a display can momentarily run faster.
            setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, config.fps.toFloat())
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 10)
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_PREPEND_HEADER_TO_SYNC_FRAMES, 0)
            // A static desktop produces no display frames at all; repeat the last one every
            // 100 ms so the client still sees life (and a lost frame is healed quickly).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            if (config.mime == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
            }
        }

        /** The first NAL of each needed type, in wire order; null if one is missing. Pure. */
        fun parameterSetOrder(nals: List<ByteArray>, hevc: Boolean): List<ByteArray>? {
            val types = if (hevc) intArrayOf(HEVC_VPS, HEVC_SPS, HEVC_PPS) else intArrayOf(H264_SPS, H264_PPS)
            return types.map { t ->
                nals.firstOrNull { it.isNotEmpty() && (if (hevc) Nal.hevcType(it) else Nal.h264Type(it)) == t } ?: return null
            }
        }

        /**
         * Picks the codec: HEVC (cleaner text per bit) when the Mac decodes it and a hardware
         * encoder takes the size, else H.264. Only the size is checked, not the claimed rate:
         * Exynos 1380 says its HEVC encoder cannot do 2880×1800@60, yet measured on the
         * SM-X510 it encodes that display with ~19–22 ms p50 composition→output latency, a
         * hair better than its H.264 encoder, which does claim it.
         */
        fun choose(width: Int, height: Int, clientHevc: Boolean, clientH264: Boolean,
                   supportsSize: (mime: String, w: Int, h: Int) -> Boolean): String {
            val mimes = buildList {
                if (clientHevc) add(MediaFormat.MIMETYPE_VIDEO_HEVC)
                if (clientH264 || isEmpty()) add(MediaFormat.MIMETYPE_VIDEO_AVC)
            }
            // If nothing claims the size, try the preferred one anyway; configure() decides.
            return mimes.firstOrNull { supportsSize(it, width, height) } ?: mimes.first()
        }

        /** MediaCodecList-backed predicate for [choose]: any hardware encoder of that type. */
        fun hardwareSupportsSize(mime: String, w: Int, h: Int): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.isHardwareAccelerated && mime in info.supportedTypes &&
                    runCatching { info.getCapabilitiesForType(mime).videoCapabilities.isSizeSupported(w, h) }
                        .getOrDefault(false)
            }
    }
}
