package com.voxturr.mirage.core

import java.net.Socket

/**
 * Framed full-duplex link over one TCP socket — port of Sources/Mirage/Core/PeerLink.swift.
 * Reader runs on its own thread; callbacks fire on that thread. send() is thread-safe and
 * never blocks the caller on the network (a dedicated writer thread drains a queue).
 */
class PeerLink(
    val socket: Socket,
    val role: Role,
    pairingCode: String?,
) {
    var onMessage: ((type: Int, flags: Int, timestampMicros: Long, payload: ByteArray) -> Unit)? = null
    /** After the key exchange when a pairing code is set, immediately otherwise. */
    var onReady: (() -> Unit)? = null
    var onFailure: ((Throwable?) -> Unit)? = null

    val clock: ClockSync = ClockSync()
    val roundTripMs: Double get() = TODO("agent 4")
    /** Bytes queued but not yet written to the socket (backpressure signal). */
    val pendingBytes: Int get() = TODO("agent 4")
    /** Non-null once an encrypted session is established (audio reuses its key). */
    val channel: SecureChannel? get() = TODO("agent 4")
    val remoteAddress: java.net.InetAddress get() = socket.inetAddress

    fun start(): Unit = TODO("agent 4")
    fun send(type: Int, flags: Int = 0, payload: ByteArray = ByteArray(0), timestampMicros: Long = MonotonicClock.micros()): Unit = TODO("agent 4")
    fun close(): Unit = TODO("agent 4")

    companion object {
        /** Applies TCP_NODELAY, AF41 traffic class, buffer sizes, keepalive. */
        fun tune(socket: Socket): Unit = TODO("agent 4")
        /** Blocking connect (call off the main thread). */
        fun connect(host: java.net.InetAddress, port: Int, role: Role, pairingCode: String?): PeerLink = TODO("agent 4")
    }
}

/** Accepts incoming links on a TCP port (0 = ephemeral). Used by the client (display) role. */
class PeerListener(port: Int = 0, private val pairingCode: String?) {
    val port: Int get() = TODO("agent 4")
    var onConnection: ((PeerLink) -> Unit)? = null
    fun start(): Unit = TODO("agent 4")
    fun stop(): Unit = TODO("agent 4")
}
