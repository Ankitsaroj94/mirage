import Foundation
import AVFoundation
import CoreMedia
import VideoToolbox

/// Decodes and presents the incoming stream.
///
/// Frames go straight into an `AVSampleBufferDisplayLayer` tagged *display immediately*.
/// That bypasses the layer's normal presentation queue: nothing is held back waiting for a
/// timebase, so a frame is on glass as soon as the hardware decoder is done with it.
final class VideoRenderer {

    let layer = AVSampleBufferDisplayLayer()

    private(set) var framesDecoded = 0
    private(set) var framesDropped = 0
    private(set) var decodeMsAverage: Double = 0

    private var formatDescription: CMFormatDescription?
    private var currentParameterSets: VideoParameterSets?
    private var awaitingKeyframe = true

    /// Raised when the renderer needs a fresh IDR — on startup, after a decoder fault, or
    /// when a frame arrives before we have parameter sets.
    var onNeedsKeyframe: (() -> Void)?

    /// Fires once, when the first frame is actually on screen.
    var onFirstFrame: (() -> Void)?
    private var hasShownFirstFrame = false

    init() {
        layer.videoGravity = .resizeAspect
        layer.backgroundColor = CGColor(red: 0, green: 0, blue: 0, alpha: 1)
        layer.preventsDisplaySleepDuringVideoPlayback = true
    }

    // MARK: Configuration

    func apply(parameterSets: VideoParameterSets) {
        guard parameterSets != currentParameterSets || formatDescription == nil else { return }
        currentParameterSets = parameterSets

        guard let created = Self.makeFormatDescription(from: parameterSets) else {
            Log.video("could not build a format description")
            return
        }

        formatDescription = created
        awaitingKeyframe = true
        hasShownFirstFrame = false
        flush()
        Log.video("decoder configured: \(parameterSets.codec.rawValue) \(parameterSets.width)x\(parameterSets.height)")
    }

    /// Rebuilds the decoder's format description from the parameter sets the host sent.
    static func makeFormatDescription(from parameterSets: VideoParameterSets) -> CMFormatDescription? {
        let pointers = parameterSets.parameterSets.map { data in
            data.withUnsafeBytes { raw -> (UnsafePointer<UInt8>, Int) in
                let copy = UnsafeMutablePointer<UInt8>.allocate(capacity: data.count)
                copy.update(from: raw.bindMemory(to: UInt8.self).baseAddress!, count: data.count)
                return (UnsafePointer(copy), data.count)
            }
        }
        defer { pointers.forEach { UnsafeMutablePointer(mutating: $0.0).deallocate() } }

        var pointerArray = pointers.map { $0.0 }
        var sizeArray = pointers.map { $0.1 }
        var created: CMFormatDescription?

        let status: OSStatus = parameterSets.codec == .hevc
            ? CMVideoFormatDescriptionCreateFromHEVCParameterSets(
                allocator: kCFAllocatorDefault,
                parameterSetCount: pointerArray.count,
                parameterSetPointers: &pointerArray,
                parameterSetSizes: &sizeArray,
                nalUnitHeaderLength: Int32(parameterSets.nalLengthSize),
                extensions: nil,
                formatDescriptionOut: &created)
            : CMVideoFormatDescriptionCreateFromH264ParameterSets(
                allocator: kCFAllocatorDefault,
                parameterSetCount: pointerArray.count,
                parameterSetPointers: &pointerArray,
                parameterSetSizes: &sizeArray,
                nalUnitHeaderLength: Int32(parameterSets.nalLengthSize),
                formatDescriptionOut: &created)

        guard status == noErr else {
            Log.video("CMVideoFormatDescriptionCreate failed (status \(status))")
            return nil
        }
        return created
    }

    func flush() {
        flushDecoder()
        Legacy.flushAndRemoveImage(layer)
    }

