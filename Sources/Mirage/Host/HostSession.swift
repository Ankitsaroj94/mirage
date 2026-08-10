import Foundation
import Network
import CoreGraphics
import VideoToolbox
import MirageBridge

/// Drives the sending Mac: create a matching virtual display, capture it, encode it,
/// push it down the link, and feed remote input back into the window server.
@MainActor
final class HostSession: ObservableObject {

    enum State: Equatable {
        case idle
        case connecting(String)
        case running(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var stats = SessionStats()
    @Published private(set) var isHiDPI = true
    @Published private(set) var virtualDisplayName = ""

    var settings: HostSettings

    private var link: PeerLink?
    private var virtualDisplay: VirtualDisplayHost?
    private var capture: DisplayCapture?
    private var encoder: VideoEncoder?
    private var injector: InputInjector?
    private var latestParameterSets: VideoParameterSets?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.host", qos: .userInteractive)

    // Adaptive bitrate state
    private var currentBitrateKbps: Int
    private var lastBitrateChange = MonotonicClock.millis()
    private var congestedStreak = 0

    init(settings: HostSettings) {
        self.settings = settings
        self.currentBitrateKbps = settings.targetBitrateKbps
    }

    // MARK: Connect

    func connect(to display: DiscoveredDisplay) {
        disconnect()
        state = .connecting(display.name)

        let parameters = LinkParameters.make(allowPeerToPeer: settings.allowPeerToPeer)
        let connection = NWConnection(to: display.endpoint, using: parameters)
        let link = PeerLink(connection: connection, queue: queue,
                            role: .initiator, pairingCode: settings.pairingCode)
        self.link = link

        link.onFailure = { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                if case .running = self.state {
                    self.state = .failed(error?.localizedDescription ?? "The other Mac disconnected.")
                } else if case .connecting = self.state {
                    self.state = .failed(error?.localizedDescription ?? "Could not reach \(display.name).")
                }
                self.teardownPipeline()
            }
        }
        link.onMessage = { [weak self] type, flags, timestamp, payload in
            Task { @MainActor in self?.handle(type: type, flags: flags, timestamp: timestamp, payload: payload) }
        }
        link.start()
    }

    func disconnect() {
        link?.send(.bye)
        teardownPipeline()
        link?.close()
        link = nil
        if case .failed = state {} else { state = .idle }
    }

    private func teardownPipeline() {
        injector?.releaseAll()
        injector = nil
        encoder?.stop()
        encoder = nil
        if let capture { Task { await capture.stop() } }
        capture = nil
        virtualDisplay?.invalidate()
        virtualDisplay = nil
        latestParameterSets = nil
    }

    // MARK: Incoming messages

    private func handle(type: MessageType, flags: UInt8, timestamp: UInt64, payload: Data) {
        switch type {
        case .hello:
            guard let hello = try? JSONDecoder().decode(ClientHello.self, from: payload) else { return }
            startPipeline(for: hello)

        case .requestKeyframe:
            encoder?.requestKeyframe()

        case .input:
            guard settings.acceptRemoteInput, let event = InputEvent(decoding: payload) else { return }
            injector?.handle(event)

        case .stats:
            guard let clientStats = try? JSONDecoder().decode(ClientStats.self, from: payload) else { return }
            stats.roundTripMs = clientStats.averageLatencyMs
            stats.decodeMs = clientStats.decodeMsAverage
            stats.framesDroppedRemote = clientStats.framesDropped

        case .ping:
            link?.send(.pong, timestampMicros: timestamp)

        case .bye:
            state = .idle
            teardownPipeline()

        default:
            break
        }
    }

    // MARK: Pipeline

    private func startPipeline(for hello: ClientHello) {
        guard hello.protocolVersion == ClientHello.currentVersion else {
            reject("Version mismatch: the other Mac is running a different build of Mirage.")
            return
        }
        guard MRGVirtualDisplay.isSupported else {
            reject("This version of macOS no longer exposes the virtual-display API.")
            return
        }

        // The virtual display is built to the receiver's exact panel geometry. That single
        // decision is what makes the picture 1:1 with no scaling anywhere in the chain.
        let name = "\(hello.deviceName) (Mirage)"
        let display: VirtualDisplayHost
        do {
            display = try VirtualDisplayHost.start(name: name,
                                                   pixelWidth: hello.pixelWidth,
                                                   pixelHeight: hello.pixelHeight,
                                                   pointWidth: hello.pointWidth,
                                                   pointHeight: hello.pointHeight,
                                                   refreshHz: hello.refreshHz)
        } catch {
            reject(error.localizedDescription)
            return
        }
        display.onUnexpectedExit = { [weak self] in
            Task { @MainActor in
                self?.state = .failed("The extra display disappeared unexpectedly.")
                self?.teardownPipeline()
            }
        }
        self.virtualDisplay = display
        self.isHiDPI = display.isHiDPI
        self.virtualDisplayName = name

        let codec = chooseCodec(hello)
        let fps = min(settings.maxFPS, hello.refreshHz)
        currentBitrateKbps = settings.targetBitrateKbps

        let encoder = VideoEncoder(configuration: .init(codec: codec,
                                                        width: hello.pixelWidth,
                                                        height: hello.pixelHeight,
                                                        fps: fps,
                                                        bitrateKbps: currentBitrateKbps,
                                                        maxAllowedQP: settings.prioritiseTextClarity ? 34 : nil))
        encoder.onParameterSets = { [weak self] sets in
            Task { @MainActor in
                self?.latestParameterSets = sets
                self?.link?.sendJSON(.videoParams, sets)
            }
        }
        encoder.onEncodedFrame = { [weak self] frame in
            Task { @MainActor in self?.send(frame) }
        }

        do {
            try encoder.start()
        } catch {
            reject(error.localizedDescription)
            return
        }
        self.encoder = encoder

        if settings.acceptRemoteInput && hello.wantsInputForwarding {
            injector = InputInjector(displayBounds: display.bounds)
        }

        let capture = DisplayCapture(configuration: .init(displayID: display.displayID,
                                                          pixelWidth: hello.pixelWidth,
                                                          pixelHeight: hello.pixelHeight,
                                                          fps: fps,
                                                          showsCursor: true))
        capture.onFrame = { [weak self] pixelBuffer, captureTime in
            self?.encodeIfLinkCanKeepUp(pixelBuffer, captureTime: captureTime)
        }
        capture.onStopped = { [weak self] (error: Error?) in
            Task { @MainActor in self?.state = .failed(error?.localizedDescription ?? "Screen capture stopped.") }
        }
        self.capture = capture

        Task {
            do {
                try await capture.start()
            } catch {
                await MainActor.run { self.reject(error.localizedDescription) }
                return
            }
            await MainActor.run {
                self.state = .running(hello.deviceName)
                let ack = HostHelloAck(accepted: true, reason: nil,
                                       hostName: Host.current().localizedName ?? "Mac",
                                       codec: codec,
                                       pixelWidth: hello.pixelWidth, pixelHeight: hello.pixelHeight,
                                       pointWidth: hello.pointWidth, pointHeight: hello.pointHeight,
                                       fps: fps, bitrateKbps: self.currentBitrateKbps,
                                       isHiDPI: display.isHiDPI)
                self.link?.sendJSON(.helloAck, ack)
                self.encoder?.requestKeyframe()
            }
        }
    }

