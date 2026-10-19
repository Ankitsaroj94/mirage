package com.voxturr.mirage.core

// JSON payloads — see spec/PROTOCOL.md §3. Must parse what Swift JSONEncoder emits
// (Data = base64, nil optionals omitted) and emit what Swift JSONDecoder accepts.

data class ClientHello(
    val protocolVersion: Int = PROTOCOL_VERSION,
    val deviceName: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val pointWidth: Int,
    val pointHeight: Int,
    val refreshHz: Int,
    val supportsHEVC: Boolean,
    val supportsH264: Boolean,
    val wantsInputForwarding: Boolean,
    val platform: String? = "android",
    val physicalDPI: Double? = null,
    val scalePreference: String? = null,
    val supportsAudio: Boolean? = null,
    val audioUDPPort: Int? = null,
    val orientation: String? = null,
) {
    fun toJson(): ByteArray = TODO("agent 4")
    companion object { fun fromJson(bytes: ByteArray): ClientHello = TODO("agent 4") }
}

data class HostHelloAck(
    val accepted: Boolean,
    val reason: String? = null,
    val hostName: String,
    val codec: String,          // "hevc" | "h264"
    val pixelWidth: Int,
    val pixelHeight: Int,
    val pointWidth: Int,
    val pointHeight: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val isHiDPI: Boolean,
    val platform: String? = null,
    val audioAvailable: Boolean? = null,
) {
    fun toJson(): ByteArray = TODO("agent 4")
    companion object { fun fromJson(bytes: ByteArray): HostHelloAck = TODO("agent 4") }
}

/** Raw NAL units (no start codes, no length prefix). HEVC: VPS,SPS,PPS. H.264: SPS,PPS. */
class VideoParameterSets(
    val codec: String,
    val width: Int,
    val height: Int,
    val parameterSets: List<ByteArray>,
    val nalLengthSize: Int = 4,
) {
    fun toJson(): ByteArray = TODO("agent 4")
    override fun equals(other: Any?): Boolean = TODO("agent 4")
    override fun hashCode(): Int = TODO("agent 4")
    companion object { fun fromJson(bytes: ByteArray): VideoParameterSets = TODO("agent 4") }
}

data class ClientStats(
    val framesDecoded: Int,
    val framesDropped: Int,
    val averageLatencyMs: Double,
    val decodeMsAverage: Double,
    val bytesReceived: Int,
) {
    fun toJson(): ByteArray = TODO("agent 4")
    companion object { fun fromJson(bytes: ByteArray): ClientStats = TODO("agent 4") }
}

data class AudioConfig(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val format: String = "s16le",
    val packetFrames: Int = 240,
    val playoutDelayMs: Int = 60,
    val encrypted: Boolean = false,
) {
    fun toJson(): ByteArray = TODO("agent 4")
    companion object { fun fromJson(bytes: ByteArray): AudioConfig = TODO("agent 4") }
}
