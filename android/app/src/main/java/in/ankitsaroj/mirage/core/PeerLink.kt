package `in`.ankitsaroj.mirage.core

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Framed full-duplex link over one TCP socket — port of Sources/Mirage/Core/PeerLink.swift.
 * Reader runs on its own thread; callbacks fire on that thread. send() is thread-safe and
 * never blocks the caller on the network (a dedicated writer thread drains a queue).
 *
 * Construct (or get one from [connect] / [PeerListener]), set the callbacks, then [start].
 * With a pairing code, every message except keyExchange is AES-GCM sealed — ping and pong
 * included, and opened *before* dispatch, which is the v2 fix (spec §6): v1 answered ping/pong
 * before decrypting and desynced encrypted links at the first heartbeat.
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

    /** Peer clock relative to ours, fed by v2 pongs. A v1 peer never feeds it, so hasEstimate stays false. */
    val clock: ClockSync = ClockSync()

    /**
     * Network round trip, ms. Measured here rather than in a session: ping and pong are both
     * handled on the I/O threads, so this reflects the link, not how busy a main thread is.
     */
    val roundTripMs: Double get() = synchronized(lock) { _roundTripMs }

    /** Bytes queued but not yet written to the socket (backpressure signal). */
    val pendingBytes: Int get() = _pendingBytes.get().toInt()

    /** Non-null once an encrypted session is established (audio reuses its key). */
    val channel: SecureChannel? get() = _channel
    val remoteAddress: java.net.InetAddress get() = socket.inetAddress

    /** False once the link has failed or been closed; sends are dropped from then on. */
    val isOpen: Boolean get() = !closed.get()

    private val handshake: Handshake? = pairingCode?.takeIf { it.isNotEmpty() }?.let { Handshake(role, it) }
    private val lock = Any()
    /**
     * Held across sealing *and* enqueueing. Nonces come from a per-direction counter, so the
     * peer can only open messages in the order they were sealed; without this a pong sealed on
     * the reader thread could overtake a frame sealed on the encoder thread and kill the link.
     */
    private val sendLock = Any()
    @Volatile private var _channel: SecureChannel? = null
    private val outbox = ArrayList<Outgoing>()          // guarded by lock
    private var _roundTripMs = 0.0                       // guarded by lock
    private val _pendingBytes = AtomicLong(0)
    private val queue = LinkedBlockingQueue<ByteArray>()
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val readyFired = AtomicBoolean(false)
    @Volatile private var heartbeat: ScheduledFuture<*>? = null

    private class Outgoing(val type: Int, val flags: Int, val payload: ByteArray, val timestampMicros: Long)

    fun start() {
        if (!started.compareAndSet(false, true) || closed.get()) return
        Thread(::writeLoop, "mirage-link-writer").apply { isDaemon = true }.start()
        Thread(::readLoop, "mirage-link-reader").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1 }.start()
        heartbeat = scheduler.scheduleWithFixedDelay(::ping, 1, 1, TimeUnit.SECONDS)
        // keyExchange goes out in the clear (it is never sealed); onReady waits for the peer's.
        handshake?.let { transmit(MessageType.KEY_EXCHANGE, 0, it.message(), MonotonicClock.micros()) }
    }

    fun send(type: Int, flags: Int = 0, payload: ByteArray = ByteArray(0), timestampMicros: Long = MonotonicClock.micros()) {
        if (closed.get()) return
        if (handshake != null) {
            synchronized(lock) {
                if (_channel == null) {
                    // Hold application traffic until the key exchange lands — a handful of
                    // control messages at connect time, never video.
                    outbox += Outgoing(type, flags, payload, timestampMicros)
                    return
                }
            }
        }
        transmit(type, flags, payload, timestampMicros)
    }

    /**
     * Idempotent, and never fires onFailure. Already-queued messages (typically a bye) still
     * get written, bounded by [CLOSE_LINGER_MS] in case the peer has stopped reading.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel(false)
        if (!started.get()) { closeSocket(); return }
        try { socket.shutdownInput() } catch (_: IOException) {}   // wakes the reader with EOF
        queue.put(POISON)
        scheduler.schedule(::closeSocket, CLOSE_LINGER_MS, TimeUnit.MILLISECONDS)
    }

    // ── Sending ──────────────────────────────────────────────────────────────────────────

    private fun ping() {
        if (closed.get()) return
        // A ping sent in the clear while the key exchange is in flight would reach a peer that
        // already holds the key, fail to open there, and drop the link.
        if (handshake != null && _channel == null) return
        transmit(MessageType.PING, 0, EMPTY, MonotonicClock.micros())
    }

    private fun transmit(type: Int, flags: Int, payload: ByteArray, timestampMicros: Long) {
        val ok = synchronized(sendLock) { transmitLocked(type, flags, payload, timestampMicros) }
        if (!ok) fail(SecureChannelException("The encrypted stream could not be written."))
    }

    /** Caller holds sendLock. Seals and enqueues in one step so wire order == counter order. */
    private fun transmitLocked(type: Int, flags: Int, payload: ByteArray, timestampMicros: Long): Boolean {
        if (closed.get()) return true
        val out = try {
            frame(type, flags, payload, timestampMicros, _channel)
        } catch (e: SecureChannelException) {
            return false
        }
        _pendingBytes.addAndGet(out.size.toLong())
        queue.put(out)
        return true
    }

    private fun writeLoop() {
        val out = socket.getOutputStream()
        try {
            while (true) {
                val bytes = queue.take()
                if (bytes === POISON) break
                // One write per message: header and body are already contiguous, so with
                // TCP_NODELAY a small message is one segment.
                out.write(bytes)
                _pendingBytes.addAndGet(-bytes.size.toLong())
            }
            if (closed.get()) closeSocket()
        } catch (e: IOException) {
            fail(e)
        } catch (_: InterruptedException) {
        }
    }

    // ── Receiving ────────────────────────────────────────────────────────────────────────

    private fun readLoop() {
        if (handshake == null) fireReady()
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 256 * 1024))
        val header = ByteArray(MessageHeader.BYTE_COUNT)    // reused: decoded and AAD'd synchronously
        var cipherScratch = ByteArray(64 * 1024)            // reused: the plaintext is a fresh array
        try {
            while (!closed.get()) {
                input.readFully(header)
                val h = MessageHeader.decode(header)
                val length = h.payloadLength
                if (length < 0 || length > MAX_PAYLOAD) throw IOException("bad payload length ${length.toUInt()}")

                val sealed = _channel != null && h.type != MessageType.KEY_EXCHANGE
                val payload: ByteArray
                if (sealed) {
                    if (cipherScratch.size < length) cipherScratch = ByteArray(maxOf(length, cipherScratch.size * 2))
                    input.readFully(cipherScratch, 0, length)
                    // Open before looking at the type — ping, pong and unknown types included.
                    // The sender sealed each with the next counter; skipping one would leave every
                    // later nonce off by one.
                    payload = _channel!!.open(cipherScratch, 0, length, header)
                } else {
                    payload = if (length == 0) EMPTY else ByteArray(length).also { input.readFully(it) }
                }
                if (!deliver(h, payload)) return
            }
        } catch (e: EOFException) {
            fail(null)
        } catch (e: Exception) {
            fail(e)
        }
    }

    /** False when the link must stop reading. */
    private fun deliver(h: MessageHeader, body: ByteArray): Boolean {
        if (h.type == MessageType.KEY_EXCHANGE) {
            completeHandshake(body)
            return !closed.get()
        }
        if (handshake != null && _channel == null) {
            // Encrypted link, but the peer sent traffic before the key exchange.
            fail(SecureChannelException("The other device sent data before the key exchange (is a pairing code set on both?)."))
            return false
        }
        // A newer peer's message type. Ignoring it is the whole compatibility contract.
        if (!MessageType.isKnown(h.type)) return true

        when (h.type) {
            // Replying inline means the peer measures the network, not our app's queues.
            // The v2 pong carries our clock; a v1 peer reads past the payload and ignores it.
            MessageType.PING -> transmit(MessageType.PONG, 0, ClockSync.pongPayload(), h.timestampMicros)
            MessageType.PONG -> {
                val now = MonotonicClock.micros()
                val sample = (now - h.timestampMicros) / 1000.0
                synchronized(lock) {
                    // Light smoothing so a single scheduling hiccup does not dominate the readout.
                    _roundTripMs = if (_roundTripMs == 0.0) sample else _roundTripMs * 0.8 + sample * 0.2
                }
                ClockSync.remoteClock(body)?.let { clock.addSample(h.timestampMicros, it, now) }
            }
            else -> onMessage?.invoke(h.type, h.flags, h.timestampMicros, body)
        }
        return true
    }

    private fun completeHandshake(peerMessage: ByteArray) {
        val handshake = handshake ?: run {
            // The Swift link ignores this and then chokes on ciphertext; say what is wrong instead.
            fail(SecureChannelException("The other device requires a pairing code."))
            return
        }
        if (_channel != null) return
        val established = try {
            handshake.complete(peerMessage)
        } catch (e: SecureChannelException) {
            fail(e)
            return
        }
        // Install the key and flush what queued behind it as one step under sendLock: a send()
        // that sees the channel early waits here, so nothing overtakes the queued messages.
        val flushed = synchronized(sendLock) {
            val queued = synchronized(lock) {
                _channel = established
                ArrayList(outbox).also { outbox.clear() }
            }
            queued.all { transmitLocked(it.type, it.flags, it.payload, it.timestampMicros) }
        }
        if (!flushed) { fail(SecureChannelException("The encrypted stream could not be written.")); return }
        fireReady()
    }

    private fun fireReady() {
        if (!closed.get() && readyFired.compareAndSet(false, true)) onReady?.invoke()
    }

    private fun fail(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        heartbeat?.cancel(false)
        queue.clear()
        queue.put(POISON)
        closeSocket()
        onFailure?.invoke(error)
    }

    private fun closeSocket() {
        try { socket.close() } catch (_: IOException) {}
    }

    companion object {
        /** Largest payload accepted; a corrupt length must not turn into a 2 GB allocation. */
        const val MAX_PAYLOAD = 64 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 8_000
        /**
         * Both ends ping every second, so a silent socket means the peer (or the Wi-Fi) is gone.
         * Java sockets cannot set a short TCP keepalive idle the way the Mac side does (2 s),
         * so this read timeout is what notices a vanished peer.
         */
        const val READ_TIMEOUT_MS = 15_000
        private const val CLOSE_LINGER_MS = 1_000L
        private const val AF41 = 0x88
        private const val RECEIVE_BUFFER = 4 * 1024 * 1024
        /**
         * Kept smaller than the receive side on purpose: bytes in the kernel send buffer have
         * already left [pendingBytes], so a huge buffer would hide congestion from the encoder.
         */
        private const val SEND_BUFFER = 1024 * 1024

        private val EMPTY = ByteArray(0)
        private val POISON = ByteArray(0)

        /** One daemon thread runs every link's heartbeat and close linger; tasks never block. */
        private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mirage-link-timer").apply { isDaemon = true }
        }

        /**
         * Header + body exactly as it goes on the wire. With a channel the body is sealed and the
         * cleartext header is authenticated as AAD — the real header, i.e. with payloadLength =
         * plaintext + 16 — so a tampered length or keyframe flag fails the tag. keyExchange is
         * never sealed. Advances the channel's send counter.
         */
        fun frame(type: Int, flags: Int, payload: ByteArray, timestampMicros: Long, channel: SecureChannel?): ByteArray {
            val seal = channel != null && type != MessageType.KEY_EXCHANGE
            val bodyLength = if (seal) payload.size + SecureChannel.TAG_BYTES else payload.size
            val header = MessageHeader(type, flags, bodyLength, timestampMicros)
            val out = ByteArray(MessageHeader.BYTE_COUNT + bodyLength)
            header.encodeInto(out, 0)
            val body = if (seal) channel!!.seal(payload, header.encoded()) else payload
            body.copyInto(out, MessageHeader.BYTE_COUNT)
            return out
        }

        /** Applies TCP_NODELAY, AF41 traffic class, buffer sizes, keepalive. */
        fun tune(socket: Socket) {
            // Each option is best effort: a platform refusing one must not cost the connection.
            fun attempt(block: () -> Unit) = try { block() } catch (_: SocketException) {}
            attempt { socket.tcpNoDelay = true }        // never coalesce; a late frame is a dropped frame
            attempt { socket.trafficClass = AF41 }      // WMM video access category on Wi-Fi
            attempt { socket.receiveBufferSize = RECEIVE_BUFFER }
            attempt { socket.sendBufferSize = SEND_BUFFER }
            attempt { socket.keepAlive = true }
            attempt { socket.soTimeout = READ_TIMEOUT_MS }
        }

        /** Blocking connect (call off the main thread). Returns an unstarted link: set callbacks, then start(). */
        fun connect(host: java.net.InetAddress, port: Int, role: Role, pairingCode: String?): PeerLink {
            val socket = Socket()
            try {
                tune(socket)                            // before connect, so the window scale is negotiated
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            } catch (e: IOException) {
                try { socket.close() } catch (_: IOException) {}
                throw e
            }
            return PeerLink(socket, role, pairingCode)
        }
    }
}

