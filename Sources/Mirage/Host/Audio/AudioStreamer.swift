import Foundation
import Network
import CryptoKit

/// System audio → client over UDP, plus synced local playout. STUB — agent 2 implements.
///
/// Contract used by HostSession (agent 1):
///   * `AudioStreamer.isSupported` — false before macOS 14.2 (no process taps).
///   * `start(...)` called after helloAck when the client sent `supportsAudio` + `audioUDPPort`
///     and `mode != .off`; returns the AudioConfig to send as `.audioConfig`.
///   * `stop()` on disconnect / teardown; must restore normal Mac audio.
final class AudioStreamer {

    static var isSupported: Bool { false }

    init(mode: AudioMode, playoutDelayMs: Int, syncOffsetMs: Int) {}

    func start(host: NWEndpoint.Host, port: UInt16, sessionKey: SymmetricKey?) throws -> AudioConfig {
        throw MirageError("Audio is not implemented yet.")
    }

    func updateSyncOffset(ms: Int) {}

    func stop() {}
}
