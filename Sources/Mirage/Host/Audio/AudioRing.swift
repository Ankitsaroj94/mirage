import Foundation

/// Read/write cursors for a single-producer, single-consumer ring that either end may drive
/// from a real-time audio thread.
///
/// Swift has no atomics before macOS 15 (the `Synchronization` module), and a plain pointer
/// store promises no ordering, so the cursors are exchanged under an `os_unfair_lock` — but
/// only ever with *try*-lock, from both sides. A side that loses the race keeps working from
/// its private copy of the cursors and publishes on its next call. Nobody ever waits on the
/// lock, so unlocking never has a waiter to wake and never enters the kernel: it is wait-free
/// in practice. Lock and unlock still give acquire/release ordering, which is what makes the
/// samples written before a publish visible to the other side after it.
///
/// A stale view is always the safe one: the producer thinks there is less free space than
/// there is, the consumer that there is less data. Cursors are absolute and never wrap, so
/// `write - read` is the fill level and the slot is `cursor & mask`.
final class SPSCCursors {
    let capacity: Int
    let mask: UInt64

    private let lock: UnsafeMutablePointer<os_unfair_lock>
    // One allocation, each side's words on its own cache line:
    //   [0] published write  [1] published read   — only under `lock`
    //   [8] producer write   [9] producer's read  — producer thread only
    //   [16] consumer read   [17] consumer's write — consumer thread only
    private let state: UnsafeMutablePointer<UInt64>

    init(capacity: Int) {
        precondition(capacity > 0 && capacity & (capacity - 1) == 0, "capacity must be a power of two")
        self.capacity = capacity
        self.mask = UInt64(capacity - 1)
        lock = .allocate(capacity: 1)
        lock.initialize(to: os_unfair_lock())
        state = .allocate(capacity: 24)
        state.initialize(repeating: 0, count: 24)
    }

    deinit {
        lock.deinitialize(count: 1); lock.deallocate()
        state.deallocate()
    }

    // MARK: Producer side

    /// Absolute index of the next slot the producer will fill.
    var producerCursor: UInt64 { state[8] }

    /// Slots the producer may fill right now, starting at `producerCursor`.
    var writableCount: Int {
        syncProducer()
        return capacity - Int(state[8] &- state[9])
    }

    func commitWrite(_ count: Int) {
        state[8] &+= UInt64(count)
        syncProducer()
    }

    /// Re-offers anything committed while the consumer held the lock.
    func publishPending() { syncProducer() }

    // MARK: Consumer side

    /// Absolute index of the next slot the consumer will read.
    var consumerCursor: UInt64 { state[16] }

    var readableCount: Int {
        syncConsumer()
        return Int(state[17] &- state[16])
    }

    func commitRead(_ count: Int) {
        state[16] &+= UInt64(count)
        syncConsumer()
    }

    // MARK: Exchange

    private func syncProducer() {
        guard os_unfair_lock_trylock(lock) else { return }
        state[0] = state[8]
        state[9] = state[1]
        os_unfair_lock_unlock(lock)
    }

    private func syncConsumer() {
        guard os_unfair_lock_trylock(lock) else { return }
        state[1] = state[16]
        state[17] = state[0]
        os_unfair_lock_unlock(lock)
    }
}

/// A value written by an ordinary thread and read by a real-time one, e.g. the sync offset.
///
/// Same try-lock idea as `SPSCCursors`: the reader never waits — if the writer happens to hold
/// the lock it just uses the value it read last time — and the writer, which is allowed to
/// wait, spins on try-lock so the reader never has to wake anyone up.
final class RealtimeValue {
    private let lock: UnsafeMutablePointer<os_unfair_lock>
    private let shared: UnsafeMutablePointer<Int64>
    private let cached: UnsafeMutablePointer<Int64>

    init(_ value: Int64) {
        lock = .allocate(capacity: 1); lock.initialize(to: os_unfair_lock())
        shared = .allocate(capacity: 1); shared.initialize(to: value)
        cached = .allocate(capacity: 1); cached.initialize(to: value)
    }

    deinit { lock.deallocate(); shared.deallocate(); cached.deallocate() }

