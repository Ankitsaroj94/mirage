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

    // v2 — Android clients
    /// Per-device override, keyed by the client's deviceName. Falls back to the client's own preference.
    var scalePreferences: [String: ScalePreference] = [:]
    var audioMode: AudioMode = .both
    /// Total playout delay shared by both speakers. Larger is more robust to Wi-Fi jitter.
    var audioPlayoutDelayMs = 60
    /// Fine-tune nudging the Mac speakers relative to the tablet (positive = Mac later).
    var audioSyncOffsetMs = 0
    var allow120Hz = false
}

/// How much desktop the host fits onto a client panel. See spec/PROTOCOL.md §5.
enum ScalePreference: String, Codable, CaseIterable {
    case crisp, larger, more, most
}

/// Where the host's system audio plays while a client that supports audio is connected.
enum AudioMode: String, Codable, CaseIterable {
    case off          // audio stays on this Mac, untouched
    case tabletOnly   // muted here, plays on the client
    case both         // plays on both, in sync
}
