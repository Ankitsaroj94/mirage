import Foundation
import Network

/// A device offering itself as a display (a Mac or an Android tablet), as advertised over
/// Bonjour — or typed in by hand when mDNS is blocked, see `manual(host:port:)`.
struct DiscoveredDisplay: Identifiable, Equatable {
    var id: String { name }
    let name: String
    let endpoint: NWEndpoint
    let pixelWidth: Int
    let pixelHeight: Int
    let pointWidth: Int
    let pointHeight: Int
    let refreshHz: Int
    let requiresPairingCode: Bool

    // v2 TXT keys. Defaults are what a v1 advertiser implies (it never sends them).
    /// "macos" | "android".
    var platform: String = "macos"
    /// Physical pixels per inch, 0 when unknown.
    var physicalDPI: Double = 0
    var supportsAudio: Bool = false
    var protocolVersion: Int = 1
    /// True for an entry made by `manual(host:port:)`: nothing is known about the panel
    /// until the device's hello arrives.
    var isManual: Bool = false

    var isAndroid: Bool { platform == "android" }

    var resolutionSummary: String {
        isManual ? "Entered by address" : "\(pixelWidth)×\(pixelHeight) @ \(refreshHz)Hz"
    }
    var scaledSummary: String { isManual ? "" : "looks like \(pointWidth)×\(pointHeight)" }

    /// A display reached by address instead of Bonjour — the fallback for networks (mesh
    /// Wi-Fi, some Samsung setups) that drop mDNS. Pass it to `HostSession.connect(to:)` like
    /// any discovered display. `host` is an IP address or host name, `port` the TCP port the
    /// receiving side shows ("Be a display" lists it; on a Mac it is
    /// `ClientSession.listeningPort`). Returns nil for an empty host or port 0.
    static func manual(host: String, port: UInt16) -> DiscoveredDisplay? {
        let trimmed = host.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, port != 0, let nwPort = NWEndpoint.Port(rawValue: port) else { return nil }
        return DiscoveredDisplay(name: "\(trimmed):\(port)",
                                 endpoint: .hostPort(host: NWEndpoint.Host(trimmed), port: nwPort),
                                 pixelWidth: 0, pixelHeight: 0, pointWidth: 0, pointHeight: 0,
                                 refreshHz: 60,
                                 // Unknown up front. A wrong guess costs nothing: with a code
                                 // set on only one side the key exchange fails with a clear error.
                                 requiresPairingCode: false,
                                 isManual: true)
    }
}

// MARK: - Receiver side: advertise this device as an available display

final class DisplayAdvertiser {
    private var listener: NWListener?
    private var pairingCode: String?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.advertiser")
    private let lock = NSLock()
    private var _port: UInt16?

    var onConnection: ((PeerLink) -> Void)?
    var onError: ((Error) -> Void)?
    /// Fires on the advertiser's queue once the listener is bound, with the TCP port a host
    /// can use for a manual connection.
    var onListening: ((UInt16) -> Void)?

    /// The bound TCP port, nil until listening. Ephemeral unless `start` was given one.
    var port: UInt16? {
        lock.lock(); defer { lock.unlock() }
        return _port
    }

    /// - Parameters:
    ///   - platform / physicalDPI / supportsAudio: the v2 TXT keys `os`, `dpi`, `audio`.
    ///   - port: a fixed TCP port, or `.any` for an ephemeral one.
    func start(displayName: String, panel: PanelDescription, pairingCode: String?, allowPeerToPeer: Bool,
               platform: String = "macos", supportsAudio: Bool = false, port: NWEndpoint.Port = .any) throws {
        stop()
        self.pairingCode = pairingCode

        let parameters = LinkParameters.make(allowPeerToPeer: allowPeerToPeer)
        let listener = try NWListener(using: parameters, on: port)

        // The TXT record lets the sending side show resolution in the picker before connecting.
        var txt = NWTXTRecord()
        txt["px"] = "\(panel.pixelWidth)x\(panel.pixelHeight)"
        txt["pt"] = "\(panel.pointWidth)x\(panel.pointHeight)"
        txt["hz"] = "\(panel.refreshHz)"
        txt["lock"] = (pairingCode?.isEmpty == false) ? "1" : "0"
        txt["v"] = "\(ClientHello.currentVersion)"
        txt["os"] = platform
        txt["dpi"] = "\(Int(panel.physicalDPI.rounded()))"
        txt["audio"] = supportsAudio ? "1" : "0"

        listener.service = NWListener.Service(name: displayName,
                                              type: LinkParameters.serviceType,
                                              txtRecord: txt)

        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            // The display listens, so it is the responder in the key exchange.
            let link = PeerLink(connection: connection, queue: self.queue,
                                role: .responder, pairingCode: self.pairingCode)
            self.onConnection?(link)
        }
        listener.stateUpdateHandler = { [weak self, weak listener] state in
            guard let self else { return }
            switch state {
            case .ready:
                guard let bound = listener?.port?.rawValue else { return }
                self.lock.lock(); self._port = bound; self.lock.unlock()
                Log.net("listening on TCP port \(bound)")
                self.onListening?(bound)
            case .failed(let error):
                self.onError?(error)
            default:
                break
            }
        }

        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
        lock.lock(); _port = nil; lock.unlock()
    }
}