    /// Any non-real-time thread.
    func set(_ value: Int64) {
        while !os_unfair_lock_trylock(lock) { sched_yield() }
        shared.pointee = value
        os_unfair_lock_unlock(lock)
    }

    /// The real-time reader only (one thread).
    func get() -> Int64 {
        if os_unfair_lock_trylock(lock) {
            cached.pointee = shared.pointee
            os_unfair_lock_unlock(lock)
        }
        return cached.pointee
    }
}

/// System audio as it comes off the tap: interleaved stereo Float32 at the tap's own rate,
/// plus timing anchors.
///
/// The real-time IOProc writes, the sender thread reads. Rather than stamping every sample,
/// each IOProc call records one anchor — "frame N was captured at host time T" — and the reader
/// extrapolates from the nearest anchor. Frames dropped because the ring was full never get an
/// index, so the next anchor carries the jump in time and the timeline stays honest.
final class CaptureRing {
    static let channels = 2

    let frames: SPSCCursors
    private let samples: UnsafeMutablePointer<Float>

    private let anchors: SPSCCursors
    private let anchorFrame: UnsafeMutablePointer<UInt64>
    private let anchorMicros: UnsafeMutablePointer<UInt64>

    /// Frames the producer had to throw away. Producer-written, read loosely for stats.
    private(set) var overflowFrames: UInt64 = 0

    init(capacityFrames: Int = 1 << 15, anchorCapacity: Int = 256) {
        frames = SPSCCursors(capacity: capacityFrames)
        samples = .allocate(capacity: capacityFrames * Self.channels)
        samples.initialize(repeating: 0, count: capacityFrames * Self.channels)
        anchors = SPSCCursors(capacity: anchorCapacity)
        anchorFrame = .allocate(capacity: anchorCapacity)
        anchorMicros = .allocate(capacity: anchorCapacity)
    }

    deinit {
        samples.deallocate()
        anchorFrame.deallocate()
        anchorMicros.deallocate()
    }

    // MARK: Producer (real-time)

    /// Copies `count` frames in from any channel layout: interleaved is `stride` = channel count
    /// with `right = left + 1`; planar is two pointers with stride 1; mono passes the same
    /// pointer twice. Returns the frames accepted.
    @discardableResult
    func write(left: UnsafePointer<Float>, right: UnsafePointer<Float>, stride: Int,
               count: Int, hostMicros: UInt64) -> Int {
        let room = frames.writableCount
        let accepted = min(count, room)
        if accepted < count { overflowFrames &+= UInt64(count - accepted) }
        guard accepted > 0 else { return 0 }

        if anchors.writableCount > 0 {
            let slot = Int(anchors.producerCursor & anchors.mask)
            anchorFrame[slot] = frames.producerCursor
            anchorMicros[slot] = hostMicros
            anchors.commitWrite(1)
        } else {
            anchors.publishPending()
        }

        let start = frames.producerCursor
        for i in 0..<accepted {
            let slot = Int((start &+ UInt64(i)) & frames.mask) * Self.channels
            samples[slot] = left[i * stride]
            samples[slot + 1] = right[i * stride]
        }
        frames.commitWrite(accepted)
        return accepted
    }

    // MARK: Consumer

    /// Oldest unread anchor, if any.
    func popAnchor() -> (frame: UInt64, micros: UInt64)? {
        guard anchors.readableCount > 0 else { return nil }
        let slot = Int(anchors.consumerCursor & anchors.mask)
        let anchor = (anchorFrame[slot], anchorMicros[slot])
        anchors.commitRead(1)
        return anchor
    }

    /// Copies up to `maxFrames` interleaved frames out. Returns the count and the absolute index
    /// of the first one.
    func read(into destination: UnsafeMutablePointer<Float>, maxFrames: Int) -> (count: Int, firstFrame: UInt64) {
        let count = min(maxFrames, frames.readableCount)
        let start = frames.consumerCursor
        for i in 0..<count {
            let slot = Int((start &+ UInt64(i)) & frames.mask) * Self.channels
            destination[i * 2] = samples[slot]
            destination[i * 2 + 1] = samples[slot + 1]
        }
        if count > 0 { frames.commitRead(count) }
        return (count, start)
    }
}
