package com.voxturr.mirage.core

import kotlin.math.roundToLong

/**
 * NTP-style offset estimate from ping/pong — spec/PROTOCOL.md §8. Thread-safe.
 *
 * Every ping goes out stamped with our clock (t0); a v2 pong echoes that stamp and carries the
 * peer's clock at reply time (t1); we note when it lands (t2). Assuming both legs take equally
 * long, `offset = t1 − (t0 + t2) / 2`. That is only as good as the round trip is short — a
 * sample that sat behind a keyframe is skewed by half its queueing delay — so only samples that
 * set a new low for the last ten seconds move the estimate, and then gently (EWMA α = 0.1), so
 * one lucky sample cannot yank audio playout around. Port of Sources/Mirage/Core/ClockSync.swift.
 */
class ClockSync {
    private class Sample(val receivedAt: Long, val roundTrip: Long)

    private val lock = Any()
    private val window = ArrayDeque<Sample>()
    private var offset = 0.0
    private var estimated = false
    private var bestRoundTrip = 0L

    /** t0 = local ping send, t1 = remote clock in pong, t2 = local pong receive (all µs). */
    fun addSample(t0: Long, t1: Long, t2: Long) {
        val roundTrip = t2 - t0
        if (roundTrip < 0) return                       // pong for a ping we never sent
        val sampleOffset = t1.toDouble() - (t0.toDouble() + t2.toDouble()) / 2

        synchronized(lock) {
            window.removeAll { t2 - it.receivedAt > WINDOW_MICROS }
            val windowBest = window.minOfOrNull { it.roundTrip }
            window.addLast(Sample(t2, roundTrip))

            when {
                // A sample's offset can be wrong by up to half its round trip. The first pong
                // usually queues behind the first keyframe, so the estimate it seeds can be
                // ~100 ms off; take a much tighter sample outright instead of easing in at 10 %.
                !estimated || roundTrip * 4 < bestRoundTrip * 3 -> { offset = sampleOffset; estimated = true }
                windowBest == null || roundTrip <= windowBest -> offset += SMOOTHING * (sampleOffset - offset)
                else -> return
            }
            bestRoundTrip = roundTrip
        }
    }

    /** remote − local, µs. 0 until the first sample. */
    val offsetMicros: Long get() = synchronized(lock) { offset.roundToLong() }
    val hasEstimate: Boolean get() = synchronized(lock) { estimated }
    /** Round trip of the sample the estimate last moved on. */
    val bestRoundTripMicros: Long get() = synchronized(lock) { bestRoundTrip }
    fun remoteToLocal(remoteMicros: Long): Long = remoteMicros - offsetMicros
    fun localToRemote(localMicros: Long): Long = localMicros + offsetMicros

    companion object {
        const val WINDOW_MICROS = 10_000_000L
        const val SMOOTHING = 0.1

        /** v2 pong body: the responder's clock at reply time, 8 bytes little-endian. */
        fun pongPayload(now: Long = MonotonicClock.micros()): ByteArray =
            ByteArray(8) { (now ushr (8 * it)).toByte() }

        /** Null for a v1 pong, which is empty. */
        fun remoteClock(pongPayload: ByteArray): Long? {
            if (pongPayload.size < 8) return null
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (pongPayload[i].toLong() and 0xff)
            return v
        }
    }
}
