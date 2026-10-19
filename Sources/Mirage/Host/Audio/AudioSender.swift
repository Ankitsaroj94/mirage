import Foundation
import Network
import CryptoKit

/// Turns the tap's float frames — at whatever rate the output device runs — into the wire
/// format: 48 kHz stereo s16le in 240-frame (5 ms) packets, each stamped with the
/// `MonotonicClock` time of its first sample.
///
/// Resampling is 4-point Catmull-Rom. At 48 kHz in (the usual case for Mac speakers and
/// AirPods) the step is exactly 1.0 and every output sample is the input sample untouched;
/// the interpolator only does real work behind a 44.1 kHz or 96 kHz device.
final class AudioPacketizer {
    static let outputRate = 48_000.0
    static let packetFrames = 240
    static let channels = 2

    let sourceRate: Double
    /// (hostTimeMicros of the first frame, 480 interleaved samples). Valid only during the call.
    var onPacket: ((UInt64, UnsafeBufferPointer<Int16>) -> Void)?

    private let step: Double
    private var anchors: [(frame: UInt64, micros: UInt64)] = []

    // Unconsumed source frames (interleaved) and the absolute index of the first one.
    private var pending: [Float] = []
    private var pendingBase: UInt64 = 0
    private var started = false
    // Read position: `pendingBase + position + fraction` in source frames.
    private var position = 0
    private var fraction = 0.0

    private var packet: [Int16]
    private var packetFill = 0
    private var packetTime: UInt64 = 0

    init(sourceRate: Double) {
        self.sourceRate = sourceRate
        self.step = sourceRate / Self.outputRate
        self.packet = [Int16](repeating: 0, count: Self.packetFrames * Self.channels)
    }

    func addAnchor(frame: UInt64, micros: UInt64) {
        anchors.append((frame, micros))
    }

    /// Host time of a (fractional) absolute source position, from the newest anchor at or
    /// before it. Anchors arrive every IOProc cycle, so the extrapolation spans a few ms at most.
    func micros(atSourcePosition position: Double) -> UInt64? {
        guard !anchors.isEmpty else { return nil }
        var chosen = 0
        for i in anchors.indices where Double(anchors[i].frame) <= position { chosen = i }
        if chosen > 0 { anchors.removeFirst(chosen) }       // older ones can never be needed again
        let anchor = anchors[0]
        let deltaMicros = (position - Double(anchor.frame)) * 1_000_000 / sourceRate
        return UInt64(max(0, (Double(anchor.micros) + deltaMicros).rounded()))
    }

    /// `frames` interleaved stereo frames whose first has absolute index `firstFrame`.
    /// Frames must be contiguous from call to call (the ring guarantees it).
    func process(_ samples: UnsafePointer<Float>, frames: Int, firstFrame: UInt64) {
        if !started {
            started = true
            pendingBase = firstFrame
        }
        pending.append(contentsOf: UnsafeBufferPointer(start: samples, count: frames * Self.channels))
        let available = pending.count / Self.channels

        // Catmull-Rom needs one frame behind and two ahead of the read position.
        while position + 2 < available {
            if packetFill == 0 {
                let absolute = Double(pendingBase) + Double(position) + fraction
                guard let time = micros(atSourcePosition: absolute) else { break }
                packetTime = time
            }
            let t = Float(fraction)
            for ch in 0..<Self.channels {
                let p0 = pending[max(position - 1, 0) * 2 + ch]
                let p1 = pending[position * 2 + ch]
                let p2 = pending[(position + 1) * 2 + ch]
                let p3 = pending[(position + 2) * 2 + ch]
                let value: Float
                if t == 0 {
                    value = p1
                } else {
                    let c1 = 0.5 * (p2 - p0)
                    let c2 = p0 - 2.5 * p1 + 2 * p2 - 0.5 * p3
                    let c3 = 0.5 * (p3 - p0) + 1.5 * (p1 - p2)
                    value = ((c3 * t + c2) * t + c1) * t + p1
                }
                packet[packetFill * 2 + ch] = AudioPacket.s16(value)
            }
            packetFill += 1
            if packetFill == Self.packetFrames {
                packet.withUnsafeBufferPointer { onPacket?(packetTime, $0) }
                packetFill = 0
            }
            fraction += step
            let whole = Int(fraction)
            position += whole
            fraction -= Double(whole)
        }

        // Keep one frame of history behind the read position for the next interpolation.
        let drop = max(0, position - 1)
        if drop > 0 {
            pending.removeFirst(drop * Self.channels)
            pendingBase &+= UInt64(drop)
            position -= drop
        }
    }
}

