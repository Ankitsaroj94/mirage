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

    var settings: ClientSettings

    let renderer = VideoRenderer()
    private let advertiser = DisplayAdvertiser()
    private var link: PeerLink?
    private var presenter: ReceiverWindowController?
    private var inputForwarder: InputForwarder?
    private var pingTimer: Timer?
    private var statsTimer: Timer?
    private var bytesSinceReport = 0
    private var roundTripSamples: [Double] = []

    init(settings: ClientSettings) {
        self.settings = settings
        renderer.onNeedsKeyframe = { [weak self] in
            Task { @MainActor in self?.link?.send(.requestKeyframe) }
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
            try advertiser.start(displayName: settings.displayName,
                                 panel: panel,
                                 pairingCode: settings.pairingCode,
                                 allowPeerToPeer: settings.allowPeerToPeer)
            state = .advertising
        } catch {
            state = .failed(error.localizedDescription)
        }
    }

    func stop() {
        pingTimer?.invalidate(); pingTimer = nil
        statsTimer?.invalidate(); statsTimer = nil
        inputForwarder?.stop(); inputForwarder = nil
        presenter?.close(); presenter = nil
        link?.send(.bye)
        link?.close(); link = nil
        advertiser.stop()
        warning = nil
        if case .failed = state {} else { state = .idle }
    }

    private func accept(_ link: PeerLink) {
        // One host at a time. A second connection is refused rather than fighting for the screen.
        guard self.link == nil else { link.close(); return }
        self.link = link

        link.onReady = { [weak self] in
            Task { @MainActor in self?.sendHello() }
        }
        link.onMessage = { [weak self] type, flags, timestamp, payload in
            Task { @MainActor in self?.handle(type: type, flags: flags, timestamp: timestamp, payload: payload) }
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

    private func sendHello() {
        let panel = Self.currentPanel(preferNative: settings.useNativePanelResolution)
        let hello = ClientHello(deviceName: settings.displayName,
                                pixelWidth: panel.pixelWidth,
                                pixelHeight: panel.pixelHeight,
                                pointWidth: panel.pointWidth,
                                pointHeight: panel.pointHeight,
                                refreshHz: panel.refreshHz,
                                supportsHEVC: VideoRenderer.supportsHardwareHEVC,
                                supportsH264: true,
                                wantsInputForwarding: settings.forwardInput)
        link?.sendJSON(.hello, hello)
    }

    // MARK: Incoming messages

    private func handle(type: MessageType, flags: UInt8, timestamp: UInt64, payload: Data) {
        switch type {
        case .helloAck:
            guard let ack = try? JSONDecoder().decode(HostHelloAck.self, from: payload) else { return }
            guard ack.accepted else {
                state = .failed(ack.reason ?? "The other Mac refused the connection.")
                link?.close(); link = nil
                return
            }
            state = .connected(ack.hostName)
            warning = ack.isHiDPI ? nil
                : "The virtual display did not take a Retina mode, so text will look soft. Try reconnecting."
            presentFullScreen(ack: ack)
            startTimers()

        case .videoParams:
            guard let sets = try? JSONDecoder().decode(VideoParameterSets.self, from: payload) else { return }
            renderer.apply(parameterSets: sets)

        case .videoFrame:
            bytesSinceReport += payload.count
            stats.record(frameBytes: payload.count)
            renderer.enqueue(frameData: payload, isKeyframe: flags & FrameFlags.keyframe != 0)
            stats.framesDroppedLocal = renderer.framesDropped
            stats.decodeMs = renderer.decodeMsAverage

        case .pong:
            let rtt = Double(MonotonicClock.micros() &- timestamp) / 1000.0
            roundTripSamples.append(rtt)
            if roundTripSamples.count > 20 { roundTripSamples.removeFirst() }
            stats.roundTripMs = roundTripSamples.reduce(0, +) / Double(roundTripSamples.count)

        case .ping:
            link?.send(.pong, timestampMicros: timestamp)

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

    private func startTimers() {
        pingTimer?.invalidate()
        pingTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.link?.send(.ping) }
        }
        statsTimer?.invalidate()
        statsTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                let report = ClientStats(framesDecoded: self.renderer.framesDecoded,
                                         framesDropped: self.renderer.framesDropped,
                                         averageLatencyMs: self.stats.roundTripMs,
                                         decodeMsAverage: self.renderer.decodeMsAverage,
                                         bytesReceived: self.bytesSinceReport)
                self.bytesSinceReport = 0
                self.link?.sendJSON(.stats, report)
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
        return PanelDescription(pixelWidth: pixelWidth,
                                pixelHeight: pixelHeight,
                                pointWidth: pointWidth,
                                pointHeight: pointHeight,
                                refreshHz: refresh > 0 ? refresh : 60)
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