    private func chooseCodec(_ hello: ClientHello) -> VideoCodec {
        // HEVC gives noticeably cleaner text at the same bitrate, but only if both sides
        // can do it in hardware — a software decode on a 2017 Intel Mac would never keep up.
        let hostCanEncodeHEVC = VTIsHardwareDecodeSupported(kCMVideoCodecType_HEVC)
        if settings.preferHEVC && hello.supportsHEVC && hostCanEncodeHEVC { return .hevc }
        return .h264
    }

    private func reject(_ reason: String) {
        state = .failed(reason)
        let ack = HostHelloAck(accepted: false, reason: reason, hostName: Host.current().localizedName ?? "Mac",
                               codec: .h264, pixelWidth: 0, pixelHeight: 0, pointWidth: 0, pointHeight: 0,
                               fps: 0, bitrateKbps: 0, isHiDPI: false)
        link?.sendJSON(.helloAck, ack)
        teardownPipeline()
    }

    // MARK: Flow control

    /// Backpressure. If the socket has not drained the previous frames there is no point
    /// encoding another one — it would only add queueing delay. Dropping here keeps the
    /// picture current rather than letting it fall behind and stay behind.
    private nonisolated func encodeIfLinkCanKeepUp(_ pixelBuffer: CVPixelBuffer, captureTime: UInt64) {
        Task { @MainActor in
            guard let link = self.link, let encoder = self.encoder else { return }
            let budget = max(64 * 1024, self.currentBitrateKbps * 1000 / 8 / 8)   // ~125 ms of data

            if link.pendingBytes > budget {
                self.stats.framesDroppedLocal += 1
                self.congestedStreak += 1
                self.adaptBitrate(congested: true)
                return
            }
            self.congestedStreak = 0
            self.adaptBitrate(congested: false)
            encoder.encode(pixelBuffer: pixelBuffer, captureTimeMicros: captureTime)
        }
    }

    /// Plain AIMD: back off hard when the link is congested, creep back up when it is not.
    private func adaptBitrate(congested: Bool) {
        guard settings.adaptiveBitrate else { return }
        let now = MonotonicClock.millis()

        if congested {
            guard congestedStreak >= 3, now - lastBitrateChange > 500 else { return }
            let reduced = max(settings.minimumBitrateKbps, Int(Double(currentBitrateKbps) * 0.75))
            if reduced != currentBitrateKbps {
                currentBitrateKbps = reduced
                encoder?.updateBitrate(kbps: reduced)
                lastBitrateChange = now
                Log.video("congestion, bitrate -> \(reduced) kbps")
            }
        } else {
            guard now - lastBitrateChange > 3_000, currentBitrateKbps < settings.targetBitrateKbps else { return }
            let raised = min(settings.targetBitrateKbps, currentBitrateKbps + max(1_000, currentBitrateKbps / 10))
            currentBitrateKbps = raised
            encoder?.updateBitrate(kbps: raised)
            lastBitrateChange = now
        }
    }

    private func send(_ frame: VideoEncoder.EncodedFrame) {
        link?.send(.videoFrame,
                   flags: frame.isKeyframe ? FrameFlags.keyframe : 0,
                   payload: frame.data,
                   timestampMicros: frame.captureTimeMicros)
        stats.record(frameBytes: frame.data.count)
        stats.bitrateKbps = currentBitrateKbps
    }
}

// MARK: - Settings

struct HostSettings: Codable, Equatable {
    var preferHEVC = true
    var maxFPS = 60
    var targetBitrateKbps = 45_000
    var minimumBitrateKbps = 6_000
    var adaptiveBitrate = true
    /// Caps encoder QP so a busy frame degrades in framerate rather than in sharpness.
    var prioritiseTextClarity = true
    var acceptRemoteInput = true
    var allowPeerToPeer = false
    var pairingCode: String? = nil
}