/// The UDP side: one `NWConnection` to the client's `audioUDPPort`.
///
/// UDP rather than the TCP link so a 100 KB keyframe can never sit in front of 5 ms of audio,
/// and `.interactiveVoice` so Wi-Fi puts it in the WMM voice queue, ahead of the video.
final class AudioSender {
    private let connection: NWConnection
    private let key: SymmetricKey?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.audio.udp", qos: .userInteractive)
    private var seq: UInt32 = 0
    private(set) var packetsSent = 0

    init(host: NWEndpoint.Host, port: UInt16, key: SymmetricKey?) {
        let parameters = NWParameters.udp
        parameters.serviceClass = .interactiveVoice
        parameters.prohibitedInterfaceTypes = [.cellular]
        parameters.preferNoProxies = true
        // Follow the TCP link onto AWDL if that is where the client is.
        parameters.includePeerToPeer = true
        connection = NWConnection(host: host, port: NWEndpoint.Port(rawValue: port) ?? .any, using: parameters)
        self.key = key
    }

    func start() {
        connection.stateUpdateHandler = { state in
            if case .failed(let error) = state { Log.net("audio udp failed: \(error.localizedDescription)") }
        }
        connection.start(queue: queue)
    }

    /// Called from the pump thread only. Datagrams sent before the connection is ready are
    /// queued by Network.framework, and a lost one is simply a 5 ms gap the client conceals.
    func send(hostTimeMicros: UInt64, pcm: UnsafeBufferPointer<Int16>) {
        let payload = Data(buffer: pcm)   // host order is little-endian on every Mac
        guard let packet = try? AudioPacket.make(seq: seq, hostTimeMicros: hostTimeMicros, pcm: payload, key: key) else { return }
        seq &+= 1
        packetsSent += 1
        connection.send(content: packet, completion: .idempotent)
    }

    func stop() {
        connection.stateUpdateHandler = nil
        connection.cancel()
    }
}

/// The sender thread: drains the capture ring, packetizes, and fans each packet out to the
/// network and to the local speakers.
///
/// It is a plain high-priority thread woken by the IOProc's semaphore (signalling is real-time
/// safe; waiting is not, which is exactly why the work happens here and not in the IOProc).
/// The timeout makes it keep turning over even if the tap goes quiet, so `stop` is prompt.
final class AudioPump {
    private let ring: CaptureRing
    private let wake: DispatchSemaphore
    private let packetizer: AudioPacketizer
    private let stopLock = NSLock()
    private var stopped = false
    private let finished = DispatchSemaphore(value: 0)
    private var thread: Thread?

    init(ring: CaptureRing, wake: DispatchSemaphore, sourceRate: Double,
         onPacket: @escaping (UInt64, UnsafeBufferPointer<Int16>) -> Void) {
        self.ring = ring
        self.wake = wake
        self.packetizer = AudioPacketizer(sourceRate: sourceRate)
        packetizer.onPacket = onPacket
    }

    func start() {
        let thread = Thread { [self] in run() }
        thread.name = "com.voxturr.mirage.audio.pump"
        thread.qualityOfService = .userInteractive
        thread.stackSize = 1 << 20
        self.thread = thread
        thread.start()
    }

    /// Blocks until the thread has exited, so the caller may tear down what it uses.
    func stop() {
        stopLock.lock()
        let wasRunning = !stopped && thread != nil
        stopped = true
        stopLock.unlock()
        guard wasRunning else { return }
        wake.signal()
        finished.wait()
        thread = nil
    }

    private var isStopped: Bool {
        stopLock.lock(); defer { stopLock.unlock() }
        return stopped
    }

    private func run() {
        let chunk = 1024
        let scratch = UnsafeMutablePointer<Float>.allocate(capacity: chunk * CaptureRing.channels)
        defer { scratch.deallocate(); finished.signal() }
        while !isStopped {
            _ = wake.wait(timeout: .now() + .milliseconds(20))
            drain(scratch: scratch, chunk: chunk)
        }
    }

    func drain(scratch: UnsafeMutablePointer<Float>, chunk: Int) {
        while let anchor = ring.popAnchor() {
            packetizer.addAnchor(frame: anchor.frame, micros: anchor.micros)
        }
        while true {
            let (count, first) = ring.read(into: scratch, maxFrames: chunk)
            if count == 0 { break }
            packetizer.process(scratch, frames: count, firstFrame: first)
        }
    }
}
