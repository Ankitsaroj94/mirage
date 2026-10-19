package `in`.ankitsaroj.mirage.receiver

/**
 * Cheap inspection of a length-prefixed (AVCC/HVCC, 4-byte BE) access unit, *before* it is
 * rewritten to Annex-B. Pure; no allocation.
 */
object NalInspect {

    /**
     * True unless the first slice NAL is provably non-reference — i.e. it is safe to drop this
     * frame without corrupting the ones after it. Malformed input counts as reference.
     *  - HEVC: VCL types 0..14 that are even (TRAIL_N, TSA_N, STSA_N, RADL_N, RASL_N, RSV_VCL_N*).
     *  - H.264: nal_ref_idc == 0 on a slice (types 1..5).
     */
    fun isReferenceFrame(buf: ByteArray, offset: Int, length: Int, hevc: Boolean): Boolean {
        var p = offset
        val end = offset + length
        while (p + 4 < end) {
            val n = ((buf[p].toInt() and 0xff) shl 24) or ((buf[p + 1].toInt() and 0xff) shl 16) or
                ((buf[p + 2].toInt() and 0xff) shl 8) or (buf[p + 3].toInt() and 0xff)
            if (n <= 0 || p + 4 + n > end) return true
            val b0 = buf[p + 4].toInt() and 0xff
            if (hevc) {
                val type = (b0 shr 1) and 0x3f
                if (type < 32) return !(type <= 14 && type % 2 == 0)
            } else {
                val type = b0 and 0x1f
                if (type in 1..5) return ((b0 shr 5) and 3) != 0
            }
            p += 4 + n
        }
        return true
    }
}
