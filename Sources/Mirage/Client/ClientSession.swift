import Foundation
import AppKit
import Network

/// Drives the receiving Mac: advertise this screen, accept one host, render what arrives.
@MainActor
final class ClientSession: ObservableObject {

    enum State: Equatable {
        case idle
        case advertising
        case connected(String)
        case failed(String)
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var stats = SessionStats()
    @Published private(set) var warning: String?
    /// TCP port this Mac is listening on while advertising. Ephemeral, so the UI shows it for
    /// a host that has to connect by address (`DiscoveredDisplay.manual(host:port:)`).
    @Published private(set) var listeningPort: UInt16?

    var settings: ClientSettings

    let renderer = VideoRenderer()
    private let advertiser = DisplayAdvertiser()
    private var link: PeerLink? {
        didSet { linkForRendering = link }
    }
    /// Reachable from the render and network queues without touching the main actor.
    private nonisolated(unsafe) var linkForRendering: PeerLink?
    private var presenter: ReceiverWindowController?
    private var inputForwarder: InputForwarder?
    private var statsTimer: Timer?
    private var reportTimer: Timer?

    /// Decode and present happen here, never on the main actor. A serial queue also keeps
    /// frames in the order they arrived — `Task { @MainActor }` did not, which could hand the
    /// decoder an out-of-order frame.
    private let renderQueue = DispatchQueue(label: "com.voxturr.mirage.render", qos: .userInteractive)
    private let collector = StatsCollector()
    private nonisolated(unsafe) var bytesSinceReport = 0
    /// Set once a v1 host has turned down our v2 hello and we have re-sent it as v1.
    private var sentLegacyHello = false

    init(settings: ClientSettings) {
        self.settings = settings
        renderer.onNeedsKeyframe = { [weak self] in
            self?.linkForRendering?.send(.requestKeyframe)
        }
    }

    // MARK: Advertising

