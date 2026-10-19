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
        if let index = arguments.firstIndex(of: "--show-android"), index + 1 < arguments.count {
            let target = arguments[index + 1]
            showAndroidDesktop(serial: target.hasPrefix("adb:") ? String(target.dropFirst(4)) : nil, attempt: 0)
            return
        }
        guard let index = arguments.firstIndex(of: "--connect"), index + 1 < arguments.count else { return }
        let target = arguments[index + 1]
        if target == "adb" || target.hasPrefix("adb:") {
            connectToAdbDevice(serial: target.hasPrefix("adb:") ? String(target.dropFirst(4)) : nil, attempt: 0)
            return
        }
        guard let colon = target.lastIndex(of: ":"),
              let port = UInt16(target[target.index(after: colon)...]),
              let display = DiscoveredDisplay.manual(host: String(target[..<colon]), port: port) else {
            Log.app("--connect expects host:port, got \(target)")
            return
        }
        Log.app("connecting to \(target) from the command line")
        model.connect(to: display)
    }

    /// `--show-android adb` (or `adb:<serial>`): *Be a display* → "Show its desktop here".
    private func showAndroidDesktop(serial: String?, attempt: Int) {
        guard let match = model.adbDevices.first(where: { $0.hasMirage && (serial == nil || $0.serial == serial) }) else {
            if attempt < 50 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { self.showAndroidDesktop(serial: serial, attempt: attempt + 1) }
            } else {
                Log.app("--show-android: no adb device with Mirage installed")
            }
            return
        }
        model.role = .receive
        model.showAndroidDesktop(from: match)
    }

    /// `--connect adb` (first tablet with Mirage) or `--connect adb:<serial>`: the same as
    /// pressing Connect on a tablet listed through adb. adb's device list fills in a moment
    /// after launch, so this waits for it.
    private func connectToAdbDevice(serial: String?, attempt: Int) {
        let match = model.adbDevices.first { $0.hasMirage && (serial == nil || $0.serial == serial) }
        guard let match else {
            if attempt < 50 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.2) { self.connectToAdbDevice(serial: serial, attempt: attempt + 1) }
            } else {
                Log.app("--connect adb: no adb device with Mirage installed")
            }
            return
        }
        Log.app("connecting to \(match.name) over adb from the command line")
        model.connectViaAdb(DiscoveredDisplay(adb: match))
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Explicit teardown so the virtual display never outlives the app and strand the
        // desktop arrangement.
        model.host.disconnect()
        model.client.stop()
        model.stopAllAndroidDesktops()
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
