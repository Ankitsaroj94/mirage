import Foundation
import Network
import CoreGraphics
import VideoToolbox
import MirageBridge

/// Drives the sending Mac: create a virtual display sized for the client, capture it, encode
/// it, push it down the link, and feed remote input back into the window server. Optionally
/// streams system audio alongside (v2 clients that ask for it).
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
    /// Geometry of the running stream, nil when idle.
    @Published private(set) var displayPlan: DisplayPlan?
    /// The connected client's hello (platform, DPI, preference…), nil when idle.
    @Published private(set) var clientHello: ClientHello?
    @Published private(set) var isStreamingAudio = false

    var settings: HostSettings {
        didSet {
            if settings.audioSyncOffsetMs != oldValue.audioSyncOffsetMs {
                audio?.updateSyncOffset(ms: settings.audioSyncOffsetMs)
            }
        }
    }

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
    private var audio: AudioStreamer?
    /// UDP port the running audio stream targets, so a reconfigure can tell whether to restart it.
    private var audioTargetPort: Int?
    /// Bumped on every teardown. Async steps (capture start, encoder callbacks) check it so a
    /// pipeline that was replaced by a reconfigure cannot act on the link afterwards.
    private var generation = 0
    private let queue = DispatchQueue(label: "com.voxturr.mirage.host", qos: .userInteractive)
    private let injectorQueue = DispatchQueue(label: "com.voxturr.mirage.input", qos: .userInteractive)
    /// Read from the network queue, so it is kept as a plain flag rather than actor state.
    private nonisolated(unsafe) var acceptsRemoteInput = false

    init(settings: HostSettings) {
        self.settings = settings
    }

    /// Whether the peer may be sent v2-only message types.
    private var peerSpeaksV2: Bool { clientHello?.speaksV2 ?? false }

    // MARK: Connect

    /// Connects to a discovered display, or to one made with `DiscoveredDisplay.manual(host:port:)`.
    func connect(to display: DiscoveredDisplay) {
        disconnect()
        state = .connecting(display.name)

        let parameters = LinkParameters.make(allowPeerToPeer: settings.allowPeerToPeer)
        let connection = NWConnection(to: display.endpoint, using: parameters)
        let link = PeerLink(connection: connection, queue: queue,
                            role: .initiator, pairingCode: settings.pairingCode)
        self.link = link

        link.onFailure = { [weak self, weak link] error in
            Task { @MainActor in
                guard let self, let link, self.link === link else { return }
                if case .running = self.state {
                    self.state = .failed(error?.localizedDescription ?? "The other device disconnected.")
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
        stopAudio(notifyClient: true)
        link?.send(.bye)
        teardownPipeline()
        link?.close()
        link = nil
        if case .failed = state {} else { state = .idle }
    }

    /// Everything but the link: a full stop.
    private func teardownPipeline() {
        stopAudio(notifyClient: true)
        teardownVideo()
        clientHello = nil
    }

    /// Display, capture, encoder and pump. The link and audio survive, which is what a
    /// `reconfigure` needs.
    private func teardownVideo() {
        generation += 1
        statsTimer?.invalidate(); statsTimer = nil
        pump?.stop(); pump = nil
        injectorQueue.sync { injector?.releaseAll(); injector = nil }
        acceptsRemoteInput = false
        encoder?.stop()
        encoder = nil
        if let capture { Task { await capture.stop() } }
        capture = nil
        virtualDisplay?.invalidate()
        virtualDisplay = nil
        latestParameterSets = nil
        displayPlan = nil
    }

    // MARK: Incoming messages

    private func handle(type: MessageType, flags: UInt8, timestamp: UInt64, payload: Data) {
        switch type {
        case .hello:
            guard let hello = try? JSONDecoder().decode(ClientHello.self, from: payload) else { return }
            if virtualDisplay != nil {
                // A repeated hello (e.g. a client retrying) is treated as a reconfigure.
                reconfigure(with: hello)
            } else {
                startPipeline(for: hello)
            }

        case .reconfigure:
            guard let hello = try? JSONDecoder().decode(ClientHello.self, from: payload) else { return }
            reconfigure(with: hello)

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

    /// Rotation or a scale change on the client: rebuild the display at the new geometry on
    /// the same link. The client sees a fresh helloAck, parameter sets and an IDR — about a
    /// second of blank screen, no reconnect.
    private func reconfigure(with hello: ClientHello) {
        Log.video("reconfigure: \(hello.pixelWidth)x\(hello.pixelHeight) \(hello.orientation ?? "") \(hello.scalePreference ?? "")")
        teardownVideo()
        startPipeline(for: hello)
    }

    // MARK: Pipeline

    private func startPipeline(for hello: ClientHello) {
        guard (ClientHello.oldestSupportedVersion...ClientHello.currentVersion).contains(hello.protocolVersion) else {
            reject("Version mismatch: the other device is running an incompatible build of Mirage.")
            return
        }
        guard MRGVirtualDisplay.isSupported else {
            reject("This version of macOS no longer exposes the virtual-display API.")
            return
        }
        clientHello = hello

        // A Mac client gets a virtual display built to its exact panel — the single decision
        // that makes the picture 1:1 with no scaling anywhere in the chain. Other clients get
        // whatever the planner decides for their panel and scale preference.
        let override = hello.isMac ? nil : settings.scalePreferences[hello.deviceName]
        let plan = DisplayGeometry.plan(for: hello, override: override)
        let fps = DisplayGeometry.frameRate(clientRefreshHz: hello.refreshHz,
                                            maxFPS: settings.maxFPS, allow120Hz: settings.allow120Hz)

        let name = "\(hello.deviceName) (Mirage)"
        let display: VirtualDisplayHost
        do {
            display = try VirtualDisplayHost.start(name: name,
                                                   pixelWidth: plan.virtualPixelWidth,
                                                   pixelHeight: plan.virtualPixelHeight,
                                                   pointWidth: plan.virtualPointWidth,
                                                   pointHeight: plan.virtualPointHeight,
                                                   refreshHz: max(hello.refreshHz, fps))
        } catch {
            reject(error.localizedDescription)
            return
        }
        display.onUnexpectedExit = { [weak self, weak display] in
            Task { @MainActor in
                guard let self, let display, self.virtualDisplay === display else { return }
                self.state = .failed("The extra display disappeared unexpectedly.")
                self.teardownPipeline()
            }
        }
        self.virtualDisplay = display
        self.isHiDPI = display.isHiDPI
        self.virtualDisplayName = name
        self.displayPlan = plan
        let generation = self.generation

        let codec = chooseCodec(hello)

        let encoder = VideoEncoder(configuration: .init(codec: codec,
                                                        width: plan.streamPixelWidth,
                                                        height: plan.streamPixelHeight,
                                                        fps: fps,
                                                        bitrateKbps: settings.targetBitrateKbps,
                                                        maxAllowedQP: settings.prioritiseTextClarity ? 34 : nil))
        // Parameter sets arrive only on an IDR, so the main-actor hop is harmless here.
        encoder.onParameterSets = { [weak self] sets in
            Task { @MainActor in
                guard let self, self.generation == generation else { return }
                self.latestParameterSets = sets
                self.link?.sendJSON(.videoParams, sets)
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
                                                          pixelWidth: plan.virtualPixelWidth,
                                                          pixelHeight: plan.virtualPixelHeight,
                                                          streamWidth: plan.streamPixelWidth,
                                                          streamHeight: plan.streamPixelHeight,
                                                          fps: fps,
                                                          showsCursor: true))
        // Straight from the capture queue into the pump. No actor hop, no reordering.
        capture.onFrame = { [pump] pixelBuffer, captureTime in
            pump.submit(pixelBuffer: pixelBuffer, captureTimeMicros: captureTime)
        }
        capture.onStopped = { [weak self] (error: Error?) in
            Task { @MainActor in
                // A capture torn down by a reconfigure may report its display vanishing.
                guard let self, self.generation == generation else { return }
                self.state = .failed(error?.localizedDescription ?? "Screen capture stopped.")
            }
        }
        self.capture = capture

        Task {
            do {
                try await capture.start()
            } catch {
                await MainActor.run {
                    guard self.generation == generation else { return }
                    self.reject(error.localizedDescription)
                }
                return
            }
            await MainActor.run {
                guard self.generation == generation else { return }
                self.state = .running(hello.deviceName)
                self.startAudioIfWanted(for: hello)
                let ack = HostHelloAck(accepted: true, reason: nil,
                                       hostName: Host.current().localizedName ?? "Mac",
                                       codec: codec,
                                       pixelWidth: plan.streamPixelWidth, pixelHeight: plan.streamPixelHeight,
                                       pointWidth: plan.virtualPointWidth, pointHeight: plan.virtualPointHeight,
                                       fps: fps, bitrateKbps: self.settings.targetBitrateKbps,
                                       isHiDPI: display.isHiDPI,
                                       platform: "macos",
                                       audioAvailable: self.audio != nil)
                self.link?.sendJSON(.helloAck, ack)
                self.pump?.requestKeyframe()
                self.startStatsSampling()
                // After the ack, as in spec §9: the client learns the stream first, then audio.
                self.sendAudioConfigIfStarted()
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
                               fps: 0, bitrateKbps: 0, isHiDPI: false, platform: "macos")
        link?.sendJSON(.helloAck, ack)
        teardownPipeline()
    }

    // MARK: Audio

    /// Pending config from the last `start`, sent once the helloAck is out.
    private var pendingAudioConfig: AudioConfig?

    /// Starts system audio towards the client when everything lines up: a v2 client that can
    /// play audio and told us where, audio not switched off here, and an OS with process taps.
    /// Already running at the same port (a reconfigure) → left alone.
    private func startAudioIfWanted(for hello: ClientHello) {
        let wanted = hello.speaksV2
            && hello.supportsAudio == true
            && hello.audioUDPPort != nil
            && settings.audioMode != .off
            && AudioStreamer.isSupported
        guard wanted, let port = hello.audioUDPPort, let udpPort = UInt16(exactly: port), udpPort != 0 else {
            stopAudio(notifyClient: true)
            return
        }
        if audio != nil, audioTargetPort == port { return }
        stopAudio(notifyClient: false)

        guard let link, let host = link.remoteHost else {
            Log.app("audio: no peer address to stream to")
            return
        }
        let streamer = AudioStreamer(mode: settings.audioMode,
                                     playoutDelayMs: settings.audioPlayoutDelayMs,
                                     syncOffsetMs: settings.audioSyncOffsetMs)
        do {
            pendingAudioConfig = try streamer.start(host: host, port: udpPort, sessionKey: link.sessionKey)
            audio = streamer
            audioTargetPort = port
            isStreamingAudio = true
            Log.app("audio: streaming to \(host):\(udpPort) (\(settings.audioMode.rawValue))")
        } catch {
            Log.app("audio: could not start: \(error.localizedDescription)")
            streamer.stop()
        }
    }

    private func sendAudioConfigIfStarted() {
        guard let config = pendingAudioConfig else { return }
        pendingAudioConfig = nil
        if peerSpeaksV2 { link?.sendJSON(.audioConfig, config) }
    }

    /// Stops audio and restores normal Mac output. Tells the client when asked and the link is
    /// still up — never a v1 peer, which would drop the link on the unknown type.
    private func stopAudio(notifyClient: Bool) {
        pendingAudioConfig = nil
        guard let audio else { return }
        audio.stop()
        self.audio = nil
        audioTargetPort = nil
        isStreamingAudio = false
        if notifyClient, peerSpeaksV2, let link, link.isOpen {
            link.send(.audioStop)
        }
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
