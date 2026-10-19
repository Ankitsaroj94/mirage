package `in`.ankitsaroj.mirage.core

// X25519 + HMAC-SHA256 + HKDF-SHA256 + AES-256-GCM — spec/PROTOCOL.md §6.
//
// A port of Sources/Mirage/Core/SecureChannel.swift. Ephemeral X25519 keys tagged with an HMAC
// keyed by the pairing code: a wrong code fails the HMAC without either side revealing it, and
// a recorded session stays unreadable even if the code leaks later. Everything is the platform
// JCA (Conscrypt on Android 13+, SunEC/SunJCE on the JVM test runner), so no BouncyCastle.

import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class Role(val direction: Int) { INITIATOR(0), RESPONDER(1) }

class SecureChannelException(message: String) : Exception(message)

/** Primitives, shared by the handshake, the channel and the tests. */
internal object Crypto {
    // Raw keys travel through the DER encodings rather than XECPublicKeySpec: the u-coordinate
    // spec wants a BigInteger of a little-endian value (easy to get backwards), and Conscrypt's
    // XDH KeyFactory is only guaranteed to understand the encoded forms. The prefixes are the
    // fixed SubjectPublicKeyInfo / PKCS#8 headers for OID 1.3.101.110 (X25519).
    private val X509_PREFIX = hex("302a300506032b656e032100")
    private val PKCS8_PREFIX = hex("302e020100300506032b656e04220420")

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

    fun hmac(key: ByteArray, vararg parts: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            parts.forEach { update(it) }
            doFinal()
        }

    /** RFC 5869 extract-then-expand. Callers here only ever need L ≤ 32, i.e. one block — but stay general. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmac(salt, ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var produced = 0
        var counter = 1
        while (produced < length) {
            previous = hmac(prk, previous, info, byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - produced)
            System.arraycopy(previous, 0, out, produced, n)
            produced += n
            counter++
        }
        return out
    }

    fun generateX25519(): java.security.KeyPair {
        val generator = KeyPairGenerator.getInstance("XDH")
        // Conscrypt's XDH generator is X25519-only and may not take a NamedParameterSpec;
        // SunEC needs it (its default is also X25519, but don't rely on that).
        try { generator.initialize(NamedParameterSpec.X25519) } catch (_: Exception) {}
        return generator.generateKeyPair()
    }

    fun publicKey(raw: ByteArray): PublicKey {
        if (raw.size != 32) throw SecureChannelException("X25519 public key must be 32 bytes")
        return KeyFactory.getInstance("XDH").generatePublic(X509EncodedKeySpec(X509_PREFIX + raw))
    }

    fun privateKey(raw: ByteArray): PrivateKey {
        require(raw.size == 32) { "X25519 private key must be 32 bytes" }
        return KeyFactory.getInstance("XDH").generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + raw))
    }

    /** The 32 raw bytes at the end of the X.509 encoding. */
    fun rawPublicKey(key: PublicKey): ByteArray {
        val encoded = key.encoded
        check(encoded.size == X509_PREFIX.size + 32) { "unexpected X25519 public key encoding (${encoded.size} bytes)" }
        return encoded.copyOfRange(encoded.size - 32, encoded.size)
    }

    /** Public key for a fixed private scalar (tests). Base-point multiplication = agreement with u = 9. */
    fun publicKeyFor(privateKey: PrivateKey): ByteArray =
        x25519(privateKey, ByteArray(32).also { it[0] = 9 })

    fun x25519(privateKey: PrivateKey, peerPublicRaw: ByteArray): ByteArray =
        KeyAgreement.getInstance("XDH").run {
            init(privateKey)
            doPhase(publicKey(peerPublicRaw), true)
            generateSecret()
        }

    /** Unsigned lexicographic compare, as Swift's `Data.lexicographicallyPrecedes`. */
    fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in 0 until minOf(a.size, b.size)) {
            val d = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
            if (d != 0) return d
        }
        return a.size - b.size
    }

    /** `[direction, 0, 0, 0, counter u64 LE]`. */
    fun nonce(direction: Int, counter: Long): ByteArray {
        val n = ByteArray(12)
        n[0] = direction.toByte()
        for (i in 0 until 8) n[4 + i] = (counter ushr (8 * i)).toByte()
        return n
    }

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}

class Handshake(val role: Role, pairingCode: String, privateKeyRaw: ByteArray? = null /* tests only */) {
    private val privateKey: PrivateKey
    /** Our raw 32-byte X25519 public key. */
    val publicKey: ByteArray
    private val codeSecret = codeSecret(pairingCode)

    init {
        if (privateKeyRaw != null) {
            // Fixed key, for reproducing spec/vectors.json only: the ephemeral key is what gives
            // a recorded session forward secrecy.
            privateKey = Crypto.privateKey(privateKeyRaw)
            publicKey = Crypto.publicKeyFor(privateKey)
        } else {
            val pair = Crypto.generateX25519()
            privateKey = pair.private
            publicKey = Crypto.rawPublicKey(pair.public)
        }
    }

