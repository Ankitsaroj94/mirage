package `in`.ankitsaroj.mirage.host

/**
 * Backpressure and adaptive bitrate for the Android host — the logic of the Mac's
 * `FramePump`, reshaped for a Surface-input encoder. Pure (no Android types, injectable
 * clock), so every rule below is unit-tested.
 *
 * ## Why it cannot be a literal port
 * The Mac pump drops a captured frame *before* encoding when the link is backed up, which is
 * free: the next frame simply references the last one that was encoded. With Surface input
 * the virtual display renders straight into the encoder; there is no "before".
 *
 * ## The design: two gates
 * 1. **Soft gate — suspend input** (normal operation). When the window is full (≥ 3 unacked
 *    frames, or more than ~125 ms of data still queued in the socket), we tell the codec
 *    `PARAMETER_KEY_SUSPEND`: the framework's buffer source discards display frames *before*
 *    they reach the encoder. That is exactly the Mac behaviour — nothing that was encoded is
 *    lost, so the reference chain stays intact and no keyframe is needed. As soon as an ack
 *    arrives or the socket drains, input resumes. Frames that were already inside the encoder
 *    when we suspended still come out and are sent; the window is a soft cap.
 * 2. **Hard gate — drop encoded output** (safety net). If the backlog grows well past the
 *    window anyway (a huge IDR stuck behind a stalled Wi-Fi link, an encoder that ignores
 *    suspend), encoded frames are dropped at output. Dropping an encoded P-frame breaks the
 *    reference chain: every following P-frame would decode as garbage on the Mac. So after a
 *    hard drop we also drop every non-key frame until an IDR, and request that IDR only once
 *    the soft window is open again — requesting it while congested would just produce a large
 *    frame that is dropped too.
 *
 * AIMD is the Mac's: congestion seen on 3 consecutive ticks (and > 500 ms since the last
 * change) → bitrate × 0.75, floor [Config.minimumKbps]; clear for 3 s → + max(1000, 10 %) up to
 * the target. Ticks come from the encoder thread about once a frame interval.
 *
 * Thread-safety: all entry points are synchronized (encoder thread, link reader thread).
 * Control callbacks run inside the lock; they must only post to the codec, never call back.
 */
