// Regenerates spec/vectors.json from the real Swift wire code.
//
//   cp Tools/make-vectors.swift "$TMPDIR/main.swift" && swiftc Sources/Mirage/Core/Protocol.swift \
//     Sources/Mirage/Support/Log.swift "$TMPDIR/main.swift" -o "$TMPDIR/mv" && "$TMPDIR/mv" > spec/vectors.json
//
// The handshake section re-derives SecureChannel's key schedule with fixed private keys,
// step for step, so the Android port can be checked byte for byte without a Mac.
import Foundation
import CryptoKit

func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }
func hex<S: Sequence>(_ s: S) -> String where S.Element == UInt8 { hex(Data(s)) }

var out: [String: Any] = [:]

// Headers
let headers: [(MessageHeader, String)] = [
    (MessageHeader(type: .videoFrame, flags: FrameFlags.keyframe, payloadLength: 81_920, timestampMicros: 1_234_567_890), "videoFrame keyframe"),
    (MessageHeader(type: .frameAck, flags: 0, payloadLength: 0, timestampMicros: 42), "frameAck"),
    (MessageHeader(type: .input, flags: 0, payloadLength: 32, timestampMicros: 0xFFEE_DDCC_BBAA_9988), "input"),
]
out["headers"] = headers.map { h, name in
    ["name": name, "type": Int(h.type.rawValue), "flags": Int(h.flags), "payloadLength": Int(h.payloadLength),
     "timestampMicros": String(h.timestampMicros), "hex": hex(h.encoded())] as [String: Any]
}

// Input events
var move = InputEvent(kind: .mouseMove); move.x = 0.25; move.y = 0.75; move.deltaX = -3.5; move.deltaY = 2
var down = InputEvent(kind: .mouseDown); down.button = MouseButton.right.rawValue; down.clickCount = 2; down.x = 0.5; down.y = 0.5
var scroll = InputEvent(kind: .scroll); scroll.isPreciseScroll = true; scroll.deltaX = 0; scroll.deltaY = -12.5; scroll.x = 0.1; scroll.y = 0.9
var keyEvent = InputEvent(kind: .keyDown); keyEvent.keyCode = 0x24; keyEvent.modifiers = UInt32(CGEventFlagsCompat.command | CGEventFlagsCompat.shift)
let namedEvents: [(String, InputEvent)] = [("mouseMove", move), ("mouseDown right double", down),
                                           ("scroll precise", scroll), ("keyDown return cmd+shift", keyEvent)]
func describe(_ name: String, _ e: InputEvent) -> [String: Any] {
    var d: [String: Any] = ["name": name, "kind": Int(e.kind.rawValue), "button": Int(e.button)]
    d["clickCount"] = Int(e.clickCount)
    d["x"] = Double(e.x); d["y"] = Double(e.y)
    d["deltaX"] = Double(e.deltaX); d["deltaY"] = Double(e.deltaY)
    d["keyCode"] = Int(e.keyCode); d["modifiers"] = Int(e.modifiers)
    d["isPreciseScroll"] = e.isPreciseScroll
    d["hex"] = hex(e.encoded())
    return d
}
out["inputEvents"] = namedEvents.map { describe($0.0, $0.1) }

enum CGEventFlagsCompat { static let shift: UInt64 = 0x20000; static let command: UInt64 = 0x100000 }

// Handshake with fixed keys, mirroring SecureChannel.Handshake exactly.
let code = "4821"
let codeSecret = SymmetricKey(data: SHA256.hash(data: Data(("mirage.pairing.v1:" + code).utf8)))
let iPriv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map { UInt8($0) }))
let rPriv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: Data((0..<32).map { UInt8(0xA0 &+ $0) }))
func message(_ k: Curve25519.KeyAgreement.PrivateKey) -> Data {
    let pub = k.publicKey.rawRepresentation
    return pub + Data(HMAC<SHA256>.authenticationCode(for: pub, using: codeSecret))
}
let shared = try! iPriv.sharedSecretFromKeyAgreement(with: rPriv.publicKey)
let a = iPriv.publicKey.rawRepresentation, b = rPriv.publicKey.rawRepresentation
let salt = a.lexicographicallyPrecedes(b) ? a + b : b + a
let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: salt,
                                         sharedInfo: Data("mirage.session.v1".utf8), outputByteCount: 32)
