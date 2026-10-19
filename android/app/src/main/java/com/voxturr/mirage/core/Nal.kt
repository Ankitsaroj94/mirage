package com.voxturr.mirage.core

/** Conversions between the wire's 4-byte-length-prefixed NALs and MediaCodec's Annex-B. */
object Nal {
    /** Rewrites each 4-byte BE length prefix to 00 00 00 01, in place (same size). Returns false if malformed. */
    fun avccToAnnexBInPlace(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Boolean = TODO("agent 4")
    /** Splits an Annex-B stream (3- or 4-byte start codes) into raw NAL units. */
    fun splitAnnexB(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): List<ByteArray> = TODO("agent 4")
    /** Annex-B → 4-byte BE length-prefixed. */
    fun annexBToAvcc(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): ByteArray = TODO("agent 4")
    /** 00 00 00 01 + nal, concatenated — for MediaCodec csd-0. */
    fun withStartCodes(nals: List<ByteArray>): ByteArray = TODO("agent 4")
    /** HEVC nal_unit_type = (b0 >> 1) & 0x3f; H.264 = b0 & 0x1f. */
    fun hevcType(nal: ByteArray): Int = (nal[0].toInt() shr 1) and 0x3f
    fun h264Type(nal: ByteArray): Int = nal[0].toInt() and 0x1f
}
