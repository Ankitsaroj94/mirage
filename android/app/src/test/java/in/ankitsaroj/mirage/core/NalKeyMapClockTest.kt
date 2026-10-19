package `in`.ankitsaroj.mirage.core

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NalTest {
    private val sps = byteArrayOf(0x42, 0x01, 0x01, 0x22)
    private val pps = byteArrayOf(0x44, 0x01, 0x05)
    private val slice = ByteArray(300) { (it % 250 + 1).toByte() }

    private fun avcc(vararg nals: ByteArray): ByteArray = Nal.annexBToAvcc(Nal.withStartCodes(nals.toList()))

    @Test fun avccToAnnexBInPlace() {
        val buf = avcc(sps, pps, slice)
        assertEquals(4 + sps.size + 4 + pps.size + 4 + slice.size, buf.size)
        assertTrue(Nal.avccToAnnexBInPlace(buf))
        assertArrayEquals(Nal.withStartCodes(listOf(sps, pps, slice)), buf)
    }

    @Test fun avccInPlaceWithOffset() {
        val inner = avcc(pps)
        val buf = ByteArray(5) + inner + ByteArray(3)
        assertTrue(Nal.avccToAnnexBInPlace(buf, 5, inner.size))
        assertArrayEquals(byteArrayOf(0, 0, 0, 1) + pps, buf.copyOfRange(5, 5 + inner.size))
    }

    @Test fun malformedAvccLeftUntouched() {
        val buf = avcc(sps, pps)
        buf[buf.size - pps.size - 1] = 99            // pps length now overruns
        val copy = buf.copyOf()
        assertFalse(Nal.avccToAnnexBInPlace(buf))
        assertArrayEquals(copy, buf)
        assertFalse(Nal.avccToAnnexBInPlace(byteArrayOf(0, 0, 1)))
    }

    @Test fun splitFourByteStartCodes() {
        val nals = Nal.splitAnnexB(Nal.withStartCodes(listOf(sps, pps, slice)))
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0]); assertArrayEquals(pps, nals[1]); assertArrayEquals(slice, nals[2])
    }

    @Test fun splitThreeByteAndMixedStartCodes() {
        val stream = byteArrayOf(0, 0, 1) + sps + byteArrayOf(0, 0, 0, 1) + pps + byteArrayOf(0, 0, 1) + slice
        val nals = Nal.splitAnnexB(stream)
        assertEquals(3, nals.size)
        assertArrayEquals(sps, nals[0]); assertArrayEquals(pps, nals[1]); assertArrayEquals(slice, nals[2])
        // And back through AVCC.
        val avcc = Nal.annexBToAvcc(stream)
        assertTrue(Nal.avccToAnnexBInPlace(avcc))
        assertArrayEquals(Nal.withStartCodes(listOf(sps, pps, slice)), avcc)
    }

    @Test fun nalTypes() {
        assertEquals(33, Nal.hevcType(sps))
        assertEquals(34, Nal.hevcType(pps))
        assertEquals(7, Nal.h264Type(byteArrayOf(0x67)))
    }
}

class KeyMapTest {
    @Test fun bijection() {
        val pairs = KeyMap.mappedPairs
        assertEquals("duplicate Android code", pairs.size, pairs.map { it.first }.toSet().size)
        assertEquals("duplicate Mac code", pairs.size, pairs.map { it.second }.toSet().size)
        for ((android, mac) in pairs) {
            assertEquals(mac, KeyMap.androidToMac(android))
            assertEquals(android, KeyMap.macToAndroid(mac))
        }
        assertTrue(pairs.size >= 100)
    }

