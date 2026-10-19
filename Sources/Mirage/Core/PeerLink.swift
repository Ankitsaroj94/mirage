import Foundation
import Network
import CryptoKit

/// Builds the TCP parameters both ends use.
///
/// Everything here is tuned for one job: pushing 60 fps of screen data across a LAN with
/// the least possible delay. Encryption, when enabled, is layered on top by `SecureChannel`
/// rather than by TLS — see that file for why.
enum LinkParameters {
    static let serviceType = "_mirage._tcp"

    static func make(allowPeerToPeer: Bool) -> NWParameters {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true                    // never coalesce; a late frame is a dropped frame
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 2
        tcp.keepaliveCount = 3
        tcp.keepaliveInterval = 2
        tcp.connectionTimeout = 8
        // noPush is TCP_NOPUSH — it *corks* the socket until a full segment accumulates.
        // It must stay off here; leaving it on held frames in the send buffer and added
        // well over a second of latency on its own.
        tcp.noPush = false
        tcp.disableAckStretching = true       // keep the ACK clock tight on Wi-Fi

        let parameters = NWParameters(tls: nil, tcp: tcp)

        // Marks packets as interactive video, which maps to the WMM video access category
        // on Wi-Fi and gets us ahead of bulk traffic on the same air.
        parameters.serviceClass = .interactiveVideo
        parameters.multipathServiceType = .disabled
        parameters.prohibitedInterfaceTypes = [.cellular]
        parameters.preferNoProxies = true

        // AWDL peer-to-peer. Handy when there is no shared router, but infrastructure
        // Wi-Fi is considerably faster, so this is a fallback rather than the default.
        parameters.includePeerToPeer = allowPeerToPeer

        return parameters
    }
}

/// A framed, full-duplex message channel over a single `NWConnection`.
///
/// One connection carries video downstream and input upstream. Head-of-line blocking is
/// a non-issue in practice: a delta frame at 40 Mbit/s is ~80 KB, which clears a LAN link
/// in well under a millisecond.
final class PeerLink {

    typealias MessageHandler = (MessageType, UInt8, UInt64, Data) -> Void

    let connection: NWConnection
    /// Only ever sees types this build knows. Ping, pong and keyExchange are handled here;
    /// anything newer than this build is opened and dropped.
    var onMessage: MessageHandler?
    /// Fires once the link is usable for application messages — after the key exchange
    /// when a pairing code is in use, immediately otherwise.
    var onReady: (() -> Void)?
    var onFailure: ((Error?) -> Void)?

    /// Peer clock relative to ours, fed by v2 pongs. Audio playout and cross-device latency
    /// stats read it; a v1 peer never feeds it, so `hasEstimate` stays false.
    let clock = ClockSync()

    private let queue: DispatchQueue
    private let lock = NSLock()
    /// Held across sealing *and* handing bytes to the connection. AES-GCM nonces come from a
    /// per-direction counter, so the peer can only open messages in the order they were
    /// sealed; without this a pong sealed on the link queue could overtake a frame sealed on
    /// the encoder queue and kill the link.
    private let sendLock = NSLock()
    private var _pendingBytes = 0
    private var started = false
    private var closed = false

    // Encryption state. Nil throughout when no pairing code is set.
    private let handshake: SecureChannel.Handshake?
    private var channel: SecureChannel?
    private var outbox: [(MessageType, UInt8, Data, UInt64)] = []

    // Heartbeat, run entirely on the link's own queue.
    private var heartbeatTimer: DispatchSourceTimer?
    private var _roundTripMs: Double = 0

    /// Network round-trip time. Measured here rather than in the session layer: ping and pong
    /// are both handled on this queue, so the number reflects the link instead of how busy
    /// the other side's main thread happens to be.
    var roundTripMs: Double {
        lock.lock(); defer { lock.unlock() }
        return _roundTripMs
    }

    /// Bytes handed to the kernel that have not yet been reported as sent. The encoder
    /// uses this as its congestion signal.
    var pendingBytes: Int {
        lock.lock(); defer { lock.unlock() }
        return _pendingBytes
    }

    /// The AES-GCM key of an encrypted link, nil when no pairing code is in use (or before
    /// the key exchange lands). The UDP audio flow encrypts with it.
    var sessionKey: SymmetricKey? {
        lock.lock(); defer { lock.unlock() }
        return channel?.sessionKey
    }

    /// The peer's address as the connection actually reached it — a resolved IP even when
    /// the link was opened from a Bonjour service. Side channels (UDP audio) go here.
    var remoteHost: NWEndpoint.Host? {
        if case .hostPort(let host, _) = connection.currentPath?.remoteEndpoint { return host }
        if case .hostPort(let host, _) = connection.endpoint { return host }
        return nil
    }

