// End-to-end latency benchmark over loopback: synthetic screen frames -> VideoEncoder ->
// FramePump -> PeerLink -> PeerLink -> hardware decode, measuring the age of each frame at
// the moment it is decoded.
//
// The second half of the run deliberately jams the main thread. Before the hot path was
// moved off the main actor, video was scheduled behind every SwiftUI update, so this is the
// case that used to produce multi-second round trips. Latency must stay flat.
import Foundation
import Network
import VideoToolbox
import CoreMedia
import CoreVideo

let WIDTH = 2880, HEIGHT = 1800
let FRAME_COUNT = 90
let FPS = 60

// MARK: - Synthetic screen content

func makeFrame(_ index: Int) -> CVPixelBuffer {
    var pb: CVPixelBuffer?
    CVPixelBufferCreate(kCFAllocatorDefault, WIDTH, HEIGHT,
                        kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
                        [kCVPixelBufferIOSurfacePropertiesKey: [:] as CFDictionary] as CFDictionary, &pb)
    guard let pb else { fatalError("pixel buffer allocation failed") }
    CVPixelBufferLockBaseAddress(pb, [])
    let y = CVPixelBufferGetBaseAddressOfPlane(pb, 0)!.assumingMemoryBound(to: UInt8.self)
    let yStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 0)
    let c = CVPixelBufferGetBaseAddressOfPlane(pb, 1)!.assumingMemoryBound(to: UInt8.self)
    let cStride = CVPixelBufferGetBytesPerRowOfPlane(pb, 1)
    let shift = index * 11
    for row in 0..<HEIGHT {
        let line = y + row * yStride
        for col in 0..<WIDTH {
            line[col] = (((col + shift) / 6) % 2 == 0) ? 225 : 20   // text-like fine detail
        }
    }
    for row in 0..<(HEIGHT / 2) {
        let line = c + row * cStride
        memset(line, 128, WIDTH)
    }
    CVPixelBufferUnlockBaseAddress(pb, [])
    return pb
}

print("preparing \(8) source frames at \(WIDTH)x\(HEIGHT)…")
let sources = (0..<8).map(makeFrame)

// MARK: - Receiving side

final class Receiver: @unchecked Sendable {
    private var session: VTDecompressionSession?
    private var format: CMFormatDescription?
    let lock = NSLock()
    var latenciesEarly: [Double] = []
    var latenciesUnderLoad: [Double] = []
    var lastTimestamp: UInt64 = 0
    var outOfOrder = 0
    var decoded = 0
    var loadPhase = false
    var ack: ((UInt64) -> Void)?
    let finished = DispatchSemaphore(value: 0)

    func configure(_ sets: VideoParameterSets) {
        guard let format = VideoRenderer.makeFormatDescription(from: sets) else {
            print("  !! could not build a format description from the parameter sets"); return
        }
        self.format = format
        lock.lock(); configured = true; lock.unlock()
        var callback = VTDecompressionOutputCallbackRecord(
            decompressionOutputCallback: { _, _, _, _, _, _, _ in }, decompressionOutputRefCon: nil)
        var created: VTDecompressionSession?
        VTDecompressionSessionCreate(allocator: kCFAllocatorDefault, formatDescription: format,
                                     decoderSpecification: nil,
                                     imageBufferAttributes: [kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_420YpCbCr8BiPlanarFullRange] as CFDictionary,
                                     outputCallback: &callback, decompressionSessionOut: &created)
        session = created
    }

    var arrived = 0
    var configured = false

    func receive(_ data: Data, timestampMicros: UInt64) {
        lock.lock(); arrived += 1; lock.unlock()
        guard let session, let format else {
            // Still acknowledge, or the host stalls waiting on a frame we cannot decode yet.
            ack?(timestampMicros)
            return
        }
        guard let sample = VideoRenderer.makeSampleBuffer(frameData: data, formatDescription: format) else { return }
        VTDecompressionSessionDecodeFrame(session, sampleBuffer: sample,
                                          flags: [._EnableAsynchronousDecompression],
                                          frameRefcon: nil, infoFlagsOut: nil)
        ack?(timestampMicros)

        // Age of the frame at the moment it is decoded — capture stamp to right now.
        let latency = Double(MonotonicClock.micros() &- timestampMicros) / 1000.0
        pumpBox.lock.lock()
        if let sentAt = pumpBox.sendStamps.removeValue(forKey: timestampMicros) {
            pumpBox.transportMs.append(Double(MonotonicClock.micros() &- sentAt) / 1000.0)
        }
        pumpBox.lock.unlock()
        lock.lock()
        if timestampMicros < lastTimestamp { outOfOrder += 1 }
        lastTimestamp = timestampMicros
        if loadPhase { latenciesUnderLoad.append(latency) } else { latenciesEarly.append(latency) }
        decoded += 1
        let total = decoded
        lock.unlock()
        if total >= FRAME_COUNT { finished.signal() }
    }
}

