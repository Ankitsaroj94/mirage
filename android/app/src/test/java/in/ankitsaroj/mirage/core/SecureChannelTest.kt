package `in`.ankitsaroj.mirage.core

import `in`.ankitsaroj.mirage.core.Vectors.hex
import `in`.ankitsaroj.mirage.core.Vectors.toHex
import `in`.ankitsaroj.mirage.core.Vectors.u64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SecureChannelTest {
    private val v = Vectors.root.getJSONObject("handshake")
    private val code = v.getString("pairingCode")
    private val initiatorPrivate = hex(v.getString("initiatorPrivateHex"))
    private val initiator = Handshake(Role.INITIATOR, code, initiatorPrivate)
    private val responder = Handshake(Role.RESPONDER, code, hex(v.getString("responderPrivateHex")))
    private val sessionKey = hex(v.getString("sessionKeyHex"))

    @Test fun codeSecret() = assertEquals(v.getString("codeSecretHex"), Handshake.codeSecret(code).toHex())

    @Test fun handshakeMessages() {
        assertEquals(v.getString("initiatorMessageHex"), initiator.message().toHex())
        assertEquals(v.getString("responderMessageHex"), responder.message().toHex())
    }

    @Test fun sharedSecretAndSalt() {
        val shared = Crypto.x25519(Crypto.privateKey(initiatorPrivate), responder.publicKey)
        assertEquals(v.getString("sharedSecretHex"), shared.toHex())
        val a = initiator.publicKey
        val b = responder.publicKey
        val salt = if (Crypto.compareUnsigned(a, b) < 0) a + b else b + a
        assertEquals(v.getString("saltHex"), salt.toHex())
    }

    @Test fun sessionKeyBothSides() {
        assertEquals(v.getString("sessionKeyHex"), initiator.complete(responder.message()).sessionKey.toHex())
        assertEquals(v.getString("sessionKeyHex"), responder.complete(initiator.message()).sessionKey.toHex())
    }

    @Test fun ephemeralKeysAgree() {
        val a = Handshake(Role.INITIATOR, "1234")
        val b = Handshake(Role.RESPONDER, "1234")
        assertArrayEquals(a.complete(b.message()).sessionKey, b.complete(a.message()).sessionKey)
        assertNotEquals(a.publicKey.toHex(), b.publicKey.toHex())
    }

    @Test fun wrongCodeRejected() {
        val other = Handshake(Role.RESPONDER, "0000", hex(v.getString("responderPrivateHex")))
        assertThrows(SecureChannelException::class.java) { initiator.complete(other.message()) }
        assertThrows(SecureChannelException::class.java) { initiator.complete(ByteArray(63)) }
    }

    @Test fun sealedMessagesMatchVectors() {
        val senders = mapOf(0 to SecureChannel.forTesting(sessionKey, Role.INITIATOR), 1 to SecureChannel.forTesting(sessionKey, Role.RESPONDER))
        val receivers = mapOf(0 to SecureChannel.forTesting(sessionKey, Role.RESPONDER), 1 to SecureChannel.forTesting(sessionKey, Role.INITIATOR))
        val sealed = mutableMapOf(0 to 0L, 1 to 0L)
        val messages = v.getJSONArray("messages")
        assertEquals(3, messages.length())
        for (i in 0 until messages.length()) {
            val m = messages.getJSONObject(i)
            val dir = m.getInt("direction")
            val tx = senders.getValue(dir)
            val rx = receivers.getValue(dir)
            // Bring both counters to the vector's value the way a real link would: by traffic.
            while (sealed.getValue(dir) < m.getLong("counter")) {
                val aad = ByteArray(16)
                rx.open(tx.seal(ByteArray(0), aad), aad)
                sealed[dir] = sealed.getValue(dir) + 1
            }
            val plaintext = hex(m.getString("plaintextHex"))
            val wire = PeerLink.frame(m.getInt("type"), 0, plaintext, u64(m.getString("timestampMicros")), tx)
            sealed[dir] = sealed.getValue(dir) + 1
            assertEquals("message $i", m.getString("wireHex"), wire.toHex())
            val header = wire.copyOfRange(0, 16)
            assertEquals(m.getString("aadHex"), header.toHex())
            assertArrayEquals(plaintext, rx.open(wire.copyOfRange(16, wire.size), header))
        }
    }

    @Test fun keyExchangeIsNeverSealed() {
        val tx = SecureChannel.forTesting(sessionKey, Role.INITIATOR)
        val wire = PeerLink.frame(MessageType.KEY_EXCHANGE, 0, initiator.message(), 0, tx)
        assertEquals(initiator.message().toHex(), wire.copyOfRange(16, wire.size).toHex())
    }

    @Test fun tamperedHeaderFailsTag() {
        val tx = SecureChannel.forTesting(sessionKey, Role.INITIATOR)
        val rx = SecureChannel.forTesting(sessionKey, Role.RESPONDER)
        val wire = PeerLink.frame(MessageType.VIDEO_FRAME, 0, ByteArray(100) { it.toByte() }, 5, tx)
        val header = wire.copyOfRange(0, 16).also { it[1] = 1 }       // flip the keyframe flag
        assertThrows(SecureChannelException::class.java) { rx.open(wire.copyOfRange(16, wire.size), header) }
    }

    @Test fun wrongDirectionFails() {
        val tx = SecureChannel.forTesting(sessionKey, Role.INITIATOR)
        val sameRole = SecureChannel.forTesting(sessionKey, Role.INITIATOR)
        val aad = ByteArray(16)
        assertThrows(SecureChannelException::class.java) { sameRole.open(tx.seal(byteArrayOf(1, 2, 3), aad), aad) }
    }

    @Test fun audioPacketMatchesVector() {
        val a = v.getJSONObject("audioPacket")
        val header = hex(a.getString("headerHex"))
        val pcm = hex(a.getString("pcmHex"))
        val seq = a.getLong("seq")
        val wire = hex(a.getString("wireHex"))
        val ch = SecureChannel.forTesting(sessionKey, Role.INITIATOR)
        assertEquals(a.getString("wireHex"), (header + ch.sealAudio(seq, header, pcm)).toHex())
        // Stateless, and either role opens it.
        val body = wire.copyOfRange(16, wire.size)
        assertArrayEquals(pcm, SecureChannel.forTesting(sessionKey, Role.RESPONDER).openAudio(seq, header, body))
        assertArrayEquals(pcm, ch.openAudio(seq, header, body))
        assertThrows(SecureChannelException::class.java) { ch.openAudio(seq + 1, header, body) }
    }

    @Test fun hkdfRfc5869Case1() {
        val okm = Crypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.toHex())
    }
}
