import Foundation

/// Estimates the peer's monotonic clock relative to ours from the heartbeat. spec/PROTOCOL.md §8.
///
/// Every ping goes out stamped with our clock (`t0`); a v2 pong echoes that stamp and carries
/// the peer's clock at reply time (`t1`); we note when it lands (`t2`). Assuming the two legs
/// take equally long, `offset = t1 − (t0 + t2) / 2`. The assumption is only as good as the
/// round trip is short — a sample that sat behind a keyframe is skewed by half its queueing
/// delay — so only samples that set a new low for the last ten seconds move the estimate, and
/// then gently (EWMA, α = 0.1), so one lucky sample cannot yank audio playout around.
///
/// Thread-safe: samples arrive on the link's queue, readers are the audio and stats paths.
final class ClockSync: @unchecked Sendable {

    static let windowMicros: UInt64 = 10_000_000
    static let smoothing = 0.1

    private struct Sample {
        let receivedAt: UInt64
        let roundTrip: Int64
    }

    private let lock = NSLock()
    private var window: [Sample] = []
    private var offset: Double = 0
    private var estimated = false
    private var bestRoundTrip: Int64 = 0

    /// `t0` local ping send, `t1` remote clock in the pong, `t2` local pong receive — all µs.
    func addSample(t0: UInt64, t1: UInt64, t2: UInt64) {
        let roundTrip = Int64(bitPattern: t2 &- t0)
        guard roundTrip >= 0 else { return }          // pong for a ping we never sent
        let sampleOffset = Double(Int64(bitPattern: t1)) - (Double(t0) + Double(t2)) / 2

        lock.lock(); defer { lock.unlock() }
        window.removeAll { t2 &- $0.receivedAt > Self.windowMicros }
        let windowBest = window.map(\.roundTrip).min()
        window.append(Sample(receivedAt: t2, roundTrip: roundTrip))

        // A sample's offset can be wrong by up to half its round trip. The first pong usually
        // queues behind the first keyframe, so the estimate it seeds can be ~100 ms off; when a
        // much tighter sample arrives, take it outright rather than easing towards it at 10 %.
        if !estimated || roundTrip * 4 < bestRoundTrip * 3 {
            offset = sampleOffset
            estimated = true
        } else if windowBest == nil || roundTrip <= windowBest! {
            offset += Self.smoothing * (sampleOffset - offset)
        } else {
            return
        }
        bestRoundTrip = roundTrip
    }

    /// Remote minus local, in µs. Zero until the first v2 pong.
    var offsetMicros: Int64 {
        lock.lock(); defer { lock.unlock() }
        return Int64(offset.rounded())
    }

    var hasEstimate: Bool {
        lock.lock(); defer { lock.unlock() }
        return estimated
    }

    /// Round trip of the sample the current estimate last moved on.
    var bestRoundTripMicros: Int64 {
        lock.lock(); defer { lock.unlock() }
        return bestRoundTrip
    }

    func remoteToLocal(_ remoteMicros: UInt64) -> UInt64 {
        remoteMicros &- UInt64(bitPattern: offsetMicros)
    }

    func localToRemote(_ localMicros: UInt64) -> UInt64 {
        localMicros &+ UInt64(bitPattern: offsetMicros)
    }

    // MARK: Pong payload

    /// v2 pong body: the responder's clock at reply time, 8 bytes little-endian.
    static func pongPayload(now: UInt64 = MonotonicClock.micros()) -> Data {
        var data = Data(capacity: 8)
        data.appendLE(now)
        return data
    }

    /// Nil for a v1 pong, which is empty.
    static func remoteClock(fromPong payload: Data) -> UInt64? {
        payload.count >= 8 ? payload.readLE(at: 0) : nil
    }
}