    /** 32-byte X25519 public key ‖ 32-byte HMAC. */
    fun message(): ByteArray = publicKey + Crypto.hmac(codeSecret, publicKey)

    /** @throws SecureChannelException on wrong code or malformed message. */
    fun complete(peerMessage: ByteArray): SecureChannel {
        if (peerMessage.size != 64) throw SecureChannelException("malformed handshake")
        val peerPublic = peerMessage.copyOfRange(0, 32)
        val tag = peerMessage.copyOfRange(32, 64)
        // Constant-time compare: the tag is the only thing standing between a guesser and the code.
        if (!MessageDigest.isEqual(tag, Crypto.hmac(codeSecret, peerPublic))) {
            throw SecureChannelException("The pairing codes on the two devices do not match.")
        }
        val shared = try {
            Crypto.x25519(privateKey, peerPublic)
        } catch (e: Exception) {
            throw SecureChannelException("malformed handshake: ${e.message}")
        }
        // Salt is both public keys in a fixed order, so the two sides derive the same key
        // without agreeing on who spoke first.
        val salt = if (Crypto.compareUnsigned(publicKey, peerPublic) < 0) publicKey + peerPublic else peerPublic + publicKey
        val key = Crypto.hkdf(shared, salt, SESSION_INFO, 32)
        return SecureChannel(key, role)
    }

    companion object {
        private val SESSION_INFO = "mirage.session.v1".toByteArray(Charsets.UTF_8)

        /** SHA256("mirage.pairing.v1:" + code) — the HMAC key; the code itself never goes on the wire. */
        fun codeSecret(pairingCode: String): ByteArray =
            Crypto.sha256(("mirage.pairing.v1:$pairingCode").toByteArray(Charsets.UTF_8))
    }
}

/**
 * AES-256-GCM over the link. One counter per direction, each starting at 0, so with a
 * per-session key a nonce is never reused — the one thing GCM cannot survive. The counters are
 * atomic, but ordering is the caller's job: the peer opens in counter order, so PeerLink seals
 * and enqueues under one lock.
 */
class SecureChannel internal constructor(val sessionKey: ByteArray, val role: Role) {
    private val key = SecretKeySpec(sessionKey, "AES")
    private val sendCounter = AtomicLong(0)
    private val receiveCounter = AtomicLong(0)
    private val sendDirection = role.direction
    private val receiveDirection = if (role == Role.INITIATOR) Role.RESPONDER.direction else Role.INITIATOR.direction

    /** ciphertext ‖ tag. Advances the send counter. */
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray =
        seal(Crypto.nonce(sendDirection, sendCounter.getAndIncrement()), plaintext, 0, plaintext.size, aad)

    /** Advances the receive counter. @throws SecureChannelException */
    fun open(payload: ByteArray, aad: ByteArray): ByteArray = open(payload, 0, payload.size, aad)

    /** As [open], on a slice — lets the reader decrypt straight out of a reused buffer. */
    fun open(payload: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray {
        // Consume the counter even for a runt: the sender did, and the link dies on it anyway.
        val nonce = Crypto.nonce(receiveDirection, receiveCounter.getAndIncrement())
        return open(nonce, payload, offset, length, aad)
    }

    /** UDP audio: direction byte 2, counter = seq. Stateless. */
    fun sealAudio(seq: Long, header: ByteArray, pcm: ByteArray): ByteArray =
        seal(Crypto.nonce(AUDIO_DIRECTION, seq), pcm, 0, pcm.size, header)

    fun openAudio(seq: Long, header: ByteArray, payload: ByteArray): ByteArray =
        open(Crypto.nonce(AUDIO_DIRECTION, seq), payload, 0, payload.size, header)

    private fun seal(nonce: ByteArray, plaintext: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray =
        try {
            // A fresh Cipher per call: SunJCE refuses to re-init GCM encryption with a repeated
            // (key, nonce) on one instance, and per-call lookup is microseconds next to a frame.
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(plaintext, offset, length)
            }
        } catch (e: Exception) {
            throw SecureChannelException("encryption failed: ${e.message}")
        }

    private fun open(nonce: ByteArray, payload: ByteArray, offset: Int, length: Int, aad: ByteArray): ByteArray {
        if (length < TAG_BYTES) throw SecureChannelException("The encrypted stream could not be read.")
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad)
                doFinal(payload, offset, length)
            }
        } catch (e: Exception) {
            throw SecureChannelException("The encrypted stream could not be read.")
        }
    }

    companion object {
        const val TAG_BYTES = 16
        private const val TAG_BITS = 128
        /** Audio's own nonce space, so it can never collide with either TCP direction (spec §7). */
        private const val AUDIO_DIRECTION = 2
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** For tests with vectors.json. */
        fun forTesting(sessionKey: ByteArray, role: Role): SecureChannel = SecureChannel(sessionKey, role)
    }
}