    func startAdvertising() {
        stop()
        let panel = Self.currentPanel(preferNative: settings.useNativePanelResolution)
        do {
            advertiser.onConnection = { [weak self] link in
                Task { @MainActor in self?.accept(link) }
            }
            advertiser.onError = { [weak self] error in
                Task { @MainActor in self?.state = .failed(error.localizedDescription) }
            }
            advertiser.onListening = { [weak self] port in
                Task { @MainActor in self?.listeningPort = port }
            }
            // This Mac plays no audio as a display (yet), so `audio` stays 0.
            try advertiser.start(displayName: settings.displayName,
                                 panel: panel,
                                 pairingCode: settings.pairingCode,
                                 allowPeerToPeer: settings.allowPeerToPeer,
                                 platform: "macos",
                                 supportsAudio: false)
            state = .advertising
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func stop() {
        statsTimer?.invalidate(); statsTimer = nil
        reportTimer?.invalidate(); reportTimer = nil
        inputForwarder?.stop(); inputForwarder = nil
        presenter?.close(); presenter = nil
        link?.send(.bye)
        link?.close(); link = nil
        advertiser.stop()
        listeningPort = nil
        warning = nil
        if case .failed = state {} else { state = .idle }
    }

    private func accept(_ link: PeerLink) {
        // One host at a time. A second connection is refused rather than fighting for the screen.
        guard self.link == nil else { link.close(); return }
        self.link = link
        sentLegacyHello = false

        link.onReady = { [weak self] in
            Task { @MainActor in self?.sendHello() }
        }
        link.onMessage = { [weak self] type, flags, timestamp, payload in
            guard let self else { return }
            // The hot path: straight onto the render queue, in order, with no actor hop and
            // no SwiftUI invalidation. Control messages are rare enough to hop.
            if type == .videoFrame {
                let isKeyframe = flags & FrameFlags.keyframe != 0
                self.renderQueue.async {
                    self.bytesSinceReport += payload.count
                    self.collector.record(frameBytes: payload.count)
                    self.renderer.enqueue(frameData: payload, isKeyframe: isKeyframe)
                    // Tells the host this frame is through; it will not run more than a few
                    // frames ahead of us, which is what keeps end-to-end latency bounded.
                    self.linkForRendering?.send(.frameAck, timestampMicros: timestamp)
                    self.collector.update {
                        $0.framesDroppedLocal = self.renderer.framesDropped
                        $0.decodeMs = self.renderer.decodeMsAverage
                    }
                }
                return
            }
            if type == .videoParams {
                guard let sets = try? JSONDecoder().decode(VideoParameterSets.self, from: payload) else { return }
                self.renderQueue.async { self.renderer.apply(parameterSets: sets) }
                return
            }
            Task { @MainActor in self.handle(type: type, flags: flags, timestamp: timestamp, payload: payload) }
        }
        link.onFailure = { [weak self] error in
            Task { @MainActor in
                guard let self else { return }
                self.presenter?.close(); self.presenter = nil
                self.inputForwarder?.stop(); self.inputForwarder = nil
                self.link = nil
                self.state = .advertising
                if let error { Log.net("link ended: \(error.localizedDescription)") }
            }
        }
        link.start()
    }

    private func makeHello() -> ClientHello {
        let panel = Self.currentPanel(preferNative: settings.useNativePanelResolution)
        var hello = ClientHello(deviceName: settings.displayName,
                                pixelWidth: panel.pixelWidth,
                                pixelHeight: panel.pixelHeight,
                                pointWidth: panel.pointWidth,
                                pointHeight: panel.pointHeight,
                                refreshHz: panel.refreshHz,
                                supportsHEVC: VideoRenderer.supportsHardwareHEVC,
                                supportsH264: true,
                                wantsInputForwarding: settings.forwardInput)
        // v2 fields. A Mac host still builds exactly the panel above for platform "macos";
        // no scale preference and no audio from this side.
        hello.platform = "macos"
        hello.physicalDPI = panel.physicalDPI > 0 ? panel.physicalDPI : nil
        hello.supportsAudio = false
        hello.orientation = panel.pixelWidth >= panel.pixelHeight ? "landscape" : "portrait"
        return hello
    }

    private func sendHello() {
        link?.sendJSON(.hello, makeHello())
    }

    // MARK: Incoming messages

    private func handle(type: MessageType, flags: UInt8, timestamp: UInt64, payload: Data) {
        switch type {
        case .helloAck:
            guard let ack = try? JSONDecoder().decode(HostHelloAck.self, from: payload) else { return }
            guard ack.accepted else {
                // A v1 host rejects any version but 1 — and says so in a helloAck without the
                // v2 `platform` field. Re-send the same panel as v1 once; the host keeps the
                // link open after a rejection and serves the next hello.
                if ack.isFromV1Host, !sentLegacyHello, ack.reason?.contains("Version mismatch") == true {
                    sentLegacyHello = true
                    Log.net("v1 host; re-sending hello as protocol v1")
                    link?.sendJSON(.hello, makeHello().downgradedToV1)
                    return
                }
                state = .failed(ack.reason ?? "The other Mac refused the connection.")
                presenter?.close(); presenter = nil
                inputForwarder?.stop(); inputForwarder = nil
                link?.close(); link = nil
                return
            }
            state = .connected(ack.hostName)
            warning = ack.isHiDPI ? nil
                : "The virtual display did not take a Retina mode, so text will look soft. Try reconnecting."
            // A second helloAck on the same link is the host confirming a rebuilt display (a
            // reconfigure): the window stays, and the renderer resets on the new parameter sets.
            if presenter == nil {
                presentFullScreen(ack: ack)
                startTimers()
            } else {
                Log.video("host updated the stream: \(ack.pixelWidth)x\(ack.pixelHeight) @ \(ack.fps)")
            }

        case .bye:
            presenter?.close(); presenter = nil
            link?.close(); link = nil
            state = .advertising

        default:
            break
        }
    }

    // MARK: Presentation

    private func presentFullScreen(ack: HostHelloAck) {
        presenter?.close()
        let controller = ReceiverWindowController(renderer: renderer,
                                                  screen: NSScreen.main ?? NSScreen.screens[0])
        controller.onExit = { [weak self] in
            Task { @MainActor in self?.stop() }
        }
        controller.statsProvider = { [weak self] in self?.stats ?? SessionStats() }
        controller.show()
        presenter = controller

        if settings.forwardInput {
            let forwarder = InputForwarder(view: controller.contentView) { [weak self] event in
                self?.link?.send(.input, payload: event.encoded())
            }
            forwarder.start()
            inputForwarder = forwarder
        }
    }

    /// Samples counters for the UI and reports them upstream. The heartbeat itself lives in
    /// PeerLink now, measured on the network queue, so the number is the link's latency
    /// rather than this app's main-thread queue depth.
    private func startTimers() {
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let rtt = self.link?.roundTripMs ?? 0
                self.collector.update { $0.roundTripMs = rtt }
                let snapshot = self.collector.snapshot()
                if snapshot != self.stats { self.stats = snapshot }
            }
        }
        reportTimer?.invalidate()
        reportTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                self.renderQueue.async {
                    let report = ClientStats(framesDecoded: self.renderer.framesDecoded,
                                             framesDropped: self.renderer.framesDropped,
                                             averageLatencyMs: self.link?.roundTripMs ?? 0,
                                             decodeMsAverage: self.renderer.decodeMsAverage,
                                             bytesReceived: self.bytesSinceReport)
                    self.bytesSinceReport = 0
                    self.linkForRendering?.sendJSON(.stats, report)
                }
            }
        }
    }

    // MARK: Panel geometry

    /// Describes the screen this Mac will render into.
    ///
    /// By default we report the current framebuffer (points x backing scale). Matching that
    /// exactly means the video, the window and the framebuffer are all the same size, so
    /// nothing in the chain resamples the picture.
    static func currentPanel(preferNative: Bool) -> PanelDescription {
        let screen = NSScreen.main ?? NSScreen.screens[0]
        let scale = screen.backingScaleFactor
        var pointWidth = Int(screen.frame.width.rounded())
        var pointHeight = Int(screen.frame.height.rounded())
        var pixelWidth = Int((screen.frame.width * scale).rounded())
        var pixelHeight = Int((screen.frame.height * scale).rounded())

        if preferNative,
           let displayID = screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber,
           let mode = CGDisplayCopyDisplayMode(CGDirectDisplayID(displayID.uint32Value)) {
            pixelWidth = mode.pixelWidth
            pixelHeight = mode.pixelHeight
            pointWidth = mode.width
            pointHeight = mode.height
        }

        // The private API only synthesises a Retina mode for an even point size.
        if pixelWidth % 2 != 0 { pixelWidth -= 1 }
        if pixelHeight % 2 != 0 { pixelHeight -= 1 }

        let refresh = screen.maximumFramesPerSecond
        let displayID = (screen.deviceDescription[NSDeviceDescriptionKey("NSScreenNumber")] as? NSNumber)
            .map { CGDirectDisplayID($0.uint32Value) }
        return PanelDescription(pixelWidth: pixelWidth,
                                pixelHeight: pixelHeight,
                                pointWidth: pointWidth,
                                pointHeight: pointHeight,
                                refreshHz: refresh > 0 ? refresh : 60,
                                physicalDPI: displayID.map(physicalDPI(of:)) ?? 0)
    }

    /// Native pixels per inch of a panel, 0 when the display does not report its size (some
    /// projectors and TVs). Informative only: a Mac host sizes a Mac client by its framebuffer.
    static func physicalDPI(of displayID: CGDirectDisplayID) -> Double {
        let millimetres = CGDisplayScreenSize(displayID).width
        guard millimetres > 0 else { return 0 }
        // The current mode may be a scaled one, so take the widest mode's pixels: the panel's own.
        let options = [kCGDisplayShowDuplicateLowResolutionModes: kCFBooleanTrue] as CFDictionary
        let modes = (CGDisplayCopyAllDisplayModes(displayID, options) as? [CGDisplayMode]) ?? []
        let nativeWidth = modes.map(\.pixelWidth).max() ?? CGDisplayPixelsWide(displayID)
        guard nativeWidth > 0 else { return 0 }
        return (Double(nativeWidth) / (millimetres / 25.4)).rounded()
    }
}

struct ClientSettings: Codable, Equatable {
    var displayName: String = Host.current().localizedName ?? "Mac"
    var forwardInput = true
    var showStatsOverlay = false
    var allowPeerToPeer = false
    var useNativePanelResolution = false
    var pairingCode: String? = nil
}
