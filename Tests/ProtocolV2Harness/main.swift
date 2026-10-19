// Protocol v2 checks that need no permissions and no second device:
//
//   1. spec/vectors.json — headers, InputEvents, the fixed-key handshake, sealed messages
//      (through PeerLink's real framing code) and the audio packet are reproduced byte for
//      byte. The Android test suite checks the same file, so both ends agree on the wire.
//   2. JSON compatibility — v1 hellos still decode, nil v2 fields stay off the wire.
//   3. ClockSync — the min-RTT filter and EWMA from spec §8, on synthetic samples.
//   4. Discovery — TXT parsing with and without the v2 keys, manual endpoints.
//   5. Live links that receive message types this build does not know (a future peer) and
//      a v1-style empty pong, in the clear and encrypted: they must be skipped, the cipher
//      counter must stay in step, and later messages must still arrive.
//
//   usage: protocolv2test [path/to/vectors.json]
import Foundation
import Network
import CryptoKit

func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func unhex(_ s: String) -> Data {
    var out = Data(capacity: s.count / 2)
    var i = s.startIndex
    while i < s.endIndex {
        let j = s.index(i, offsetBy: 2)
        out.append(UInt8(s[i..<j], radix: 16)!)
        i = j
    }
    return out
}
func check(_ condition: Bool, _ message: @autoclosure () -> String) {
    if !condition { print("✗ \(message())"); exit(1) }
}

// MARK: - 1. vectors.json

let vectorsPath = CommandLine.arguments.dropFirst().first ?? "spec/vectors.json"
guard let vectorsData = FileManager.default.contents(atPath: vectorsPath),
      let vectors = try? JSONSerialization.jsonObject(with: vectorsData) as? [String: Any] else {
    print("✗ cannot read \(vectorsPath)"); exit(1)
}

// Headers
let headerVectors = vectors["headers"] as! [[String: Any]]
for v in headerVectors {
    let name = v["name"] as! String
    let type = MessageType(rawValue: UInt8(v["type"] as! Int))!
    let ts = UInt64(v["timestampMicros"] as! String)!
    let header = MessageHeader(type: type, flags: UInt8(v["flags"] as! Int),
                               payloadLength: UInt32(v["payloadLength"] as! Int), timestampMicros: ts)
    check(hex(header.encoded()) == v["hex"] as! String, "header \(name): \(hex(header.encoded())) != \(v["hex"]!)")
    guard let back = MessageHeader(decoding: unhex(v["hex"] as! String)) else { check(false, "header \(name) decode"); fatalError() }
    check(back.type == type && back.timestampMicros == ts && back.payloadLength == header.payloadLength
          && back.flags == header.flags, "header \(name) did not decode back")
}
print("✓ vectors: \(headerVectors.count) headers")

// InputEvents
let inputVectors = vectors["inputEvents"] as! [[String: Any]]
for v in inputVectors {
    let name = v["name"] as! String
    var e = InputEvent(kind: InputKind(rawValue: UInt8(v["kind"] as! Int))!)
    e.button = UInt8(v["button"] as! Int)
    e.clickCount = UInt8(v["clickCount"] as! Int)
    e.x = Float(v["x"] as! Double); e.y = Float(v["y"] as! Double)
    e.deltaX = Float(v["deltaX"] as! Double); e.deltaY = Float(v["deltaY"] as! Double)
    e.keyCode = UInt16(v["keyCode"] as! Int)
    e.modifiers = UInt32(v["modifiers"] as! Int)
    e.isPreciseScroll = v["isPreciseScroll"] as! Bool
    check(hex(e.encoded()) == v["hex"] as! String, "input \(name): \(hex(e.encoded())) != \(v["hex"]!)")
    guard let d = InputEvent(decoding: unhex(v["hex"] as! String)) else { check(false, "input \(name) decode"); fatalError() }
    check(d.kind == e.kind && d.button == e.button && d.clickCount == e.clickCount && d.x == e.x && d.y == e.y
          && d.deltaX == e.deltaX && d.deltaY == e.deltaY && d.keyCode == e.keyCode && d.modifiers == e.modifiers
          && d.isPreciseScroll == e.isPreciseScroll, "input \(name) did not decode back")
}
print("✓ vectors: \(inputVectors.count) InputEvents")

