package `in`.ankitsaroj.mirage.receiver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NalInspectTest {

    private fun avcc(vararg nals: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (n in nals) {
            out.write(byteArrayOf((n.size ushr 24).toByte(), (n.size ushr 16).toByte(), (n.size ushr 8).toByte(), n.size.toByte()))
            out.write(n)
        }
        return out.toByteArray()
    }

    private fun hevcNal(type: Int, len: Int = 6) = ByteArray(len).also { it[0] = (type shl 1).toByte(); it[1] = 1 }
    private fun h264Nal(refIdc: Int, type: Int, len: Int = 6) = ByteArray(len).also { it[0] = ((refIdc shl 5) or type).toByte() }

    private fun hevcRef(vararg nals: ByteArray) = avcc(*nals).let { NalInspect.isReferenceFrame(it, 0, it.size, hevc = true) }
    private fun h264Ref(vararg nals: ByteArray) = avcc(*nals).let { NalInspect.isReferenceFrame(it, 0, it.size, hevc = false) }

    @Test fun hevcTrailNIsNonReference() = assertFalse(hevcRef(hevcNal(0)))
    @Test fun hevcTrailRIsReference() = assertTrue(hevcRef(hevcNal(1)))
    @Test fun hevcIdrIsReference() = assertTrue(hevcRef(hevcNal(32), hevcNal(33), hevcNal(34), hevcNal(19)))
    @Test fun hevcSkipsSeiBeforeSlice() = assertFalse(hevcRef(hevcNal(39), hevcNal(35), hevcNal(0)))
    @Test fun hevcRaslNIsNonReference() = assertFalse(hevcRef(hevcNal(8)))

    @Test fun h264NonRefSlice() = assertFalse(h264Ref(h264Nal(0, 6), h264Nal(0, 1)))
    @Test fun h264RefSlice() = assertTrue(h264Ref(h264Nal(2, 1)))
    @Test fun h264Idr() = assertTrue(h264Ref(h264Nal(3, 7), h264Nal(3, 8), h264Nal(3, 5)))

    @Test fun malformedCountsAsReference() {
        val bad = byteArrayOf(0, 0, 0, 50, 2, 1) // length runs past the end
        assertTrue(NalInspect.isReferenceFrame(bad, 0, bad.size, hevc = true))
        assertTrue(NalInspect.isReferenceFrame(ByteArray(0), 0, 0, hevc = true))
    }

    @Test fun respectsOffsetAndLength() {
        val inner = avcc(hevcNal(0))
        val buf = ByteArray(3) + inner + ByteArray(4)
        assertFalse(NalInspect.isReferenceFrame(buf, 3, inner.size, hevc = true))
    }
}
