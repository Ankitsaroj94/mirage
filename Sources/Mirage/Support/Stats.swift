import Foundation

/// Rolling counters shared by both roles, sampled once a second.
struct SessionStats: Equatable {
    var fps: Double = 0
    var measuredKbps: Double = 0
    var bitrateKbps: Int = 0
    var roundTripMs: Double = 0
    var decodeMs: Double = 0
    var framesDroppedLocal: Int = 0
    var framesDroppedRemote: Int = 0

    private var windowStartMs: Double = MonotonicClock.millis()
    private var framesInWindow: Int = 0
    private var bytesInWindow: Int = 0

    mutating func record(frameBytes: Int) {
        framesInWindow += 1
        bytesInWindow += frameBytes

        let now = MonotonicClock.millis()
        let elapsed = now - windowStartMs
        guard elapsed >= 1000 else { return }
        fps = Double(framesInWindow) * 1000.0 / elapsed
        measuredKbps = Double(bytesInWindow) * 8.0 / elapsed      // bytes/ms * 8 == kbit/s
        framesInWindow = 0
        bytesInWindow = 0
        windowStartMs = now
    }

    var overlayDescription: String {
        String(format: """
        %.0f fps   %.1f Mbps
        round trip  %.1f ms
        decode      %.1f ms
        dropped     %d local / %d remote
        """, fps, measuredKbps / 1000.0, roundTripMs, decodeMs, framesDroppedLocal, framesDroppedRemote)
    }
}