// Handshake with fixed keys, through the real SecureChannel.
let hs = vectors["handshake"] as! [String: Any]
let code = hs["pairingCode"] as! String
let initiatorKey = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: unhex(hs["initiatorPrivateHex"] as! String))
let responderKey = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: unhex(hs["responderPrivateHex"] as! String))
let initiator = SecureChannel.Handshake(role: .initiator, pairingCode: code, privateKey: initiatorKey)
let responder = SecureChannel.Handshake(role: .responder, pairingCode: code, privateKey: responderKey)
check(hex(initiator.codeSecret.withUnsafeBytes { Data($0) }) == hs["codeSecretHex"] as! String, "codeSecret")
check(hex(initiator.message()) == hs["initiatorMessageHex"] as! String, "initiator keyExchange message")
check(hex(responder.message()) == hs["responderMessageHex"] as! String, "responder keyExchange message")
let initiatorChannel = try! initiator.complete(with: responder.message())
let responderChannel = try! responder.complete(with: initiator.message())
for channel in [initiatorChannel, responderChannel] {
    check(hex(channel.sessionKey.withUnsafeBytes { Data($0) }) == hs["sessionKeyHex"] as! String, "sessionKey")
}
print("✓ vectors: fixed-key handshake → keyExchange messages and session key")

// Sealed messages, framed by PeerLink.frame (what transmit sends), in counter order.
let messageVectors = hs["messages"] as! [[String: Any]]
var expectedCounter: [Int: Int] = [0: 0, 1: 0]
for v in messageVectors {
    let direction = v["direction"] as! Int
    check(v["counter"] as! Int == expectedCounter[direction]!, "vector messages out of counter order")
    expectedCounter[direction]! += 1
    let type = MessageType(rawValue: UInt8(v["type"] as! Int))!
    let plain = unhex(v["plaintextHex"] as! String)
    let ts = UInt64(v["timestampMicros"] as! String)!
    let sender = direction == 0 ? initiatorChannel : responderChannel
    let receiver = direction == 0 ? responderChannel : initiatorChannel
    let wire = try! PeerLink.frame(type, flags: 0, payload: plain, timestampMicros: ts, sealingWith: sender)
    check(hex(wire) == v["wireHex"] as! String, "sealed \(type): \(hex(wire)) != \(v["wireHex"]!)")
    check(hex(wire.prefix(16)) == v["aadHex"] as! String, "aad \(type)")
    let opened = try! receiver.open(wire.dropFirst(16), authenticating: wire.prefix(16))
    check(opened == plain, "\(type) did not open on the other side")
}
print("✓ vectors: \(messageVectors.count) sealed messages reproduced and opened (incl. ping)")

// Audio: the session key with nonce direction byte 2, counter = seq.
let audio = hs["audioPacket"] as! [String: Any]
var audioNonce = Data([2, 0, 0, 0]); audioNonce.appendLE(UInt64(audio["seq"] as! Int))
let audioHeader = unhex(audio["headerHex"] as! String)
let audioBox = try! AES.GCM.seal(unhex(audio["pcmHex"] as! String), using: initiatorChannel.sessionKey,
                                 nonce: try! AES.GCM.Nonce(data: audioNonce), authenticating: audioHeader)
check(hex(audioHeader + audioBox.ciphertext + audioBox.tag) == audio["wireHex"] as! String, "audio packet")
print("✓ vectors: audio packet sealed with PeerLink's session key")

