import Foundation

// MARK: - Wire format
//
// Every message is a fixed 16-byte little-endian header followed by `payloadLength`
// bytes of payload:
//
//   offset  size  field
//   0       1     type          MessageType
//   1       1     flags         type-specific bit flags
//   2       2     reserved      must be 0
//   4       4     payloadLength
//   8       8     timestampMicros   host monotonic clock, used for latency stats
//
// Control messages carry JSON. Video and input carry packed binary, because those
// are on the hot path.

enum MessageType: UInt8 {
    case hello           = 0x01   // client -> host, JSON ClientHello
    case helloAck        = 0x02   // host -> client, JSON HostHelloAck
    case videoParams     = 0x10   // host -> client, JSON VideoParameterSets
    case videoFrame      = 0x11   // host -> client, AVCC-framed NAL units
    case reconfigure     = 0x12   // client -> host, JSON ClientHello (v2: rotation / scale change)
    case input           = 0x20   // client -> host, packed InputEvent
    case stats           = 0x30   // client -> host, JSON ClientStats
    case requestKeyframe = 0x31   // client -> host, empty
    case frameAck        = 0x32   // client -> host, empty; echoes the frame's timestamp
    case ping            = 0x40
    case pong            = 0x41
    case bye             = 0x50
    case keyExchange     = 0x60   // either direction, cleartext X25519 public key + HMAC
    case audioConfig     = 0x70   // host -> client, JSON AudioConfig (v2)
    case audioStop       = 0x71   // host -> client, empty (v2)
}

enum FrameFlags {
    static let keyframe: UInt8 = 1 << 0
}

struct MessageHeader {
    static let byteCount = 16

    var type: MessageType
    var flags: UInt8
    var payloadLength: UInt32
    var timestampMicros: UInt64

    func encoded() -> Data {
        var data = Data(capacity: MessageHeader.byteCount)
        data.append(type.rawValue)
        data.append(flags)
        data.append(0); data.append(0)                  // reserved
        data.appendLE(payloadLength)
        data.appendLE(timestampMicros)
        return data
    }

    init(type: MessageType, flags: UInt8 = 0, payloadLength: UInt32, timestampMicros: UInt64 = MonotonicClock.micros()) {
        self.type = type
        self.flags = flags
        self.payloadLength = payloadLength
        self.timestampMicros = timestampMicros
    }

    init?(decoding data: Data) {
        guard data.count >= MessageHeader.byteCount else { return nil }
        let bytes = [UInt8](data)
        guard let type = MessageType(rawValue: bytes[0]) else { return nil }
        self.type = type
        self.flags = bytes[1]
        self.payloadLength = data.readLE(at: 4)
        self.timestampMicros = data.readLE(at: 8)
    }
}

// MARK: - Control payloads

/// Describes the receiving Mac's panel. The host builds a virtual display that matches
/// this exactly, which is what keeps the picture free of any resampling.
struct ClientHello: Codable {
    static let currentVersion = 1

    var protocolVersion: Int = ClientHello.currentVersion
    var deviceName: String
    var pixelWidth: Int
    var pixelHeight: Int
    var pointWidth: Int
    var pointHeight: Int
    var refreshHz: Int
    var supportsHEVC: Bool
    var supportsH264: Bool
    var wantsInputForwarding: Bool

    // v2. Optional so a v1 peer's JSON still decodes; nil is omitted on the wire.
    var platform: String? = nil            // "macos" | "android"; nil = macos
    var physicalDPI: Double? = nil
    var scalePreference: String? = nil     // ScalePreference raw value
    var supportsAudio: Bool? = nil
    var audioUDPPort: Int? = nil
    var orientation: String? = nil         // "landscape" | "portrait"
}

struct HostHelloAck: Codable {
    var accepted: Bool
    var reason: String?
    var hostName: String
    var codec: VideoCodec
    var pixelWidth: Int
    var pixelHeight: Int
    var pointWidth: Int
    var pointHeight: Int
    var fps: Int
    var bitrateKbps: Int
    /// False when the virtual display would not take a 2x mode; the client shows a warning.
    var isHiDPI: Bool
    // v2
    var platform: String? = nil
    var audioAvailable: Bool? = nil
}

enum VideoCodec: String, Codable {
    case hevc
    case h264
}

