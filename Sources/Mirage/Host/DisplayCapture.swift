import Foundation
import ScreenCaptureKit
import CoreMedia
import CoreVideo

/// Captures one display with ScreenCaptureKit and hands out NV12 frames.
///
/// ScreenCaptureKit only delivers a frame when the display content actually changes, so a
/// static page of text costs essentially nothing and leaves the whole bitrate budget for
/// the moments something moves.
final class DisplayCapture: NSObject, SCStreamOutput, SCStreamDelegate {

    struct Configuration {
        var displayID: CGDirectDisplayID
        /// Backing-store size of the display being captured.
        var pixelWidth: Int
        var pixelHeight: Int
        /// Size of the frames handed out — the encoder's size. Equal to the display's size
        /// except for a "more space"-style plan (see `DisplayGeometry`).
        var streamWidth: Int
        var streamHeight: Int
        var fps: Int
        var showsCursor: Bool

        init(displayID: CGDirectDisplayID, pixelWidth: Int, pixelHeight: Int,
             streamWidth: Int? = nil, streamHeight: Int? = nil, fps: Int, showsCursor: Bool) {
            self.displayID = displayID
            self.pixelWidth = pixelWidth
            self.pixelHeight = pixelHeight
            self.streamWidth = streamWidth ?? pixelWidth
            self.streamHeight = streamHeight ?? pixelHeight
            self.fps = fps
            self.showsCursor = showsCursor
        }

        var scalesToFit: Bool { streamWidth != pixelWidth || streamHeight != pixelHeight }
    }

    var onFrame: ((CVPixelBuffer, UInt64) -> Void)?
    var onStopped: ((Error?) -> Void)?

    private var stream: SCStream?
    private let configuration: Configuration
    private let outputQueue = DispatchQueue(label: "com.voxturr.mirage.capture", qos: .userInteractive)

    init(configuration: Configuration) {
        self.configuration = configuration
    }

    func start() async throws {
        // The virtual display has only just been attached by the helper process, and
        // ScreenCaptureKit's shareable-content list can lag the display arrangement by a
        // beat. Retry briefly rather than failing a connection over a race.
        var found: SCDisplay?
        var lastError: Error?
        for attempt in 0..<12 {
            do {
                let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
                if let match = content.displays.first(where: { $0.displayID == configuration.displayID }) {
                    found = match
                    break
                }
            } catch {
                lastError = error
                // A permission denial will never resolve by waiting, so surface it at once.
                if (error as NSError).code == -3801 { break }
            }
            if attempt < 11 { try? await Task.sleep(nanoseconds: 250_000_000) }
        }

        guard let display = found else {
            if let lastError, (lastError as NSError).code == -3801 {
                throw MirageError("Mirage needs Screen Recording permission to capture the extra display. Grant it in System Settings › Privacy & Security › Screen Recording, then quit and reopen Mirage.")
            }
            throw MirageError("ScreenCaptureKit never saw the extra display (id \(configuration.displayID)). Try disconnecting and connecting again.")
        }

        let streamConfig = SCStreamConfiguration()

        // Capture at the exact backing-store size of the virtual display. Any mismatch here
        // would introduce a resample, which is the fastest way to make text look fuzzy —
        // so it only happens when the plan asks for it (a scaled "more space" mode), and
        // then on the GPU inside ScreenCaptureKit, never on the CPU.
        streamConfig.width  = configuration.streamWidth
        streamConfig.height = configuration.streamHeight

        streamConfig.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        streamConfig.colorSpaceName = CGColorSpace.sRGB
        streamConfig.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(configuration.fps))
        streamConfig.queueDepth = 6
        streamConfig.showsCursor = configuration.showsCursor
        streamConfig.scalesToFit = configuration.scalesToFit
        streamConfig.capturesAudio = false

        if #available(macOS 14.0, *) {
            streamConfig.captureResolution = .best
        }

        let filter = SCContentFilter(display: display, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: streamConfig, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: outputQueue)
        try await stream.startCapture()
        self.stream = stream

        Log.video("capturing display \(configuration.displayID) at \(configuration.pixelWidth)x\(configuration.pixelHeight)"
                  + (configuration.scalesToFit ? " -> \(configuration.streamWidth)x\(configuration.streamHeight) (scaled)" : ""))
    }

    func stop() async {
        guard let stream else { return }
        self.stream = nil
        try? await stream.stopCapture()
    }

    // MARK: SCStreamOutput

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, CMSampleBufferGetNumSamples(sampleBuffer) > 0 else { return }

        // Skip idle/blank frames: the surface is unchanged, so re-encoding it would spend
        // bitrate to transmit nothing.
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false) as? [[SCStreamFrameInfo: Any]],
           let raw = attachments.first?[.status] as? Int,
           let status = SCFrameStatus(rawValue: raw),
           status != .complete {
            return
        }

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        onFrame?(pixelBuffer, MonotonicClock.micros())
    }

    // MARK: SCStreamDelegate

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStopped?(error)
    }
}