// JSON as Swift emits it (Data → base64, sorted keys).
let json = vectors["json"] as! [String: String]
let params = VideoParameterSets(codec: .hevc, width: 2304, height: 1440,
                                parameterSets: [Data([0x40, 0x01, 0x0c]), Data([0x42, 0x01]), Data([0x44, 0x01])],
                                nalLengthSize: 4)
let sortedEncoder = JSONEncoder(); sortedEncoder.outputFormatting = [.sortedKeys]
check(String(data: try! sortedEncoder.encode(params), encoding: .utf8) == json["videoParameterSets"], "VideoParameterSets JSON")
let paramsBack = try! JSONDecoder().decode(VideoParameterSets.self, from: Data(json["videoParameterSets"]!.utf8))
check(paramsBack.parameterSets == params.parameterSets && paramsBack.width == 2304, "VideoParameterSets decode")
print("✓ vectors: VideoParameterSets JSON")

// MARK: - 2. JSON compatibility

let v1Hello = #"{"protocolVersion":1,"deviceName":"MacBook","pixelWidth":2940,"pixelHeight":1912,"pointWidth":1470,"pointHeight":956,"refreshHz":120,"supportsHEVC":true,"supportsH264":true,"wantsInputForwarding":true}"#
let decodedV1 = try! JSONDecoder().decode(ClientHello.self, from: Data(v1Hello.utf8))
check(decodedV1.protocolVersion == 1 && decodedV1.platform == nil && decodedV1.isMac && !decodedV1.speaksV2
      && decodedV1.supportsAudio == nil && decodedV1.audioUDPPort == nil, "v1 hello decode")

let androidHello = #"{"protocolVersion":2,"deviceName":"Galaxy Tab S9 FE","pixelWidth":2304,"pixelHeight":1440,"pointWidth":1152,"pointHeight":720,"refreshHz":90,"supportsHEVC":true,"supportsH264":true,"wantsInputForwarding":true,"platform":"android","physicalDPI":249.0,"scalePreference":"crisp","supportsAudio":true,"audioUDPPort":50123,"orientation":"landscape","someFutureKey":7}"#
let decodedV2 = try! JSONDecoder().decode(ClientHello.self, from: Data(androidHello.utf8))
check(decodedV2.speaksV2 && !decodedV2.isMac && decodedV2.physicalDPI == 249 && decodedV2.audioUDPPort == 50123
      && decodedV2.supportsAudio == true && decodedV2.scalePreference == "crisp", "v2 hello decode (unknown keys ignored)")

let macHello = ClientHello(deviceName: "Mac", pixelWidth: 2, pixelHeight: 2, pointWidth: 1, pointHeight: 1,
                           refreshHz: 60, supportsHEVC: true, supportsH264: true, wantsInputForwarding: true)
check(macHello.protocolVersion == 2, "currentVersion is 2")
let macJSON = String(data: try! JSONEncoder().encode(macHello), encoding: .utf8)!
check(!macJSON.contains("platform") && !macJSON.contains("audioUDPPort"), "nil v2 fields must be omitted: \(macJSON)")
let legacy = decodedV2.downgradedToV1
check(legacy.protocolVersion == 1 && legacy.platform == nil && legacy.audioUDPPort == nil && legacy.pixelWidth == 2304,
      "downgradedToV1")

let v1Ack = #"{"accepted":false,"reason":"Version mismatch: the other Mac is running a different build of Mirage.","hostName":"Mac","codec":"h264","pixelWidth":0,"pixelHeight":0,"pointWidth":0,"pointHeight":0,"fps":0,"bitrateKbps":0,"isHiDPI":false}"#
let decodedAck = try! JSONDecoder().decode(HostHelloAck.self, from: Data(v1Ack.utf8))
check(decodedAck.isFromV1Host && decodedAck.audioAvailable == nil, "v1 helloAck decode")

