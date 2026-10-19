package `in`.ankitsaroj.mirage.receiver

// Pure audio logic for AudioReceiver: packet header, jitter buffer, playout scheduling and drift
// resampling. No android.* types, so all of it is unit-tested with fake clocks.

/** spec §7 UDP audio header, 16 bytes little-endian. */
object AudioPacket {
    const val HEADER_BYTES = 16
    const val VERSION = 1
    const val FLAG_ENCRYPTED = 1

    fun version(b: ByteArray): Int = b[0].toInt() and 0xff
    fun flags(b: ByteArray): Int = b[1].toInt() and 0xff
    fun seq(b: ByteArray): Long = readLE(b, 4, 4)
    fun hostTimeMicros(b: ByteArray): Long = readLE(b, 8, 8)

    fun readLE(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in n - 1 downTo 0) v = (v shl 8) or (b[off + i].toLong() and 0xff)
        return v
    }

    /** For tests and tools: builds a header. */
    fun header(seq: Long, hostTimeMicros: Long, flags: Int = 0): ByteArray {
        val b = ByteArray(HEADER_BYTES)
        b[0] = VERSION.toByte(); b[1] = flags.toByte()
        for (i in 0 until 4) b[4 + i] = (seq ushr (8 * i)).toByte()
        for (i in 0 until 8) b[8 + i] = (hostTimeMicros ushr (8 * i)).toByte()
        return b
    }
}

/**
 * Packets keyed by sequence number in a fixed ring of preallocated slots (no per-packet
 * allocation). PCM is stored as interleaved shorts. Thread-safe; [awaitAny] lets the writer
 * thread sleep until something arrives.
 */
class JitterBuffer(private val slots: Int = 64, private val maxSamplesPerPacket: Int = 4096) {
    private val lock = Object()
    private val seqs = LongArray(slots) { -1 }
    private val hostTimes = LongArray(slots)
    private val lengths = IntArray(slots)
    private val data = Array(slots) { ShortArray(maxSamplesPerPacket) }
    private var count = 0
    /** Packets older than this were already played (or skipped) and are refused. */
    private var floorSeq = -1L // -1 = no base yet
    var newestSeq = -1L; private set
    var newestHostTime = 0L; private set

    /**
     * Forget everything (new audioConfig). seq does NOT restart at 0 per audioConfig — it runs
     * for the whole TCP connection (it is the AES-GCM nonce) — so the first packet that arrives
     * after a clear becomes the new base.
     */
    fun clear() = synchronized(lock) {
        seqs.fill(-1); count = 0; floorSeq = -1; newestSeq = -1
    }

    val size: Int get() = synchronized(lock) { count }

    /** Stores s16le [pcm] bytes. Returns false if late (already played) or invalid. */
    fun put(seq: Long, hostTimeMicros: Long, pcm: ByteArray, off: Int, len: Int): Boolean = synchronized(lock) {
        if (seq < 0) return false
        // First packet after a clear sets the base; a little slack lets the stream's first few
        // packets arrive out of order.
        if (floorSeq < 0) floorSeq = maxOf(0L, seq - START_REORDER_SLACK)
        if (seq < floorSeq) return false
        val samples = len / 2
        if (samples <= 0 || samples > maxSamplesPerPacket) return false
        // Ring would alias: evict everything too old to share it with this packet.
        if (seq - floorSeq >= slots) advanceFloor(seq - slots + 1)
        val s = (seq % slots).toInt()
        if (seqs[s] != seq) { if (seqs[s] < 0) count++; seqs[s] = seq }
        hostTimes[s] = hostTimeMicros
        lengths[s] = samples
        val dst = data[s]
        var j = off
        for (i in 0 until samples) {
            dst[i] = ((pcm[j].toInt() and 0xff) or (pcm[j + 1].toInt() shl 8)).toShort()
            j += 2
        }
        if (seq > newestSeq) { newestSeq = seq; newestHostTime = hostTimeMicros }
        lock.notifyAll()
        true
    }

    private companion object { const val START_REORDER_SLACK = 8L }

    /** Oldest buffered seq, or -1. */
    fun oldestSeq(): Long = synchronized(lock) {
        var best = -1L
        for (q in seqs) if (q >= 0 && (best < 0 || q < best)) best = q
        best
    }

