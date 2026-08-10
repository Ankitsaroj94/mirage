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
    /// Sampled from the pump on a timer rather than written per frame — see FramePump.
    @Published private(set) var stats = SessionStats()
    @Published private(set) var isHiDPI = true
    @Published private(set) var virtualDisplayName = ""

    var settings: HostSettings

    private var link: PeerLink?
    private var virtualDisplay: VirtualDisplayHost?
    private var capture: DisplayCapture?
    private var encoder: VideoEncoder?
    private nonisolated(unsafe) var injector: InputInjector?
    private var pump: FramePump? {
        didSet { pumpForNetwork = pump }
    }
    /// Reachable from the network queue without hopping to the main actor.
    private nonisolated(unsafe) var pumpForNetwork: FramePump?
    private var statsTimer: Timer?
    private var latestParameterSets: VideoParameterSets?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.host", qos: .userInteractive)
    private let injectorQueue = DispatchQueue(label: "com.voxturr.mirage.input", qos: .userInteractive)
    /// Read from the network queue, so it is kept as a plain flag rather than actor state.
    private nonisolated(unsafe) var acceptsRemoteInput = false

    init(settings: HostSettings) {
        self.settings = settings
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
            // Input is replayed straight from the network queue: routing it through the main
            // actor added a scheduling hop to every pointer move.
            if type == .input {
                guard let self, self.acceptsRemoteInput, let event = InputEvent(decoding: payload) else { return }
                self.injectorQueue.async { self.injector?.handle(event) }
                return
            }
            if type == .frameAck {
                self?.pumpForNetwork?.acknowledgeFrame()
                return
            }
            if type == .requestKeyframe {
                self?.pumpForNetwork?.requestKeyframe()
                return
            }
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
        statsTimer?.invalidate(); statsTimer = nil
        pump?.stop(); pump = nil
        injectorQueue.sync { injector?.releaseAll(); injector = nil }
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

        case .stats:
            guard let clientStats = try? JSONDecoder().decode(ClientStats.self, from: payload) else { return }
            pump?.recordRemote(droppedFrames: clientStats.framesDropped, decodeMs: clientStats.decodeMsAverage)

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

        let encoder = VideoEncoder(configuration: .init(codec: codec,
                                                        width: hello.pixelWidth,
                                                        height: hello.pixelHeight,
                                                        fps: fps,
                                                        bitrateKbps: settings.targetBitrateKbps,
                                                        maxAllowedQP: settings.prioritiseTextClarity ? 34 : nil))
        // Parameter sets arrive only on an IDR, so the main-actor hop is harmless here.
        encoder.onParameterSets = { [weak self] sets in
            Task { @MainActor in
                self?.latestParameterSets = sets
                self?.link?.sendJSON(.videoParams, sets)
            }
        }

        do {
            try encoder.start()
        } catch {
            reject(error.localizedDescription)
            return
        }
        self.encoder = encoder

        acceptsRemoteInput = settings.acceptRemoteInput && hello.wantsInputForwarding
        if acceptsRemoteInput {
            let bounds = display.bounds
            injectorQueue.sync { self.injector = InputInjector(displayBounds: bounds) }
        }

        guard let link else { reject("The link closed before the stream could start."); return }
        let pump = FramePump(link: link, encoder: encoder, settings: settings)
        self.pump = pump

        let capture = DisplayCapture(configuration: .init(displayID: display.displayID,
                                                          pixelWidth: hello.pixelWidth,
                                                          pixelHeight: hello.pixelHeight,
                                                          fps: fps,
                                                          showsCursor: true))
        // Straight from the capture queue into the pump. No actor hop, no reordering.
        capture.onFrame = { [pump] pixelBuffer, captureTime in
            pump.submit(pixelBuffer: pixelBuffer, captureTimeMicros: captureTime)
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
                                       fps: fps, bitrateKbps: self.settings.targetBitrateKbps,
                                       isHiDPI: display.isHiDPI)
                self.link?.sendJSON(.helloAck, ack)
                self.pump?.requestKeyframe()
                self.startStatsSampling()
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

    // MARK: UI sampling

    /// The video path never touches `@Published` state. The UI just reads a snapshot twice a
    /// second, which is plenty for a statistics readout and costs the stream nothing.
    private func startStatsSampling() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, let pump = self.pump else { return }
                pump.refreshLinkStats()
                let snapshot = pump.stats.snapshot()
                if snapshot != self.stats { self.stats = snapshot }
            }
        }
    }
}

