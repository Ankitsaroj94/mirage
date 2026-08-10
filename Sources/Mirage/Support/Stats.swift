import Foundation

/// Immutable snapshot handed to the UI. Plain value type, safe to pass anywhere.
struct SessionStats: Equatable {
    var fps: Double = 0
    var measuredKbps: Double = 0
    var bitrateKbps: Int = 0
    var roundTripMs: Double = 0
    var decodeMs: Double = 0
    var framesDroppedLocal: Int = 0
    var framesDroppedRemote: Int = 0

    var overlayDescription: String {
        String(format: """
        %.0f fps   %.1f Mbps
        round trip  %.1f ms
        decode      %.1f ms
        dropped     %d local / %d remote
        """, fps, measuredKbps / 1000.0, roundTripMs, decodeMs, framesDroppedLocal, framesDroppedRemote)
    }
}

/// Collects counters from the video threads without ever touching the main actor.
///
/// The previous version mutated an `@Published` struct once per frame, which invalidated the
/// whole SwiftUI control panel up to 180 times a second on the receiving Mac. Statistics are
/// now accumulated under a lock here and the UI samples a snapshot twice a second.
final class StatsCollector: @unchecked Sendable {

    private let lock = NSLock()
    private var current = SessionStats()

    private var framesInWindow = 0
    private var bytesInWindow = 0
    private var windowStartMs = MonotonicClock.millis()

    /// Called on the capture, encoder or network queue — never the main thread.
    func record(frameBytes: Int) {
        lock.lock(); defer { lock.unlock() }
        framesInWindow += 1
        bytesInWindow += frameBytes

        let now = MonotonicClock.millis()
        let elapsed = now - windowStartMs
        guard elapsed >= 500 else { return }
        current.fps = Double(framesInWindow) * 1000.0 / elapsed
        current.measuredKbps = Double(bytesInWindow) * 8.0 / elapsed   // bytes/ms * 8 == kbit/s
        framesInWindow = 0
        bytesInWindow = 0
        windowStartMs = now
    }

    func update(_ mutate: (inout SessionStats) -> Void) {
        lock.lock(); defer { lock.unlock() }
        mutate(&current)
    }

    /// Reports zero throughput once the stream has genuinely gone quiet, so a stalled link
    /// is distinguishable from an idle screen that simply has nothing new to send.
    func snapshot() -> SessionStats {
        lock.lock(); defer { lock.unlock() }
        if MonotonicClock.millis() - windowStartMs > 1_500 {
            current.fps = 0
            current.measuredKbps = 0
        }
        return current
    }

    /// True when nothing has been sent recently — the screen is simply static.
    var isIdle: Bool {
        lock.lock(); defer { lock.unlock() }
        return MonotonicClock.millis() - windowStartMs > 1_500
    }
}
