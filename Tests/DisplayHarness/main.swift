// Drives MirageDisplayHelper the way the app does and checks that each run produces a
// genuine Retina virtual display, attached to the arrangement and removed on exit.
//
// This is the regression test for the constraint that shaped the architecture: CoreGraphics
// only configures one CGVirtualDisplay per process, so every display must come from a fresh
// helper. Running three geometries back to back proves the helper approach holds.
import Foundation
import CoreGraphics

// Re-invoked as a child to inspect a display from a fresh process. A long-lived process's
// CGDisplayCopyDisplayMode cache goes stale after the first virtual display appears, so
// mode inspection has to happen somewhere fresh — exactly what the helper itself is for.
if CommandLine.arguments.count == 3, CommandLine.arguments[1] == "--inspect" {
    let id = CGDirectDisplayID(CommandLine.arguments[2])!
    if let mode = CGDisplayCopyDisplayMode(id) {
        print("\(mode.width) \(mode.height) \(mode.pixelWidth) \(mode.pixelHeight)")
    } else {
        print("0 0 0 0")
    }
    exit(0)
}

let helperPath = ProcessInfo.processInfo.environment["MIRAGE_HELPER"] ?? ".build/release/MirageDisplayHelper"
guard FileManager.default.isExecutableFile(atPath: helperPath) else {
    print("✗ helper not found at \(helperPath) — run `swift build -c release` first"); exit(1)
}

func activeDisplayCount() -> Int {
    var count: UInt32 = 0
    CGGetActiveDisplayList(0, nil, &count)
    return Int(count)
}

struct Helper {
    let process: Process
    let stdin: Pipe
    let displayID: CGDirectDisplayID
    let isHiDPI: Bool
}

func launch(px: Int, py: Int, ptx: Int, pty: Int) -> Helper? {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: helperPath)
    process.arguments = ["\(px)", "\(py)", "\(ptx)", "\(pty)", "60"]
    let out = Pipe(), input = Pipe()
    process.standardOutput = out
    process.standardInput = input
    try? process.run()

    var buffer = Data()
    while !buffer.contains(UInt8(ascii: "\n")) {
        let chunk = out.fileHandleForReading.availableData
        if chunk.isEmpty { break }
        buffer.append(chunk)
    }
    let line = String(data: buffer, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
    let fields = line.split(separator: " ")
    guard fields.first == "OK", fields.count >= 3, let id = CGDirectDisplayID(fields[1]) else {
        print("     helper said: \(line.isEmpty ? "<nothing>" : line)")
        process.terminate()
        return nil
    }
    return Helper(process: process, stdin: input, displayID: id, isHiDPI: fields[2] == "1")
}

// 15" and 13" 2017 MacBook Pro panels, plus a scaled desktop.
let cases: [(String, Int, Int, Int, Int)] = [
    ("15\" 2017 MBP native",  2880, 1800, 1440, 900),
    ("13\" 2017 MBP native",  2560, 1600, 1280, 800),
    ("15\" scaled 1680x1050", 3360, 2100, 1680, 1050),
]

/// Asks a fresh copy of this binary what mode the display is actually in.
func inspect(_ id: CGDirectDisplayID) -> ((Int, Int), (Int, Int)) {
    let process = Process()
    process.executableURL = URL(fileURLWithPath: CommandLine.arguments[0])
    process.arguments = ["--inspect", "\(id)"]
    let out = Pipe()
    process.standardOutput = out
    try? process.run()
    let data = out.fileHandleForReading.readDataToEndOfFile()
    process.waitUntilExit()
    let n = (String(data: data, encoding: .utf8) ?? "").split(separator: " ").compactMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
    guard n.count == 4 else { return ((0, 0), (0, 0)) }
    return ((n[0], n[1]), (n[2], n[3]))
}

var allOK = true
for (label, px, py, ptx, pty) in cases {
    let before = activeDisplayCount()
    guard let helper = launch(px: px, py: py, ptx: ptx, pty: pty) else {
        print("  \(label): ✗ helper did not create a display"); allOK = false; continue
    }

    let (gotPoints, gotPixels) = inspect(helper.displayID)
    let bounds = CGDisplayBounds(helper.displayID)
    let after = activeDisplayCount()

    print("  \(label): id=\(helper.displayID) mode=\(gotPoints.0)x\(gotPoints.1)pt / \(gotPixels.0)x\(gotPixels.1)px "
          + "hiDPI=\(helper.isHiDPI) bounds=\(Int(bounds.width))x\(Int(bounds.height)) displays \(before)->\(after)")

    let ok = helper.isHiDPI
        && gotPixels == (px, py)
        && gotPoints == (ptx, pty)
        && Int(bounds.width) == ptx && Int(bounds.height) == pty
        && after == before + 1
    if ok {
        print("    ✓ Retina 2x, exact geometry, attached to the arrangement")
    } else {
        print("    ✗ expected a \(ptx)x\(pty)pt / \(px)x\(py)px Retina mode on a newly attached display")
        allOK = false
    }

    // Closing stdin is how the app releases the display.
    try? helper.stdin.fileHandleForWriting.close()
    helper.process.waitUntilExit()
    usleep(800_000)
    let cleaned = activeDisplayCount()
    if cleaned == before {
        print("    ✓ removed when the helper exited")
    } else {
        print("    ✗ display survived helper exit (\(cleaned) vs \(before))"); allOK = false
    }
}

print(allOK ? "\nVIRTUAL DISPLAY TESTS PASSED" : "\nVIRTUAL DISPLAY TESTS FAILED")
exit(allOK ? 0 : 1)
