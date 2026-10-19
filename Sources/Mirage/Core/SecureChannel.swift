import Foundation
import CryptoKit

/// Optional end-to-end encryption for the link, keyed by the pairing code.
///
/// Network.framework cannot actually do TLS-PSK — none of the PSK ciphersuites are exposed
/// in `tls_ciphersuite_t`, and TLS 1.3 external PSK is rejected by `NWListener` — so the
/// encryption lives here instead. It is a straightforward authenticated key exchange:
///
///   1. Each side sends an ephemeral X25519 public key, tagged with an HMAC over that key
///      using a secret derived from the pairing code.
///   2. A wrong code fails the HMAC check, so neither side ever reveals the code itself.
///   3. The X25519 shared secret becomes the AES-GCM session key.
///
/// Ephemeral keys mean a recorded session cannot be decrypted later even if the pairing
/// code leaks. Cost is one round trip at connect time and AES-GCM per message, which is
/// hardware-accelerated on both Macs and disappears next to the video codecs.
struct SecureChannel {

    enum Role: UInt8 {
        case initiator = 0   // the sending Mac
        case responder = 1   // the receiving Mac
    }

    enum Failure: Error, LocalizedError {
        case badPairingCode
        case malformedHandshake
        case decryptionFailed

        var errorDescription: String? {
            switch self {
            case .badPairingCode:    return "The pairing codes on the two Macs do not match."
            case .malformedHandshake: return "The other Mac sent a handshake this version does not understand."
            case .decryptionFailed:  return "The encrypted stream could not be read."
            }
        }
    }

    // MARK: Handshake

    /// Everything one side needs to keep between sending and receiving a handshake.
    struct Handshake {
        let role: Role
        let privateKey: Curve25519.KeyAgreement.PrivateKey
        let codeSecret: SymmetricKey

        init(role: Role, pairingCode: String) {
            self.init(role: role, pairingCode: pairingCode, privateKey: Curve25519.KeyAgreement.PrivateKey())
        }

        /// Fixed-key variant, for reproducing spec/vectors.json. Never use it on a real link:
        /// the ephemeral key is what gives a recorded session forward secrecy.
        init(role: Role, pairingCode: String, privateKey: Curve25519.KeyAgreement.PrivateKey) {
            self.role = role
            self.privateKey = privateKey
            self.codeSecret = SymmetricKey(data: SHA256.hash(data: Data(("mirage.pairing.v1:" + pairingCode).utf8)))
        }

        /// 32-byte public key followed by a 32-byte HMAC over it.
        func message() -> Data {
            let publicKey = privateKey.publicKey.rawRepresentation
            var payload = Data(publicKey)
            payload.append(contentsOf: HMAC<SHA256>.authenticationCode(for: publicKey, using: codeSecret))
            return payload
        }

        func complete(with peerMessage: Data) throws -> SecureChannel {
            guard peerMessage.count == 64 else { throw Failure.malformedHandshake }
            let peerPublicKeyData = peerMessage.prefix(32)
            let tag = peerMessage.suffix(32)

            guard HMAC<SHA256>.isValidAuthenticationCode(tag, authenticating: peerPublicKeyData, using: codeSecret) else {
                throw Failure.badPairingCode
            }
            guard let peerPublicKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: peerPublicKeyData) else {
                throw Failure.malformedHandshake
            }

            let shared = try privateKey.sharedSecretFromKeyAgreement(with: peerPublicKey)

            // Salt is both public keys in a fixed order, so the two sides derive the same
            // key without needing to agree on who spoke first.
            let ours = privateKey.publicKey.rawRepresentation
            let theirs = Data(peerPublicKeyData)
            let salt = ours.lexicographicallyPrecedes(theirs) ? ours + theirs : theirs + ours

            let key = shared.hkdfDerivedSymmetricKey(using: SHA256.self,
                                                     salt: salt,
                                                     sharedInfo: Data("mirage.session.v1".utf8),
                                                     outputByteCount: 32)
            return SecureChannel(key: key, role: role)
        }
    }

    // MARK: Message encryption

    private let key: SymmetricKey
    private let role: Role
    private let sendCounter = Counter()
    private let receiveCounter = Counter()

    fileprivate init(key: SymmetricKey, role: Role) {
        self.key = key
        self.role = role
    }

    /// The HKDF output both sides derived. The UDP audio flow reuses it with nonce direction
    /// byte 2 (spec/PROTOCOL.md §7), so its nonces can never collide with the TCP link's.
    var sessionKey: SymmetricKey { key }

    /// Nonce layout: one byte of direction, three zero bytes, then an eight-byte counter.
    /// Each direction has its own counter and the key is per-session, so a nonce is never
    /// reused — which is the one thing AES-GCM cannot survive.
    private static func nonce(direction: Role, counter: UInt64) -> AES.GCM.Nonce {
        var bytes = Data(count: 12)
        bytes[0] = direction.rawValue
        withUnsafeBytes(of: counter.littleEndian) { raw in
            for i in 0..<8 { bytes[4 + i] = raw[i] }
        }
        return try! AES.GCM.Nonce(data: bytes)
    }

    private var sendDirection: Role { role }
    private var receiveDirection: Role { role == .initiator ? .responder : .initiator }

    func seal(_ plaintext: Data, authenticating aad: Data) throws -> Data {
        let box = try AES.GCM.seal(plaintext,
                                   using: key,
                                   nonce: Self.nonce(direction: sendDirection, counter: sendCounter.next()),
                                   authenticating: aad)
        return box.ciphertext + box.tag
    }

    func open(_ payload: Data, authenticating aad: Data) throws -> Data {
        guard payload.count >= 16 else { throw Failure.decryptionFailed }
        let ciphertext = payload.prefix(payload.count - 16)
        let tag = payload.suffix(16)
        let box = try AES.GCM.SealedBox(nonce: Self.nonce(direction: receiveDirection, counter: receiveCounter.next()),
                                        ciphertext: ciphertext,
                                        tag: tag)
        do {
            return try AES.GCM.open(box, using: key, authenticating: aad)
        } catch {
            throw Failure.decryptionFailed
        }
    }

    /// Reference type so the counters advance on a `let` channel shared across callbacks.
    final class Counter {
        private var value: UInt64 = 0
        private let lock = NSLock()
        func next() -> UInt64 {
            lock.lock(); defer { lock.unlock() }
            let current = value
            value &+= 1
            return current
        }
    }
}
