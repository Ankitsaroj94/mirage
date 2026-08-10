import Foundation
import CoreGraphics
import MirageBridge

//
//  MirageDisplayHelper
//
//  Owns exactly one virtual display and nothing else.
//
//  CoreGraphics only lets a process configure one CGVirtualDisplay: the first one created
//  gets a working mode list, and every one after it attaches but reports no modes at all —
//  regardless of geometry, identity, or whether the earlier one was torn down. Verified on
//  macOS 26.5.
//
//  Putting the display in a short-lived helper turns that limit into a non-issue. Each
//  connection gets a fresh process, and quitting the process removes the display from the
//  arrangement cleanly. Display IDs are system-wide, so the main app captures it normally.
//
//  Protocol: argv carries the geometry; one line goes to stdout —
//      OK <displayID> <hiDPI 0|1>
//      ERR <message>
//  The helper then runs until stdin reaches EOF (the parent exited) or it is signalled.
//

setbuf(stdout, nil)

let arguments = CommandLine.arguments
guard arguments.count == 6,
      let pixelWidth = UInt32(arguments[1]), let pixelHeight = UInt32(arguments[2]),
      let pointWidth = UInt32(arguments[3]), let pointHeight = UInt32(arguments[4]),
      let refreshHz = Double(arguments[5]) else {
    print("ERR usage: MirageDisplayHelper <pixelW> <pixelH> <pointW> <pointH> <refreshHz>")
    exit(2)
}

let name = ProcessInfo.processInfo.environment["MIRAGE_DISPLAY_NAME"] ?? "Mirage Display"

let display: MRGVirtualDisplay
do {
    display = try MRGVirtualDisplay.make(name: name,
                                         pixelWidth: pixelWidth, pixelHeight: pixelHeight,
                                         pointWidth: pointWidth, pointHeight: pointHeight,
                                         refreshRate: refreshHz)
} catch {
    print("ERR \(error.localizedDescription)")
    exit(1)
}

print("OK \(display.displayID) \(display.isHiDPI ? 1 : 0)")

// Exit when the parent goes away. Reading stdin gives us that for free: the pipe closes
// when the app exits or crashes, so the display never outlives its owner.
let stdinSource = DispatchSource.makeReadSource(fileDescriptor: STDIN_FILENO, queue: .main)
stdinSource.setEventHandler {
    var byte: UInt8 = 0
    if read(STDIN_FILENO, &byte, 1) <= 0 {
        display.invalidate()
        exit(0)
    }
}
stdinSource.setCancelHandler { display.invalidate(); exit(0) }
stdinSource.resume()

// Held for the process lifetime; a released DispatchSourceSignal stops firing.
var signalSources: [DispatchSourceSignal] = []
for signalNumber in [SIGTERM, SIGINT, SIGHUP] {
    signal(signalNumber, SIG_IGN)
    let source = DispatchSource.makeSignalSource(signal: signalNumber, queue: .main)
    source.setEventHandler { display.invalidate(); exit(0) }
    source.resume()
    signalSources.append(source)
}

// The display's own callbacks are delivered on the main queue, so this run loop is
// load-bearing, not just a way to stay alive.
RunLoop.main.run()