    fun has(seq: Long): Boolean = synchronized(lock) { seq >= 0 && seqs[(seq % slots).toInt()] == seq }

    /** Host time of [seq] if buffered, else [fallback]. */
    fun hostTimeOf(seq: Long, fallback: Long): Long = synchronized(lock) {
        val s = (seq % slots).toInt()
        if (seq >= 0 && seqs[s] == seq) hostTimes[s] else fallback
    }

    /** Copies [seq]'s samples into [out] and frees it. Returns sample count, or -1 if missing. */
    fun take(seq: Long, out: ShortArray): Int = synchronized(lock) {
        if (seq < 0) return -1
        val s = (seq % slots).toInt()
        var n = -1
        if (seqs[s] == seq) {
            n = lengths[s]
            System.arraycopy(data[s], 0, out, 0, n)
            seqs[s] = -1; count--
        }
        advanceFloor(seq + 1)
        n
    }

    /** Drops [seq] (and anything older). */
    fun discardThrough(seq: Long) = synchronized(lock) { advanceFloor(seq + 1) }

    private fun advanceFloor(newFloor: Long) {
        if (newFloor <= floorSeq) return
        floorSeq = newFloor
        for (i in 0 until slots) if (seqs[i] in 0 until newFloor) { seqs[i] = -1; count-- }
    }

    /** Waits up to [timeoutMs] for any packet. */
    fun awaitAny(timeoutMs: Long): Boolean = synchronized(lock) {
        if (count > 0) return true
        lock.wait(timeoutMs)
        count > 0
    }
}

/** When will the next frame written to the AudioTrack reach the speaker? Pure. */
object SpeakerClock {
    /**
     * From an AudioTimestamp (frame [tsFrame] presented at [tsNanos]) and the total frames
     * written so far: next written frame's presentation time, ns.
     */
    fun nextFrameNanos(tsFrame: Long, tsNanos: Long, framesWritten: Long, sampleRate: Int): Long =
        tsNanos + (framesWritten - tsFrame) * 1_000_000_000L / sampleRate

    /** Fallback without a timestamp: head position + an assumed fixed output latency. */
    fun estimateNanos(nowNanos: Long, headFrames: Long, framesWritten: Long, sampleRate: Int, assumedLatencyNanos: Long): Long =
        nowNanos + (framesWritten - headFrames).coerceAtLeast(0) * 1_000_000_000L / sampleRate + assumedLatencyNanos
}

/**
 * Decides, per packet, how to keep the speaker on the host's playout clock.
 * error = (when the next written frame will play) − (when this packet's first sample should play);
 * positive = late. spec §7: resample ±0.5 % at most; drop only to recover a >100 ms gap.
 *
 * LOCKING (start, after a gap): align hard — insert silence if early, trim samples if late.
 *   It is the start of a stream, so the edit is inaudible.
 * LOCKED: only resample (proportional controller on a smoothed error), except early by > 20 ms
 *   → insert silence and re-lock, late by > 100 ms → drop and re-lock.
 */
class PlayoutController(private val sampleRate: Int) {
    enum class Action { PLAY, SILENCE, TRIM, DROP }

    /** Reused result (no allocation per packet). */
    class Decision {
        var action = Action.PLAY
        /** SILENCE: frames to insert; TRIM: frames to skip at the start of the packet. */
        var frames = 0
        /** PLAY: input frames consumed per output frame (>1 = catch up). */
        var ratio = 1.0
    }

    var locked = false; private set
    var smoothedErrorNanos = 0.0; private set
    private val d = Decision()

    /** Lateness we have accepted because nothing could fix it (see [acceptLate]). */
    var lateBiasNanos = 0L; private set

    fun reset() { locked = false; smoothedErrorNanos = 0.0; lateBiasNanos = 0 }

