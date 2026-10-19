// Loopback exercise of the wire protocol: listener + connection in one process,
// with and without a pairing code, plus round-trip checks on the packed structs.
//
//   --psk          encrypt the link (X25519 + AES-GCM)
//   --wrong-code   the two sides use different codes; the link must be refused
//   --soak         hold the link open through several heartbeats (both directions), with
//                  messages sealed concurrently from several threads, then check it still
//                  delivers. v1 desynced encrypted links at the first heartbeat.
import Foundation
import Network

let usePSK = CommandLine.arguments.contains("--psk")
let mismatch = CommandLine.arguments.contains("--wrong-code")
let soak = CommandLine.arguments.contains("--soak")
let serverCode: String? = usePSK ? "4821" : nil
let clientCode: String? = usePSK ? (mismatch ? "9999" : "4821") : nil

// --- struct round-trips -------------------------------------------------------
var ev = InputEvent(kind: .scroll)
ev.x = 0.25; ev.y = 0.75; ev.deltaX = -3.5; ev.deltaY = 12.25
ev.keyCode = 0xBEEF; ev.modifiers = 0x0012_3456; ev.button = 2; ev.clickCount = 3
ev.isPreciseScroll = true
let evBytes = ev.encoded()
precondition(evBytes.count == InputEvent.byteCount, "InputEvent must be exactly 32 bytes, got \(evBytes.count)")
guard let back = InputEvent(decoding: evBytes) else { fatalError("InputEvent decode failed") }
precondition(back.x == ev.x && back.y == ev.y && back.deltaX == ev.deltaX && back.deltaY == ev.deltaY,
             "float fields did not survive the round trip")
precondition(back.keyCode == ev.keyCode && back.modifiers == ev.modifiers && back.button == ev.button
             && back.clickCount == ev.clickCount && back.isPreciseScroll, "int/bool fields corrupted")
print("✓ InputEvent round-trip (32 bytes)")

let hdr = MessageHeader(type: .videoFrame, flags: FrameFlags.keyframe, payloadLength: 0xDEADBEEF, timestampMicros: 0x0102_0304_0506_0708)
let hdrBytes = hdr.encoded()
precondition(hdrBytes.count == MessageHeader.byteCount, "header must be 16 bytes")
guard let hdrBack = MessageHeader(decoding: hdrBytes) else { fatalError("header decode failed") }
precondition(hdrBack.type == .videoFrame && hdrBack.flags == FrameFlags.keyframe
             && hdrBack.payloadLength == 0xDEADBEEF && hdrBack.timestampMicros == 0x0102_0304_0506_0708,
             "header round-trip mismatch")
// Decoding from a mid-buffer slice must respect startIndex, not absolute offsets.
var padded = Data([0xAA, 0xBB, 0xCC]); padded.append(hdrBytes)
guard let sliced = MessageHeader(decoding: padded[3...]) else { fatalError("sliced header decode failed") }
precondition(sliced.payloadLength == 0xDEADBEEF, "Data slice offsets are wrong")
guard let rawSliced = RawMessageHeader(decoding: padded[3...]), rawSliced.type == .videoFrame else {
    fatalError("sliced raw header decode failed")
}
print("✓ MessageHeader round-trip, including non-zero-based slices")

// --- live loopback ------------------------------------------------------------
let done = DispatchSemaphore(value: 0)
var received: [(MessageType, Int, Bool)] = []
var soakReceived = 0
var serverError: Error?
var clientError: Error?
let lock = NSLock()

// Payload sizes chosen to straddle a single TCP segment: the receive loop must
// reassemble a message that arrives in several chunks.
let payloads: [(MessageType, Int)] = [(.hello, 12), (.videoParams, 900), (.videoFrame, 400_000), (.videoFrame, 37), (.bye, 0)]

// Soak: small messages from four threads at once over ~3.5 s while both sides' heartbeats
// seal pings and pongs on the link queues — every one of them takes a nonce.
let soakThreads = 4
let soakPerThread = 70
let soakSeconds = 3.5

let params = LinkParameters.make(allowPeerToPeer: false)
let listener = try! NWListener(using: params, on: 0)
var serverLink: PeerLink?
var clientLink: PeerLink?

listener.newConnectionHandler = { conn in
    let link = PeerLink(connection: conn, queue: .global(), role: .responder, pairingCode: serverCode)
    serverLink = link
    link.onMessage = { type, flags, _, payload in
        lock.lock()
        if type == .stats {
            soakReceived += 1
            lock.unlock()
            return
        }
        received.append((type, payload.count, flags & FrameFlags.keyframe != 0))
        let n = received.count
        lock.unlock()
        if n == payloads.count { done.signal() }
    }
    link.onFailure = { err in
        lock.lock(); serverError = err; lock.unlock()
        done.signal()
    }
    link.start()
}

