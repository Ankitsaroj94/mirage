package com.voxturr.mirage.core

// JSON payloads — see spec/PROTOCOL.md §3. Must parse what Swift JSONEncoder emits
// (Data = base64, nil optionals omitted) and emit what Swift JSONDecoder accepts.
//
// Decoders read required v1 keys strictly (a missing one throws, as Swift's would) and every
// v2 key leniently: absent or null means "a v1 peer", never an error. Unknown keys are ignored.

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

private fun JSONObject.bytes(): ByteArray = toString().toByteArray(Charsets.UTF_8)
private fun parse(bytes: ByteArray) = JSONObject(String(bytes, Charsets.UTF_8))

/** Swift's JSONEncoder omits nil optionals; JSONObject.put(k, null) would remove anyway, but be explicit. */
private fun JSONObject.putOpt(key: String, value: Any?): JSONObject = if (value == null) this else put(key, value)

/** org.json refuses NaN/∞, and so would Swift's decoder — send 0 instead of dropping the message. */
private fun finite(d: Double) = if (d.isFinite()) d else 0.0

private fun JSONObject.stringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
private fun JSONObject.optIntOrNull(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key) else null
private fun JSONObject.optBoolOrNull(key: String): Boolean? = if (has(key) && !isNull(key)) getBoolean(key) else null

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
    fun toJson(): ByteArray = JSONObject()
        .put("protocolVersion", protocolVersion)
        .put("deviceName", deviceName)
        .put("pixelWidth", pixelWidth).put("pixelHeight", pixelHeight)
        .put("pointWidth", pointWidth).put("pointHeight", pointHeight)
        .put("refreshHz", refreshHz)
        .put("supportsHEVC", supportsHEVC).put("supportsH264", supportsH264)
        .put("wantsInputForwarding", wantsInputForwarding)
        .putOpt("platform", platform)
        .putOpt("physicalDPI", physicalDPI?.let(::finite))
        .putOpt("scalePreference", scalePreference)
        .putOpt("supportsAudio", supportsAudio)
        .putOpt("audioUDPPort", audioUDPPort)
        .putOpt("orientation", orientation)
        .bytes()

    companion object {
        /** A v1 hello leaves every v2 field null — including platform, which then means macOS. */
        fun fromJson(bytes: ByteArray): ClientHello = parse(bytes).run {
            ClientHello(
                protocolVersion = getInt("protocolVersion"),
                deviceName = getString("deviceName"),
                pixelWidth = getInt("pixelWidth"), pixelHeight = getInt("pixelHeight"),
                pointWidth = getInt("pointWidth"), pointHeight = getInt("pointHeight"),
                refreshHz = getInt("refreshHz"),
                supportsHEVC = getBoolean("supportsHEVC"), supportsH264 = getBoolean("supportsH264"),
                wantsInputForwarding = getBoolean("wantsInputForwarding"),
                platform = stringOrNull("platform"),
                physicalDPI = optDoubleOrNull("physicalDPI"),
                scalePreference = stringOrNull("scalePreference"),
                supportsAudio = optBoolOrNull("supportsAudio"),
                audioUDPPort = optIntOrNull("audioUDPPort"),
                orientation = stringOrNull("orientation"),
            )
        }
    }
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
    /** Only a v2 host fills `platform`, so its absence identifies a v1 (Mac) host. */
    val isFromV1Host: Boolean get() = platform == null

    fun toJson(): ByteArray = JSONObject()
        .put("accepted", accepted)
        .putOpt("reason", reason)
        .put("hostName", hostName)
        .put("codec", codec)
        .put("pixelWidth", pixelWidth).put("pixelHeight", pixelHeight)
        .put("pointWidth", pointWidth).put("pointHeight", pointHeight)
        .put("fps", fps)
        .put("bitrateKbps", bitrateKbps)
        .put("isHiDPI", isHiDPI)
        .putOpt("platform", platform)
        .putOpt("audioAvailable", audioAvailable)
        .bytes()

    companion object {
        fun fromJson(bytes: ByteArray): HostHelloAck = parse(bytes).run {
            HostHelloAck(
                accepted = getBoolean("accepted"),
                reason = stringOrNull("reason"),
                hostName = getString("hostName"),
                codec = getString("codec"),
                pixelWidth = getInt("pixelWidth"), pixelHeight = getInt("pixelHeight"),
                pointWidth = getInt("pointWidth"), pointHeight = getInt("pointHeight"),
                fps = getInt("fps"),
                bitrateKbps = getInt("bitrateKbps"),
                isHiDPI = getBoolean("isHiDPI"),
                platform = stringOrNull("platform"),
                audioAvailable = optBoolOrNull("audioAvailable"),
            )
        }
    }
}

