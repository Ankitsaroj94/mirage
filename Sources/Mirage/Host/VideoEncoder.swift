import Foundation
import VideoToolbox
import CoreMedia
import CoreVideo

/// Hardware H.265/H.264 encoder configured for screen content at minimum latency.
///
/// Three settings do most of the work here:
///   * low-latency rate control, which removes the encoder's lookahead buffer entirely
///   * B-frames disabled, so a frame never waits on a future frame to be decodable
///   * a very long keyframe interval, because the receiver asks for an IDR when it
///     actually needs one instead of us spending bandwidth on periodic ones
final class VideoEncoder {

    struct EncodedFrame {
        let data: Data              // AVCC / HVCC: 4-byte big-endian length prefix per NAL
        let isKeyframe: Bool
        let captureTimeMicros: UInt64
    }

    struct Configuration {
        var codec: VideoCodec
        var width: Int
        var height: Int
        var fps: Int
        var bitrateKbps: Int
        /// Caps how far quality is allowed to fall on a busy frame. Screen text goes to
        /// mush above roughly QP 40, so we would rather drop a frame than blur one.
        var maxAllowedQP: Int?
    }

    private(set) var configuration: Configuration
    private var session: VTCompressionSession?
    private let lock = NSLock()

    var onEncodedFrame: ((EncodedFrame) -> Void)?
    var onParameterSets: ((VideoParameterSets) -> Void)?

    private var lastFormatDescription: CMFormatDescription?
    private var forceKeyframeNext = false
    private var frameIndex: Int64 = 0

    init(configuration: Configuration) {
        self.configuration = configuration
    }

    deinit { stop() }

    // MARK: Lifecycle

    func start() throws {
        stop()

        let encoderSpec: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true,
            kVTVideoEncoderSpecification_RequireHardwareAcceleratedVideoEncoder: true,
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true,
        ]

        // NV12 in, matching what ScreenCaptureKit hands us, so there is no pixel conversion
        // between capture and encode.
        let sourceAttributes: [CFString: Any] = [
            kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            kCVPixelBufferMetalCompatibilityKey: true,
            kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary,
        ]

        var session: VTCompressionSession?
        let status = VTCompressionSessionCreate(
            allocator: kCFAllocatorDefault,
            width: Int32(configuration.width),
            height: Int32(configuration.height),
            codecType: configuration.codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264,
            encoderSpecification: encoderSpec as CFDictionary,
            imageBufferAttributes: sourceAttributes as CFDictionary,
            compressedDataAllocator: nil,
            outputCallback: nil,
            refcon: nil,
            compressionSessionOut: &session)

        guard status == noErr, let session else {
            throw MirageError("Could not create the \(configuration.codec.rawValue.uppercased()) encoder (status \(status)).")
        }
        self.session = session