    @Test fun spotChecksAgainstEventsH() {
        assertEquals(0x00, KeyMap.androidToMac(KeyEvent.KEYCODE_A))
        assertEquals(0x0B, KeyMap.androidToMac(KeyEvent.KEYCODE_B))
        assertEquals(0x12, KeyMap.androidToMac(KeyEvent.KEYCODE_1))
        assertEquals(0x1D, KeyMap.androidToMac(KeyEvent.KEYCODE_0))
        assertEquals(0x24, KeyMap.androidToMac(KeyEvent.KEYCODE_ENTER))
        assertEquals(0x33, KeyMap.androidToMac(KeyEvent.KEYCODE_DEL))
        assertEquals(0x75, KeyMap.androidToMac(KeyEvent.KEYCODE_FORWARD_DEL))
        assertEquals(0x37, KeyMap.androidToMac(KeyEvent.KEYCODE_META_LEFT))
        assertEquals(0x7A, KeyMap.androidToMac(KeyEvent.KEYCODE_F1))
        assertEquals(0x6F, KeyMap.androidToMac(KeyEvent.KEYCODE_F12))
        assertEquals(0x7E, KeyMap.androidToMac(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(0x52, KeyMap.androidToMac(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(-1, KeyMap.androidToMac(KeyEvent.KEYCODE_BACK))
        assertEquals(-1, KeyMap.macToAndroid(0x0A))
    }

    @Test fun modifiers() {
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON
        assertEquals(Modifiers.SHIFT or Modifiers.CONTROL or Modifiers.COMMAND, KeyMap.metaToModifiers(meta))
        assertEquals(Modifiers.SHIFT or Modifiers.COMMAND, KeyMap.metaToModifiers(meta, ctrlAsCommand = true))
        assertEquals(Modifiers.OPTION or Modifiers.CAPS_LOCK or Modifiers.FN,
            KeyMap.metaToModifiers(KeyEvent.META_ALT_ON or KeyEvent.META_CAPS_LOCK_ON or KeyEvent.META_FUNCTION_ON))

        val back = KeyMap.modifiersToMeta(Modifiers.COMMAND or Modifiers.SHIFT)
        assertTrue(back and KeyEvent.META_CTRL_ON != 0)
        assertTrue(back and KeyEvent.META_SHIFT_ON != 0)
        assertEquals(0, back and KeyEvent.META_META_ON)
        assertTrue(KeyMap.modifiersToMeta(Modifiers.COMMAND, commandAsCtrl = false) and KeyEvent.META_META_ON != 0)
        // Round trip without the Ctrl/Command folding.
        val all = Modifiers.SHIFT or Modifiers.OPTION or Modifiers.CONTROL or Modifiers.COMMAND or Modifiers.CAPS_LOCK or Modifiers.FN
        assertEquals(all, KeyMap.metaToModifiers(KeyMap.modifiersToMeta(all, commandAsCtrl = false)))
    }
}

class ClockSyncTest {
    @Test fun noEstimateUntilFirstSample() {
        val c = ClockSync()
        assertFalse(c.hasEstimate)
        assertEquals(0, c.offsetMicros)
        assertEquals(500L, c.remoteToLocal(500))
    }

    @Test fun firstSampleSetsOffsetExactly() {
        val c = ClockSync()
        // Remote is 1 s ahead; 2 ms symmetric RTT.
        c.addSample(t0 = 10_000, t1 = 1_011_000, t2 = 12_000)
        assertTrue(c.hasEstimate)
        assertEquals(1_000_000, c.offsetMicros)
        assertEquals(2_000, c.bestRoundTripMicros)
        assertEquals(12_000L, c.remoteToLocal(1_012_000))
        assertEquals(1_012_000L, c.localToRemote(12_000))
    }

    @Test fun slowSamplesIgnoredFastOnesSmoothed() {
        val c = ClockSync()
        c.addSample(0, 1_001_000, 2_000)                          // offset 1 000 000, rtt 2 ms
        // Slower round trip, skewed by queueing: ignored.
        c.addSample(1_000_000, 2_050_000, 1_040_000)             // rtt 40 ms, offset 1 030 000
        assertEquals(1_000_000, c.offsetMicros)
        // As fast as the best: moves the estimate by α = 0.1 of the difference.
        c.addSample(2_000_000, 3_002_000, 2_002_000)             // rtt 2 ms, offset 1 001 000
        assertEquals(1_000_100, c.offsetMicros)
    }

    @Test fun windowExpiresOldMinimum() {
        val c = ClockSync()
        c.addSample(0, 500, 1_000)                                // rtt 1 ms, offset 0
        // 11 s later the 1 ms sample has left the window, so a 5 ms sample counts again.
        c.addSample(11_000_000, 11_002_500 + 10_000, 11_005_000) // rtt 5 ms, offset 10 000
        assertEquals(1_000, c.offsetMicros)
        assertEquals(5_000, c.bestRoundTripMicros)
    }

    @Test fun negativeRoundTripIgnored() {
        val c = ClockSync()
        c.addSample(t0 = 10, t1 = 0, t2 = 5)
        assertFalse(c.hasEstimate)
    }

    @Test fun converges() {
        val c = ClockSync()
        val trueOffset = -7_654_321L
        var t = 0L
        repeat(200) { i ->
            val rtt = 1_000L + (i * 37 % 9) * 1_000                // jittery 1–9 ms, symmetric
            c.addSample(t, t + rtt / 2 + trueOffset, t + rtt)
            t += 1_000_000
        }
        assertTrue("offset ${c.offsetMicros}", abs(c.offsetMicros - trueOffset) <= 1)
    }

    @Test fun pongPayload() {
        val p = ClockSync.pongPayload(0x0102030405060708)
        assertArrayEquals(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1), p)
        assertEquals(0x0102030405060708L, ClockSync.remoteClock(p))
        assertEquals(null, ClockSync.remoteClock(ByteArray(0)))
    }
}
