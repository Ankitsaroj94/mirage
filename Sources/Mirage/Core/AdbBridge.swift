import Foundation
import Darwin

/// An Android device `adb` can see, with Mirage installed or not.
struct AdbDevice: Identifiable, Equatable {
    var id: String { serial }
    let serial: String
    /// The name the owner gave it ("Ankit's Tab S9 FE"), else the model.
    let name: String
    let model: String
    /// Connected with Wireless debugging rather than a cable.
    let isWireless: Bool
    let hasMirage: Bool

    var transportSummary: String { isWireless ? "via Wi-Fi adb" : "via USB" }
}

/// Where to connect to a tablet whose receiver adb just started.
struct AdbReceiverTarget {
    let host: String
    let port: UInt16
    /// True when no Wi-Fi path to the tablet was found and video goes through `adb forward`
    /// instead — it works, but adds latency and carries no sound (that is UDP).
    let viaForward: Bool
}

/// Lets a tablet that is plugged in (or paired for wireless debugging) show up in Mirage
/// without Bonjour, and does the setup a person would otherwise do by hand:
///
///   * *Send my screen*: start the tablet's "Be a display" with an intent, read its Wi-Fi
///     address, and connect straight to it. Video goes over Wi-Fi, not through adb.
///   * *Be a display*: start the tablet's shell-privileged host server (spec: README, "Mac as a
///     display for Android") pointed back at this Mac.
///
/// Every adb call runs on a private queue, never the main thread.
final class AdbBridge {

    /// The port the tablet's receiver listens on first, so the Mac never has to discover it.
    static let receiverPort: UInt16 = 47_800
    static let packageName = "in.ankitsaroj.mirage"

    var onUpdate: (([AdbDevice]) -> Void)?

    private let queue = DispatchQueue(label: "com.voxturr.mirage.adb", qos: .utility)
    private var timer: DispatchSourceTimer?
    /// Name and package lookups cost a few adb round trips, so they're done once per device.
    private var known: [String: AdbDevice] = [:]
    private var lastPublished: [AdbDevice] = []

    static let adbURL: URL? = locateAdb()
    static var isAvailable: Bool { adbURL != nil }

    // MARK: Watching

    func start() {
        guard Self.isAvailable, timer == nil else { return }
        let timer = DispatchSource.makeTimerSource(queue: queue)
        timer.schedule(deadline: .now(), repeating: 2)
        timer.setEventHandler { [weak self] in self?.poll() }
        timer.resume()
        self.timer = timer
    }

    func stop() {
        timer?.cancel()
        timer = nil
    }

    private func poll() {
        guard let out = try? Self.adb(["devices", "-l"], timeout: 4) else { return }
        var devices: [AdbDevice] = []
        for line in out.split(separator: "\n").dropFirst() {
            let fields = line.split(separator: " ", omittingEmptySubsequences: true)
            guard fields.count >= 2, fields[1] == "device" else { continue }   // skip unauthorized/offline
            let serial = String(fields[0])
            // Emulators can't reach the Mac's network directly and aren't what this is for.
            if serial.hasPrefix("emulator-") { continue }
            if let cached = known[serial] { devices.append(cached); continue }
            let model = fields.first { $0.hasPrefix("model:") }.map { String($0.dropFirst(6)).replacingOccurrences(of: "_", with: "-") } ?? serial
            let given = (try? Self.adb(["-s", serial, "shell", "settings", "get", "global", "device_name"], timeout: 4))?
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let hasMirage = ((try? Self.adb(["-s", serial, "shell", "pm", "path", Self.packageName], timeout: 4)) ?? "")
                .contains("package:")
            let device = AdbDevice(serial: serial,
                                   name: (given?.isEmpty == false && given != "null") ? given! : model,
                                   model: model,
                                   isWireless: serial.contains(":") || serial.contains("._adb-tls-"),
                                   hasMirage: hasMirage)
            known[serial] = device
            devices.append(device)
        }
        // Forget unplugged devices so a reinstall of Mirage is noticed on the next plug-in.
        known = known.filter { entry in devices.contains { $0.serial == entry.key } }
        guard devices != lastPublished else { return }
        lastPublished = devices
        DispatchQueue.main.async { self.onUpdate?(devices) }
    }

