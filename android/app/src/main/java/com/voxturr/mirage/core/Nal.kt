package com.voxturr.mirage.core

import java.io.ByteArrayOutputStream

/**
 * Conversions between the wire's 4-byte-length-prefixed NALs and MediaCodec's Annex-B.
 *
 * The wire carries AVCC/HVCC framing because that is what VideoToolbox produces on the Mac;
 * MediaCodec wants start codes. A 4-byte length and a 4-byte start code are the same size, so
 * the receiver's hot path rewrites in place instead of copying a frame.
 */
object Nal {
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Rewrites each 4-byte BE length prefix to 00 00 00 01, in place (same size). Returns false if malformed. */
    fun avccToAnnexBInPlace(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Boolean {
        val end = offset + length
        if (offset < 0 || length < 0 || end > buf.size) return false
        // Validate first, so a malformed frame is left untouched rather than half-rewritten.
        var pos = offset
        while (pos < end) {
            if (end - pos < 4) return false
            val n = readLength(buf, pos)
            if (n < 0 || n > end - pos - 4) return false
            pos += 4 + n
        }
        pos = offset
        while (pos < end) {
            val n = readLength(buf, pos)
            START_CODE.copyInto(buf, pos)
            pos += 4 + n
        }
        return true
    }

    /** Splits an Annex-B stream (3- or 4-byte start codes) into raw NAL units. */
    fun splitAnnexB(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): List<ByteArray> {
        val end = offset + length
        val nals = ArrayList<ByteArray>()
        var start = -1                                  // first byte of the current NAL
        var i = offset
        while (i + 2 < end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) {
                if (start >= 0) {
                    // A zero just before 00 00 01 is the first byte of a 4-byte start code.
                    val nalEnd = if (i > start && buf[i - 1].toInt() == 0) i - 1 else i
                    if (nalEnd > start) nals += buf.copyOfRange(start, nalEnd)
                }
                i += 3
                start = i
            } else {
                i++
            }
        }
        if (start in 0 until end) nals += buf.copyOfRange(start, end)
        return nals
    }

    /** Annex-B → 4-byte BE length-prefixed. */
    fun annexBToAvcc(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): ByteArray {
        val nals = splitAnnexB(buf, offset, length)
        val out = ByteArray(nals.sumOf { 4 + it.size })
        var pos = 0
        for (nal in nals) {
            out[pos] = (nal.size ushr 24).toByte()
            out[pos + 1] = (nal.size ushr 16).toByte()
            out[pos + 2] = (nal.size ushr 8).toByte()
            out[pos + 3] = nal.size.toByte()
            nal.copyInto(out, pos + 4)
            pos += 4 + nal.size
        }
        return out
    }

    /** 00 00 00 01 + nal, concatenated — for MediaCodec csd-0. */
    fun withStartCodes(nals: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(nals.sumOf { 4 + it.size })
        for (nal in nals) { out.write(START_CODE); out.write(nal) }
        return out.toByteArray()
    }

    /** HEVC nal_unit_type = (b0 >> 1) & 0x3f; H.264 = b0 & 0x1f. */
    fun hevcType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3f
    fun h264Type(nal: ByteArray): Int = nal[0].toInt() and 0x1f

    private fun readLength(buf: ByteArray, pos: Int): Int =
        ((buf[pos].toInt() and 0xff) shl 24) or ((buf[pos + 1].toInt() and 0xff) shl 16) or
            ((buf[pos + 2].toInt() and 0xff) shl 8) or (buf[pos + 3].toInt() and 0xff)
}