    /// `AVSampleBufferDisplayLayer`'s own flush/status/error are deprecated on macOS 15+ in
    /// favour of `sampleBufferRenderer`, which does not exist on macOS 13. The receiving Mac
    /// here is a 2017 model that tops out at macOS 13, so both paths have to stay.
    private func flushDecoder() {
        if #available(macOS 14.0, *) {
            layer.sampleBufferRenderer.flush()
        } else {
            Legacy.flush(layer)
        }
    }

    private var isLayerFailed: Bool {
        if #available(macOS 14.0, *) { return layer.sampleBufferRenderer.status == .failed }
        return Legacy.status(layer) == .failed
    }

    private var failureDescription: String {
        if #available(macOS 14.0, *) {
            return layer.sampleBufferRenderer.error?.localizedDescription ?? "unknown"
        }
        return Legacy.error(layer)?.localizedDescription ?? "unknown"
    }

    /// The pre-macOS-15 layer API, isolated here.
    ///
    /// These calls warn at build time and that is left deliberately visible: they are the
    /// only thing tying the app to `AVSampleBufferDisplayLayer` rather than
    /// `sampleBufferRenderer`, and they exist solely so the 2017 Mac — which cannot go past
    /// macOS 13 — is still supported. When that stops mattering, delete this enum and the
    /// `#available` branches that use it.
    private enum Legacy {
        static func flush(_ l: AVSampleBufferDisplayLayer) { l.flush() }
        static func flushAndRemoveImage(_ l: AVSampleBufferDisplayLayer) { l.flushAndRemoveImage() }
        static func status(_ l: AVSampleBufferDisplayLayer) -> AVQueuedSampleBufferRenderingStatus { l.status }
        static func error(_ l: AVSampleBufferDisplayLayer) -> Error? { l.error }
    }

    // MARK: Frames

    func enqueue(frameData: Data, isKeyframe: Bool) {
        guard let formatDescription else {
            onNeedsKeyframe?()
            return
        }
        if awaitingKeyframe {
            guard isKeyframe else { framesDropped += 1; return }
            awaitingKeyframe = false
        }

        // A failed layer never recovers on its own; reset it and ask for a new IDR.
        if isLayerFailed {
            Log.video("display layer failed: \(failureDescription); resetting")
            flushDecoder()
            awaitingKeyframe = true
            onNeedsKeyframe?()
            return
        }

        let start = MonotonicClock.millis()
        guard let sampleBuffer = Self.makeSampleBuffer(frameData: frameData, formatDescription: formatDescription) else {
            framesDropped += 1
            return
        }

        // Present as soon as decoded rather than scheduling against a timebase.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true),
           CFArrayGetCount(attachments) > 0 {
            let dict = unsafeBitCast(CFArrayGetValueAtIndex(attachments, 0), to: CFMutableDictionary.self)
            CFDictionarySetValue(dict,
                                 Unmanaged.passUnretained(kCMSampleAttachmentKey_DisplayImmediately).toOpaque(),
                                 Unmanaged.passUnretained(kCFBooleanTrue).toOpaque())
        }

        layer.enqueue(sampleBuffer)
        framesDecoded += 1

        if !hasShownFirstFrame {
            hasShownFirstFrame = true
            onFirstFrame?()
        }

        let elapsed = MonotonicClock.millis() - start
        decodeMsAverage = decodeMsAverage == 0 ? elapsed : decodeMsAverage * 0.9 + elapsed * 0.1
    }

    static func makeSampleBuffer(frameData: Data, formatDescription: CMFormatDescription) -> CMSampleBuffer? {
        var blockBuffer: CMBlockBuffer?
        let length = frameData.count

        // Copy into a CMBlockBuffer-owned allocation: the sample buffer outlives this call.
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: length,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: length,
            flags: 0,
            blockBufferOut: &blockBuffer)
        guard status == noErr, let blockBuffer else { return nil }

        status = frameData.withUnsafeBytes { raw in
            CMBlockBufferReplaceDataBytes(with: raw.baseAddress!, blockBuffer: blockBuffer,
                                          offsetIntoDestination: 0, dataLength: length)
        }
        guard status == noErr else { return nil }

        var sampleBuffer: CMSampleBuffer?
        var sampleSize = length
        var timing = CMSampleTimingInfo(duration: .invalid,
                                        presentationTimeStamp: .invalid,
                                        decodeTimeStamp: .invalid)
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer)
        guard status == noErr else { return nil }
        return sampleBuffer
    }

    static var supportsHardwareHEVC: Bool {
        VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
    }
}

extension VideoParameterSets: Equatable {
    static func == (lhs: VideoParameterSets, rhs: VideoParameterSets) -> Bool {
        lhs.codec == rhs.codec && lhs.width == rhs.width && lhs.height == rhs.height
            && lhs.nalLengthSize == rhs.nalLengthSize && lhs.parameterSets == rhs.parameterSets
    }
}
