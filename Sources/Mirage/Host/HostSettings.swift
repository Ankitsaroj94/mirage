import Foundation

/// Everything the sending Mac's pipeline is configured with.
///
/// Kept separate from `HostSession` so the non-actor-isolated parts of the pipeline — and the
/// test harnesses — can use it without dragging in the UI layer.
struct HostSettings: Codable, Equatable {
    var preferHEVC = true
    var maxFPS = 60
    var targetBitrateKbps = 45_000
    var minimumBitrateKbps = 6_000
    var adaptiveBitrate = true
    /// Caps encoder QP so a busy frame degrades in framerate rather than in sharpness.
    var prioritiseTextClarity = true
    var acceptRemoteInput = true
    var allowPeerToPeer = false
    var pairingCode: String? = nil
}
