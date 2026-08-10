import Foundation
import AppKit

/// Captures input inside the fullscreen receiver window and ships it to the host.
///
/// Local monitors are deliberate: they only see events already routed to our own key
/// window, so this needs no Accessibility permission on the receiving Mac. Nothing is
/// observed outside the fullscreen view.
final class InputForwarder {

    private weak var view: NSView?
    private let send: (InputEvent) -> Void
    private var monitor: Any?
    private var lastMoveSent = MonotonicClock.millis()

    /// Coalesce pointer moves to roughly 240 Hz. Anything finer is below the frame
    /// interval and just burns bandwidth.
    private let moveIntervalMs: Double = 1000.0 / 240.0

    init(view: NSView?, send: @escaping (InputEvent) -> Void) {
        self.view = view
        self.send = send
    }

    func start() {
        stop()
        let mask: NSEvent.EventTypeMask = [
            .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged,
            .leftMouseDown, .leftMouseUp, .rightMouseDown, .rightMouseUp,
            .otherMouseDown, .otherMouseUp, .scrollWheel,
            .keyDown, .keyUp, .flagsChanged,
        ]
        monitor = NSEvent.addLocalMonitorForEvents(matching: mask) { [weak self] event in
            guard let self else { return event }
            // Escape stays local so there is always a way out of fullscreen.
            if event.type == .keyDown && event.keyCode == 53 { return event }
            self.forward(event)
            return nil   // swallow: the event belongs to the other Mac now
        }
    }

    func stop() {
        if let monitor { NSEvent.removeMonitor(monitor) }
        monitor = nil
    }

    private func forward(_ event: NSEvent) {
        guard let view, let window = view.window else { return }

        func normalised() -> (Float, Float) {
            let inWindow = event.locationInWindow
            let inView = view.convert(inWindow, from: nil)
            let bounds = view.bounds
            guard bounds.width > 0, bounds.height > 0 else { return (0, 0) }
            let x = Float(min(max(inView.x / bounds.width, 0), 1))
            // AppKit is bottom-left origin, CoreGraphics is top-left. Flip once, here.
            let y = Float(min(max(1 - (inView.y / bounds.height), 0), 1))
            return (x, y)
        }

        var out: InputEvent
        switch event.type {
        case .mouseMoved, .leftMouseDragged, .rightMouseDragged, .otherMouseDragged:
            let now = MonotonicClock.millis()
            guard now - lastMoveSent >= moveIntervalMs else { return }
            lastMoveSent = now
            out = InputEvent(kind: .mouseMove)
            (out.x, out.y) = normalised()
            out.deltaX = Float(event.deltaX)
            out.deltaY = Float(event.deltaY)

        case .leftMouseDown, .rightMouseDown, .otherMouseDown:
            out = InputEvent(kind: .mouseDown)
            (out.x, out.y) = normalised()
            out.button = Self.button(for: event)
            out.clickCount = UInt8(min(event.clickCount, 3))

        case .leftMouseUp, .rightMouseUp, .otherMouseUp:
            out = InputEvent(kind: .mouseUp)
            (out.x, out.y) = normalised()
            out.button = Self.button(for: event)
            out.clickCount = UInt8(min(event.clickCount, 3))

        case .scrollWheel:
            out = InputEvent(kind: .scroll)
            (out.x, out.y) = normalised()
            out.isPreciseScroll = event.hasPreciseScrollingDeltas
            out.deltaX = Float(event.hasPreciseScrollingDeltas ? event.scrollingDeltaX : event.deltaX)
            out.deltaY = Float(event.hasPreciseScrollingDeltas ? event.scrollingDeltaY : event.deltaY)

        case .keyDown:
            guard !event.isARepeat || true else { return }
            out = InputEvent(kind: .keyDown)
            out.keyCode = event.keyCode

        case .keyUp:
            out = InputEvent(kind: .keyUp)
            out.keyCode = event.keyCode

        case .flagsChanged:
            out = InputEvent(kind: .flagsChanged)
            out.keyCode = event.keyCode

        default:
            return
        }

        out.modifiers = UInt32(truncatingIfNeeded: event.modifierFlags.cgEventFlags.rawValue)
        _ = window
        send(out)
    }

    private static func button(for event: NSEvent) -> UInt8 {
        switch event.buttonNumber {
        case 0: return MouseButton.left.rawValue
        case 1: return MouseButton.right.rawValue
        default: return MouseButton.middle.rawValue
        }
    }
}

private extension NSEvent.ModifierFlags {
    /// AppKit and CoreGraphics use the same bit positions for the modifiers we care about,
    /// but the masks are declared separately. Translate explicitly rather than casting.
    var cgEventFlags: CGEventFlags {
        var flags: CGEventFlags = []
        if contains(.capsLock) { flags.insert(.maskAlphaShift) }
        if contains(.shift)    { flags.insert(.maskShift) }
        if contains(.control)  { flags.insert(.maskControl) }
        if contains(.option)   { flags.insert(.maskAlternate) }
        if contains(.command)  { flags.insert(.maskCommand) }
        if contains(.numericPad) { flags.insert(.maskNumericPad) }
        if contains(.help)     { flags.insert(.maskHelp) }
        if contains(.function) { flags.insert(.maskSecondaryFn) }
        return flags
    }
}