    fun decide(errorNanos: Long, packetFrames: Int): Decision {
        d.frames = 0; d.ratio = 1.0
        if (!locked) {
            when {
                errorNanos < -LOCK_TOLERANCE_NS -> { d.action = Action.SILENCE; d.frames = nanosToFrames(-errorNanos) }
                errorNanos > LOCK_TOLERANCE_NS -> {
                    val f = nanosToFrames(errorNanos)
                    if (f >= packetFrames) { d.action = Action.DROP } else { d.action = Action.TRIM; d.frames = f }
                }
                else -> { locked = true; smoothedErrorNanos = errorNanos.toDouble(); d.action = Action.PLAY }
            }
            if (d.action != Action.PLAY && d.frames == 0 && d.action != Action.DROP) {
                locked = true; d.action = Action.PLAY
            }
            return d
        }
        // Accepted lateness shrinks as soon as the path gets faster, never grows back on its own.
        if (errorNanos < lateBiasNanos) lateBiasNanos = maxOf(0L, errorNanos)
        val error = errorNanos - lateBiasNanos
        if (error > LATE_DROP_NS) { locked = false; d.action = Action.DROP; return d }
        if (error < -EARLY_HOLD_NS) {
            locked = false
            d.action = Action.SILENCE; d.frames = nanosToFrames(-error)
            return d
        }
        smoothedErrorNanos += (error - smoothedErrorNanos) * SMOOTHING
        val errMs = smoothedErrorNanos / 1e6
        val e = if (kotlin.math.abs(errMs) < DEADBAND_MS) 0.0 else errMs
        d.ratio = 1.0 + (e * GAIN_PER_MS).coerceIn(-MAX_DEVIATION, MAX_DEVIATION)
        d.action = Action.PLAY
        return d
    }

    /**
     * The path is slower than the playout delay and there is nothing newer to skip to: lock on
     * the current error rather than dropping forever. Sync is off by [errorNanos] until the
     * delay is raised, but the audio plays.
     */
    fun acceptLate(errorNanos: Long) {
        locked = true
        smoothedErrorNanos = 0.0
        lateBiasNanos = errorNanos
    }

    fun nanosToFrames(ns: Long): Int = (ns * sampleRate / 1_000_000_000L).toInt()

    companion object {
        const val LOCK_TOLERANCE_NS = 1_000_000L      // 1 ms
        const val EARLY_HOLD_NS = 20_000_000L         // 20 ms
        const val LATE_DROP_NS = 100_000_000L         // 100 ms
        const val MAX_DEVIATION = 0.005               // ±0.5 %
        const val GAIN_PER_MS = 0.0005                // 10 ms error → full 0.5 %
        const val DEADBAND_MS = 0.25
        const val SMOOTHING = 0.05
    }
}

/**
 * Linear-interpolation resampler for interleaved s16, continuous across calls (keeps the last
 * input frame and the fractional phase). ratio = input frames per output frame.
 */
class LinearResampler(private val channels: Int) {
    private val last = ShortArray(channels)
    private var pos = 1.0 // position in [last] ++ input; 1.0 = input[0]

    fun reset() { last.fill(0); pos = 1.0 }

    /** Output capacity needed for [inFrames] at [ratio]. */
    fun maxOutFrames(inFrames: Int, ratio: Double): Int = (inFrames / ratio).toInt() + 2

    /** Returns output frames written to [out]. */
    fun process(input: ShortArray, inFrames: Int, ratio: Double, out: ShortArray): Int {
        if (inFrames <= 0) return 0
        var o = 0
        var p = pos
        val ch = channels
        if (ratio == 1.0 && p == 1.0) {
            System.arraycopy(input, 0, out, 0, inFrames * ch)
            System.arraycopy(input, (inFrames - 1) * ch, last, 0, ch)
            pos = 1.0
            return inFrames
        }
        // Virtual input V = [last] ++ input; p indexes V. Emit while V[floor(p)] exists.
        while (p <= inFrames) {
            val i = p.toInt()
            val frac = p - i
            if (i >= inFrames) {
                // exactly on the last input frame
                for (c in 0 until ch) out[o * ch + c] = input[(inFrames - 1) * ch + c]
            } else {
                for (c in 0 until ch) {
                    val a = if (i == 0) last[c].toInt() else input[(i - 1) * ch + c].toInt()
                    val b = input[i * ch + c].toInt()
                    out[o * ch + c] = (a + (b - a) * frac).toInt().toShort()
                }
            }
            o++
            p += ratio
        }
        pos = p - inFrames
        System.arraycopy(input, (inFrames - 1) * ch, last, 0, ch)
        return o
    }
}