let sharedRaw = shared.withUnsafeBytes { Data($0) }
let keyRaw = key.withUnsafeBytes { Data($0) }

func nonce(_ direction: UInt8, _ counter: UInt64) -> Data {
    var n = Data(count: 12); n[0] = direction
    withUnsafeBytes(of: counter.littleEndian) { for i in 0..<8 { n[4 + i] = $0[i] } }
    return n
}
func seal(_ plain: Data, _ aad: Data, _ dir: UInt8, _ ctr: UInt64) -> Data {
    let box = try! AES.GCM.seal(plain, using: key, nonce: try! AES.GCM.Nonce(data: nonce(dir, ctr)), authenticating: aad)
    return box.ciphertext + box.tag
}
// What PeerLink.transmit does: AAD is the header with payloadLength = plaintext + 16.
func sealedMessage(_ type: MessageType, _ plain: Data, ts: UInt64, dir: UInt8, ctr: UInt64) -> [String: Any] {
    let aad = MessageHeader(type: type, flags: 0, payloadLength: UInt32(plain.count + 16), timestampMicros: ts).encoded()
    let body = seal(plain, aad, dir, ctr)
    return ["type": Int(type.rawValue), "timestampMicros": String(ts), "counter": Int(ctr), "direction": Int(dir),
            "plaintextHex": hex(plain), "aadHex": hex(aad), "wireHex": hex(aad + body)]
}
out["handshake"] = [
    "pairingCode": code,
    "codeSecretHex": hex(codeSecret.withUnsafeBytes { Data($0) }),
    "initiatorPrivateHex": hex(iPriv.rawRepresentation),
    "responderPrivateHex": hex(rPriv.rawRepresentation),
    "initiatorMessageHex": hex(message(iPriv)),
    "responderMessageHex": hex(message(rPriv)),
    "sharedSecretHex": hex(sharedRaw),
    "saltHex": hex(salt),
    "sessionKeyHex": hex(keyRaw),
    "messages": [
        sealedMessage(.hello, Data("{\"a\":1}".utf8), ts: 1000, dir: 0, ctr: 0),
        sealedMessage(.ping, Data(), ts: 2000, dir: 0, ctr: 1),
        sealedMessage(.helloAck, Data("ok".utf8), ts: 3000, dir: 1, ctr: 0),
    ],
    "audioPacket": { () -> [String: Any] in
        // UDP audio: 16-byte header is the AAD, nonce direction byte 2, counter = seq.
        var hdr = Data([1, 1, 0, 0]); hdr.appendLE(UInt32(7)); hdr.appendLE(UInt64(5_000_000))
        let pcm = Data((0..<16).map { UInt8($0) })
        return ["seq": 7, "hostTimeMicros": "5000000", "headerHex": hex(hdr), "pcmHex": hex(pcm),
                "wireHex": hex(hdr + seal(pcm, hdr, 2, 7))]
    }(),
]

// JSON exactly as Swift's JSONEncoder emits it (Data -> base64).
let params = VideoParameterSets(codec: .hevc, width: 2304, height: 1440,
                                parameterSets: [Data([0x40, 0x01, 0x0c]), Data([0x42, 0x01]), Data([0x44, 0x01])],
                                nalLengthSize: 4)
let enc = JSONEncoder(); enc.outputFormatting = [.sortedKeys]
out["json"] = ["videoParameterSets": String(data: try! enc.encode(params), encoding: .utf8)!]

let data = try! JSONSerialization.data(withJSONObject: out, options: [.prettyPrinted, .sortedKeys])
print(String(data: data, encoding: .utf8)!)
