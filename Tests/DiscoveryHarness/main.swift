// Runs the real DisplayAdvertiser and DisplayBrowser in one process and reports what
// each one does, with the failure states that the app currently swallows.
import Foundation
import Network

let role = CommandLine.arguments.dropFirst().first ?? "both"
let panel = PanelDescription(pixelWidth: 2880, pixelHeight: 1800, pointWidth: 1440, pointHeight: 900, refreshHz: 60)

if role == "advertise" || role == "both" {
    let advertiser = DisplayAdvertiser()
    advertiser.onConnection = { _ in print("  [advertiser] incoming connection") }
    advertiser.onError = { print("  [advertiser] ERROR: \($0)") }
    do {
        try advertiser.start(displayName: "DiscoveryTest", panel: panel, pairingCode: nil, allowPeerToPeer: false)
        print("  [advertiser] start() returned without throwing")
    } catch {
        print("  [advertiser] THREW: \(error)")
    }
}

if role == "browse" || role == "both" {
    let browser = DisplayBrowser()
    browser.onUpdate = { displays in
        print("  [browser] \(displays.count) result(s): \(displays.map { "\($0.name) \($0.pixelWidth)x\($0.pixelHeight)" })")
    }
    browser.start(allowPeerToPeer: false)
    print("  [browser] started")
}

// Independent confirmation via dns-sd, which does not care about our code at all.
DispatchQueue.global().asyncAfter(deadline: .now() + 3) {
    let p = Process()
    p.executableURL = URL(fileURLWithPath: "/usr/bin/dns-sd")
    p.arguments = ["-B", "_mirage._tcp", "local."]
    let out = Pipe(); p.standardOutput = out
    try? p.run()
    DispatchQueue.global().asyncAfter(deadline: .now() + 4) { p.terminate() }
    let d = out.fileHandleForReading.readDataToEndOfFile()
    print("  [dns-sd] sees:\n" + (String(data: d, encoding: .utf8) ?? "").split(separator: "\n").map { "      \($0)" }.joined(separator: "\n"))
}

RunLoop.main.run(until: Date().addingTimeInterval(10))
print("done")
