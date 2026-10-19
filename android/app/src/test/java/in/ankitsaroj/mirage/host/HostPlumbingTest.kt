package `in`.ankitsaroj.mirage.host

import `in`.ankitsaroj.mirage.core.ClientHello
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** InjectSpec wire format, ServerArgs, display planning, codec choice, status lines. */
class HostPlumbingTest {

    @Test
    fun injectSpecRoundTrips() {
        val specs = listOf(
            InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_BUTTON_PRESS, 12.5f, 1799f, buttonState = 3, actionButton = 2, metaState = 0x1000, downTime = 123456789012L),
            InjectSpec(InjectSpec.KIND_MOTION, AndroidInput.ACTION_SCROLL, vScroll = -1.25f, hScroll = 0.5f),
            InjectSpec(InjectSpec.KIND_KEY, AndroidInput.KEY_ACTION_DOWN, keyCode = 29, repeatCount = 1, metaState = 1),
        )
        val bytes = InjectSpec.encode(specs)
        assertEquals(3 * InjectSpec.BYTES, bytes.size)
        assertEquals(specs, InjectSpec.decode(bytes))
        assertTrue(InjectSpec.decode(ByteArray(10)).isEmpty())   // truncated input is ignored
    }

    @Test
    fun serverArgsParseTheContract() {
        val a = ServerArgs.parse(arrayOf("--host", "192.168.1.20", "--port", "7250", "--code", "1234", "--bitrate", "20000",
            "--fps", "30", "--codec", "h264", "--no-input", "--cmd-as-meta", "--name", "Tab"))
        assertEquals("192.168.1.20", a.host)
        assertEquals(7250, a.port)
        assertEquals("1234", a.pairingCode)
        assertEquals("Tab", a.hostName)
        assertEquals(20_000, a.options.targetBitrateKbps)
        assertEquals(30, a.options.maxFps)
        assertTrue(a.options.forceH264)
        assertEquals(false, a.options.acceptRemoteInput)
        assertEquals(false, a.options.commandAsCtrl)

        val d = ServerArgs.parse(arrayOf("--host", "::1", "--port", "1"))
        assertNull(d.pairingCode)
        assertEquals(HostSession.Options(), d.options)
    }

    @Test
    fun serverArgsRejectNonsenseWithReadableMessages() {
        for ((args, message) in listOf(
            arrayOf("--port", "7250") to "--host is required",
            arrayOf("--host", "x") to "--port is required",
            arrayOf("--host", "x", "--port", "99999") to "--port out of range",
            arrayOf("--host", "x", "--port", "1", "--bogus") to "unknown argument --bogus",
            arrayOf("--host") to "missing value for --host",
            arrayOf("--host", "x", "--port", "1", "--codec", "vp9") to "--codec must be auto, hevc or h264",
        )) {
            try {
                ServerArgs.parse(args); fail("accepted ${args.toList()}")
            } catch (e: IllegalArgumentException) {
                assertEquals(message, e.message)
            }
        }
    }

    @Test
    fun manualAddresses() {
        assertEquals("192.168.1.20" to 7250, ServerArgs.parseAddress(" 192.168.1.20:7250 "))
        assertEquals("fe80::1" to 80, ServerArgs.parseAddress("[fe80::1]:80"))
        assertEquals("mac.local" to 1, ServerArgs.parseAddress("mac.local:1"))
        assertNull(ServerArgs.parseAddress("192.168.1.20"))
        assertNull(ServerArgs.parseAddress(":7250"))
        assertNull(ServerArgs.parseAddress("host:0"))
    }

    private fun hello(px: Int, py: Int, ptx: Int, pty: Int, hz: Int = 60) = ClientHello(
        deviceName = "MacBook", pixelWidth = px, pixelHeight = py, pointWidth = ptx, pointHeight = pty, refreshHz = hz,
        supportsHEVC = true, supportsH264 = true, wantsInputForwarding = true, platform = "macos",
    )

    @Test
    fun displayPlanFollowsSpecSection5() {
        // Retina MacBook Pro 14": 3024×1964 px, 1512×982 pt → 320 dpi, 120 Hz capped to 60.
        assertEquals(HostSession.DisplayPlan(3024, 1964, 320, 60), HostSession.plan(hello(3024, 1964, 1512, 982, 120), 60))
        // A 1× external monitor → 160 dpi; odd sizes rounded down to even for the encoder.
        assertEquals(HostSession.DisplayPlan(1920, 1080, 160, 60), HostSession.plan(hello(1921, 1081, 1921, 1081), 60))
        // Scaled Retina mode (1.5×): 2880×1800 px over 1920×1200 pt → 240 dpi; 30 Hz panel stays 30.
        assertEquals(HostSession.DisplayPlan(2880, 1800, 240, 30), HostSession.plan(hello(2880, 1800, 1920, 1200, 30), 60))
    }

    @Test
    fun codecChoice() {
        val hevc = "video/hevc"
        val avc = "video/avc"
        assertEquals(hevc, VideoEncoderAndroid.choose(2880, 1800, clientHevc = true, clientH264 = true) { _, _, _ -> true })
        assertEquals(avc, VideoEncoderAndroid.choose(2880, 1800, clientHevc = true, clientH264 = true) { m, _, _ -> m == avc })
        assertEquals(avc, VideoEncoderAndroid.choose(2880, 1800, clientHevc = false, clientH264 = true) { _, _, _ -> true })
        assertEquals(hevc, VideoEncoderAndroid.choose(9000, 9000, clientHevc = true, clientH264 = true) { _, _, _ -> false })
    }

    @Test
    fun parameterSetsAreOrderedForTheMac() {
        val vps = byteArrayOf(0x40, 0x01); val sps = byteArrayOf(0x42, 0x01); val pps = byteArrayOf(0x44, 0x01)
        val sei = byteArrayOf(0x4E, 0x01)
        assertEquals(listOf(vps, sps, pps), VideoEncoderAndroid.parameterSetOrder(listOf(sei, pps, sps, vps), hevc = true))
        assertNull(VideoEncoderAndroid.parameterSetOrder(listOf(sps, pps), hevc = true))
        val hSps = byteArrayOf(0x67, 0x64); val hPps = byteArrayOf(0x68, 0x01)
        assertEquals(listOf(hSps, hPps), VideoEncoderAndroid.parameterSetOrder(listOf(hPps, hSps), hevc = false))
    }

    @Test
    fun statusLinesMatchTheAdbContract() {
        assertEquals("CONNECTING 10.0.0.2:7250", HostSession.statusLine(HostSession.State.Connecting("10.0.0.2:7250")))
        assertEquals("CONNECTED", HostSession.statusLine(HostSession.State.Waiting))
        assertEquals("OK 7 3024x1964 hevc 60", HostSession.statusLine(HostSession.State.Running("MacBook", 3024, 1964, "hevc", 60, 7)))
        assertEquals("ERR a b", HostSession.statusLine(HostSession.State.Failed("a\nb")))
        assertEquals("BYE", HostSession.statusLine(HostSession.State.Ended))
        assertTrue(HostSession.statsLine(HostSession.Stats(fps = 60)).startsWith("STATS fps=60 "))
    }
}
