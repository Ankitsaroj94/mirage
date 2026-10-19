package com.voxturr.mirage.core

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Real localhost sockets, both ends Kotlin. */
class PeerLinkTest {
    private class Msg(val type: Int, val flags: Int, val ts: Long, val payload: ByteArray)

    /** One end of a pair, with its callbacks captured. */
    private class End(val link: PeerLink) {
        val messages = LinkedBlockingQueue<Msg>()
        val ready = CountDownLatch(1)
        val failed = CountDownLatch(1)
        @Volatile var readyCount = 0
        @Volatile var failureCount = 0
        @Volatile var failure: Throwable? = null

        init {
            link.onMessage = { t, f, ts, p -> messages.put(Msg(t, f, ts, p)) }
            link.onReady = { readyCount++; ready.countDown() }
            link.onFailure = { e -> failureCount++; failure = e; failed.countDown() }
        }

        fun next(timeoutMs: Long = 5_000): Msg = checkNotNull(messages.poll(timeoutMs, TimeUnit.MILLISECONDS)) { "no message" }
        fun awaitReady() = assertTrue("not ready", ready.await(5, TimeUnit.SECONDS))
        fun awaitFailure() = assertTrue("no failure", failed.await(5, TimeUnit.SECONDS))
    }

    private val listeners = mutableListOf<PeerListener>()
    private val links = mutableListOf<PeerLink>()

    @After fun tearDown() {
        links.forEach { it.close() }
        listeners.forEach { it.stop() }
    }

    /** host = initiator (connects out), client = responder (listens) — as in spec §9. */
    private fun pair(hostCode: String?, clientCode: String?, start: Boolean = true): Pair<End, End> {
        val accepted = LinkedBlockingQueue<PeerLink>()
        val listener = PeerListener(0, clientCode).also { listeners += it }
        listener.onConnection = { accepted.put(it) }
        listener.start()
        assertTrue(listener.port > 0)
        val host = End(PeerLink.connect(InetAddress.getLoopbackAddress(), listener.port, Role.INITIATOR, hostCode))
        val client = End(checkNotNull(accepted.poll(5, TimeUnit.SECONDS)))
        links += host.link; links += client.link
        assertEquals(Role.RESPONDER, client.link.role)
        if (start) { client.link.start(); host.link.start() }
        return host to client
    }

    @Test fun plaintextExchange() {
        val (host, client) = pair(null, null)
        host.awaitReady(); client.awaitReady()
        assertNull(host.link.channel)

        client.link.send(MessageType.HELLO, payload = "hi".toByteArray(), timestampMicros = 42)
        val m = host.next()
        assertEquals(MessageType.HELLO, m.type)
        assertEquals(42L, m.ts)
        assertEquals("hi", String(m.payload))

        host.link.send(MessageType.VIDEO_FRAME, FrameFlags.KEYFRAME, ByteArray(1000) { it.toByte() })
        val f = client.next()
        assertEquals(MessageType.VIDEO_FRAME, f.type)
        assertEquals(FrameFlags.KEYFRAME, f.flags)
        assertEquals(1000, f.payload.size)

        client.link.send(MessageType.REQUEST_KEYFRAME)                 // empty payload
        assertEquals(0, host.next().payload.size)
    }

    @Test fun encryptedExchange() {
        val (host, client) = pair("4821", "4821")
        host.awaitReady(); client.awaitReady()
        assertArrayEquals(host.link.channel!!.sessionKey, client.link.channel!!.sessionKey)

        client.link.send(MessageType.HELLO, payload = "{\"a\":1}".toByteArray())
        assertEquals("{\"a\":1}", String(host.next().payload))
        host.link.send(MessageType.HELLO_ACK, payload = "ok".toByteArray())
        assertEquals("ok", String(client.next().payload))
        assertEquals(1, host.readyCount); assertEquals(1, client.readyCount)
    }

    @Test fun messagesSentBeforeHandshakeAreHeldThenDelivered() {
        val (host, client) = pair("1111", "1111", start = false)
        // Queued before start() and before the key exchange — must arrive sealed, in order.
        host.link.send(MessageType.HELLO_ACK, payload = byteArrayOf(1))
        client.link.start(); host.link.start()
        host.link.send(MessageType.HELLO_ACK, payload = byteArrayOf(2))
        assertEquals(1, client.next().payload[0].toInt())
        assertEquals(2, client.next().payload[0].toInt())
        assertNotNull(client.link.channel)
    }

    @Test fun wrongCodeRejected() {
        val (host, client) = pair("1234", "9999")
        host.awaitFailure(); client.awaitFailure()
        // At least the side that checked the HMAC reports the mismatch.
        assertTrue(host.failure is SecureChannelException || client.failure is SecureChannelException)
        assertEquals(0, host.readyCount); assertEquals(0, client.readyCount)
        assertFalse(host.link.isOpen)
    }

    @Test fun codeOnOneSideOnlyFails() {
        val (host, client) = pair(null, "9999")
        // The host is "ready" at once and may send cleartext; the client must refuse it.
        client.awaitFailure()
        host.awaitFailure()
        assertEquals(0, client.readyCount)
    }

    @Test fun largeEncryptedMessage() {
        val (host, client) = pair("4821", "4821")
        val big = ByteArray(400 * 1024) { (it * 31 + 7).toByte() }
        host.link.send(MessageType.VIDEO_FRAME, FrameFlags.KEYFRAME, big)
        host.link.send(MessageType.VIDEO_FRAME, 0, big.copyOf(1))
        val m = client.next()
        assertArrayEquals(big, m.payload)
        assertEquals(1, client.next().payload.size)
    }