    init(connection: NWConnection, queue: DispatchQueue, role: SecureChannel.Role, pairingCode: String?) {
        self.connection = connection
        self.queue = queue
        if let pairingCode, !pairingCode.isEmpty {
            self.handshake = SecureChannel.Handshake(role: role, pairingCode: pairingCode)
        } else {
            self.handshake = nil
        }
    }

    func start() {
        guard !started else { return }
        started = true

        connection.stateUpdateHandler = { [weak self] state in
            guard let self else { return }
            switch state {
            case .ready:
                self.receiveHeader()
                self.startHeartbeat()
                if let handshake = self.handshake {
                    self.transmit(.keyExchange, flags: 0, payload: handshake.message(),
                                  timestampMicros: MonotonicClock.micros())
                } else {
                    self.onReady?()
                }
            case .failed(let error):
                self.fail(error)
            case .cancelled:
                self.fail(nil)
            case .waiting(let error):
                Log.net("waiting: \(error.localizedDescription)")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func close() {
        guard !closed else { return }
        closed = true
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        connection.cancel()
    }

    /// False once the link has failed or been closed; sends are dropped from then on.
    var isOpen: Bool { !closed }

    private func startHeartbeat() {
        heartbeatTimer?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now() + 1, repeating: 1)
        timer.setEventHandler { [weak self] in
            guard let self, !self.closed else { return }
            // A ping sent in the clear while the key exchange is still in flight would reach
            // a peer that already holds the key, fail to open there, and drop the link.
            if self.handshake != nil, self.sessionKey == nil { return }
            self.transmit(.ping, flags: 0, payload: Data(), timestampMicros: MonotonicClock.micros())
        }
        timer.resume()
        heartbeatTimer = timer
    }

    private func fail(_ error: Error?) {
        guard !closed else { return }
        closed = true
        heartbeatTimer?.cancel()
        heartbeatTimer = nil
        onFailure?(error)
    }

    // MARK: Sending

    func send(_ type: MessageType, flags: UInt8 = 0, payload: Data = Data(), timestampMicros: UInt64 = MonotonicClock.micros()) {
        guard !closed else { return }

        if handshake != nil {
            lock.lock()
            if channel == nil {
                // Hold application traffic until the key exchange lands. This is a handful
                // of control messages at connect time, never video.
                outbox.append((type, flags, payload, timestampMicros))
                lock.unlock()
                return
            }
            lock.unlock()
        }
        transmit(type, flags: flags, payload: payload, timestampMicros: timestampMicros)
    }

    func sendJSON<T: Encodable>(_ type: MessageType, _ value: T) {
        guard let data = try? JSONEncoder().encode(value) else { return }
        send(type, payload: data)
    }

    /// Header + body exactly as it goes on the wire. With a channel the body is sealed and the
    /// header — sent in the clear — is authenticated as associated data, so a tampered length
    /// or frame flag fails the GCM tag rather than being trusted. The AAD is the real header,
    /// i.e. with `payloadLength` = plaintext + 16. spec/PROTOCOL.md §6.
    static func frame(_ type: MessageType, flags: UInt8, payload: Data, timestampMicros: UInt64,
                      sealingWith channel: SecureChannel?) throws -> Data {
        var body = payload
        if type != .keyExchange, let channel {
            let provisional = MessageHeader(type: type, flags: flags,
                                            payloadLength: UInt32(payload.count + 16),
                                            timestampMicros: timestampMicros).encoded()
            body = try channel.seal(payload, authenticating: provisional)
        }

        let header = MessageHeader(type: type, flags: flags,
                                   payloadLength: UInt32(body.count),
                                   timestampMicros: timestampMicros)
        var out = Data()
        out.reserveCapacity(MessageHeader.byteCount + body.count)
        out.append(header.encoded())
        out.append(body)
        return out
    }

    private func transmit(_ type: MessageType, flags: UInt8, payload: Data, timestampMicros: UInt64) {
        sendLock.lock()
        let ok = transmitLocked(type, flags: flags, payload: payload, timestampMicros: timestampMicros)
        sendLock.unlock()
        if !ok { fail(SecureChannel.Failure.decryptionFailed) }
    }

    /// Caller holds `sendLock`. Seals and enqueues in one step so wire order == counter order.
    private func transmitLocked(_ type: MessageType, flags: UInt8, payload: Data, timestampMicros: UInt64) -> Bool {
        guard let out = try? Self.frame(type, flags: flags, payload: payload,
                                        timestampMicros: timestampMicros, sealingWith: channel) else {
            return false
        }

        lock.lock(); _pendingBytes += out.count; lock.unlock()
        let sentCount = out.count

        connection.send(content: out, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.lock.lock(); self._pendingBytes -= sentCount; self.lock.unlock()
            if let error { self.fail(error) }
        })
        return true
    }

    // MARK: Receiving

    private func receiveHeader() {
        connection.receive(minimumIncompleteLength: MessageHeader.byteCount,
                           maximumLength: MessageHeader.byteCount) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.fail(error); return }
            guard let data, let header = RawMessageHeader(decoding: data) else {
                if isComplete { self.fail(nil) }
                return
            }
            if header.payloadLength == 0 {
                self.deliver(header: header, headerBytes: data, payload: Data())
                self.receiveHeader()
            } else {
                self.receivePayload(header: header, headerBytes: data)
            }
        }
    }

    private func receivePayload(header: RawMessageHeader, headerBytes: Data, accumulated: Data? = nil) {
        // Reserve the whole frame up front. A keyframe arrives in many chunks, and growing
        // the buffer per chunk turned reassembly into repeated reallocation and copying.
        var accumulated = accumulated ?? {
            var buffer = Data()
            buffer.reserveCapacity(Int(header.payloadLength))
            return buffer
        }()
        let remaining = Int(header.payloadLength) - accumulated.count
        connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.fail(error); return }
            guard let data, !data.isEmpty else {
                if isComplete { self.fail(nil) }
                return
            }
            accumulated.append(data)
            if accumulated.count < Int(header.payloadLength) {
                self.receivePayload(header: header, headerBytes: headerBytes, accumulated: accumulated)
            } else {
                self.deliver(header: header, headerBytes: headerBytes, payload: accumulated)
                self.receiveHeader()
            }
        }
    }

    private func deliver(header: RawMessageHeader, headerBytes: Data, payload: Data) {
        if header.type == .keyExchange {
            completeHandshake(with: payload)
            return
        }

        // Every other message is opened *before* it is looked at — ping, pong and types this
        // build does not know included. The sender sealed each of them with the next counter
        // value, so skipping one here would leave every later nonce off by one. (v1 answered
        // ping/pong first and desynced encrypted links at the first heartbeat.)
        var body = payload
        if let channel {
            do {
                body = try channel.open(payload, authenticating: headerBytes)
            } catch {
                fail(error)
                return
            }
        } else if handshake != nil {
            // Encrypted link, but the peer sent traffic before the key exchange.
            fail(SecureChannel.Failure.malformedHandshake)
            return
        }

        guard let type = header.type else {
            // A newer peer's message type. Ignoring it is the whole compatibility contract.
            return
        }

        // Heartbeat never reaches the session layer. Replying inline means the peer's
        // measurement is of the network, not of our main thread's queue depth.
        switch type {
        case .ping:
            // v2 pong carries our clock; a v1 peer reads past the payload and ignores it.
            transmit(.pong, flags: 0, payload: ClockSync.pongPayload(), timestampMicros: header.timestampMicros)
        case .pong:
            let now = MonotonicClock.micros()
            let sample = Double(now &- header.timestampMicros) / 1000.0
            lock.lock()
            // Light smoothing so a single scheduling hiccup does not dominate the readout.
            _roundTripMs = _roundTripMs == 0 ? sample : _roundTripMs * 0.8 + sample * 0.2
            lock.unlock()
            if let remote = ClockSync.remoteClock(fromPong: body) {
                clock.addSample(t0: header.timestampMicros, t1: remote, t2: now)
            }
        default:
            onMessage?(type, header.flags, header.timestampMicros, body)
        }
    }

    private func completeHandshake(with peerMessage: Data) {
        guard let handshake, channel == nil else { return }
        do {
            let established = try handshake.complete(with: peerMessage)

            // Install the key and flush what queued up behind it as one step under `sendLock`:
            // a `send` that sees the channel early just waits here, so nothing overtakes the
            // queued messages and the seal counter matches wire order.
            sendLock.lock()
            lock.lock()
            channel = established
            let queued = outbox
            outbox.removeAll()
            lock.unlock()
            var flushed = true
            for (type, flags, payload, timestamp) in queued where flushed {
                flushed = transmitLocked(type, flags: flags, payload: payload, timestampMicros: timestamp)
            }
            sendLock.unlock()
            guard flushed else { fail(SecureChannel.Failure.decryptionFailed); return }

            Log.net("encrypted link established")
            onReady?()
        } catch {
            Log.net("key exchange failed: \(error.localizedDescription)")
            fail(error)
        }
    }
}