// MARK: - Sending side: find devices offering themselves as displays

final class DisplayBrowser {
    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.browser")

    var onUpdate: (([DiscoveredDisplay]) -> Void)?

    func start(allowPeerToPeer: Bool) {
        stop()
        let parameters = NWParameters()
        parameters.includePeerToPeer = allowPeerToPeer

        let browser = NWBrowser(for: .bonjourWithTXTRecord(type: LinkParameters.serviceType, domain: nil),
                                using: parameters)
        browser.browseResultsChangedHandler = { [weak self] results, _ in
            let displays = results.compactMap { Self.parse($0) }
                                  .sorted { $0.name < $1.name }
            DispatchQueue.main.async { self?.onUpdate?(displays) }
        }
        browser.start(queue: queue)
        self.browser = browser
    }

    func stop() {
        browser?.cancel()
        browser = nil
    }

    private static func parse(_ result: NWBrowser.Result) -> DiscoveredDisplay? {
        guard case .service(let name, _, _, _) = result.endpoint else { return nil }
        guard case .bonjour(let txt) = result.metadata else {
            return DiscoveredDisplay(name: name, endpoint: result.endpoint,
                                     pixelWidth: 0, pixelHeight: 0, pointWidth: 0, pointHeight: 0,
                                     refreshHz: 60, requiresPairingCode: false)
        }
        return parse(name: name, endpoint: result.endpoint, txt: txt.dictionary)
    }

    /// TXT → display. Split out so the key handling can be tested without Bonjour.
    static func parse(name: String, endpoint: NWEndpoint, txt: [String: String]) -> DiscoveredDisplay {
        let px = Self.parsePair(txt["px"]) ?? (0, 0)
        let pt = Self.parsePair(txt["pt"]) ?? (0, 0)
        return DiscoveredDisplay(name: name,
                                 endpoint: endpoint,
                                 pixelWidth: px.0, pixelHeight: px.1,
                                 pointWidth: pt.0, pointHeight: pt.1,
                                 refreshHz: Int(txt["hz"] ?? "60") ?? 60,
                                 requiresPairingCode: txt["lock"] == "1",
                                 // A missing `os` means a v1 advertiser, which is always a Mac.
                                 platform: txt["os"].flatMap { $0.isEmpty ? nil : $0 } ?? "macos",
                                 physicalDPI: Double(txt["dpi"] ?? "") ?? 0,
                                 supportsAudio: txt["audio"] == "1",
                                 protocolVersion: Int(txt["v"] ?? "") ?? 1)
    }

    private static func parsePair(_ value: String?) -> (Int, Int)? {
        guard let parts = value?.split(separator: "x"), parts.count == 2,
              let w = Int(parts[0]), let h = Int(parts[1]) else { return nil }
        return (w, h)
    }
}

/// Physical description of the receiving device's panel.
struct PanelDescription: Equatable {
    var pixelWidth: Int
    var pixelHeight: Int
    var pointWidth: Int
    var pointHeight: Int
    var refreshHz: Int
    /// Physical pixels per inch (v2 `dpi` TXT key / `ClientHello.physicalDPI`), 0 when unknown.
    var physicalDPI: Double = 0
}
