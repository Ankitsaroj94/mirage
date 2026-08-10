import Foundation
import Network

/// A receiving Mac seen on the network, as advertised over Bonjour.
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

    var resolutionSummary: String { "\(pixelWidth)×\(pixelHeight) @ \(refreshHz)Hz" }
    var scaledSummary: String { "looks like \(pointWidth)×\(pointHeight)" }
}

// MARK: - Receiver side: advertise this Mac as an available display

final class DisplayAdvertiser {
    private var listener: NWListener?
    private var pairingCode: String?
    private let queue = DispatchQueue(label: "com.voxturr.mirage.advertiser")

    var onConnection: ((PeerLink) -> Void)?
    var onError: ((Error) -> Void)?

    func start(displayName: String, panel: PanelDescription, pairingCode: String?, allowPeerToPeer: Bool) throws {
        stop()
        self.pairingCode = pairingCode

        let parameters = LinkParameters.make(allowPeerToPeer: allowPeerToPeer)
        let listener = try NWListener(using: parameters)

        // The TXT record lets the sending Mac show resolution in the picker before connecting.
        var txt = NWTXTRecord()
        txt["px"] = "\(panel.pixelWidth)x\(panel.pixelHeight)"
        txt["pt"] = "\(panel.pointWidth)x\(panel.pointHeight)"
        txt["hz"] = "\(panel.refreshHz)"
        txt["lock"] = (pairingCode?.isEmpty == false) ? "1" : "0"
        txt["v"] = "\(ClientHello.currentVersion)"

        listener.service = NWListener.Service(name: displayName,
                                              type: LinkParameters.serviceType,
                                              txtRecord: txt)

        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            // The receiving Mac listens, so it is the responder in the key exchange.
            let link = PeerLink(connection: connection, queue: self.queue,
                                role: .responder, pairingCode: self.pairingCode)
            self.onConnection?(link)
        }
        listener.stateUpdateHandler = { [weak self] state in
            if case .failed(let error) = state { self?.onError?(error) }
        }

        listener.start(queue: queue)
        self.listener = listener
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }
}

// MARK: - Sending side: find Macs offering themselves as displays

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
        let px = Self.parsePair(txt["px"]) ?? (0, 0)
        let pt = Self.parsePair(txt["pt"]) ?? (0, 0)
        return DiscoveredDisplay(name: name,
                                 endpoint: result.endpoint,
                                 pixelWidth: px.0, pixelHeight: px.1,
                                 pointWidth: pt.0, pointHeight: pt.1,
                                 refreshHz: Int(txt["hz"] ?? "60") ?? 60,
                                 requiresPairingCode: txt["lock"] == "1")
    }

    private static func parsePair(_ value: String?) -> (Int, Int)? {
        guard let parts = value?.split(separator: "x"), parts.count == 2,
              let w = Int(parts[0]), let h = Int(parts[1]) else { return nil }
        return (w, h)
    }
}

/// Physical description of the receiving Mac's built-in panel.
struct PanelDescription: Equatable {
    var pixelWidth: Int
    var pixelHeight: Int
    var pointWidth: Int
    var pointHeight: Int
    var refreshHz: Int
}
