package `in`.ankitsaroj.mirage.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonTest {
    private fun obj(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))

    @Test fun videoParameterSetsFromSwift() {
        val swift = Vectors.root.getJSONObject("json").getString("videoParameterSets").toByteArray()
        val sets = VideoParameterSets.fromJson(swift)
        assertEquals("hevc", sets.codec)
        assertEquals(2304, sets.width)
        assertEquals(1440, sets.height)
        assertEquals(4, sets.nalLengthSize)
        assertEquals(3, sets.parameterSets.size)
        assertArrayEquals(byteArrayOf(0x40, 0x01, 0x0C), sets.parameterSets[0])
        assertArrayEquals(byteArrayOf(0x42, 0x01), sets.parameterSets[1])
        assertArrayEquals(byteArrayOf(0x44, 0x01), sets.parameterSets[2])

        // Round trip, and content equality (not ByteArray identity).
        val again = VideoParameterSets.fromJson(sets.toJson())
        assertEquals(sets, again)
        assertEquals(sets.hashCode(), again.hashCode())
        // Same JSON object as Swift's, key order aside.
        val ours = obj(sets.toJson())
        val theirs = JSONObject(String(swift))
        assertEquals(theirs.keys().asSequence().toSortedSet(), ours.keys().asSequence().toSortedSet())
        for (key in theirs.keys()) assertEquals(key, theirs.get(key).toString(), ours.get(key).toString())
    }

    @Test fun videoParameterSetsInequality() {
        val a = VideoParameterSets("h264", 10, 10, listOf(byteArrayOf(1, 2)))
        assertFalse(a == VideoParameterSets("h264", 10, 10, listOf(byteArrayOf(1, 3))))
        assertFalse(a == VideoParameterSets("h264", 10, 10, listOf(byteArrayOf(1, 2), byteArrayOf())))
        assertTrue(a == VideoParameterSets("h264", 10, 10, listOf(byteArrayOf(1, 2))))
    }

    @Test fun clientHelloOmitsNulls() {
        val hello = ClientHello(
            deviceName = "Tab", pixelWidth = 2304, pixelHeight = 1440, pointWidth = 1152, pointHeight = 720,
            refreshHz = 60, supportsHEVC = true, supportsH264 = true, wantsInputForwarding = true,
            platform = null,
        )
        val json = obj(hello.toJson())
        for (key in listOf("platform", "physicalDPI", "scalePreference", "supportsAudio", "audioUDPPort", "orientation")) {
            assertFalse("$key must be omitted", json.has(key))
        }
        assertEquals(2, json.getInt("protocolVersion"))
        assertEquals(hello, ClientHello.fromJson(hello.toJson()))
    }

    @Test fun clientHelloV2RoundTrip() {
        val hello = ClientHello(
            deviceName = "Galaxy Tab S9 FE", pixelWidth = 2304, pixelHeight = 1440, pointWidth = 1152, pointHeight = 720,
            refreshHz = 60, supportsHEVC = true, supportsH264 = true, wantsInputForwarding = true,
            physicalDPI = 249.0, scalePreference = "crisp", supportsAudio = true, audioUDPPort = 50123, orientation = "landscape",
        )
        assertEquals("android", obj(hello.toJson()).getString("platform"))
        assertEquals(hello, ClientHello.fromJson(hello.toJson()))
    }

    @Test fun v1HelloDecodesWithDefaultsAndIgnoresUnknownKeys() {
        val v1 = """{"protocolVersion":1,"deviceName":"MacBook","pixelWidth":2880,"pixelHeight":1800,
            "pointWidth":1440,"pointHeight":900,"refreshHz":60,"supportsHEVC":true,"supportsH264":true,
            "wantsInputForwarding":true,"someFutureKey":{"x":1}}"""
        val hello = ClientHello.fromJson(v1.toByteArray())
        assertEquals(1, hello.protocolVersion)
        assertNull(hello.platform)
        assertNull(hello.physicalDPI)
        assertNull(hello.supportsAudio)
        assertNull(hello.audioUDPPort)
    }

    @Test fun hostHelloAck() {
        val v1 = """{"accepted":true,"hostName":"Mac","codec":"hevc","pixelWidth":2304,"pixelHeight":1440,
            "pointWidth":1152,"pointHeight":720,"fps":60,"bitrateKbps":40000,"isHiDPI":true}"""
        val ack = HostHelloAck.fromJson(v1.toByteArray())
        assertTrue(ack.isFromV1Host)
        assertNull(ack.reason)
        assertNull(ack.audioAvailable)
        assertFalse(obj(ack.toJson()).has("reason"))
        val v2 = ack.copy(platform = "android", audioAvailable = true, accepted = false, reason = "busy")
        assertEquals(v2, HostHelloAck.fromJson(v2.toJson()))
    }

    @Test fun statsAndAudioConfig() {
        val stats = ClientStats(10, 1, 12.5, 3.25, 123456)
        assertEquals(stats, ClientStats.fromJson(stats.toJson()))
        // NaN is not JSON; it must not throw or poison the message.
        assertEquals(0.0, ClientStats.fromJson(ClientStats(0, 0, Double.NaN, 0.0, 0).toJson()).averageLatencyMs, 0.0)

        val cfg = AudioConfig(sampleRate = 44_100, encrypted = true)
        assertEquals(cfg, AudioConfig.fromJson(cfg.toJson()))
        assertEquals(AudioConfig(), AudioConfig.fromJson("{}".toByteArray()))
    }
}