    // MARK: Send my screen → tablet

    /// Starts "Be a display" on the tablet and returns where to connect. Blocking; call off
    /// the main thread.
    static func prepareReceiver(on serial: String) throws -> AdbReceiverTarget {
        _ = try adb(["-s", serial, "shell", "am", "start", "-n", "\(packageName)/.ui.MainActivity",
                     "-a", "\(packageName).BE_A_DISPLAY"], timeout: 8)

        // The service binds within a second or so; wait for the socket rather than guessing.
        let hexPort = String(format: ":%04X ", receiverPort)
        var listening = false
        for _ in 0..<30 {
            // State 0A is LISTEN. Filtered on the tablet: the full tables can be large.
            let tables = (try? adb(["-s", serial, "shell", "grep", "-h", "-i", "'\(hexPort)'", "/proc/net/tcp", "/proc/net/tcp6"], timeout: 4)) ?? ""
            if tables.split(separator: "\n").contains(where: { $0.contains(" 0A ") }) {
                listening = true
                break
            }
            usleep(200_000)
        }
        guard listening else {
            throw MirageError("Mirage on the tablet didn't start listening. Open it once on the tablet and allow its notifications, then try again.")
        }

        if let ip = wifiAddress(of: serial), localAddress(onSameSubnetAs: ip) != nil {
            return AdbReceiverTarget(host: ip, port: receiverPort, viaForward: false)
        }
        // No shared Wi-Fi: tunnel the TCP link through adb.
        let out = try adb(["-s", serial, "forward", "tcp:0", "tcp:\(receiverPort)"], timeout: 6)
        guard let local = UInt16(out.trimmingCharacters(in: .whitespacesAndNewlines)) else {
            throw MirageError("adb could not forward a port to the tablet.")
        }
        return AdbReceiverTarget(host: "127.0.0.1", port: local, viaForward: true)
    }

    // MARK: Be a display ← tablet

    /// Runs the tablet's host server, pointed at this Mac's receiver. The process lives as
    /// long as the session: closing its stdin (or `terminate()`) removes the Android display
    /// and restores the tablet's settings. `onLine` gets each status line (CONNECTING,
    /// CONNECTED, OK …, STATS …, BYE, ERR …).
    static func startAndroidDesktop(on serial: String, macPort: UInt16, pairingCode: String?,
                                    onLine: @escaping (String) -> Void,
                                    onExit: @escaping (Int32) -> Void) throws -> Process {
        guard let adbURL else { throw MirageError("adb was not found. Install Android platform-tools.") }
        let apk = try adb(["-s", serial, "shell", "pm", "path", packageName], timeout: 6)
            .split(separator: "\n").first { $0.hasPrefix("package:") }
            .map { String($0.dropFirst("package:".count)).trimmingCharacters(in: .whitespacesAndNewlines) }
        guard let apk else { throw MirageError("Mirage isn't installed on the tablet.") }

        // Prefer the Mac's address on the tablet's Wi-Fi: video then never touches adb.
        var host = "127.0.0.1"
        if let ip = wifiAddress(of: serial), let mine = localAddress(onSameSubnetAs: ip) {
            host = mine
        } else {
            _ = try adb(["-s", serial, "reverse", "tcp:\(macPort)", "tcp:\(macPort)"], timeout: 6)
        }

        var remote = "CLASSPATH=\(apk) app_process / \(packageName).host.privileged.ServerMain --host \(host) --port \(macPort)"
        if let code = pairingCode, !code.isEmpty { remote += " --code '\(code.replacingOccurrences(of: "'", with: ""))'" }

        let process = Process()
        process.executableURL = adbURL
        process.arguments = ["-s", serial, "shell", remote]
        let stdin = Pipe(), stdout = Pipe()
        process.standardInput = stdin       // kept open: EOF is the server's signal to tear down
        process.standardOutput = stdout
        process.standardError = stdout
        var buffer = Data()
        stdout.fileHandleForReading.readabilityHandler = { handle in
            let chunk = handle.availableData
            guard !chunk.isEmpty else { handle.readabilityHandler = nil; return }
            buffer.append(chunk)
            while let newline = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                let line = String(decoding: buffer[..<newline], as: UTF8.self).trimmingCharacters(in: .whitespaces)
                buffer.removeSubrange(...newline)
                if !line.isEmpty { onLine(line) }
            }
        }
        process.terminationHandler = { onExit($0.terminationStatus) }
        try process.run()
        Log.net("adb: android desktop server on \(serial) → \(host):\(macPort)")
        return process
    }