let audioConfigJSON = try! JSONDecoder().decode(AudioConfig.self, from: Data(#"{"sampleRate":48000,"channels":2,"format":"s16le","packetFrames":240,"playoutDelayMs":100,"encrypted":false}"#.utf8))
check(audioConfigJSON == AudioConfig(), "AudioConfig defaults match spec §3")

// Unknown types: strict decode refuses, raw decode keeps the byte.
var unknownHeader = MessageHeader(type: .ping, payloadLength: 5, timestampMicros: 9).encoded()
unknownHeader[0] = 0x99
check(MessageHeader(decoding: unknownHeader) == nil, "strict header must refuse an unknown type")
let rawUnknown = RawMessageHeader(decoding: unknownHeader)
check(rawUnknown?.rawType == 0x99 && rawUnknown?.type == nil && rawUnknown?.payloadLength == 5, "raw header keeps unknown type")
print("✓ JSON: v1 hello/ack decode, v2 fields optional and omitted when nil; unknown header types kept raw")

// MARK: - 3. ClockSync

do {
    let clock = ClockSync()
    check(!clock.hasEstimate && clock.offsetMicros == 0, "fresh clock")
    let skew: UInt64 = 1_000_000_000          // remote clock runs 1000 s ahead
    // Symmetric 200 µs round trip → exact offset.
    clock.addSample(t0: 10_000, t1: 10_100 + skew, t2: 10_200)
    check(clock.hasEstimate && clock.offsetMicros == Int64(skew) && clock.bestRoundTripMicros == 200, "first sample")
    // Slow, lopsided sample: would be off by 2.4 ms, must be ignored.
    clock.addSample(t0: 20_000, t1: 20_050 + skew, t2: 25_000)
    check(clock.offsetMicros == Int64(skew), "a high-RTT sample moved the estimate to \(clock.offsetMicros)")
    // Equal minimum RTT with a 1000 µs different offset: EWMA moves 10 % of the way.
    clock.addSample(t0: 30_000, t1: 30_100 + skew + 1_000, t2: 30_200)
    check(clock.offsetMicros == Int64(skew) + 100 && clock.bestRoundTripMicros == 200, "EWMA step: \(clock.offsetMicros)")
    // A much tighter sample (100 µs vs 200 µs) replaces the estimate outright: the first pong
    // often queued behind a keyframe, and easing out of that at 10 % per second took minutes.
    clock.addSample(t0: 40_000, t1: 40_050 + skew + 3_000, t2: 40_100)
    check(clock.offsetMicros == Int64(skew) + 3_000 && clock.bestRoundTripMicros == 100, "tighter sample jump: \(clock.offsetMicros)")
    // 10 s later the old minimum has aged out, so a 300 µs sample is the window's best and is
    // eased in: 3000 + 10 % of (100 − 3000) = 2710.
    clock.addSample(t0: 10_100_000, t1: 10_100_150 + skew + 100, t2: 10_100_300)
    check(clock.offsetMicros == Int64(skew) + 2_710 && clock.bestRoundTripMicros == 300, "window expiry: \(clock.offsetMicros)")
    check(clock.remoteToLocal(5_000 + skew + 2_710) == 5_000 && clock.localToRemote(5_000) == 5_000 + skew + 2_710, "conversions")
    // Negative offset (remote behind) and a pong for a ping "from the future" (ignored).
    let behind = ClockSync()
    behind.addSample(t0: 5_000_000, t1: 1_000, t2: 5_000_100)
    check(behind.offsetMicros == 1_000 - 5_000_050, "negative offset: \(behind.offsetMicros)")
    behind.addSample(t0: 9_000_000, t1: 1, t2: 8_000_000)
    check(behind.offsetMicros == 1_000 - 5_000_050, "rtt < 0 must be ignored")

    check(ClockSync.remoteClock(fromPong: ClockSync.pongPayload(now: 0x0102_0304_0506_0708)) == 0x0102_0304_0506_0708,
          "pong payload round trip")
    check(hex(ClockSync.pongPayload(now: 0x0102_0304_0506_0708)) == "0807060504030201", "pong payload is LE")
    check(ClockSync.remoteClock(fromPong: Data()) == nil, "v1 empty pong")
}
print("✓ ClockSync: exact offset, high-RTT rejection, EWMA on new minima, 10 s window, negative offsets")

// MARK: - 4. Discovery

do {
    let ep = NWEndpoint.hostPort(host: "10.0.0.2", port: 7000)
    let v1 = DisplayBrowser.parse(name: "Old Mac", endpoint: ep,
                                  txt: ["px": "2880x1800", "pt": "1440x900", "hz": "60", "lock": "1", "v": "1"])
    check(v1.platform == "macos" && v1.physicalDPI == 0 && !v1.supportsAudio && v1.requiresPairingCode
          && v1.pixelWidth == 2880 && v1.protocolVersion == 1, "v1 TXT")
    let tab = DisplayBrowser.parse(name: "Tab", endpoint: ep,
                                   txt: ["px": "2304x1440", "pt": "1152x720", "hz": "90", "lock": "0", "v": "2",
                                         "os": "android", "dpi": "249", "audio": "1"])
    check(tab.isAndroid && tab.physicalDPI == 249 && tab.supportsAudio && tab.refreshHz == 90 && tab.protocolVersion == 2,
          "v2 TXT")
    guard let manual = DiscoveredDisplay.manual(host: " 192.168.1.20 ", port: 51234) else { check(false, "manual"); fatalError() }
    check(manual.isManual && manual.name == "192.168.1.20:51234"
          && manual.endpoint == .hostPort(host: "192.168.1.20", port: 51234), "manual endpoint: \(manual.endpoint)")
    check(DiscoveredDisplay.manual(host: "", port: 1) == nil && DiscoveredDisplay.manual(host: "x", port: 0) == nil,
          "manual rejects empty host / port 0")
}
print("✓ Discovery: v1/v2 TXT parsing, manual host:port endpoints")

// MARK: - 5. Live: unknown types and v1 pongs on a real link

/// A peer written by hand — what a newer (or older) build would put on the wire.
func rawFrame(_ rawType: UInt8, _ payload: Data, sealedWith channel: SecureChannel?) -> Data {
    func header(_ length: Int) -> Data {
        var h = Data([rawType, 0, 0, 0]); h.appendLE(UInt32(length)); h.appendLE(UInt64(1234)); return h
    }
    guard let channel else { return header(payload.count) + payload }
    let aad = header(payload.count + 16)
    return aad + (try! channel.seal(payload, authenticating: aad))
}

func runUnknownTypeLink(encrypted: Bool) -> Bool {
    let code: String? = encrypted ? "4821" : nil
    let delivered = DispatchSemaphore(value: 0)
    let lock = NSLock()
    var got: [MessageType] = []
    var failure: Error?
    var server: PeerLink?

    let listener = try! NWListener(using: LinkParameters.make(allowPeerToPeer: false), on: 0)
    listener.newConnectionHandler = { conn in
        let link = PeerLink(connection: conn, queue: .global(), role: .responder, pairingCode: code)
        server = link
        link.onMessage = { type, _, _, _ in
            lock.lock(); got.append(type); let n = got.count; lock.unlock()
            if n == 3 { delivered.signal() }
        }
        link.onFailure = { error in
            lock.lock(); failure = error; lock.unlock()
            delivered.signal()
        }
        link.start()
    }

    let ready = DispatchSemaphore(value: 0)
    listener.stateUpdateHandler = { if case .ready = $0 { ready.signal() } }
    listener.start(queue: .global())
    _ = ready.wait(timeout: .now() + 5)

    let conn = NWConnection(host: "127.0.0.1", port: listener.port!, using: LinkParameters.make(allowPeerToPeer: false))
    let connReady = DispatchSemaphore(value: 0)
    conn.stateUpdateHandler = { if case .ready = $0 { connReady.signal() } }
    conn.start(queue: .global())
    guard connReady.wait(timeout: .now() + 5) == .success else { print("  raw peer could not connect"); return false }

    var channel: SecureChannel?
    if let code {
        let handshake = SecureChannel.Handshake(role: .initiator, pairingCode: code)
        conn.send(content: rawFrame(MessageType.keyExchange.rawValue, handshake.message(), sealedWith: nil),
                  completion: .idempotent)
        let gotKey = DispatchSemaphore(value: 0)
        var peerKey: Data?
        conn.receive(minimumIncompleteLength: 80, maximumLength: 80) { data, _, _, _ in peerKey = data; gotKey.signal() }
        _ = gotKey.wait(timeout: .now() + 5)
        guard let peerKey, peerKey.count == 80, peerKey.first == MessageType.keyExchange.rawValue,
              let established = try? handshake.complete(with: peerKey.dropFirst(16)) else {
            print("  raw peer key exchange failed"); return false
        }
        channel = established
    }

    // Each of these takes a nonce on an encrypted link, known type or not.
    var stream = Data()
    stream += rawFrame(0x99, Data("from the future".utf8), sealedWith: channel)                 // unknown, with payload
    stream += rawFrame(MessageType.hello.rawValue, Data("{}".utf8), sealedWith: channel)
    stream += rawFrame(MessageType.pong.rawValue, Data(), sealedWith: channel)                   // v1 pong: empty
    stream += rawFrame(0x7E, Data(), sealedWith: channel)                                        // unknown, empty
    stream += rawFrame(MessageType.stats.rawValue, Data("{}".utf8), sealedWith: channel)
    stream += rawFrame(MessageType.ping.rawValue, Data(), sealedWith: channel)                   // must be answered
    stream += rawFrame(0xFF, Data(count: 70_000), sealedWith: channel)                           // big unknown
    stream += rawFrame(MessageType.requestKeyframe.rawValue, Data(), sealedWith: channel)
    conn.send(content: stream, completion: .idempotent)

    let finished = delivered.wait(timeout: .now() + 10) == .success
    // The server's pong to our ping must open with the next responder counter (0) — proof its
    // send side stayed in step too.
    let pongCheck = DispatchSemaphore(value: 0)
    var pongOK = false
    conn.receive(minimumIncompleteLength: 16, maximumLength: 16) { header, _, _, _ in
        guard let header, let h = RawMessageHeader(decoding: header), h.type == .pong else { pongCheck.signal(); return }
        conn.receive(minimumIncompleteLength: Int(h.payloadLength), maximumLength: Int(h.payloadLength)) { body, _, _, _ in
            if let body {
                let opened = channel.map { try? $0.open(body, authenticating: header) } ?? body
                pongOK = opened.flatMap(ClockSync.remoteClock(fromPong:)) != nil
            }
            pongCheck.signal()
        }
    }
    _ = pongCheck.wait(timeout: .now() + 5)

    lock.lock(); let types = got; let error = failure; lock.unlock()
    conn.cancel(); server?.close(); listener.cancel()
    guard finished, error == nil, types == [.hello, .stats, .requestKeyframe] else {
        print("  delivered \(types), failure: \(error?.localizedDescription ?? "none")")
        return false
    }
    guard pongOK else { print("  no v2 pong with a clock payload came back"); return false }
    return true
}

check(runUnknownTypeLink(encrypted: false), "plaintext link did not survive unknown types")
check(runUnknownTypeLink(encrypted: true), "encrypted link did not survive unknown types")
print("✓ live: unknown types (empty, small, 70 KB) and a v1 empty pong skipped; later messages delivered; pong carries clock — plaintext and encrypted")

print("✓ protocol v2: all checks passed")
exit(0)
