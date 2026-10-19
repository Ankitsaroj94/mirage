import SwiftUI
import AppKit

enum Role: String, CaseIterable, Identifiable {
    case send    = "Send my screen"
    case receive = "Be a display"
    var id: String { rawValue }

    var explanation: String {
        switch self {
        case .send:    return "Creates an extra display on this Mac and streams it to another Mac or an Android tablet."
        case .receive: return "Offers this Mac's screen to another Mac or an Android tablet on the network."
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
    @Published var showingConnectByAddress = false

    let host: HostSession
    let client: ClientSession
    private let browser = DisplayBrowser()

    /// Host settings survive relaunches so per-tablet screen sizes are remembered by name.
    private static let hostSettingsKey = "hostSettings"

    init() {
        let hostSettings = UserDefaults.standard.data(forKey: Self.hostSettingsKey)
            .flatMap { try? JSONDecoder().decode(HostSettings.self, from: $0) } ?? HostSettings()
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
        if let data = try? JSONEncoder().encode(hostSettings) {
            UserDefaults.standard.set(data, forKey: Self.hostSettingsKey)
        }

        var c = clientSettings
        c.pairingCode = usePairingCode ? pairingCode : nil
        client.settings = c
    }

    func connect(to display: DiscoveredDisplay) {
        applySettings()
        host.connect(to: display)
    }

    /// Per-device screen size for Android tablets. Crisp unless the user picked otherwise.
    func scalePreference(for display: DiscoveredDisplay) -> Binding<ScalePreference> {
        Binding(get: { self.hostSettings.scalePreferences[display.name] ?? .crisp },
                set: { self.hostSettings.scalePreferences[display.name] = $0 })
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
                VStack(spacing: 6) {
                    if model.discovered.isEmpty {
                        HStack(spacing: 8) {
                            ProgressView().controlSize(.small)
                            Text("Looking for a Mac or Android tablet running Mirage…")
                                .foregroundColor(.secondary)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(2)
                    } else {
                        ForEach(model.discovered) { display in
                            DiscoveredRow(display: display,
                                          scale: display.isAndroid ? model.scalePreference(for: display) : nil,
                                          isBusy: isConnecting(display),
                                          isConnected: isConnected(display)) {
                                model.connect(to: display)
                            } disconnect: {
                                model.host.disconnect()
                            }
                        }
                    }
                    HStack {
                        Spacer()
                        Button("Connect by address…") { model.showingConnectByAddress = true }
                            .controlSize(.small)
                    }
                }
                .padding(4)
            }
            .sheet(isPresented: $model.showingConnectByAddress) {
                ConnectByAddressSheet { host, port in
                    model.showingConnectByAddress = false
                    if let display = DiscoveredDisplay.manual(host: host, port: port) {
                        model.connect(to: display)
                    }
                } cancel: {
                    model.showingConnectByAddress = false
                }
            }

            GroupBox("Quality") {
                VStack(alignment: .leading, spacing: 10) {
                    Toggle("Prefer HEVC when both sides support it", isOn: $model.hostSettings.preferHEVC)
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
                    Toggle("Allow 120 Hz", isOn: $model.hostSettings.allow120Hz)
                    Text("Only used when the display's panel runs at 120 Hz, like the Galaxy Tab S9 FE. Costs more bandwidth and battery.")
                        .font(.caption).foregroundColor(.secondary)

                    Toggle("Accept keyboard, trackpad and touch from the display", isOn: $model.hostSettings.acceptRemoteInput)
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

            GroupBox("Sound") {
                SoundSettings(settings: $model.hostSettings)
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
    /// Only Android tablets get a screen-size choice; a Mac always gets its exact panel.
    let scale: Binding<ScalePreference>?
    let isBusy: Bool
    let isConnected: Bool
    let connect: () -> Void
    let disconnect: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: display.isAndroid ? "ipad.landscape" : "laptopcomputer")
                    .font(.title2)
                    .foregroundColor(.accentColor)
                    .frame(width: 32)
                VStack(alignment: .leading, spacing: 2) {
                    HStack(spacing: 4) {
                        Text(display.name).fontWeight(.medium)
                        if display.isAndroid {
                            Text("Android")
                                .font(.caption2).fontWeight(.medium)
                                .foregroundColor(.green)
                                .padding(.horizontal, 5).padding(.vertical, 1)
                                .background(Color.green.opacity(0.15))
                                .cornerRadius(4)
                        }
                        if display.requiresPairingCode {
                            Image(systemName: "lock.fill").font(.caption2).foregroundColor(.secondary)
                        }
                    }
                    if display.pixelWidth > 0 {
                        Text(display.isAndroid ? display.resolutionSummary
                                               : "\(display.resolutionSummary) · \(display.scaledSummary)")
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

            if let scale {
                HStack {
                    Picker("Screen size", selection: scale) {
                        ForEach(ScalePreference.allCases, id: \.self) { Text(ScaleHint.title($0)).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .fixedSize()
                    if display.pixelWidth > 0 {
                        let pt = ScaleHint.looksLike(pixelWidth: display.pixelWidth,
                                                     pixelHeight: display.pixelHeight,
                                                     preference: scale.wrappedValue)
                        Text("looks like \(pt.width)×\(pt.height)")
                            .font(.caption).foregroundColor(.secondary).monospacedDigit()
                    }
                }
                .font(.callout)
                .padding(.leading, 40)
            }
        }
        .padding(.vertical, 4)
    }
}

/// "Play Mac sound on" plus its two timing sliders.
private struct SoundSettings: View {
    @Binding var settings: HostSettings

    private var supported: Bool { AudioStreamer.isSupported }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Picker("Play Mac sound on", selection: $settings.audioMode) {
                Text("Mac + tablet, in sync").tag(AudioMode.both)
                Text("Tablet only").tag(AudioMode.tabletOnly)
                Text("This Mac only").tag(AudioMode.off)
            }
            .disabled(!supported)

            Text(supported
                 ? "Applies to Android tablets. macOS asks for System Audio Recording the first time."
                 : "Sending sound needs macOS 14.2 or later, so it stays on this Mac.")
                .font(.caption).foregroundColor(.secondary)

            Group {
                HStack {
                    Text("Delay")
                    Slider(value: Binding(
                        get: { Double(settings.audioPlayoutDelayMs) },
                        set: { settings.audioPlayoutDelayMs = Int($0) }),
                           in: 40...150, step: 5)
                    Text("\(settings.audioPlayoutDelayMs) ms")
                        .monospacedDigit().frame(width: 70, alignment: .trailing)
                }
                Text("Both speakers hold the sound this long before playing. Raise it if the tablet crackles on busy Wi-Fi.")
                    .font(.caption).foregroundColor(.secondary)

                HStack {
                    Text("Sync")
                    Slider(value: Binding(
                        get: { Double(settings.audioSyncOffsetMs) },
                        set: { settings.audioSyncOffsetMs = Int($0) }),
                           in: -50...50, step: 1)
                    Text(String(format: "%+d ms", settings.audioSyncOffsetMs))
                        .monospacedDigit().frame(width: 70, alignment: .trailing)
                }
                .disabled(settings.audioMode != .both)
                Text("Nudges this Mac's speakers against the tablet's. Positive plays the Mac later.")
                    .font(.caption).foregroundColor(.secondary)
            }
            .disabled(!supported || settings.audioMode == .off)
        }
    }
}

/// Host and port typed by hand, for networks that filter Bonjour between clients.
private struct ConnectByAddressSheet: View {
    let connect: (String, UInt16) -> Void
    let cancel: () -> Void

    @State private var host = ""
    @State private var port = ""

    private var trimmedHost: String { host.trimmingCharacters(in: .whitespaces) }
    private var parsedPort: UInt16? {
        guard let value = UInt16(port.trimmingCharacters(in: .whitespaces)), value > 0 else { return nil }
        return value
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Connect by address").font(.headline)
            Text("For when the display never shows up in the list. Its address and port are shown on the display under “Be a display”.")
                .font(.caption).foregroundColor(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            HStack {
                Text("Address").frame(width: 60, alignment: .leading)
                TextField("192.168.1.20", text: $host)
            }
            HStack {
                Text("Port").frame(width: 60, alignment: .leading)
                TextField("e.g. 51234", text: $port).frame(width: 100)
                Spacer()
            }
            HStack {
                Spacer()
                Button("Cancel", action: cancel)
                    .keyboardShortcut(.cancelAction)
                Button("Connect") {
                    if let parsedPort { connect(trimmedHost, parsedPort) }
                }
                .keyboardShortcut(.defaultAction)
                .disabled(trimmedHost.isEmpty || parsedPort == nil)
            }
        }
        .padding(16)
        .frame(width: 340)
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
                    if let port = client.listeningPort {
                        LabeledRow("Address", connectAddress(port: port))
                    }
                    Text("The sending device builds a virtual display with exactly these numbers, so the picture is never scaled.")
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
                    Toggle("Send my keyboard and trackpad to the sending device", isOn: $model.clientSettings.forwardInput)
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
                           title: "Waiting for the other device",
                           message: waitingMessage) { EmptyView() }
            case .connected(let name):
                CalloutBox(kind: .success, title: "Connected to \(name)", message: client.warning) { EmptyView() }
            case .failed(let reason):
                CalloutBox(kind: .warning, title: "Stopped", message: reason) { EmptyView() }
            }
        }
    }

    private var waitingMessage: String {
        var text = "Open Mirage on your other Mac, choose “Send my screen”, and pick \(model.clientSettings.displayName). Android tablets can also send their screen here."
        if let port = client.listeningPort {
            text += "\nNot listed? Connect by address: \(connectAddress(port: port))."
        }
        return text
    }

    /// "192.168.1.20 port 51234", or just the port when no LAN address is up.
    private func connectAddress(port: UInt16) -> String {
        guard let address = LocalAddress.primaryIPv4() else { return "port \(port)" }
        return "\(address) port \(port)"
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
                    Text("Encrypts the stream. Enter the same code on both devices.")
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

// MARK: - Helpers

/// What each screen-size choice means on a given panel, for the picker's hint. Mirrors the
/// host's geometry rule (spec/PROTOCOL.md §5): crisp is half the panel's pixels in points,
/// the others scale that by a factor, rounded to even numbers.
enum ScaleHint {
    static func title(_ preference: ScalePreference) -> String {
        switch preference {
        case .crisp:  return "Crisp"
        case .larger: return "Larger text"
        case .more:   return "More space"
        case .most:   return "Most space"
        }
    }

    static func factor(_ preference: ScalePreference) -> Double {
        switch preference {
        case .crisp:  return 1.0
        case .larger: return 0.85
        case .more:   return 1.15
        case .most:   return 1.3
        }
    }

    static func looksLike(pixelWidth: Int, pixelHeight: Int,
                          preference: ScalePreference) -> (width: Int, height: Int) {
        let f = factor(preference)
        func even(_ value: Double) -> Int { Int((value / 2).rounded()) * 2 }
        return (even(Double(pixelWidth) / 2 * f), even(Double(pixelHeight) / 2 * f))
    }
}

/// This Mac's LAN address, shown so another device can connect by hand when Bonjour is blocked.
enum LocalAddress {
    static func primaryIPv4() -> String? {
        var head: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&head) == 0, let first = head else { return nil }
        defer { freeifaddrs(head) }

        var candidates: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let entry = pointer.pointee
            let flags = Int32(entry.ifa_flags)
            guard let addr = entry.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  flags & IFF_UP != 0, flags & IFF_LOOPBACK == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(addr, socklen_t(addr.pointee.sa_len), &host, socklen_t(host.count),
                              nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let address = String(cString: host)
            guard !address.hasPrefix("169.254.") else { continue }   // link-local, not routable
            candidates.append((String(cString: entry.ifa_name), address))
        }
        // Wi-Fi / built-in Ethernet first, then anything else (USB tethering and the like).
        return (candidates.first { $0.name.hasPrefix("en") } ?? candidates.first)?.address
    }
}
