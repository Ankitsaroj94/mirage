import Foundation
import CoreVideo

/// The capture → encode → send path, deliberately kept off the main actor.
///
/// This used to hop to `@MainActor` twice per frame (once from the capture callback, once
/// from the encoder callback), and each hop also mutated `@Published` statistics, which
/// invalidated the whole SwiftUI control panel. At 60 fps that put video behind every view
/// update the app wanted to do.
///
/// Worse, `Task { @MainActor in … }` makes no ordering guarantee, so frames could reach the
/// socket out of the order the encoder produced them.
///
/// Everything on the hot path now runs on the capture and encoder queues, and the UI samples
/// a snapshot from `stats` a couple of times a second.
final class FramePump: @unchecked Sendable {

    /// One frame encoding, one queued behind it. Beyond that, extra frames only add delay.
    private static let maxFramesInFlight = 2

    /// Frames sent but not yet acknowledged as displayed.
    ///
    /// This is the backstop that actually bounds latency. Socket-level backpressure only
    /// reports what Network.framework accepted from us, not what the other Mac consumed, so
    /// if the receiver is the slow part — a busy decoder, a congested link — frames queue up
    /// invisibly and delay grows without limit. Waiting on acknowledgements caps the whole
    /// chain at a few frames no matter which stage is the bottleneck.
    private static let maxUnacknowledgedFrames = 3

    let stats = StatsCollector()

    private let link: PeerLink
    private let encoder: VideoEncoder
    private let lock = NSLock()

    private let targetBitrateKbps: Int
    private let minimumBitrateKbps: Int
    private let adaptiveBitrate: Bool

    private var currentBitrateKbps: Int
    private var lastBitrateChangeMs = MonotonicClock.millis()
    private var congestedStreak = 0
    private var droppedLocally = 0
    private var unacknowledged = 0
    private var lastAckMs = MonotonicClock.millis()
    private var stopped = false

    init(link: PeerLink, encoder: VideoEncoder, settings: HostSettings) {
        self.link = link
        self.encoder = encoder
        self.targetBitrateKbps = settings.targetBitrateKbps
        self.minimumBitrateKbps = settings.minimumBitrateKbps
        self.adaptiveBitrate = settings.adaptiveBitrate
        self.currentBitrateKbps = settings.targetBitrateKbps

        stats.update { $0.bitrateKbps = settings.targetBitrateKbps }

        // Runs on the encoder's callback queue. Straight to the socket, no hops.
        encoder.onEncodedFrame = { [weak self] frame in
            guard let self, !self.stopped else { return }
            self.lock.lock(); self.unacknowledged += 1; self.lock.unlock()
            self.link.send(.videoFrame,
                           flags: frame.isKeyframe ? FrameFlags.keyframe : 0,
                           payload: frame.data,
                           timestampMicros: frame.captureTimeMicros)
            self.stats.record(frameBytes: frame.data.count)
        }
    }

    func stop() {
        stopped = true
    }

    /// Called straight from the ScreenCaptureKit output queue.
    ///
    /// Backpressure: if the socket has not drained the previous frames there is no point
    /// encoding another — it would only add queueing delay. Dropping here keeps the picture
    /// current rather than letting it fall behind and stay behind.
    func submit(pixelBuffer: CVPixelBuffer, captureTimeMicros: UInt64) {
        guard !stopped else { return }

        lock.lock()
        let bitrate = currentBitrateKbps
        var waiting = unacknowledged
        // If acknowledgements dry up entirely the peer is wedged or gone; clear the window
        // rather than freezing the stream forever waiting on replies that will never come.
        if waiting > 0, MonotonicClock.millis() - lastAckMs > 2_000 {
            unacknowledged = 0
            waiting = 0
        }
        lock.unlock()

        let budget = max(64 * 1024, bitrate * 1000 / 8 / 8)   // roughly 125 ms of data

        // Two sources of backlog, and either one left unchecked makes latency grow without
        // bound: the encoder (usually the binding constraint at full resolution) and the
        // socket. Dropping the newest frame keeps the picture current — a screen stream
        // wants the latest frame, never a complete one.
        if waiting >= Self.maxUnacknowledgedFrames
            || encoder.framesInFlight >= Self.maxFramesInFlight
            || link.pendingBytes > budget {
            lock.lock()
            droppedLocally += 1
            congestedStreak += 1
            let dropped = droppedLocally
            lock.unlock()
            stats.update { $0.framesDroppedLocal = dropped }
            adaptBitrate(congested: true)
            return
        }

        lock.lock(); congestedStreak = 0; lock.unlock()
        adaptBitrate(congested: false)
        encoder.encode(pixelBuffer: pixelBuffer, captureTimeMicros: captureTimeMicros)
    }

    func requestKeyframe() {
        encoder.requestKeyframe()
    }

    /// Called from the network queue when the receiver reports a frame displayed.
    func acknowledgeFrame() {
        lock.lock()
        if unacknowledged > 0 { unacknowledged -= 1 }
        lastAckMs = MonotonicClock.millis()
        lock.unlock()
    }

    /// Samples the link's own round-trip measurement into the snapshot the UI reads.
    func refreshLinkStats() {
        let rtt = link.roundTripMs
        stats.update { $0.roundTripMs = rtt }
    }

    func recordRemote(droppedFrames: Int, decodeMs: Double) {
        stats.update {
            $0.framesDroppedRemote = droppedFrames
            $0.decodeMs = decodeMs
        }
    }

    /// Plain AIMD: back off hard when the link is congested, creep back up when it is not.
    private func adaptBitrate(congested: Bool) {
        guard adaptiveBitrate else { return }
        let now = MonotonicClock.millis()

        lock.lock()
        var newBitrate: Int?
        if congested {
            if congestedStreak >= 3, now - lastBitrateChangeMs > 500 {
                let reduced = max(minimumBitrateKbps, Int(Double(currentBitrateKbps) * 0.75))
                if reduced != currentBitrateKbps {
                    currentBitrateKbps = reduced
                    lastBitrateChangeMs = now
                    newBitrate = reduced
                }
            }
        } else if now - lastBitrateChangeMs > 3_000, currentBitrateKbps < targetBitrateKbps {
            let raised = min(targetBitrateKbps, currentBitrateKbps + max(1_000, currentBitrateKbps / 10))
            currentBitrateKbps = raised
            lastBitrateChangeMs = now
            newBitrate = raised
        }
        lock.unlock()

        guard let newBitrate else { return }
        encoder.updateBitrate(kbps: newBitrate)
        stats.update { $0.bitrateKbps = newBitrate }
        if congested { Log.video("congestion, bitrate -> \(newBitrate) kbps") }
    }
}