/** Accepts incoming links on a TCP port (0 = ephemeral). Used by the client (display) role. */
class PeerListener(port: Int = 0, private val pairingCode: String?) {
    private val requestedPort = port
    @Volatile private var server: ServerSocket? = null

    /** The bound port once [start] returns; 0 before. */
    val port: Int get() = server?.localPort ?: 0
    /** Fires on the accept thread with an unstarted RESPONDER link: set callbacks, then start(). */
    var onConnection: ((PeerLink) -> Unit)? = null

    /** Binds synchronously (so [port] is valid on return), then accepts on a daemon thread. */
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        try {
            s.reuseAddress = true
            // Accepted sockets inherit this, and it must be set before listen for window scaling.
            s.receiveBufferSize = 4 * 1024 * 1024
            s.bind(InetSocketAddress(requestedPort))
        } catch (e: IOException) {
            s.close()
            throw e
        }
        server = s
        Thread({ acceptLoop(s) }, "mirage-listener").apply { isDaemon = true }.start()
    }

    fun stop() {
        val s = server ?: return
        server = null
        try { s.close() } catch (_: IOException) {}
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try { s.accept() } catch (_: IOException) { break }
            PeerLink.tune(socket)
            // The display listens, so it is the responder in the key exchange.
            val link = PeerLink(socket, Role.RESPONDER, pairingCode)
            val handler = onConnection
            if (handler == null) link.close() else handler(link)
        }
    }
}
