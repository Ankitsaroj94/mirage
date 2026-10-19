import AppKit
import SwiftUI

@MainActor
final class AppDelegate: NSObject, NSApplicationDelegate {
    private var window: NSWindow?
    private let model = AppModel()

    func applicationDidFinishLaunching(_ notification: Notification) {
        let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 460, height: 620),
                              styleMask: [.titled, .closable, .miniaturizable],
                              backing: .buffered,
                              defer: false)
        window.title = "Mirage"
        window.contentView = NSHostingView(rootView: ControlPanel(model: model))
        window.center()
        window.isReleasedWhenClosed = false
        window.makeKeyAndOrderFront(nil)
        self.window = window

        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        Log.app("Mirage launched")
        connectFromCommandLine()
    }

    /// `Mirage --connect 192.168.1.17:42815` sends the screen straight to that address, the
    /// same as "Connect by address…". Handy for scripts and for testing against a tablet.
    private func connectFromCommandLine() {
        let arguments = CommandLine.arguments
        guard let index = arguments.firstIndex(of: "--connect"), index + 1 < arguments.count else { return }
        let target = arguments[index + 1]
        guard let colon = target.lastIndex(of: ":"),
              let port = UInt16(target[target.index(after: colon)...]),
              let display = DiscoveredDisplay.manual(host: String(target[..<colon]), port: port) else {
            Log.app("--connect expects host:port, got \(target)")
            return
        }
        Log.app("connecting to \(target) from the command line")
        model.connect(to: display)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Explicit teardown so the virtual display never outlives the app and strand the
        // desktop arrangement.
        model.host.disconnect()
        model.client.stop()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { window?.makeKeyAndOrderFront(nil) }
        return true
    }
}

// Top-level code is nonisolated, but everything below genuinely runs on the main thread
// before the run loop starts, so asserting that is accurate rather than a workaround.
MainActor.assumeIsolated {
    let application = NSApplication.shared
    let delegate = AppDelegate()
    application.delegate = delegate
    application.setActivationPolicy(.regular)
    application.run()
}
