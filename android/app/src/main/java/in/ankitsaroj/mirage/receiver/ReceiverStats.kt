package `in`.ankitsaroj.mirage.receiver

/**
 * Counters shared by the network, decode and audio threads; read by the overlay and the 2 s
 * ClientStats report. Single writer per field, @Volatile for visibility — no locks per frame.
 */
class ReceiverStats {
    @Volatile var framesDecoded = 0L
    @Volatile var framesDropped = 0L
    @Volatile var bytesReceived = 0L
    /** EWMA of queue→output time, ms. */
    @Volatile var decodeMs = 0.0
    /** EWMA of host capture → local render, ms (needs a clock estimate; NaN until then). */
    @Volatile var glassLatencyMs = Double.NaN
    @Volatile var roundTripMs = 0.0
    @Volatile var audioBufferedMs = 0.0
    @Volatile var audioErrorMs = 0.0
    /** Playout delay we need from the host, 0 = no measurement yet. */
    @Volatile var audioDelayNeededMs = 0
    @Volatile var streamWidth = 0
    @Volatile var streamHeight = 0
    @Volatile var codec = ""

    // Rates over the last window (computed by sample()).
    @Volatile var fps = 0.0
    @Volatile var mbps = 0.0
    private var lastSampleAt = 0L
    private var lastFrames = 0L
    private var lastBytes = 0L

    /** Call ~every 500 ms from one thread to refresh [fps] / [mbps]. */
    fun sample(nowNanos: Long) {
        if (lastSampleAt != 0L) {
            val dt = (nowNanos - lastSampleAt) / 1e9
            if (dt > 0) {
                fps = (framesDecoded - lastFrames) / dt
                mbps = (bytesReceived - lastBytes) * 8 / dt / 1e6
            }
        }
        lastSampleAt = nowNanos; lastFrames = framesDecoded; lastBytes = bytesReceived
    }

    fun overlayText(): String = buildString {
        append(codec.uppercase()).append(' ').append(streamWidth).append('×').append(streamHeight).append('\n')
        append("%.0f fps  %.1f Mbit/s\n".format(fps, mbps))
        append("decode %.1f ms  rtt %.1f ms\n".format(decodeMs, roundTripMs))
        append(if (glassLatencyMs.isNaN()) "latency —\n" else "latency %.1f ms\n".format(glassLatencyMs))
        append("dropped %d".format(framesDropped))
        if (audioBufferedMs > 0) append("\naudio %.0f ms buf  %+.1f ms".format(audioBufferedMs, audioErrorMs))
    }
}

internal fun ewma(prev: Double, sample: Double, alpha: Double = 0.1): Double =
    if (prev == 0.0 || prev.isNaN()) sample else prev + (sample - prev) * alpha
