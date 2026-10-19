package `in`.ankitsaroj.mirage.receiver

/**
 * Hands incoming video frames to the decoder's free input buffers. Pure logic (no MediaCodec),
 * so the policy is unit-tested on the JVM; [VideoDecoder] supplies the [Sink].
 *
 * Policy, tuned for latency:
 *  - A frame is queued the moment a codec input buffer is free — on the caller's (network
 *    reader) thread, no hop.
 *  - With no free buffer the frame waits in a tiny ring (default 3 = the host's in-flight cap,
 *    since the host only runs ≤3 un-acked frames ahead and we ack on *queue*, overflow is rare).
 *  - A keyframe supersedes everything pending: older frames are dropped (the newest picture wins).
 *  - On overflow the oldest pending frame is dropped. If it was a reference frame the chain is
 *    broken, so everything up to the next keyframe is dropped and a keyframe is requested.
 *  - Every frame ends in exactly one of [Sink.queue] / [Sink.dropped], so the host gets exactly
 *    one frameAck per frame and never stalls.
 *
 * Thread-safe: every public method takes the internal lock; [Sink] calls happen under it.
 * Codec lifecycle changes in the decoder use [withLock] so they never race a queue.
 */
class FrameQueue(
    private val capacity: Int = 3,
    private val sink: Sink,
    /** While awaiting a keyframe, re-request after this many dropped frames. */
    private val rerequestAfterDrops: Int = 60,
) {
    interface Sink {
        /** Copy [data] into codec input buffer [index] and queue it. False = failed (frame lost). */
        fun queue(index: Int, data: ByteArray, length: Int, ptsUs: Long, isKeyframe: Boolean): Boolean
        /** Frame discarded without decoding (still needs its frameAck). */
        fun dropped(ptsUs: Long)
        fun requestKeyframe()
    }

    private val lock = Object()

    // Free codec input indices (FIFO ring).
    private val free = IntArray(64)
    private var freeHead = 0
    private var freeCount = 0

    // Pending frames (FIFO ring). Arrays hold references only — no copies.
    private val pData = arrayOfNulls<ByteArray>(capacity)
    private val pLen = IntArray(capacity)
    private val pPts = LongArray(capacity)
    private val pKey = BooleanArray(capacity)
    private val pRef = BooleanArray(capacity)
    private var pHead = 0
    private var pCount = 0

    private var ready = false
    private var awaitingKeyframe = true
    private var keyframeRequested = false
    private var dropsWhileAwaiting = 0

    var framesQueued = 0L; private set
    var framesDropped = 0L; private set

    val pendingCount: Int get() = synchronized(lock) { pCount }
    val isAwaitingKeyframe: Boolean get() = synchronized(lock) { awaitingKeyframe }

    /** Runs [block] under the queue lock (codec lifecycle changes; not per frame). */
    fun <T> withLock(block: () -> T): T = synchronized(lock) { block() }

    /**
     * Codec (re)started or torn down. Clears free indices (they belong to the old codec), drops
     * pending frames, and waits for a keyframe. [ready] = a started codec will report inputs.
     */
    fun reset(ready: Boolean, requestKeyframe: Boolean) = synchronized(lock) {
        freeHead = 0; freeCount = 0
        dropAllPending()
        this.ready = ready
        awaitingKeyframe = true
        keyframeRequested = false
        dropsWhileAwaiting = 0
        if (ready && requestKeyframe) {
            keyframeRequested = true
            sink.requestKeyframe()
        }
    }

    /** A frame that could not be parsed: drop it (acked) and resync on the next keyframe. */
    fun corrupt(ptsUs: Long) = synchronized(lock) {
        drop(ptsUs)
        if (ready && !awaitingKeyframe) { dropAllPending(); enterAwaitingKeyframe() }
    }

    /** A codec input buffer became free. */
    fun inputAvailable(index: Int) = synchronized(lock) {
        if (!ready) return@synchronized
        if (freeCount == free.size) return@synchronized // cannot happen with real codecs
        free[(freeHead + freeCount) % free.size] = index
        freeCount++
        drain()
    }

    /**
     * A frame arrived (already Annex-B). [isReference] = false only when the frame is provably
     * non-reference (dropping it cannot corrupt later frames).
     */
    fun submit(data: ByteArray, length: Int, ptsUs: Long, isKeyframe: Boolean, isReference: Boolean) = synchronized(lock) {
        if (!ready) { drop(ptsUs); return@synchronized }
        if (awaitingKeyframe) {
            if (!isKeyframe) {
                drop(ptsUs)
                dropsWhileAwaiting++
                if (!keyframeRequested || dropsWhileAwaiting >= rerequestAfterDrops) {
                    keyframeRequested = true
                    dropsWhileAwaiting = 0
                    sink.requestKeyframe()
                }
                return@synchronized
            }
            awaitingKeyframe = false
            keyframeRequested = false
            dropsWhileAwaiting = 0
        }
        if (isKeyframe) dropAllPending()

        if (pCount == 0 && freeCount > 0) {
            queueNow(data, length, ptsUs, isKeyframe)
            return@synchronized
        }

        if (pCount == capacity) {
            val oldest = pHead
            if (pRef[oldest]) {
                // Chain broken: nothing until the next IDR can decode cleanly.
                dropAllPending()
                drop(ptsUs)
                enterAwaitingKeyframe()
                return@synchronized
            }
            popPending(dropIt = true)
        }
        val slot = (pHead + pCount) % capacity
        pData[slot] = data; pLen[slot] = length; pPts[slot] = ptsUs
        pKey[slot] = isKeyframe; pRef[slot] = isReference
        pCount++
        drain()
    }

    private fun drain() {
        while (pCount > 0 && freeCount > 0) {
            val s = pHead
            val d = pData[s]!!
            val len = pLen[s]; val pts = pPts[s]; val key = pKey[s]
            popPending(dropIt = false)
            queueNow(d, len, pts, key)
            if (awaitingKeyframe) return // queue failure broke the chain
        }
    }

    private fun queueNow(data: ByteArray, length: Int, ptsUs: Long, isKeyframe: Boolean) {
        val index = free[freeHead]
        freeHead = (freeHead + 1) % free.size
        freeCount--
        if (sink.queue(index, data, length, ptsUs, isKeyframe)) {
            framesQueued++
        } else {
            drop(ptsUs)
            dropAllPending()
            enterAwaitingKeyframe()
        }
    }

    private fun enterAwaitingKeyframe() {
        awaitingKeyframe = true
        keyframeRequested = true
        dropsWhileAwaiting = 0
        sink.requestKeyframe()
    }

    private fun popPending(dropIt: Boolean) {
        val s = pHead
        if (dropIt) drop(pPts[s])
        pData[s] = null
        pHead = (pHead + 1) % capacity
        pCount--
    }

    private fun dropAllPending() {
        while (pCount > 0) popPending(dropIt = true)
    }

    private fun drop(ptsUs: Long) {
        framesDropped++
        sink.dropped(ptsUs)
    }
}