/// Codec configuration, sent before the first frame and again after any encoder restart.
struct VideoParameterSets: Codable {
    var codec: VideoCodec
    var width: Int
    var height: Int
    /// Base64 parameter sets. HEVC: [VPS, SPS, PPS]. H.264: [SPS, PPS].
    var parameterSets: [Data]
    var nalLengthSize: Int
}

struct ClientStats: Codable {
    var framesDecoded: Int
    var framesDropped: Int
    var averageLatencyMs: Double
    var decodeMsAverage: Double
    /// Bytes the client has received since the last report; lets the host see goodput.
    var bytesReceived: Int
}

/// Starts (or restarts) the UDP audio stream to the client's `audioUDPPort`. See spec/PROTOCOL.md §7.
struct AudioConfig: Codable, Equatable {
    var sampleRate: Int = 48_000
    var channels: Int = 2
    var format: String = "s16le"
    var packetFrames: Int = 240
    var playoutDelayMs: Int = 60
    var encrypted: Bool = false
}

// MARK: - Input events (packed, 32 bytes)

enum InputKind: UInt8 {
    case mouseMove  = 1
    case mouseDown  = 2
    case mouseUp    = 3
    case scroll     = 4
    case keyDown    = 5
    case keyUp      = 6
    case flagsChanged = 7
}

enum MouseButton: UInt8 {
    case left = 0, right = 1, middle = 2
}

/// Coordinates are normalised 0...1 against the receiver's own view, so the host can map
/// them onto the virtual display without either side knowing the other's geometry.
struct InputEvent {
    static let byteCount = 32

    var kind: InputKind
    var button: UInt8 = 0
    var clickCount: UInt8 = 1
    var x: Float = 0
    var y: Float = 0
    var deltaX: Float = 0
    var deltaY: Float = 0
    var keyCode: UInt16 = 0
    var modifiers: UInt32 = 0
    var isPreciseScroll: Bool = false

    func encoded() -> Data {
        var data = Data(capacity: InputEvent.byteCount)
        data.append(kind.rawValue)
        data.append(button)
        data.append(clickCount)
        data.append(isPreciseScroll ? 1 : 0)
        data.appendLE(x.bitPattern)
        data.appendLE(y.bitPattern)
        data.appendLE(deltaX.bitPattern)
        data.appendLE(deltaY.bitPattern)
        data.appendLE(keyCode)
        data.append(0); data.append(0)                  // padding
        data.appendLE(modifiers)
        data.append(Data(count: InputEvent.byteCount - data.count))
        return data
    }

    init(kind: InputKind) { self.kind = kind }

    init?(decoding data: Data) {
        guard data.count >= InputEvent.byteCount, let kind = InputKind(rawValue: data[data.startIndex]) else { return nil }
        self.kind = kind
        self.button = data[data.startIndex + 1]
        self.clickCount = data[data.startIndex + 2]
        self.isPreciseScroll = data[data.startIndex + 3] != 0
        self.x = Float(bitPattern: data.readLE(at: 4))
        self.y = Float(bitPattern: data.readLE(at: 8))
        self.deltaX = Float(bitPattern: data.readLE(at: 12))
        self.deltaY = Float(bitPattern: data.readLE(at: 16))
        self.keyCode = data.readLE(at: 20)
        self.modifiers = data.readLE(at: 24)
    }
}

// MARK: - Little-endian helpers

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var le = value.littleEndian
        Swift.withUnsafeBytes(of: &le) { append(contentsOf: $0) }
    }

    /// Reads a little-endian integer at `offset` bytes from the start of the slice.
    func readLE<T: FixedWidthInteger>(at offset: Int) -> T {
        let start = index(startIndex, offsetBy: offset)
        var value: T = 0
        _ = Swift.withUnsafeMutableBytes(of: &value) { dst in
            self[start..<index(start, offsetBy: MemoryLayout<T>.size)].copyBytes(to: dst)
        }
        return T(littleEndian: value)
    }
}

enum MonotonicClock {
    private static let base = DispatchTime.now().uptimeNanoseconds

    static func micros() -> UInt64 {
        (DispatchTime.now().uptimeNanoseconds &- base) / 1_000
    }

    static func millis() -> Double {
        Double(micros()) / 1000.0
    }
}