        set(kVTCompressionPropertyKey_RealTime, true)
        set(kVTCompressionPropertyKey_AllowFrameReordering, false)     // no B-frames
        set(kVTCompressionPropertyKey_AllowTemporalCompression, true)
        set(kVTCompressionPropertyKey_ProfileLevel,
            configuration.codec == .hevc ? kVTProfileLevel_HEVC_Main_AutoLevel
                                         : kVTProfileLevel_H264_High_AutoLevel)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, configuration.fps as CFNumber)

        // Long GOP. The receiver requests an IDR on connect and after any loss.
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, 600 as CFNumber)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 10 as CFNumber)

        if configuration.codec == .h264 {
            set(kVTCompressionPropertyKey_H264EntropyMode, kVTH264EntropyMode_CABAC)
        }

        // Rec.709 / sRGB end to end. Tagging both sides identically avoids a colour
        // transform on playback, which would otherwise soften glyph edges.
        set(kVTCompressionPropertyKey_ColorPrimaries, kCVImageBufferColorPrimaries_ITU_R_709_2)
        set(kVTCompressionPropertyKey_TransferFunction, kCVImageBufferTransferFunction_ITU_R_709_2)
        set(kVTCompressionPropertyKey_YCbCrMatrix, kCVImageBufferYCbCrMatrix_ITU_R_709_2)

        if let qp = configuration.maxAllowedQP {
            set(kVTCompressionPropertyKey_MaxAllowedFrameQP, qp as CFNumber)
        }

        applyBitrate(configuration.bitrateKbps)
        VTCompressionSessionPrepareToEncodeFrames(session)

        Log.video("encoder up: \(configuration.codec.rawValue) \(configuration.width)x\(configuration.height) @\(configuration.fps) \(configuration.bitrateKbps)kbps")
    }

    /// Pushes any frame the encoder is still holding out through the callback.
    func flush() {
        lock.lock(); let session = self.session; lock.unlock()
        guard let session else { return }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
    }

    func stop() {
        lock.lock(); defer { lock.unlock() }
        guard let session else { return }
        VTCompressionSessionCompleteFrames(session, untilPresentationTimeStamp: .invalid)
        VTCompressionSessionInvalidate(session)
        self.session = nil
        self.lastFormatDescription = nil
    }

    // MARK: Runtime control

    func requestKeyframe() {
        lock.lock(); forceKeyframeNext = true; lock.unlock()
    }

    /// Adjusts the target bitrate without restarting the session, so there is no
    /// visible glitch when the link speed changes.
    func updateBitrate(kbps: Int) {
        lock.lock()
        guard configuration.bitrateKbps != kbps else { lock.unlock(); return }
        configuration.bitrateKbps = kbps
        lock.unlock()
        applyBitrate(kbps)
    }

    private func applyBitrate(_ kbps: Int) {
        let bitsPerSecond = kbps * 1000
        set(kVTCompressionPropertyKey_AverageBitRate, bitsPerSecond as CFNumber)
        // Hard ceiling just above the average over a one-second window. Keeps a sudden
        // full-screen redraw from blowing out the Wi-Fi queue and spiking latency.
        let bytesPerSecond = Double(bitsPerSecond) / 8.0 * 1.4
        set(kVTCompressionPropertyKey_DataRateLimits, [bytesPerSecond, 1.0] as CFArray)
    }

    private func set(_ key: CFString, _ value: Any) {
        guard let session else { return }
        VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
    }

    // MARK: Encoding

    func encode(pixelBuffer: CVPixelBuffer, captureTimeMicros: UInt64) {
        lock.lock()
        guard let session else { lock.unlock(); return }
        let forceKey = forceKeyframeNext
        forceKeyframeNext = false
        let index = frameIndex
        frameIndex += 1
        lock.unlock()

        let pts = CMTime(value: index, timescale: CMTimeScale(configuration.fps))
        let duration = CMTime(value: 1, timescale: CMTimeScale(configuration.fps))
        let properties: CFDictionary? = forceKey
            ? [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
            : nil

        VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: pts,
            duration: duration,
            frameProperties: properties,
            infoFlagsOut: nil) { [weak self] status, _, sampleBuffer in
                guard let self, status == noErr, let sampleBuffer,
                      CMSampleBufferDataIsReady(sampleBuffer) else { return }
                self.handleEncoded(sampleBuffer, captureTimeMicros: captureTimeMicros)
            }
    }

    private func handleEncoded(_ sampleBuffer: CMSampleBuffer, captureTimeMicros: UInt64) {
        let isKeyframe = Self.isKeyframe(sampleBuffer)

        // Parameter sets change only on (re)start, but we resend them with every IDR so a
        // client that joins or recovers mid-stream can always build its decoder.
        if isKeyframe, let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            if lastFormatDescription == nil || !CMFormatDescriptionEqual(format, otherFormatDescription: lastFormatDescription) {
                lastFormatDescription = format
            }
            if let sets = Self.parameterSets(from: format, codec: configuration.codec) {
                onParameterSets?(sets)
            }
        }

        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }
        var length = 0
        var pointer: UnsafeMutablePointer<Int8>?
        guard CMBlockBufferGetDataPointer(blockBuffer, atOffset: 0, lengthAtOffsetOut: nil,
                                          totalLengthOut: &length, dataPointerOut: &pointer) == noErr,
              let pointer else { return }

        // VideoToolbox already emits length-prefixed NAL units, which is exactly the
        // layout AVSampleBufferDisplayLayer wants on the far end. No Annex-B round trip.
        let data = Data(bytes: pointer, count: length)
        onEncodedFrame?(EncodedFrame(data: data, isKeyframe: isKeyframe, captureTimeMicros: captureTimeMicros))
    }

    private static func isKeyframe(_ sampleBuffer: CMSampleBuffer) -> Bool {
        guard let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false),
              CFArrayGetCount(attachments) > 0 else { return true }
        let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFDictionary.self)
        guard let raw = CFDictionaryGetValue(dict, Unmanaged.passUnretained(kCMSampleAttachmentKey_NotSync).toOpaque()) else {
            return true   // absence of NotSync means it is a sync sample
        }
        return !CFBooleanGetValue(unsafeBitCast(raw, to: CFBoolean.self))
    }

    static func parameterSets(from format: CMFormatDescription, codec: VideoCodec) -> VideoParameterSets? {
        var count = 0
        var nalLengthSize: Int32 = 4

        let countStatus: OSStatus = codec == .hevc
            ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, parameterSetIndex: 0,
                parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLengthSize)
            : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: 0,
                parameterSetPointerOut: nil, parameterSetSizeOut: nil,
                parameterSetCountOut: &count, nalUnitHeaderLengthOut: &nalLengthSize)
        guard countStatus == noErr, count > 0 else { return nil }

        var sets: [Data] = []
        for index in 0..<count {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            let status: OSStatus = codec == .hevc
                ? CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(format, parameterSetIndex: index,
                    parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
                : CMVideoFormatDescriptionGetH264ParameterSetAtIndex(format, parameterSetIndex: index,
                    parameterSetPointerOut: &pointer, parameterSetSizeOut: &size,
                    parameterSetCountOut: nil, nalUnitHeaderLengthOut: nil)
            guard status == noErr, let pointer else { return nil }
            sets.append(Data(bytes: pointer, count: size))
        }

        let dimensions = CMVideoFormatDescriptionGetDimensions(format)
        return VideoParameterSets(codec: codec,
                                  width: Int(dimensions.width),
                                  height: Int(dimensions.height),
                                  parameterSets: sets,
                                  nalLengthSize: Int(nalLengthSize))
    }
}

struct MirageError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}