/** Raw NAL units (no start codes, no length prefix). HEVC: VPS,SPS,PPS. H.264: SPS,PPS. */
class VideoParameterSets(
    val codec: String,
    val width: Int,
    val height: Int,
    val parameterSets: List<ByteArray>,
    val nalLengthSize: Int = 4,
) {
    /** Swift encodes `[Data]` as base64 strings (standard alphabet, padded). */
    fun toJson(): ByteArray = JSONObject()
        .put("codec", codec)
        .put("width", width)
        .put("height", height)
        .put("parameterSets", JSONArray().apply { parameterSets.forEach { put(Base64.getEncoder().encodeToString(it)) } })
        .put("nalLengthSize", nalLengthSize)
        .bytes()

    // By content: the receiver compares incoming sets with the current ones to decide whether
    // the decoder needs a reset, and ByteArray equality is identity.
    override fun equals(other: Any?): Boolean =
        other is VideoParameterSets && codec == other.codec && width == other.width && height == other.height &&
            nalLengthSize == other.nalLengthSize && parameterSets.size == other.parameterSets.size &&
            parameterSets.indices.all { parameterSets[it].contentEquals(other.parameterSets[it]) }

    override fun hashCode(): Int {
        var h = codec.hashCode()
        h = 31 * h + width
        h = 31 * h + height
        h = 31 * h + nalLengthSize
        parameterSets.forEach { h = 31 * h + it.contentHashCode() }
        return h
    }

    override fun toString(): String =
        "VideoParameterSets($codec ${width}x$height, ${parameterSets.map { it.size }} bytes, nal=$nalLengthSize)"

    companion object {
        fun fromJson(bytes: ByteArray): VideoParameterSets = parse(bytes).run {
            val sets = getJSONArray("parameterSets")
            VideoParameterSets(
                codec = getString("codec"),
                width = getInt("width"),
                height = getInt("height"),
                parameterSets = List(sets.length()) { Base64.getDecoder().decode(sets.getString(it)) },
                // Swift always sends it; default only so a hand-written message still decodes.
                nalLengthSize = optInt("nalLengthSize", 4),
            )
        }
    }
}

data class ClientStats(
    val framesDecoded: Int,
    val framesDropped: Int,
    val averageLatencyMs: Double,
    val decodeMsAverage: Double,
    val bytesReceived: Int,
    /** v2: playout delay this client needs to play every audio packet on time (spec §7). */
    val audioDelayNeededMs: Int? = null,
) {
    fun toJson(): ByteArray = JSONObject()
        .apply { audioDelayNeededMs?.let { put("audioDelayNeededMs", it) } }
        .put("framesDecoded", framesDecoded)
        .put("framesDropped", framesDropped)
        .put("averageLatencyMs", finite(averageLatencyMs))
        .put("decodeMsAverage", finite(decodeMsAverage))
        .put("bytesReceived", bytesReceived)
        .bytes()

    companion object {
        fun fromJson(bytes: ByteArray): ClientStats = parse(bytes).run {
            ClientStats(
                framesDecoded = getInt("framesDecoded"),
                framesDropped = getInt("framesDropped"),
                averageLatencyMs = getDouble("averageLatencyMs"),
                decodeMsAverage = getDouble("decodeMsAverage"),
                bytesReceived = getInt("bytesReceived"),
                audioDelayNeededMs = if (has("audioDelayNeededMs")) getInt("audioDelayNeededMs") else null,
            )
        }
    }
}

data class AudioConfig(
    val sampleRate: Int = 48_000,
    val channels: Int = 2,
    val format: String = "s16le",
    val packetFrames: Int = 240,
    val playoutDelayMs: Int = 100,
    val encrypted: Boolean = false,
) {
    fun toJson(): ByteArray = JSONObject()
        .put("sampleRate", sampleRate)
        .put("channels", channels)
        .put("format", format)
        .put("packetFrames", packetFrames)
        .put("playoutDelayMs", playoutDelayMs)
        .put("encrypted", encrypted)
        .bytes()

    companion object {
        /** Every key defaults, like the Swift struct's property defaults. */
        fun fromJson(bytes: ByteArray): AudioConfig = parse(bytes).run {
            val d = AudioConfig()
            AudioConfig(
                sampleRate = optInt("sampleRate", d.sampleRate),
                channels = optInt("channels", d.channels),
                format = stringOrNull("format") ?: d.format,
                packetFrames = optInt("packetFrames", d.packetFrames),
                playoutDelayMs = optInt("playoutDelayMs", d.playoutDelayMs),
                encrypted = optBoolean("encrypted", d.encrypted),
            )
        }
    }
}
