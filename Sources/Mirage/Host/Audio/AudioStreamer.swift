import Foundation
import Network
import CryptoKit

/// System audio → client over UDP, plus synced local playout. See spec/PROTOCOL.md §7.
///
///   tap IOProc (real-time) ─► CaptureRing ─► AudioPump thread ─┬─► AudioSender (UDP)
///                                                              └─► LocalPlayout (mode .both)
///
/// Both outputs play a sample captured at T at `T + playoutDelay` on this Mac's clock; the
/// client converts that to its own clock with the ping/pong offset, so the two speakers line up.
///
/// Contract used by HostSession:
///   * `AudioStreamer.isSupported` — false before macOS 14.2 (no process taps).
///   * `start(...)` called after helloAck when the client sent `supportsAudio` + `audioUDPPort`
///     and `mode != .off`; returns the AudioConfig to send as `.audioConfig`. Calling it again
///     restarts the stream. Pass `firstSeq: nextSeq` of the previous stream on the same link:
///     sequence numbers never repeat within a connection, because they are the audio nonce.
///   * `stop()` on disconnect / teardown; restores normal Mac audio. Safe to call repeatedly.
///
/// Not thread-safe itself: call it from one thread (the session's).
final class AudioStreamer {

    static var isSupported: Bool {
        if #available(macOS 14.2, *) { return true }
        return false
    }

    private let mode: AudioMode
    private let playoutDelayMs: Int
    private var syncOffsetMs: Int

    private var tap: AnyObject?            // SystemAudioTap, which only exists on 14.2+
    private var pump: AudioPump?
    private var sender: AudioSender?
    private var playout: LocalPlayout?

    /// Where the sequence numbers stopped, for the next stream on the same link.
    private(set) var nextSeq: UInt32 = 0

    init(mode: AudioMode, playoutDelayMs: Int, syncOffsetMs: Int) {
        self.mode = mode
        self.playoutDelayMs = playoutDelayMs
        self.syncOffsetMs = syncOffsetMs
    }

    deinit { stop() }

    func start(host: NWEndpoint.Host, port: UInt16, sessionKey: SymmetricKey?, firstSeq: UInt32 = 0) throws -> AudioConfig {
        guard mode != .off else { throw MirageError("Audio streaming is turned off.") }
        guard #available(macOS 14.2, *) else {
            throw MirageError("Sending the Mac's sound needs macOS 14.2 or later.")
        }
        stop()

        let ring = CaptureRing()
        let wake = DispatchSemaphore(value: 0)
        let sender = AudioSender(host: host, port: port, key: sessionKey, firstSeq: firstSeq)

        do {
            // Speakers and socket first, tap last: the tap is what mutes the Mac, so by the time
            // it does, the delayed copy is ready to take over. Starting the playout first also
            // gives this process a Core Audio process object for the tap to exclude.
            var playout: LocalPlayout?
            if mode == .both {
                playout = LocalPlayout(playoutDelayMs: playoutDelayMs, syncOffsetMs: syncOffsetMs)
                try playout?.start()
            }
            self.playout = playout
            sender.start()
            self.sender = sender

            let tap = SystemAudioTap(ring: ring, wake: wake, mutesSystemOutput: true)
            try tap.start()
            self.tap = tap

            let playoutBuffer = playout?.buffer
            let pump = AudioPump(ring: ring, wake: wake, sourceRate: tap.sampleRate) { time, pcm in
                sender.send(hostTimeMicros: time, pcm: pcm)
                playoutBuffer?.enqueue(hostTimeMicros: time, pcm: pcm)
            }
            pump.start()
            self.pump = pump

            Log.app("audio: \(mode.rawValue) → \(host):\(port), tap \(Int(tap.sampleRate)) Hz, "
                    + "delay \(playoutDelayMs) ms, \(sessionKey == nil ? "clear" : "encrypted")")
        } catch {
            stop()
            throw error
        }

        return AudioConfig(sampleRate: Int(AudioPacketizer.outputRate),
                           channels: AudioPacketizer.channels,
                           format: "s16le",
                           packetFrames: AudioPacketizer.packetFrames,
                           playoutDelayMs: playoutDelayMs,
                           encrypted: sessionKey != nil)
    }

    func updateSyncOffset(ms: Int) {
        syncOffsetMs = ms
        playout?.updateSyncOffset(ms: ms)
    }

    func stop() {
        // Tap first so normal audio comes back at once and nothing new enters the ring; then
        // the pump (it reads the ring and feeds the other two); then the outputs.
        if #available(macOS 14.2, *), let tap = tap as? SystemAudioTap { tap.stop() }
        tap = nil
        pump?.stop()
        pump = nil
        playout?.stop()
        playout = nil
        if let sender { nextSeq = sender.nextSeq }
        sender?.stop()
        sender = nil
    }
}
