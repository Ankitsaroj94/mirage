import Foundation
import Network

// TEMP: removed at integration when agent 1's real members land.
// Fakes exactly: DiscoveredDisplay.platform / .physicalDPI / .supportsAudio / .manual(host:port:)
// and ClientSession.listeningPort. Delete this file once Core/Client define them for real.

extension DiscoveredDisplay {
    var platform: String { "macos" }
    var physicalDPI: Double { 0 }
    var supportsAudio: Bool { false }

    static func manual(host: String, port: UInt16) -> DiscoveredDisplay {
        DiscoveredDisplay(name: "\(host):\(port)",
                          endpoint: .hostPort(host: NWEndpoint.Host(host),
                                              port: NWEndpoint.Port(rawValue: port) ?? .any),
                          pixelWidth: 0, pixelHeight: 0, pointWidth: 0, pointHeight: 0,
                          refreshHz: 60, requiresPairingCode: false)
    }
}

extension ClientSession {
    var listeningPort: UInt16? { nil }
}
