import Foundation
import CryptoKit

/// One UDP audio datagram, byte for byte as spec/PROTOCOL.md §7 lays it out:
///
///   offset  size  field
///   0       1     version = 1
///   1       1     flags, bit0 = encrypted
///   2       2     reserved 0
///   4       4     seq u32 LE, +1 per packet, restarts at 0 with each audioConfig
///   8       8     hostTimeMicros u64 LE — capture time of the first sample
///   16      n     PCM s16le interleaved, or AES-GCM(pcm) ‖ tag when encrypted
///
/// The header goes out in the clear but is the GCM associated data, so a forged timestamp or
/// sequence number fails authentication instead of shifting playout.
enum AudioPacket {
    static let headerSize = 16
    static let version: UInt8 = 1
    static let encryptedFlag: UInt8 = 1 << 0
    /// Nonce byte 0. 0 and 1 are the two TCP directions (SecureChannel), so audio can share
    /// the session key without ever repeating a nonce.
    static let nonceDirection: UInt8 = 2

    struct Parsed {
        var seq: UInt32
        var hostTimeMicros: UInt64
        var encrypted: Bool
        var pcm: Data
    }

    enum Failure: Error { case malformed, decryptionFailed }

    static func header(seq: UInt32, hostTimeMicros: UInt64, encrypted: Bool) -> Data {
        var data = Data(capacity: headerSize)
        data.append(version)
        data.append(encrypted ? encryptedFlag : 0)
        data.append(0); data.append(0)
        data.appendLE(seq)
        data.appendLE(hostTimeMicros)
        return data
    }

    static func nonce(seq: UInt32) -> AES.GCM.Nonce {
        var bytes = Data(count: 12)
        bytes[0] = nonceDirection
        withUnsafeBytes(of: UInt64(seq).littleEndian) { raw in
            for i in 0..<8 { bytes[4 + i] = raw[i] }
        }
        return try! AES.GCM.Nonce(data: bytes)
    }

    /// Any PCM length is accepted (the spec vector uses 16 bytes); the stream always sends
    /// `packetFrames × channels × 2`.
    static func make(seq: UInt32, hostTimeMicros: UInt64, pcm: Data, key: SymmetricKey?) throws -> Data {
        let head = header(seq: seq, hostTimeMicros: hostTimeMicros, encrypted: key != nil)
        guard let key else { return head + pcm }
        let box = try AES.GCM.seal(pcm, using: key, nonce: nonce(seq: seq), authenticating: head)
        var out = head
        out.reserveCapacity(headerSize + pcm.count + 16)
        out.append(box.ciphertext)
        out.append(box.tag)
        return out
    }

    /// The receiving side. Mirage's own client never plays audio yet; this is what the tests
    /// (and a future Mac receiver) check packets with.
    static func parse(_ data: Data, key: SymmetricKey?) throws -> Parsed {
        guard data.count >= headerSize, data[data.startIndex] == version else { throw Failure.malformed }
        let head = data.prefix(headerSize)
        let encrypted = data[data.startIndex + 1] & encryptedFlag != 0
        let seq: UInt32 = data.readLE(at: 4)
        let time: UInt64 = data.readLE(at: 8)
        var body = Data(data.dropFirst(headerSize))
        if encrypted {
            guard let key, body.count >= 16 else { throw Failure.decryptionFailed }
            do {
                let box = try AES.GCM.SealedBox(nonce: nonce(seq: seq),
                                                ciphertext: body.prefix(body.count - 16),
                                                tag: body.suffix(16))
                body = try AES.GCM.open(box, using: key, authenticating: Data(head))
            } catch {
                throw Failure.decryptionFailed
            }
        }
        return Parsed(seq: seq, hostTimeMicros: time, encrypted: encrypted, pcm: body)
    }

    /// Float sample → s16. Symmetric scale (±32767) so silence stays exactly 0 and a full-scale
    /// sine does not pick up a DC offset; anything beyond ±1 (the system mix can exceed it)
    /// clips rather than wrapping. NaN becomes silence.
    @inline(__always)
    static func s16(_ x: Float) -> Int16 {
        guard x == x else { return 0 }
        let scaled = (x * 32767).rounded()
        if scaled >= 32767 { return 32767 }
        if scaled <= -32768 { return -32768 }
        return Int16(scaled)
    }
}
