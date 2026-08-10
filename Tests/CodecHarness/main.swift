// End-to-end codec check: synthetic screen-like frames -> VideoEncoder -> the wire
// representation -> VideoRenderer's format description -> VTDecompressionSession,
// then compare luma against the source. Runs without Screen Recording permission.
import Foundation
import VideoToolbox
import CoreMedia
import CoreVideo
import Accelerate

let W = 2880, H = 1800

/// Fine 1px-stride vertical bars plus solid blocks: the harshest realistic case for a
/// 4:2:0 encoder, and a good proxy for small text.
func makeFrame(index: Int) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, W, H,
                        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                        [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary, &pb)
    guard let pb else { fatalError("could not allocate pixel buffer") }
    CVPixelBufferLockBaseAddress(pb, [])
    let yPlane = CVPixelBufferGetBaseAddressOfPlane(pb, 0)!.assumingMemoryBound(to: UInt8.self)
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    let cPlane = CVPixelBufferGetBaseAddressOfPlane(pb, 1)!.assumingMemoryBound(to: UInt8.self)
    let cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)

    let shift = index * 7
    for y in 0..<H {
        let row = yPlane + y * yStride
        for x in 0..<W {
            let band = (y / 200) % 3
            let value: UInt8
            switch band {
            case 0: value = ((x + shift) % 2 == 0) ? 235 : 16      // 1px alternating
            case 1: value = (((x + shift) / 8) % 2 == 0) ? 220 : 30 // 8px bars
            default: value = UInt8(clamping: (x + shift) % 256)     // gradient
            }
            row[x] = value
        }
    }
    for y in 0..<(H / 2) {
        let row = cPlane + y * cStride
        for x in 0..<(W / 2) { row[2*x] = 128; row[2*x+1] = 128 }   // neutral chroma
    }
    CVPixelBufferUnlockBaseAddress(pb, [])
    return pb
}

func luma(_ pb: CVPixelBuffer) -> [UInt8] {
    CVPixelBufferLockBaseAddress(pb, .readOnly)
    defer { CVPixelBufferUnlockBaseAddress(pb, .readOnly) }
    let base = CVPixelBufferGetBaseAddressOfPlane(pb, 0)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    let w = CVPixelBufferGetWidthOfPlane(pb, 0), h = CVPixelBufferGetHeightOfPlane(pb, 0)
    var out = [UInt8](repeating: 0, count: w * h)
    for y in 0..<h { memcpy(&out[y*w], base + y*stride, w) }
    return out
}

func psnr(_ a: [UInt8], _ b: [UInt8]) -> Double {
    precondition(a.count == b.count)
    var mse = 0.0
    for i in 0..<a.count { let d = Double(a[i]) - Double(b[i]); mse += d*d }
    mse /= Double(a.count)
    return mse == 0 ? 99 : 10 * log10(255*255 / mse)
}

