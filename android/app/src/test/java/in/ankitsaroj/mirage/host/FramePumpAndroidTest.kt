package `in`.ankitsaroj.mirage.host

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FramePumpAndroidTest {

    private var now = 10_000L
    private var pending = 0
    private val log = mutableListOf<String>()
    private lateinit var pump: FramePumpAndroid

    private val control = object : FramePumpAndroid.EncoderControl {
        override fun setSuspended(suspended: Boolean) { log += "suspend=$suspended" }
        override fun setBitrate(kbps: Int) { log += "bitrate=$kbps" }
        override fun requestKeyframe() { log += "keyframe" }
    }

    @Before
    fun setUp() {
        pump = FramePumpAndroid(control, { pending }, FramePumpAndroid.Config(targetKbps = 30_000, minimumKbps = 6_000)) { now }
    }

    @Test
    fun windowOfThreeSuspendsInputAndAnAckResumesIt() {
        assertTrue(pump.shouldSend(true))
        assertTrue(pump.shouldSend(false))
        assertTrue(log.isEmpty())
        assertTrue(pump.shouldSend(false))           // third in flight fills the window
        assertEquals(listOf("suspend=true"), log)
        // A frame that was already inside the encoder is still sent: chain intact, soft cap.
        assertTrue(pump.shouldSend(false))
        assertEquals(4, pump.snapshot().unacknowledged)
        pump.acknowledgeFrame()
        pump.acknowledgeFrame()
        assertEquals(listOf("suspend=true", "suspend=false"), log)
        assertEquals(0, pump.snapshot().droppedEncoded)
    }

    @Test
    fun socketBacklogSuspendsUntilItDrains() {
        // Budget = 125 ms at 30 Mbit/s = 30e6 / 8 / 8 = 468 750 bytes.
        pending = 200_000
        pump.tick()
        assertTrue(log.isEmpty())
        pending = 500_000
        pump.tick()
        assertEquals(listOf("suspend=true"), log)
        pending = 0
        pump.tick()
        assertEquals(listOf("suspend=true", "suspend=false"), log)
    }

    @Test
    fun hardCongestionDropsUntilAKeyframeRequestedOnceTheWindowOpens() {
        repeat(5) { assertTrue(pump.shouldSend(it == 0)) }   // 5 unacked = hard limit (3 + 2)
        log.clear()
        assertFalse(pump.shouldSend(false))           // hard drop: chain broken
        assertFalse(log.contains("keyframe"))         // not while congested
        repeat(5) { pump.acknowledgeFrame() }         // window opens → ask for the IDR now
        assertTrue(log.contains("keyframe"))
        assertFalse(pump.shouldSend(false))           // P-frames referencing the hole are dropped
        assertTrue(pump.shouldSend(true))             // the IDR goes through and heals the stream
        assertTrue(pump.shouldSend(false))
        assertEquals(2, pump.snapshot().droppedEncoded)
    }

    @Test
    fun keyframeRequestIsRetriedIfTheIdrNeverComes() {
        pending = 2_000_000                           // far past 2× budget
        assertFalse(pump.shouldSend(false))
        pending = 0
        pump.tick()
        assertEquals(1, log.count { it == "keyframe" })
        now += 500; pump.tick()
        assertEquals(1, log.count { it == "keyframe" })
        now += 600; pump.tick()
        assertEquals(2, log.count { it == "keyframe" })
    }

    @Test
    fun staleAcksExpireAfterTwoSeconds() {
        repeat(3) { pump.shouldSend(false) }
        assertEquals(listOf("suspend=true"), log)
        now += 2_001
        pump.tick()
        assertEquals(0, pump.snapshot().unacknowledged)
        assertEquals(listOf("suspend=true", "suspend=false"), log)
    }

    @Test
    fun aimdBacksOffAfterThreeCongestedTicksAndCreepsBack() {
        pending = 1_000_000
        now += 600
        pump.tick(); pump.tick()
        assertFalse(log.any { it.startsWith("bitrate") })
        pump.tick()
        assertEquals(22_500, pump.currentBitrateKbps)
        pump.tick()                                   // < 500 ms since the change: hold
        assertEquals(22_500, pump.currentBitrateKbps)
        now += 501; pump.tick()
        assertEquals(16_875, pump.currentBitrateKbps)

        pending = 0
        now += 3_001; pump.tick()
        assertEquals(18_562, pump.currentBitrateKbps)   // +10 %
        now += 1_000; pump.tick()
        assertEquals(18_562, pump.currentBitrateKbps)   // only every 3 s
    }

    @Test
    fun bitrateNeverDropsBelowTheFloor() {
        pending = 10_000_000
        repeat(40) { now += 501; pump.tick() }
        assertEquals(6_000, pump.currentBitrateKbps)
    }

    @Test
    fun clientKeyframeRequestPassesStraightThrough() {
        pump.requestKeyframe()
        assertEquals(listOf("keyframe"), log)
    }

    @Test
    fun stoppedPumpSendsNothing() {
        pump.stop()
        assertFalse(pump.shouldSend(true))
        pump.tick()
        assertTrue(log.isEmpty())
    }
}
