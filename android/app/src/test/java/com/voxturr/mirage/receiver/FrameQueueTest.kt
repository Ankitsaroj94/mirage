package com.voxturr.mirage.receiver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FrameQueueTest {

    private class FakeSink : FrameQueue.Sink {
        val queued = mutableListOf<Pair<Int, Long>>() // index, pts
        val dropped = mutableListOf<Long>()
        var keyframeRequests = 0
        var failNext = false
        override fun queue(index: Int, data: ByteArray, length: Int, ptsUs: Long, isKeyframe: Boolean): Boolean {
            if (failNext) { failNext = false; return false }
            queued += index to ptsUs
            return true
        }
        override fun dropped(ptsUs: Long) { dropped += ptsUs }
        override fun requestKeyframe() { keyframeRequests++ }
        /** Every frame must be acked exactly once (queued or dropped). */
        fun acked(): List<Long> = (queued.map { it.second } + dropped).sorted()
    }

    private lateinit var sink: FakeSink
    private lateinit var q: FrameQueue
    private val data = ByteArray(16)

    @Before fun setUp() {
        sink = FakeSink()
        q = FrameQueue(capacity = 3, sink = sink)
    }

    private fun frame(pts: Long, key: Boolean = false, ref: Boolean = true) = q.submit(data, data.size, pts, key, ref)

    @Test fun notReadyDropsAndAcks() {
        frame(1, key = true)
        assertEquals(listOf(1L), sink.dropped)
        assertTrue(sink.queued.isEmpty())
    }

    @Test fun queuesImmediatelyWhenInputFree() {
        q.reset(ready = true, requestKeyframe = false)
        q.inputAvailable(7)
        frame(1, key = true)
        assertEquals(listOf(7 to 1L), sink.queued)
        assertEquals(0, q.pendingCount)
    }

    @Test fun awaitsKeyframeAndRequestsOnce() {
        q.reset(ready = true, requestKeyframe = false)
        q.inputAvailable(0)
        frame(1); frame(2); frame(3)
        assertEquals(listOf(1L, 2L, 3L), sink.dropped)
        assertEquals(1, sink.keyframeRequests)
        frame(4, key = true)
        assertEquals(listOf(0 to 4L), sink.queued)
    }

    @Test fun resetWithRequestAsksForKeyframe() {
        q.reset(ready = true, requestKeyframe = true)
        assertEquals(1, sink.keyframeRequests)
        frame(1) // non-key while awaiting: already requested, no second request
        assertEquals(1, sink.keyframeRequests)
    }

    @Test fun pendsUntilInputAvailableInOrder() {
        q.reset(ready = true, requestKeyframe = false)
        frame(1, key = true); frame(2); frame(3)
        assertEquals(3, q.pendingCount)
        q.inputAvailable(5)
        q.inputAvailable(6)
        q.inputAvailable(9)
        assertEquals(listOf(5 to 1L, 6 to 2L, 9 to 3L), sink.queued)
        assertTrue(sink.dropped.isEmpty())
    }

    @Test fun overflowOfReferenceFrameBreaksChainAndRequestsKeyframe() {
        q.reset(ready = true, requestKeyframe = false)
        frame(1, key = true); frame(2); frame(3)
        frame(4) // overflow: oldest (IDR, a reference) must go → chain broken
        assertEquals(listOf(1L, 2L, 3L, 4L), sink.dropped)
        assertEquals(1, sink.keyframeRequests)
        assertTrue(q.isAwaitingKeyframe)
        q.inputAvailable(0)
        frame(5)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), sink.dropped)
        frame(6, key = true)
        assertEquals(listOf(0 to 6L), sink.queued)
        assertEquals((1L..6L).toList(), sink.acked())
    }

    @Test fun overflowOfNonReferenceFrameDropsOnlyIt() {
        q.reset(ready = true, requestKeyframe = false)
        q.inputAvailable(0)
        frame(1, key = true) // queued
        frame(2, ref = false); frame(3); frame(4)
        frame(5) // oldest pending (2) is non-reference → safe to drop alone
        assertEquals(listOf(2L), sink.dropped)
        assertFalse(q.isAwaitingKeyframe)
        q.inputAvailable(1); q.inputAvailable(2); q.inputAvailable(3)
        assertEquals(listOf(1L, 3L, 4L, 5L), sink.queued.map { it.second })
        assertEquals(0, sink.keyframeRequests)
    }

    @Test fun keyframeSupersedesPending() {
        q.reset(ready = true, requestKeyframe = false)
        frame(1, key = true); frame(2)
        frame(3, key = true)
        assertEquals(listOf(1L, 2L), sink.dropped)
        q.inputAvailable(4)
        assertEquals(listOf(4 to 3L), sink.queued)
    }

    @Test fun queueFailureDropsAndResyncs() {
        q.reset(ready = true, requestKeyframe = false)
        q.inputAvailable(0)
        sink.failNext = true
        frame(1, key = true)
        assertEquals(listOf(1L), sink.dropped)
        assertTrue(q.isAwaitingKeyframe)
        assertEquals(1, sink.keyframeRequests)
    }

    @Test fun corruptFrameIsAckedAndResyncs() {
        q.reset(ready = true, requestKeyframe = false)
        q.inputAvailable(0)
        frame(1, key = true)
        q.corrupt(2)
        assertEquals(listOf(2L), sink.dropped)
        assertTrue(q.isAwaitingKeyframe)
    }

    @Test fun resetDropsPendingAndForgetsInputs() {
        q.reset(ready = true, requestKeyframe = false)
        frame(1, key = true); frame(2)
        q.reset(ready = true, requestKeyframe = false)
        assertEquals(listOf(1L, 2L), sink.dropped)
        frame(3, key = true) // no free inputs from the new codec yet → pends
        assertEquals(1, q.pendingCount)
        assertTrue(sink.queued.isEmpty())
    }

    @Test fun rerequestsKeyframeAfterManyDrops() {
        val qq = FrameQueue(capacity = 3, sink = sink, rerequestAfterDrops = 5)
        qq.reset(ready = true, requestKeyframe = true)
        repeat(11) { qq.submit(data, data.size, it.toLong(), false, true) }
        assertEquals(3, sink.keyframeRequests) // initial + after 5 + after 10
    }
}
