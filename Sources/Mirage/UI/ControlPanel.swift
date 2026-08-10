import SwiftUI
import AppKit

enum Role: String, CaseIterable, Identifiable {
    case send    = "Send my screen"
    case receive = "Be a display"
    var id: String { rawValue }

    var explanation: String {
        switch self {
        case .send:    return "Creates an extra display on this Mac and streams it to another Mac."
        case .receive: return "Offers this Mac's screen to another Mac on the network."
        }
    }
}

@MainActor
final class AppModel: ObservableObject {
    @Published var role: Role = .send
    @Published var discovered: [DiscoveredDisplay] = []
    @Published var hostSettings = HostSettings()
    @Published var clientSettings = ClientSettings()
    @Published var pairingCode: String = ""
    @Published var usePairingCode = false
    @Published var screenRecordingGranted = false

    let host: HostSession
    let client: ClientSession
    private let browser = DisplayBrowser()

    init() {
        let hostSettings = HostSettings()
        let clientSettings = ClientSettings()
        self.hostSettings = hostSettings
        self.clientSettings = clientSettings
        self.host = HostSession(settings: hostSettings)
        self.client = ClientSession(settings: clientSettings)

        browser.onUpdate = { [weak self] displays in self?.discovered = displays }
        refreshPermissions()
    }

    func startBrowsing() {
        browser.start(allowPeerToPeer: hostSettings.allowPeerToPeer)
    }

    func stopBrowsing() {
        browser.stop()
    }

    func applySettings() {
        var h = hostSettings
        h.pairingCode = usePairingCode ? pairingCode : nil
        host.settings = h

        var c = clientSettings
        c.pairingCode = usePairingCode ? pairingCode : nil
        client.settings = c
    }

    func refreshPermissions() {
        screenRecordingGranted = CGPreflightScreenCaptureAccess()
    }

    func requestScreenRecording() {
        CGRequestScreenCaptureAccess()
        // TCC only re-reads the grant at launch, so a relaunch is genuinely required.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { self.refreshPermissions() }
    }
}

struct ControlPanel: View {
    @ObservedObject var model: AppModel

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $model.role) {
                ForEach(Role.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            .padding(12)

            Divider()

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(model.role.explanation)
                        .font(.callout)
                        .foregroundColor(.secondary)

                    switch model.role {
                    case .send:    SenderPane(model: model)
                    case .receive: ReceiverPane(model: model)
                    }

                    Divider()
                    SharedSettingsPane(model: model)
                }
                .padding(16)
            }
        }
        .frame(width: 460, height: 620)
        .onChange(of: model.role) { _ in
            model.applySettings()
            if model.role == .send { model.startBrowsing() } else { model.stopBrowsing() }
        }
        .onAppear {
            model.applySettings()
            if model.role == .send { model.startBrowsing() }
        }
    }
}

// MARK: - Sending Mac

