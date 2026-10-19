package com.voxturr.mirage.core

// X25519 + HMAC-SHA256 + HKDF-SHA256 + AES-256-GCM — spec/PROTOCOL.md §6.

enum class Role(val direction: Int) { INITIATOR(0), RESPONDER(1) }

class SecureChannelException(message: String) : Exception(message)

class Handshake(val role: Role, pairingCode: String, privateKeyRaw: ByteArray? = null /* tests only */) {
    /** 32-byte X25519 public key ‖ 32-byte HMAC. */
    fun message(): ByteArray = TODO("agent 4")
    /** @throws SecureChannelException on wrong code or malformed message. */
    fun complete(peerMessage: ByteArray): SecureChannel = TODO("agent 4")
}

class SecureChannel internal constructor(val sessionKey: ByteArray, val role: Role) {
    /** ciphertext ‖ tag. Advances the send counter. */
    fun seal(plaintext: ByteArray, aad: ByteArray): ByteArray = TODO("agent 4")
    /** Advances the receive counter. @throws SecureChannelException */
    fun open(payload: ByteArray, aad: ByteArray): ByteArray = TODO("agent 4")
    /** UDP audio: direction byte 2, counter = seq. Stateless. */
    fun sealAudio(seq: Long, header: ByteArray, pcm: ByteArray): ByteArray = TODO("agent 4")
    fun openAudio(seq: Long, header: ByteArray, payload: ByteArray): ByteArray = TODO("agent 4")

    companion object {
        /** For tests with vectors.json. */
        fun forTesting(sessionKey: ByteArray, role: Role): SecureChannel = SecureChannel(sessionKey, role)
    }
}