let receiver = Receiver()

/// The pump is built after the link, so the link's handler reaches it through this.
final class PumpBox: @unchecked Sendable {
    var value: FramePump?
    let lock = NSLock()
    var acksReceived = 0
    var sendStamps: [UInt64: UInt64] = [:]      // captureStamp -> time the frame hit the socket
    var transportMs: [Double] = []
}
let pumpBox = PumpBox()

// MARK: - Wire the two ends together over loopback

let hostQueue = DispatchQueue(label: "bench.host")
let clientQueue = DispatchQueue(label: "bench.client")
let listener = try! NWListener(using: LinkParameters.make(allowPeerToPeer: false), on: 0)
var serverLink: PeerLink?
var hostLink: PeerLink?
let linkReady = DispatchSemaphore(value: 0)

listener.newConnectionHandler = { connection in
    let link = PeerLink(connection: connection, queue: clientQueue, role: .responder, pairingCode: nil)
    serverLink = link
    _ = link
    receiver.ack = { [weak link] stamp in link?.send(.frameAck, timestampMicros: stamp) }
    link.onMessage = { type, _, timestamp, payload in
        switch type {
        case .videoParams:
            if let sets = try? JSONDecoder().decode(VideoParameterSets.self, from: payload) {
                receiver.configure(sets)
            }
        case .videoFrame:
            receiver.receive(payload, timestampMicros: timestamp)
        default:
            break
        }
    }
    link.start()
}
listener.stateUpdateHandler = { state in
    guard case .ready = state, let port = listener.port else { return }
    let connection = NWConnection(host: "127.0.0.1", port: port,
                                  using: LinkParameters.make(allowPeerToPeer: false))
    let link = PeerLink(connection: connection, queue: hostQueue, role: .initiator, pairingCode: nil)
    link.onMessage = { type, _, _, _ in
        if type == .frameAck {
            pumpBox.lock.lock(); pumpBox.acksReceived += 1; pumpBox.lock.unlock()
            pumpBox.value?.acknowledgeFrame()
        }
    }
    link.onReady = { linkReady.signal() }
    link.start()
    hostLink = link
}
listener.start(queue: hostQueue)
guard linkReady.wait(timeout: .now() + 10) == .success, let hostLink else {
    print("✗ loopback link never came up"); exit(1)
}

let encoder = VideoEncoder(configuration: .init(codec: .hevc, width: WIDTH, height: HEIGHT,
                                                fps: FPS, bitrateKbps: 45_000, maxAllowedQP: 34))
encoder.onParameterSets = { sets in hostLink.sendJSON(.videoParams, sets) }
try! encoder.start()
let pump = FramePump(link: hostLink, encoder: encoder, settings: HostSettings())
pumpBox.value = pump
pump.requestKeyframe()

// Stage instrumentation: where does the time actually go?
final class Stages: @unchecked Sendable {
    let lock = NSLock()
    var submitted = 0, encoded = 0
    var encodeMs: [Double] = []
    var transportMs: [Double] = []
    var inFlightSamples: [Int] = []
}
let stages = Stages()
let originalOnEncoded = encoder.onEncodedFrame
encoder.onEncodedFrame = { frame in
    stages.lock.lock()
    stages.encoded += 1
    stages.encodeMs.append(Double(MonotonicClock.micros() &- frame.captureTimeMicros) / 1000.0)
    stages.lock.unlock()
    pumpBox.lock.lock(); pumpBox.sendStamps[frame.captureTimeMicros] = MonotonicClock.micros(); pumpBox.lock.unlock()
    originalOnEncoded?(frame)
}