private struct SenderPane: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var host: HostSession

    init(model: AppModel) {
        self.model = model
        self.host = model.host
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {

            if !model.screenRecordingGranted {
                CalloutBox(kind: .warning,
                           title: "Screen Recording permission needed",
                           message: "Mirage captures the extra display it creates, so macOS treats it as screen recording. Grant it, then relaunch the app.") {
                    Button("Grant…") { model.requestScreenRecording() }
                }
            }

            statusRow

            GroupBox("Available displays") {
                if model.discovered.isEmpty {
                    HStack(spacing: 8) {
                        ProgressView().controlSize(.small)
                        Text("Looking for another Mac running Mirage…")
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(6)
                } else {
                    VStack(spacing: 6) {
                        ForEach(model.discovered) { display in
                            DiscoveredRow(display: display,
                                          isBusy: isConnecting(display),
                                          isConnected: isConnected(display)) {
                                model.applySettings()
                                model.host.connect(to: display)
                            } disconnect: {
                                model.host.disconnect()
                            }
                        }
                    }
                    .padding(4)
                }
            }

            GroupBox("Quality") {
                VStack(alignment: .leading, spacing: 10) {
                    Toggle("Prefer HEVC when both Macs support it", isOn: $model.hostSettings.preferHEVC)
                    Toggle("Keep text sharp under load", isOn: $model.hostSettings.prioritiseTextClarity)
                    Text("Caps the encoder's quantiser, so a busy moment costs frame rate instead of legibility.")
                        .font(.caption).foregroundColor(.secondary)

                    HStack {
                        Text("Bitrate")
                        Slider(value: Binding(
                            get: { Double(model.hostSettings.targetBitrateKbps) / 1000 },
                            set: { model.hostSettings.targetBitrateKbps = Int($0) * 1000 }),
                               in: 8...120, step: 1)
                        Text("\(model.hostSettings.targetBitrateKbps / 1000) Mbps")
                            .monospacedDigit().frame(width: 70, alignment: .trailing)
                    }
                    Toggle("Lower the bitrate automatically when Wi-Fi struggles", isOn: $model.hostSettings.adaptiveBitrate)

                    Picker("Frame rate", selection: $model.hostSettings.maxFPS) {
                        Text("30 fps").tag(30)
                        Text("60 fps").tag(60)
                    }
                    .pickerStyle(.segmented)

                    Toggle("Accept keyboard and trackpad from the other Mac", isOn: $model.hostSettings.acceptRemoteInput)
                    if model.hostSettings.acceptRemoteInput && !InputInjector.isPermitted {
                        HStack {
                            Text("Needs Accessibility permission.")
                                .font(.caption).foregroundColor(.secondary)
                            Button("Open Settings") { InputInjector.requestPermission() }
                                .controlSize(.small)
                        }
                    }
                }
                .padding(6)
            }
            .onChange(of: model.hostSettings) { _ in model.applySettings() }
        }
    }

    private var statusRow: some View {
        Group {
            switch host.state {
            case .idle:
                EmptyView()
            case .connecting(let name):
                CalloutBox(kind: .info, title: "Connecting to \(name)…", message: nil) { EmptyView() }
            case .running(let name):
                CalloutBox(kind: .success,
                           title: "Extending to \(name)",
                           message: liveSummary) {
                    Button("Stop") { model.host.disconnect() }
                }
            case .failed(let reason):
                CalloutBox(kind: .warning, title: "Disconnected", message: reason) { EmptyView() }
            }
        }
    }

    private var liveSummary: String {
        let stats = host.stats
        var line = String(format: "%.0f fps · %.1f Mbps · %.0f ms round trip",
                          stats.fps, stats.measuredKbps / 1000, stats.roundTripMs)
        if !host.isHiDPI { line += "\n⚠︎ Not running at Retina scale — text will look soft." }
        return line
    }

    private func isConnecting(_ display: DiscoveredDisplay) -> Bool {
        if case .connecting(let name) = host.state { return name == display.name }
        return false
    }

    private func isConnected(_ display: DiscoveredDisplay) -> Bool {
        if case .running = host.state { return true }
        return false
    }
}

private struct DiscoveredRow: View {
    let display: DiscoveredDisplay
    let isBusy: Bool
    let isConnected: Bool
    let connect: () -> Void
    let disconnect: () -> Void

    var body: some View {
        HStack {
            Image(systemName: "display")
                .font(.title2)
                .foregroundColor(.accentColor)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 4) {
                    Text(display.name).fontWeight(.medium)
                    if display.requiresPairingCode {
                        Image(systemName: "lock.fill").font(.caption2).foregroundColor(.secondary)
                    }
                }
                if display.pixelWidth > 0 {
                    Text("\(display.resolutionSummary) · \(display.scaledSummary)")
                        .font(.caption).foregroundColor(.secondary)
                }
            }
            Spacer()
            if isConnected {
                Button("Stop", action: disconnect)
            } else if isBusy {
                ProgressView().controlSize(.small)
            } else {
                Button("Connect", action: connect)
            }
        }
        .padding(.vertical, 4)
    }
}

// MARK: - Receiving Mac

private struct ReceiverPane: View {
    @ObservedObject var model: AppModel
    @ObservedObject private var client: ClientSession