func sendPayloads(_ client: PeerLink) {
    for (type, size) in payloads {
        var body = Data(count: size)
        for i in stride(from: 0, to: size, by: 997) { body[i] = UInt8(i % 251) }
        client.send(type, flags: size > 100_000 ? FrameFlags.keyframe : 0, payload: body)
    }
}

listener.stateUpdateHandler = { state in
    guard case .ready = state, let port = listener.port else { return }
    let conn = NWConnection(host: "127.0.0.1", port: port, using: LinkParameters.make(allowPeerToPeer: false))
    let client = PeerLink(connection: conn, queue: .global(), role: .initiator, pairingCode: clientCode)
    clientLink = client
    client.onReady = {
        guard soak else { sendPayloads(client); return }
        let interval = soakSeconds / Double(soakPerThread)
        let group = DispatchGroup()
        for t in 0..<soakThreads {
            DispatchQueue.global().async(group: group) {
                for i in 0..<soakPerThread {
                    client.send(.stats, payload: Data("{\"t\":\(t),\"i\":\(i)}".utf8))
                    Thread.sleep(forTimeInterval: interval)
                }
            }
        }
        group.notify(queue: .global()) { sendPayloads(client) }
    }
    client.onFailure = { err in
        lock.lock(); clientError = err; lock.unlock()
        done.signal()
    }
    client.start()
}
listener.start(queue: .global())

// A deliberately wrong pairing code must be rejected, not silently accepted.
if mismatch {
    _ = done.wait(timeout: .now() + 10)
    lock.lock(); let sErr = serverError; let cErr = clientError; let got = received; lock.unlock()
    precondition(got.isEmpty, "messages got through despite a wrong pairing code: \(got)")
    guard sErr != nil || cErr != nil else { print("✗ wrong pairing code was not rejected"); exit(1) }
    let reported = (sErr ?? cErr)!
    print("✓ wrong pairing code rejected: \(reported.localizedDescription)")
    exit(0)
}

if done.wait(timeout: .now() + (soak ? 30 : 20)) == .timedOut {
    lock.lock(); let got = received; lock.unlock()
    print("✗ TIMEOUT — received \(got.count)/\(payloads.count): \(got)")
    exit(1)
}
lock.lock(); let got = received; let sErr = serverError; let cErr = clientError; let soakGot = soakReceived; lock.unlock()
if let failure = sErr ?? cErr {
    print("✗ link failed after \(got.count)/\(payloads.count) messages: \(failure.localizedDescription)")
    exit(1)
}
for (i, expected) in payloads.enumerated() {
    precondition(got[i].0 == expected.0, "message \(i): expected \(expected.0), got \(got[i].0)")
    precondition(got[i].1 == expected.1, "message \(i): expected \(expected.1) bytes, got \(got[i].1)")
}
precondition(got[2].2 == true && got[3].2 == false, "keyframe flag did not survive")

if soak {
    precondition(soakGot == soakThreads * soakPerThread,
                 "soak: \(soakGot)/\(soakThreads * soakPerThread) concurrent messages arrived")
    guard let s = serverLink, let c = clientLink else { fatalError("links missing") }
    // Heartbeats ran both ways: each side measured RTT and, from the v2 pong payload, the
    // other side's clock. Same process, same clock, so the offset must be ~0.
    for (name, link) in [("host", c), ("client", s)] {
        precondition(link.roundTripMs > 0, "\(name): no pong ever came back")
        precondition(link.clock.hasEstimate, "\(name): v2 pong clock payload never arrived")
        precondition(abs(link.clock.offsetMicros) < 5_000, "\(name): same-clock offset is \(link.clock.offsetMicros) µs")
        precondition(link.isOpen, "\(name): link closed during the soak")
    }
    if usePSK { precondition(s.sessionKey != nil && c.sessionKey != nil, "sessionKey not exposed") }
    print("✓ soak \(usePSK ? "encrypted" : "plaintext"): link alive through \(soakSeconds)s of heartbeats, "
          + "\(soakGot) messages sealed from \(soakThreads) threads, then \(payloads.count) more delivered; "
          + String(format: "rtt %.2f ms, clock offset %d µs", c.roundTripMs, c.clock.offsetMicros))
    exit(0)
}
print("✓ framed loopback \(usePSK ? "encrypted (X25519 + AES-GCM)" : "plaintext"): \(payloads.count) messages, largest \(payloads.map(\.1).max()!) bytes, order and flags intact")
_ = serverLink
exit(0)
