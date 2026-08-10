import Foundation
import CoreGraphics

/// Runs `MirageDisplayHelper` and exposes the display it created.
///
/// The display lives in a child process on purpose — CoreGraphics only ever configures one
/// `CGVirtualDisplay` per process, so doing it in-app would work for the first connection
/// and silently produce a non-Retina display for every one after it. A fresh helper per
/// connection sidesteps that, and killing the helper removes the display from the display
/// arrangement immediately.
final class VirtualDisplayHost {

    let displayID: CGDirectDisplayID
    let isHiDPI: Bool
    let pixelWidth: Int
    let pixelHeight: Int
    let pointWidth: Int
    let pointHeight: Int

    private let process: Process
    private let stdinPipe: Pipe
    private var invalidated = false

    var bounds: CGRect { CGDisplayBounds(displayID) }

    /// Fires if the helper dies unexpectedly, which means the display is gone.
    var onUnexpectedExit: (() -> Void)?

    private init(displayID: CGDirectDisplayID, isHiDPI: Bool,
                 pixelWidth: Int, pixelHeight: Int, pointWidth: Int, pointHeight: Int,
                 process: Process, stdinPipe: Pipe) {
        self.displayID = displayID
        self.isHiDPI = isHiDPI
        self.pixelWidth = pixelWidth
        self.pixelHeight = pixelHeight
        self.pointWidth = pointWidth
        self.pointHeight = pointHeight
        self.process = process
        self.stdinPipe = stdinPipe
    }

    static func start(name: String,
                      pixelWidth: Int, pixelHeight: Int,
                      pointWidth: Int, pointHeight: Int,
                      refreshHz: Int) throws -> VirtualDisplayHost {

        guard let helperURL = helperExecutableURL() else {
            throw MirageError("MirageDisplayHelper is missing from the app bundle. Rebuild with `make`.")
        }

        let process = Process()
        process.executableURL = helperURL
        process.arguments = ["\(pixelWidth)", "\(pixelHeight)", "\(pointWidth)", "\(pointHeight)", "\(refreshHz)"]
        var environment = ProcessInfo.processInfo.environment
        environment["MIRAGE_DISPLAY_NAME"] = name
        process.environment = environment

        let stdoutPipe = Pipe()
        let stdinPipe = Pipe()
        process.standardOutput = stdoutPipe
        process.standardInput = stdinPipe
        process.standardError = FileHandle.nullDevice

        do {
            try process.run()
        } catch {
            throw MirageError("Could not launch the display helper: \(error.localizedDescription)")
        }

        // The helper prints one line as soon as the display is up, or an error and exits.
        guard let line = readLine(from: stdoutPipe.fileHandleForReading, timeout: 10) else {
            process.terminate()
            throw MirageError("The display helper did not respond. macOS may have changed the private virtual-display API.")
        }

        let fields = line.split(separator: " ")
        guard fields.first == "OK", fields.count >= 3,
              let displayID = CGDirectDisplayID(fields[1]) else {
            process.terminate()
            let reason = line.hasPrefix("ERR ") ? String(line.dropFirst(4)) : line
            throw MirageError(reason.isEmpty ? "The display helper failed to create a display." : reason)
        }

        let host = VirtualDisplayHost(displayID: displayID,
                                      isHiDPI: fields[2] == "1",
                                      pixelWidth: pixelWidth, pixelHeight: pixelHeight,
                                      pointWidth: pointWidth, pointHeight: pointHeight,
                                      process: process, stdinPipe: stdinPipe)

        process.terminationHandler = { [weak host] _ in
            guard let host, !host.invalidated else { return }
            Log.video("display helper exited unexpectedly")
            host.onUnexpectedExit?()
        }

        Log.video("virtual display \(displayID) up: \(pointWidth)x\(pointHeight)pt / \(pixelWidth)x\(pixelHeight)px, hiDPI=\(host.isHiDPI)")
        return host
    }

    /// Removes the display. Safe to call more than once.
    func invalidate() {
        guard !invalidated else { return }
        invalidated = true

        // Closing stdin is the graceful path: the helper sees EOF and tears the display
        // down itself. Terminate is the backstop if it is wedged.
        try? stdinPipe.fileHandleForWriting.close()
        if process.isRunning {
            process.terminate()
            let deadline = Date().addingTimeInterval(2)
            while process.isRunning && Date() < deadline { usleep(20_000) }
            if process.isRunning { kill(process.processIdentifier, SIGKILL) }
        }
    }

    deinit { invalidate() }

    // MARK: -

    private static func helperExecutableURL() -> URL? {
        let name = "MirageDisplayHelper"
        // Inside the bundle when installed; alongside the binary during `swift run`.
        let candidates = [
            Bundle.main.bundleURL.appendingPathComponent("Contents/MacOS/\(name)"),
            Bundle.main.executableURL?.deletingLastPathComponent().appendingPathComponent(name),
            URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent().appendingPathComponent(name),
        ].compactMap { $0 }

        return candidates.first { FileManager.default.isExecutableFile(atPath: $0.path) }
    }

    private static func readLine(from handle: FileHandle, timeout: TimeInterval) -> String? {
        let deadline = Date().addingTimeInterval(timeout)
        var buffer = Data()
        while Date() < deadline {
            let chunk = handle.availableData      // blocks until data or EOF
            if chunk.isEmpty { break }            // EOF: helper exited
            buffer.append(chunk)
            if let newlineIndex = buffer.firstIndex(of: UInt8(ascii: "\n")) {
                return String(data: buffer[..<newlineIndex], encoding: .utf8)
            }
        }
        return buffer.isEmpty ? nil : String(data: buffer, encoding: .utf8)
    }
}
