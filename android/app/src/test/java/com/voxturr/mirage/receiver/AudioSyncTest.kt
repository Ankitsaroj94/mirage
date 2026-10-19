package com.voxturr.mirage.receiver

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AudioSyncTest {

    // ---------------------------------------------------------------- packet header

    @Test fun headerRoundTrip() {
        val h = AudioPacket.header(seq = 0xFFFF_FFF0L, hostTimeMicros = 123_456_789_012L, flags = 1)
        assertEquals(1, AudioPacket.version(h))
        assertEquals(1, AudioPacket.flags(h))
        assertEquals(0xFFFF_FFF0L, AudioPacket.seq(h))
        assertEquals(123_456_789_012L, AudioPacket.hostTimeMicros(h))
        assertEquals(0, h[2].toInt()); assertEquals(0, h[3].toInt())
    }

    // ---------------------------------------------------------------- jitter buffer

    private fun pcm(vararg samples: Int): ByteArray {
        val b = ByteArray(samples.size * 2)
        samples.forEachIndexed { i, s -> b[2 * i] = s.toByte(); b[2 * i + 1] = (s shr 8).toByte() }
        return b
    }

    @Test fun jitterBufferReordersAndDecodesLittleEndian() {
        val jb = JitterBuffer(slots = 8, maxSamplesPerPacket = 4)
        assertTrue(jb.put(1, 1005, pcm(3, -3), 0, 4))
        assertTrue(jb.put(0, 1000, pcm(1, -1, 32767, -32768), 0, 8))
        assertEquals(0L, jb.oldestSeq())
        val out = ShortArray(4)
        assertEquals(4, jb.take(0, out))
        assertArrayEquals(shortArrayOf(1, -1, 32767, -32768), out)
        assertEquals(1005L, jb.hostTimeOf(1, -1))
        assertEquals(2, jb.take(1, out))
        assertEquals(-1, jb.take(2, out))
    }

    @Test fun jitterBufferRefusesLatePackets() {
        val jb = JitterBuffer(slots = 8, maxSamplesPerPacket = 4)
        val out = ShortArray(4)
        jb.put(5, 0, pcm(1, 1), 0, 4)
        jb.take(5, out)
        assertFalse(jb.put(4, 0, pcm(1, 1), 0, 4))
        jb.discardThrough(9)
        assertFalse(jb.put(9, 0, pcm(1, 1), 0, 4))
        assertTrue(jb.put(10, 0, pcm(1, 1), 0, 4))
    }

    @Test fun jitterBufferEvictsOnlyWhatWouldAlias() {
        val jb = JitterBuffer(slots = 4, maxSamplesPerPacket = 4)
        for (s in 0L..3L) jb.put(s, s, pcm(1, 1), 0, 4)
        jb.put(5, 5, pcm(2, 2), 0, 4) // needs slot of seq 1 → evicts 0 and 1
        assertEquals(2L, jb.oldestSeq())
        assertEquals(3, jb.size)
        assertFalse(jb.has(1))
        assertTrue(jb.has(5))
    }

    @Test fun jitterBufferTakesFirstSeqAfterClearAsBase() {
        // seq runs for the whole connection (GCM nonce): a restarted stream may begin anywhere.
        val jb = JitterBuffer(slots = 8, maxSamplesPerPacket = 4)
        val out = ShortArray(4)
        jb.put(3, 0, pcm(1, 1), 0, 4); jb.take(3, out)
        jb.clear()
        assertTrue(jb.put(70_000, 10, pcm(2, 2), 0, 4))
        assertTrue(jb.put(69_998, 5, pcm(2, 2), 0, 4))  // early reorder within the slack
        assertFalse(jb.put(60_000, 5, pcm(2, 2), 0, 4)) // far older than the new base
        assertTrue(jb.put(70_001, 15, pcm(2, 2), 0, 4))
        assertEquals(69_998L, jb.oldestSeq())
        assertEquals(2, jb.take(70_000, out))
    }

    // ---------------------------------------------------------------- speaker clock

    @Test fun speakerClockExtrapolatesFromTimestamp() {
        // frame 48 000 presented at t = 10 s; 480 more frames written → +10 ms
        assertEquals(10_010_000_000L, SpeakerClock.nextFrameNanos(48_000, 10_000_000_000L, 48_480, 48_000))
        assertEquals(1_030_000_000L, SpeakerClock.estimateNanos(1_000_000_000L, 0, 480, 48_000, 20_000_000L))
    }

    // ---------------------------------------------------------------- controller

    @Test fun lockingInsertsSilenceWhenEarly() {
        val c = PlayoutController(48_000)
        val d = c.decide(-10_000_000L, 240)
        assertEquals(PlayoutController.Action.SILENCE, d.action)
        assertEquals(480, d.frames)
        assertFalse(c.locked)
    }

    @Test fun lockingTrimsOrDropsWhenLate() {
        val c = PlayoutController(48_000)
        val d = c.decide(2_000_000L, 240)
        assertEquals(PlayoutController.Action.TRIM, d.action)
        assertEquals(96, d.frames)
        assertEquals(PlayoutController.Action.DROP, c.decide(30_000_000L, 240).action)
    }

    @Test fun locksWithinToleranceThenOnlyResamples() {
        val c = PlayoutController(48_000)
        assertEquals(PlayoutController.Action.PLAY, c.decide(500_000L, 240).action)
        assertTrue(c.locked)
        // 15 ms late while locked: no drop, only a bounded speed-up
        var d = c.decide(15_000_000L, 240)
        repeat(100) { d = c.decide(15_000_000L, 240) }
        assertEquals(PlayoutController.Action.PLAY, d.action)
        assertTrue(d.ratio > 1.0)
        assertTrue(d.ratio <= 1.0 + PlayoutController.MAX_DEVIATION + 1e-12)
        repeat(100) { d = c.decide(-15_000_000L, 240) }
        assertTrue(d.ratio < 1.0)
        assertTrue(d.ratio >= 1.0 - PlayoutController.MAX_DEVIATION - 1e-12)
    }

    @Test fun lockedRecoversFromBigGapsOnly() {
        val c = PlayoutController(48_000)
        c.decide(0, 240)
        assertEquals(PlayoutController.Action.DROP, c.decide(150_000_000L, 240).action)
        assertFalse(c.locked)
        c.decide(0, 240)
        assertEquals(PlayoutController.Action.SILENCE, c.decide(-30_000_000L, 240).action)
        assertFalse(c.locked)
    }

    // ---------------------------------------------------------------- resampler

    @Test fun resamplerIsExactAtUnity() {
        val r = LinearResampler(2)
        val out = ShortArray(64)
        val a = ShortArray(20) { (it * 100).toShort() }
        assertEquals(10, r.process(a, 10, 1.0, out))
        assertArrayEquals(a, out.copyOf(20))
    }

    @Test fun resamplerRatioChangesFrameCountAndStaysContinuous() {
        val r = LinearResampler(1)
        val out = ShortArray(512)
        var produced = 0
        var prevLast = Int.MIN_VALUE
        var maxStep = 0
        // A ramp: any discontinuity across packet boundaries would show as a big step.
        for (p in 0 until 120) { // ramp stays inside the s16 range
            val input = ShortArray(240) { (p * 240 + it).toShort() }
            val n = r.process(input, 240, 1.005, out)
            for (i in 0 until n) {
                val v = out[i].toInt()
                if (prevLast != Int.MIN_VALUE) maxStep = maxOf(maxStep, abs(v - prevLast))
                prevLast = v
            }
            produced += n
        }
        val expected = 120 * 240 / 1.005
        assertTrue("produced $produced vs $expected", abs(produced - expected) <= 2)
        assertTrue("max step $maxStep", maxStep <= 2) // ramp slope 1.005 per output frame
    }

    @Test fun resamplerSwitchesBetweenRatiosSmoothly() {
        val r = LinearResampler(1)
        val out = ShortArray(512)
        var prev = -1
        var maxStep = 0
        for (p in 0 until 50) {
            val input = ShortArray(240) { (p * 240 + it).toShort() }
            val ratio = if (p % 2 == 0) 1.0 else 0.996
            val n = r.process(input, 240, ratio, out)
            for (i in 0 until n) { if (prev >= 0) maxStep = maxOf(maxStep, abs(out[i] - prev)); prev = out[i].toInt() }
        }
        assertTrue("max step $maxStep", maxStep <= 1)
    }

    // ---------------------------------------------------------------- end-to-end, fake clocks

    /**
     * Host emits 5 ms packets; the tablet's DAC runs [ppm] fast relative to the host clock, starts
     * [initialOffsetMs] off, and has a fixed output latency. The writer loop mirrors
     * AudioReceiver.writeLoop's use of the controller and resampler. The speaker must converge to
     * the host's playout time and stay within ±1.5 ms without ever dropping once locked.
     */
    private fun simulate(ppm: Double, initialOffsetMs: Double): Pair<Double, Int> {
        val sr = 48_000
        val frames = 240
        val delayUs = 60_000L
        val devRate = sr * (1 + ppm / 1e6)
        val c = PlayoutController(sr)
        val r = LinearResampler(2)
        val pcm = ShortArray(frames * 2)
        val out = ShortArray(2048)
        val outputLatencyNs = 12_000_000L
        val startNs = 1_000_000_000L + (60_000_000L + initialOffsetMs * 1e6).toLong() - outputLatencyNs
        var written = 0L
        fun nextOut(): Long = startNs + outputLatencyNs + (written / devRate * 1e9).toLong()
        var seq = 0L
        var worst = 0.0
        var dropsAfterLock = 0
        var lockedOnce = false
        var guard = 0
        while (seq < 4000 && guard++ < 20_000) {
            val hostTimeUs = 1_000_000L + seq * 5_000L          // host µs; local clock == host clock + 0
            val target = (hostTimeUs + delayUs) * 1000L
            val err = nextOut() - target
            val d = c.decide(err, frames)
            if (c.locked) lockedOnce = true
            when (d.action) {
                PlayoutController.Action.SILENCE -> written += minOf(d.frames, sr / 50)
                PlayoutController.Action.DROP -> { if (lockedOnce) dropsAfterLock++; seq++ }
                PlayoutController.Action.TRIM -> { written += frames - d.frames; seq++; r.reset() }
                PlayoutController.Action.PLAY -> { written += r.process(pcm, frames, d.ratio, out); seq++ }
            }
            if (seq > 2000) worst = maxOf(worst, abs(err / 1e6))
        }
        return worst to dropsAfterLock
    }

    @Test fun convergesWithFastDac() {
        val (worst, drops) = simulate(ppm = 300.0, initialOffsetMs = -25.0)
        assertTrue("worst error $worst ms", worst < 1.5)
        assertEquals(0, drops)
    }

    @Test fun convergesWithSlowDacStartingLate() {
        val (worst, drops) = simulate(ppm = -400.0, initialOffsetMs = 7.0)
        assertTrue("worst error $worst ms", worst < 1.5)
        assertEquals(0, drops)
    }
}
