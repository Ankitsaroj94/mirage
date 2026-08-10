import Foundation
import Network

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
        tcp.noPush = true
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
    var onMessage: MessageHandler?
    /// Fires once the link is usable for application messages — after the key exchange
    /// when a pairing code is in use, immediately otherwise.
    var onReady: (() -> Void)?
    var onFailure: ((Error?) -> Void)?

    private let queue: DispatchQueue
    private let lock = NSLock()
    private var _pendingBytes = 0
    private var started = false
    private var closed = false

    // Encryption state. Nil throughout when no pairing code is set.
    private let handshake: SecureChannel.Handshake?
    private var channel: SecureChannel?
    private var outbox: [(MessageType, UInt8, Data, UInt64)] = []

    /// Bytes handed to the kernel that have not yet been reported as sent. The encoder
    /// uses this as its congestion signal.
    var pendingBytes: Int {
        lock.lock(); defer { lock.unlock() }
        return _pendingBytes
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
        connection.cancel()
    }

    private func fail(_ error: Error?) {
        guard !closed else { return }
        closed = true
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

    private func transmit(_ type: MessageType, flags: UInt8, payload: Data, timestampMicros: UInt64) {
        var body = payload

        // The header is sent in the clear but authenticated as associated data, so a
        // tampered length or frame flag fails the GCM tag rather than being trusted.
        if type != .keyExchange, let channel {
            let provisional = MessageHeader(type: type, flags: flags,
                                            payloadLength: UInt32(payload.count + 16),
                                            timestampMicros: timestampMicros).encoded()
            guard let sealed = try? channel.seal(payload, authenticating: provisional) else {
                fail(SecureChannel.Failure.decryptionFailed)
                return
            }
            body = sealed
        }

        let header = MessageHeader(type: type, flags: flags,
                                   payloadLength: UInt32(body.count),
                                   timestampMicros: timestampMicros)
        var out = header.encoded()
        out.append(body)

        lock.lock(); _pendingBytes += out.count; lock.unlock()
        let sentCount = out.count

        connection.send(content: out, completion: .contentProcessed { [weak self] error in
            guard let self else { return }
            self.lock.lock(); self._pendingBytes -= sentCount; self.lock.unlock()
            if let error { self.fail(error) }
        })
    }

    // MARK: Receiving

    private func receiveHeader() {
        connection.receive(minimumIncompleteLength: MessageHeader.byteCount,
                           maximumLength: MessageHeader.byteCount) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.fail(error); return }
            guard let data, let header = MessageHeader(decoding: data) else {
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

    private func receivePayload(header: MessageHeader, headerBytes: Data, accumulated: Data = Data()) {
        let remaining = Int(header.payloadLength) - accumulated.count
        connection.receive(minimumIncompleteLength: 1, maximumLength: remaining) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let error { self.fail(error); return }
            guard let data, !data.isEmpty else {
                if isComplete { self.fail(nil) }
                return
            }
            var buffer = accumulated
            buffer.append(data)
            if buffer.count < Int(header.payloadLength) {
                self.receivePayload(header: header, headerBytes: headerBytes, accumulated: buffer)
            } else {
                self.deliver(header: header, headerBytes: headerBytes, payload: buffer)
                self.receiveHeader()
            }
        }
    }

    private func deliver(header: MessageHeader, headerBytes: Data, payload: Data) {
        if header.type == .keyExchange {
            completeHandshake(with: payload)
            return
        }

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
        onMessage?(header.type, header.flags, header.timestampMicros, body)
    }

    private func completeHandshake(with peerMessage: Data) {
        guard let handshake, channel == nil else { return }
        do {
            let established = try handshake.complete(with: peerMessage)
            lock.lock()
            channel = established
            let queued = outbox
            outbox.removeAll()
            lock.unlock()

            onReady?()
            for (type, flags, payload, timestamp) in queued {
                transmit(type, flags: flags, payload: payload, timestampMicros: timestamp)
            }
            Log.net("encrypted link established")
        } catch {
            Log.net("key exchange failed: \(error.localizedDescription)")
            fail(error)
        }
    }
}