    init(model: AppModel) {
        self.model = model
        self.client = model.client
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            statusRow

            GroupBox("This screen") {
                let panel = ClientSession.currentPanel(preferNative: model.clientSettings.useNativePanelResolution)
                VStack(alignment: .leading, spacing: 6) {
                    LabeledRow("Streams at", "\(panel.pixelWidth) × \(panel.pixelHeight) pixels")
                    LabeledRow("Appears as", "\(panel.pointWidth) × \(panel.pointHeight) points")
                    LabeledRow("Refresh", "\(panel.refreshHz) Hz")
                    LabeledRow("HEVC decode", VideoRenderer.supportsHardwareHEVC ? "hardware" : "software only")
                    Text("The other Mac builds a virtual display with exactly these numbers, so the picture is never scaled.")
                        .font(.caption).foregroundColor(.secondary).padding(.top, 2)
                }
                .padding(6)
            }

            GroupBox("Options") {
                VStack(alignment: .leading, spacing: 10) {
                    HStack {
                        Text("Name")
                        TextField("Mac", text: $model.clientSettings.displayName)
                    }
                    Toggle("Send my keyboard and trackpad to the other Mac", isOn: $model.clientSettings.forwardInput)
                    Toggle("Use the panel's native resolution instead of the current scaled one",
                           isOn: $model.clientSettings.useNativePanelResolution)
                }
                .padding(6)
            }
            .onChange(of: model.clientSettings) { _ in model.applySettings() }

            HStack {
                if case .idle = client.state {
                    Button("Make this Mac available") {
                        model.applySettings()
                        model.client.startAdvertising()
                    }
                    .keyboardShortcut(.defaultAction)
                } else {
                    Button("Stop") { model.client.stop() }
                }
            }

            Text("While connected: press esc to disconnect, s to show statistics.")
                .font(.caption).foregroundColor(.secondary)
        }
    }

    private var statusRow: some View {
        Group {
            switch client.state {
            case .idle:
                EmptyView()
            case .advertising:
                CalloutBox(kind: .info,
                           title: "Waiting for the other Mac",
                           message: "Open Mirage on your other Mac, choose “Send my screen”, and pick \(model.clientSettings.displayName).") { EmptyView() }
            case .connected(let name):
                CalloutBox(kind: .success, title: "Connected to \(name)", message: client.warning) { EmptyView() }
            case .failed(let reason):
                CalloutBox(kind: .warning, title: "Stopped", message: reason) { EmptyView() }
            }
        }
    }
}

// MARK: - Shared

private struct SharedSettingsPane: View {
    @ObservedObject var model: AppModel

    var body: some View {
        DisclosureGroup("Network") {
            VStack(alignment: .leading, spacing: 10) {
                Toggle("Require a pairing code", isOn: $model.usePairingCode)
                if model.usePairingCode {
                    HStack {
                        Text("Code")
                        TextField("e.g. 4821", text: $model.pairingCode)
                    }
                    Text("Encrypts the stream with TLS. Enter the same code on both Macs.")
                        .font(.caption).foregroundColor(.secondary)
                }
                Toggle("Allow direct Wi-Fi (no router)", isOn: $model.hostSettings.allowPeerToPeer)
                Text("Uses Apple's peer-to-peer radio. Works without a shared network but is slower than both Macs on the same 5 GHz Wi-Fi.")
                    .font(.caption).foregroundColor(.secondary)
            }
            .padding(.top, 8)
            .onChange(of: model.usePairingCode) { _ in model.applySettings() }
            .onChange(of: model.pairingCode) { _ in model.applySettings() }
            .onChange(of: model.hostSettings.allowPeerToPeer) { newValue in
                model.clientSettings.allowPeerToPeer = newValue
                model.applySettings()
            }
        }
        .font(.callout)
    }
}

private struct LabeledRow: View {
    let label: String
    let value: String
    init(_ label: String, _ value: String) { self.label = label; self.value = value }

    var body: some View {
        HStack {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).monospacedDigit()
        }
        .font(.callout)
    }
}

private struct CalloutBox<Accessory: View>: View {
    enum Kind { case info, success, warning }

    let kind: Kind
    let title: String
    let message: String?
    @ViewBuilder let accessory: () -> Accessory

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: symbol).foregroundColor(tint)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).fontWeight(.medium)
                if let message {
                    Text(message).font(.caption).foregroundColor(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer()
            accessory()
        }
        .padding(10)
        .background(tint.opacity(0.10))
        .cornerRadius(8)
    }

    private var symbol: String {
        switch kind {
        case .info: return "info.circle.fill"
        case .success: return "checkmark.circle.fill"
        case .warning: return "exclamationmark.triangle.fill"
        }
    }

    private var tint: Color {
        switch kind {
        case .info: return .accentColor
        case .success: return .green
        case .warning: return .orange
        }
    }
}