class FramePumpAndroid(
    private val control: EncoderControl,
    private val pendingBytes: () -> Int,
    private val config: Config = Config(),
    private val clockMs: () -> Long,
) {
    interface EncoderControl {
        fun setSuspended(suspended: Boolean)
        fun setBitrate(kbps: Int)
        fun requestKeyframe()
    }

    data class Config(
        val targetKbps: Int = 30_000,
        val minimumKbps: Int = 6_000,
        val adaptive: Boolean = true,
        val maxUnacknowledged: Int = 3,
        val ackTimeoutMs: Long = 2_000,
        /** How far past the soft window the hard gate sits (frames). */
        val hardExtraFrames: Int = 2,
        /** Re-ask for the IDR if it has not shown up this long after a request. */
        val keyframeRetryMs: Long = 1_000,
    )

    data class Snapshot(
        val bitrateKbps: Int,
        val unacknowledged: Int,
        val suspended: Boolean,
        val droppedEncoded: Int,
        val suspensions: Int,
        val framesSent: Int,
    )

    private var bitrateKbps = config.targetKbps
    private var lastBitrateChangeMs = clockMs()
    private var congestedStreak = 0
    private var unacknowledged = 0
    private var lastAckMs = clockMs()
    private var suspended = false
    private var awaitingKeyframe = false
    private var keyframeRequestedMs = Long.MIN_VALUE / 2
    private var droppedEncoded = 0
    private var suspensions = 0
    private var framesSent = 0
    private var stopped = false

    val currentBitrateKbps: Int @Synchronized get() = bitrateKbps

    @Synchronized
    fun snapshot() = Snapshot(bitrateKbps, unacknowledged, suspended, droppedEncoded, suspensions, framesSent)

    /**
     * Encoder output: may this frame go on the wire? Counts it as in flight when it may.
     * The caller sends parameter sets ahead of every keyframe it is allowed to send.
     */
    @Synchronized
    fun shouldSend(isKeyframe: Boolean): Boolean {
        if (stopped) return false
        val now = clockMs()
        expireStaleWindow(now)
        val pending = pendingBytes()

        if (isHardCongested(pending)) {
            droppedEncoded++
            awaitingKeyframe = true
            congestedStreak++
            adaptBitrate(congested = true, now)
            evaluate(now, pending)
            return false
        }
        if (awaitingKeyframe && !isKeyframe) {
            // Its reference was dropped; it would only paint corruption.
            droppedEncoded++
            evaluate(now, pending)
            return false
        }
        if (isKeyframe) awaitingKeyframe = false
        unacknowledged++
        framesSent++
        // The window may just have filled: close the soft gate now rather than at the next tick.
        evaluate(now, pending)
        return true
    }

    /** The client displayed a frame (frameAck). Opens the window immediately. */
    @Synchronized
    fun acknowledgeFrame() {
        if (unacknowledged > 0) unacknowledged--
        lastAckMs = clockMs()
        if (!stopped) evaluate(lastAckMs, pendingBytes())
    }

    /**
     * Periodic, about once per frame interval: the only place the socket draining or the ack
     * timeout can reopen a suspended encoder (nothing else happens while it is suspended), and
     * the AIMD clock.
     */
    @Synchronized
    fun tick() {
        if (stopped) return
        val now = clockMs()
        expireStaleWindow(now)
        val pending = pendingBytes()
        val congested = isSoftCongested(pending)
        if (congested) congestedStreak++ else congestedStreak = 0
        adaptBitrate(congested, now)
        evaluate(now, pending)
    }

    /** The client asked for an IDR (decoder error, new parameters). */
    @Synchronized
    fun requestKeyframe() {
        if (stopped) return
        keyframeRequestedMs = clockMs()
        control.requestKeyframe()
    }

    @Synchronized
    fun stop() {
        stopped = true
    }

    // MARK: -

    private fun budgetBytes() = maxOf(64 * 1024, bitrateKbps * 1000 / 8 / 8)   // ~125 ms of data

    private fun isSoftCongested(pending: Int) =
        unacknowledged >= config.maxUnacknowledged || pending > budgetBytes()

    private fun isHardCongested(pending: Int) =
        unacknowledged >= config.maxUnacknowledged + config.hardExtraFrames || pending > 2 * budgetBytes()

    /** No acks for 2 s: the peer is wedged or gone. Clear the window rather than freeze forever. */
    private fun expireStaleWindow(now: Long) {
        if (unacknowledged > 0 && now - lastAckMs > config.ackTimeoutMs) {
            unacknowledged = 0
            lastAckMs = now
        }
    }

    /** Opens or closes the soft gate, and asks for the IDR a hard drop left us waiting on. */
    private fun evaluate(now: Long, pending: Int) {
        val congested = isSoftCongested(pending)
        if (congested != suspended) {
            suspended = congested
            if (congested) suspensions++
            control.setSuspended(congested)
        }
        if (!congested && awaitingKeyframe && now - keyframeRequestedMs >= config.keyframeRetryMs) {
            keyframeRequestedMs = now
            control.requestKeyframe()
        }
    }

    private fun adaptBitrate(congested: Boolean, now: Long) {
        if (!config.adaptive) return
        val next = if (congested) {
            if (congestedStreak < 3 || now - lastBitrateChangeMs <= 500) return
            maxOf(config.minimumKbps, (bitrateKbps * 0.75).toInt())
        } else {
            if (now - lastBitrateChangeMs <= 3_000 || bitrateKbps >= config.targetKbps) return
            minOf(config.targetKbps, bitrateKbps + maxOf(1_000, bitrateKbps / 10))
        }
        if (next == bitrateKbps) return
        bitrateKbps = next
        lastBitrateChangeMs = now
        control.setBitrate(next)
    }
}