    @Test fun orderingAcrossThreadsEncrypted() {
        val (host, client) = pair("4821", "4821")
        host.awaitReady()
        val perThread = 2_000
        val threads = (0 until 4).map { t ->
            Thread {
                repeat(perThread) { i ->
                    val p = java.nio.ByteBuffer.allocate(8 + (i % 7) * 100).putInt(t).putInt(i).array()
                    host.link.send(MessageType.INPUT, payload = p)
                }
            }
        }
        threads.forEach { it.start() }
        val nextExpected = IntArray(4)
        repeat(4 * perThread) {
            val p = java.nio.ByteBuffer.wrap(client.next().payload)
            val t = p.int
            assertEquals("thread $t out of order", nextExpected[t], p.int)
            nextExpected[t]++
        }
        threads.forEach { it.join() }
        assertEquals("link failed: ${client.failure}", 1L, client.failed.count)
    }

    @Test fun unknownTypesAreOpenedAndDropped() {
        val (host, client) = pair("4821", "4821")
        host.link.send(0x99, payload = byteArrayOf(9, 9))                // a "future" type
        host.link.send(MessageType.BYE)
        // If the unknown message's counter were skipped, BYE would fail to open and kill the link.
        assertEquals(MessageType.BYE, client.next().type)
        assertTrue(client.link.isOpen)
    }

    @Test(timeout = 15_000)
    fun encryptedHeartbeatKeepsLinkAlive() {
        val (host, client) = pair("4821", "4821")
        host.awaitReady(); client.awaitReady()
        // v1 desynced encrypted links at the first ping; survive several, both directions.
        Thread.sleep(3_500)
        assertTrue(host.link.isOpen && client.link.isOpen)
        assertEquals(1L, host.failed.count); assertEquals(1L, client.failed.count)
        assertTrue("rtt ${host.link.roundTripMs}", host.link.roundTripMs > 0)
        assertTrue(client.link.roundTripMs > 0)
        // Same process, same MonotonicClock: the estimated offset must be ~0.
        assertTrue(host.link.clock.hasEstimate)
        assertTrue("offset ${host.link.clock.offsetMicros}", abs(host.link.clock.offsetMicros) < 5_000)
        // And application traffic still flows after the pings.
        host.link.send(MessageType.HELLO_ACK, payload = "still here".toByteArray())
        assertEquals("still here", String(client.next().payload))
    }

    @Test fun pendingBytesDrains() {
        val (host, client) = pair(null, null)
        host.awaitReady()
        val chunk = ByteArray(256 * 1024)
        repeat(40) { host.link.send(MessageType.VIDEO_FRAME, payload = chunk) }   // 10 MB burst
        assertTrue(host.link.pendingBytes >= 0)
        repeat(40) { client.next() }
        val deadline = System.currentTimeMillis() + 5_000
        while (host.link.pendingBytes != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(0, host.link.pendingBytes)
    }

    @Test fun closeIsIdempotentAndQuiet() {
        val (host, client) = pair(null, null)
        host.awaitReady(); client.awaitReady()
        host.link.send(MessageType.BYE)
        host.link.close()
        host.link.close()
        // The queued bye still goes out, then the peer sees the socket end.
        assertEquals(MessageType.BYE, client.next().type)
        client.awaitFailure()
        assertNull(client.failure)
        Thread.sleep(100)
        assertEquals(0, host.failureCount)                // close() never reports a failure
        assertEquals(1, client.failureCount)
        host.link.send(MessageType.PING)                  // dropped, no throw
    }

    @Test fun v1StylePongWithoutPayloadIsAccepted() {
        // A raw peer that answers pings the v1 way: empty pong.
        ServerSocket(0).use { server ->
            val link = End(PeerLink.connect(InetAddress.getLoopbackAddress(), server.localPort, Role.INITIATOR, null))
            links += link.link
            server.accept().use { raw ->
                link.link.start()
                val input = DataInputStream(raw.getInputStream())
                val header = ByteArray(16)
                input.readFully(header)
                val ping = MessageHeader.decode(header)
                assertEquals(MessageType.PING, ping.type)
                raw.getOutputStream().write(MessageHeader(MessageType.PONG, 0, 0, ping.timestampMicros).encoded())
                val deadline = System.currentTimeMillis() + 3_000
                while (link.link.roundTripMs == 0.0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertTrue(link.link.roundTripMs > 0)
                assertFalse(link.link.clock.hasEstimate)
                assertTrue(link.link.isOpen)
            }
        }
    }

    @Test fun answersPingWithV2Pong() {
        ServerSocket(0).use { server ->
            val link = End(PeerLink.connect(InetAddress.getLoopbackAddress(), server.localPort, Role.INITIATOR, null))
            links += link.link
            server.accept().use { raw: Socket ->
                link.link.start()
                raw.getOutputStream().write(MessageHeader(MessageType.PING, 0, 0, 123_456).encoded())
                val input = DataInputStream(raw.getInputStream())
                val header = ByteArray(16)
                while (true) {
                    input.readFully(header)
                    val h = MessageHeader.decode(header)
                    val body = ByteArray(h.payloadLength).also { input.readFully(it) }
                    if (h.type != MessageType.PONG) continue          // skip our own heartbeat pings
                    assertEquals(123_456L, h.timestampMicros)
                    assertEquals(8, body.size)
                    assertTrue(ClockSync.remoteClock(body)!! > 0)
                    break
                }
            }
        }
    }
}