// MARK: - Feed frames on a capture-like queue, jamming the main thread halfway through

let captureQueue = DispatchQueue(label: "bench.capture", qos: .userInteractive)
var sent = 0
let ticker = DispatchSource.makeTimerSource(queue: captureQueue)
ticker.schedule(deadline: .now(), repeating: .milliseconds(1000 / FPS))
ticker.setEventHandler {
    guard sent < FRAME_COUNT else { ticker.cancel(); return }
    if sent == FRAME_COUNT / 2 && ProcessInfo.processInfo.environment["JAM_MAIN"] == "1" {
        receiver.lock.lock(); receiver.loadPhase = true; receiver.lock.unlock()
        DispatchQueue.main.async {
            let until = Date().addingTimeInterval(1.2)
            while Date() < until { _ = (0..<60_000).reduce(0, &+) }
        }
    }
    stages.lock.lock()
    stages.submitted += 1
    stages.inFlightSamples.append(encoder.framesInFlight)
    stages.lock.unlock()
    pump.submit(pixelBuffer: sources[sent % sources.count], captureTimeMicros: MonotonicClock.micros())
    sent += 1
}
ticker.resume()

_ = receiver.finished.wait(timeout: .now() + 30)
encoder.flush()
Thread.sleep(forTimeInterval: 0.4)

// MARK: - Report

func summarise(_ label: String, _ values: [Double]) -> Bool {
    guard !values.isEmpty else { print("  \(label): no frames"); return false }
    let sorted = values.sorted()
    let median = sorted[sorted.count / 2]
    let p95 = sorted[min(sorted.count - 1, Int(Double(sorted.count) * 0.95))]
    print(String(format: "  %@  median %.1f ms   p95 %.1f ms   max %.1f ms   (%d frames)",
                 label, median, p95, sorted.last ?? 0, values.count))
    return p95 < 150
}

let jamming = ProcessInfo.processInfo.environment["JAM_MAIN"] == "1"
receiver.lock.lock()
let early = receiver.latenciesEarly, loaded = receiver.latenciesUnderLoad
let ooo = receiver.outOfOrder, total = receiver.decoded
receiver.lock.unlock()

stages.lock.lock()
let sub = stages.submitted, enc = stages.encoded
let encMs = stages.encodeMs.sorted(), inflight = stages.inFlightSamples
stages.lock.unlock()
receiver.lock.lock(); let arrived = receiver.arrived, configured = receiver.configured; receiver.lock.unlock()
print("\nstage breakdown:")
pumpBox.lock.lock(); let acks = pumpBox.acksReceived; let tms = pumpBox.transportMs.sorted(); pumpBox.lock.unlock()
print("  frames arrived at receiver \(arrived), decoder configured: \(configured), acks back at host \(acks)")
if !tms.isEmpty {
    print(String(format: "  socket -> arrival: median %.1f ms, max %.1f ms", tms[tms.count/2], tms.last!))
}
print("  submitted \(sub), encoded \(enc), dropped before encode \(sub - enc)")
if !encMs.isEmpty {
    print(String(format: "  submit -> encoded: median %.1f ms, max %.1f ms", encMs[encMs.count/2], encMs.last!))
}
if !inflight.isEmpty {
    print("  encoder framesInFlight seen at submit: max \(inflight.max()!), avg \(String(format: "%.1f", Double(inflight.reduce(0,+))/Double(inflight.count)))")
}
print("\nend-to-end latency (capture stamp -> decoded), \(total) frames at \(WIDTH)x\(HEIGHT):")
let okEarly = summarise("idle main thread ", early)
let okLoaded = jamming ? summarise("main thread busy ", loaded) : true
print("  frame ordering: \(ooo == 0 ? "in order ✓" : "\(ooo) OUT OF ORDER ✗")")

let pass = okEarly && okLoaded && ooo == 0 && total >= FRAME_COUNT / 2
if jamming && !okLoaded {
    print("  ✗ latency degrades when the main thread is busy — the hot path is still coupled to it")
}
if !jamming { print("  (re-run with JAM_MAIN=1 to verify latency holds while the main thread is busy)") }
print(pass ? "\nLATENCY TEST PASSED" : "\nLATENCY TEST FAILED")
exit(pass ? 0 : 1)