    // MARK: adb plumbing

    private static func locateAdb() -> URL? {
        var candidates: [String] = []
        let env = ProcessInfo.processInfo.environment
        for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
            if let sdk = env[key] { candidates.append("\(sdk)/platform-tools/adb") }
        }
        candidates += [
            NSHomeDirectory() + "/Library/Android/sdk/platform-tools/adb",
            "/opt/homebrew/bin/adb",
            "/usr/local/bin/adb",
        ]
        // An app launched from Finder doesn't inherit the shell's PATH, so the fixed places above
        // come first; PATH is the last resort for `swift run` and terminals.
        candidates += (env["PATH"] ?? "").split(separator: ":").map { "\($0)/adb" }
        return candidates.first { FileManager.default.isExecutableFile(atPath: $0) }.map(URL.init(fileURLWithPath:))
    }

    @discardableResult
    static func adb(_ arguments: [String], timeout: TimeInterval) throws -> String {
        guard let adbURL else { throw MirageError("adb was not found.") }
        let process = Process()
        process.executableURL = adbURL
        process.arguments = arguments
        let out = Pipe()
        process.standardOutput = out
        process.standardError = out
        try process.run()
        // Read while it runs — waiting for exit first deadlocks once the output outgrows the
        // pipe — and let a watchdog kill a hung adb.
        let timedOut = Atomic()
        DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
            if process.isRunning { timedOut.set(); process.terminate() }
        }
        let data = out.fileHandleForReading.readDataToEndOfFile()
        process.waitUntilExit()
        if timedOut.isSet { throw MirageError("adb \(arguments.joined(separator: " ")) timed out.") }
        return String(decoding: data, as: UTF8.self)
    }

    /// The tablet's IPv4 address on Wi-Fi, if it has one.
    static func wifiAddress(of serial: String) -> String? {
        guard let out = try? adb(["-s", serial, "shell", "ip", "-4", "-o", "addr", "show", "wlan0"], timeout: 4) else { return nil }
        // "34: wlan0    inet 192.168.1.17/24 brd …"
        guard let range = out.range(of: #"inet (\d+\.\d+\.\d+\.\d+)"#, options: .regularExpression) else { return nil }
        return String(out[range].dropFirst("inet ".count))
    }

    /// This Mac's IPv4 address on the same subnet as `peer`, if any interface has one.
    static func localAddress(onSameSubnetAs peer: String) -> String? {
        var peerAddr = in_addr()
        guard inet_pton(AF_INET, peer, &peerAddr) == 1 else { return nil }
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        for entry in sequence(first: first, next: { $0.pointee.ifa_next }) {
            guard let addr = entry.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  let mask = entry.pointee.ifa_netmask else { continue }
            let mine = addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            let netmask = mask.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr }
            guard mine & netmask == peerAddr.s_addr & netmask, mine != peerAddr.s_addr else { continue }
            var text = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
            var copy = in_addr(s_addr: mine)
            inet_ntop(AF_INET, &copy, &text, socklen_t(INET_ADDRSTRLEN))
            return String(cString: text)
        }
        return nil
    }
}

/// A flag set from one thread and read from another.
private final class Atomic {
    private let lock = NSLock()
    private var value = false
    func set() { lock.lock(); value = true; lock.unlock() }
    var isSet: Bool { lock.lock(); defer { lock.unlock() }; return value }
}