func run(codec: VideoCodec, bitrateKbps: Int, textMode: Bool) -> Bool {
    print("\n=== \(codec.rawValue.uppercased()) @ \(bitrateKbps/1000) Mbps \(W)x\(H) \(textMode ? "(QP-capped)" : "") ===")
    let encoder = VideoEncoder(configuration: .init(codec: codec, width: W, height: H, fps: 60,
                                                    bitrateKbps: bitrateKbps,
                                                    maxAllowedQP: textMode ? 34 : nil))
    var sets: VideoParameterSets?
    var frames: [(Data, Bool)] = []
    let framesWanted = 12

    encoder.onParameterSets = { sets = $0 }
    encoder.onEncodedFrame = { frames.append(($0.data, $0.isKeyframe)) }
    do { try encoder.start() } catch { print("✗ encoder start failed: \(error)"); return false }
    encoder.requestKeyframe()

    var sources: [[UInt8]] = []
    let encodeStart = Date()
    for i in 0..<framesWanted {
        let pb = makeFrame(index: i)
        sources.append(luma(pb))
        encoder.encode(pixelBuffer: pb, captureTimeMicros: UInt64(i) * 16_667)
    }
    encoder.flush()   // push out anything the encoder is still holding
    let deadline = Date().addingTimeInterval(10)
    while frames.count < framesWanted && Date() < deadline { usleep(20_000) }
    let encodeMs = Date().timeIntervalSince(encodeStart) * 1000

    // Low-latency rate control is allowed to drop frames it cannot fit in the budget.
    // That is correct behaviour under congestion, so tolerate it and report it.
    let dropped = framesWanted - frames.count
    guard frames.count >= framesWanted - 2 else {
        print("✗ encoder produced only \(frames.count)/\(framesWanted) frames"); return false }
    if dropped > 0 { print("  note: rate control dropped \(dropped) frame(s) to stay in budget") }
    guard let sets else { print("✗ no parameter sets emitted"); return false }
    print("  parameter sets: \(sets.parameterSets.count) (\(sets.parameterSets.map(\.count))), nalLengthSize=\(sets.nalLengthSize)")
    guard frames[0].1 else { print("✗ first frame is not a keyframe"); return false }
    let totalBytes = frames.reduce(0) { $0 + $1.0.count }
    print("  encoded \(frames.count) frames, \(totalBytes/1024) KB total, \(String(format: "%.1f", encodeMs/Double(frames.count))) ms/frame")

    // --- rebuild the decoder exactly the way the receiving Mac does ---
    guard let format = VideoRenderer.makeFormatDescription(from: sets) else {
        print("✗ could not rebuild the format description from the transmitted parameter sets"); return false
    }
    let dims = CMVideoFormatDescriptionGetDimensions(format)
    guard dims.width == Int32(W), dims.height == Int32(H) else {
        print("✗ format description is \(dims.width)x\(dims.height), expected \(W)x\(H)"); return false
    }

    var session: VTDecompressionSession?
    let attrs: [CFString: Any] = [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange]
    var decoded: [[UInt8]] = []
    let lock = NSLock()
    var callback = VTDecompressionOutputCallbackRecord(
        decompressionOutputCallback: { refcon, _, status, _, imageBuffer, _, _ in
            guard status == noErr, let imageBuffer else { return }
            let box = Unmanaged<Collector>.fromOpaque(refcon!).takeUnretainedValue()
            box.add(luma(imageBuffer))
        }, decompressionOutputRefCon: nil)

    final class Collector { var frames: [[UInt8]] = []; let l = NSLock()
        func add(_ f: [UInt8]) { l.lock(); frames.append(f); l.unlock() } }
    let collector = Collector()
    callback.decompressionOutputRefCon = Unmanaged.passUnretained(collector).toOpaque()

    let st = VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format,
                                          decoderSpecification: nil, imageBufferAttributes: attrs as CFDictionary,
                                          outputCallback: &callback, decompressionSessionOut: &session)
    guard st == noErr, let session else { print("✗ decoder create failed (\(st))"); return false }

    var hwDecode: CFTypeRef?
    VTSessionCopyProperty(session, key: kVTDecompressionPropertyKey_UsingHardwareAcceleratedVideoDecoder,
                          allocator: kCFAllocatorDefault, valueOut: &hwDecode)
    print("  hardware decode: \((hwDecode as? Bool) ?? false)")

    let decodeStart = Date()
    for (data, _) in frames {
        guard let sb = VideoRenderer.makeSampleBuffer(frameData: data, formatDescription: format) else {
            print("✗ could not wrap the wire bytes into a sample buffer"); return false
        }
        VTDecompressionSessionDecodeFrame(session, sampleBuffer: sb,
                                          flags: [._EnableAsynchronousDecompression], frameRefcon: nil, infoFlagsOut: nil)
    }
    VTDecompressionSessionWaitForAsynchronousFrames(session)
    let decodeMs = Date().timeIntervalSince(decodeStart) * 1000
    decoded = collector.frames
    _ = lock

    guard decoded.count == frames.count else { print("✗ decoded \(decoded.count)/\(frames.count)"); return false }
    print("  decoded \(decoded.count) frames, \(String(format: "%.2f", decodeMs/Double(decoded.count))) ms/frame")

    // When rate control drops a frame the decoded sequence is shorter, so the last decoded
    // frame is not necessarily sources[last]. Match it against every source and take the best.
    let first = psnr(sources[0], decoded[0])
    var bestScore = 0.0, bestIndex = -1
    for (i, src) in sources.enumerated() {
        let score = psnr(src, decoded[decoded.count-1])
        if score > bestScore { bestScore = score; bestIndex = i }
    }
    print(String(format: "  luma PSNR: keyframe %.1f dB, last decoded frame %.1f dB (matches source #%d of %d)",
                 first, bestScore, bestIndex, sources.count - 1))
    guard first > 30 else { print("✗ keyframe PSNR too low — the picture is not being reconstructed"); return false }
    guard bestScore > 30 else { print("✗ inter-frame PSNR too low"); return false }
    guard bestIndex >= sources.count - 1 - dropped else {
        print("✗ the decoded tail lags the source by more than the \(dropped) dropped frame(s)"); return false }
    print("  ✓ pass")
    return true
}

var ok = true
ok = run(codec: .h264, bitrateKbps: 45_000, textMode: true) && ok
ok = run(codec: .hevc, bitrateKbps: 45_000, textMode: true) && ok
ok = run(codec: .hevc, bitrateKbps: 12_000, textMode: false) && ok
print(ok ? "\nALL CODEC TESTS PASSED" : "\nFAILURES")
exit(ok ? 0 : 1)
