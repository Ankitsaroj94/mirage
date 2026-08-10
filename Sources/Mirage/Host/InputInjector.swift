import Foundation
import CoreGraphics
import AppKit

/// Replays input from the receiving Mac onto the virtual display.
///
/// This is optional. Because the virtual display is a genuine entry in the display
/// arrangement, the host's own trackpad already moves the pointer onto it. Forwarding
/// exists so you can also drive it from the other Mac's keyboard and trackpad.
///
/// Requires Accessibility permission on the host.
final class InputInjector {

    private var displayBounds: CGRect
    private let source = CGEventSource(stateID: .hidSystemState)
    private var pressedButtons: Set<UInt8> = []
    private var currentFlags: CGEventFlags = []
    private var lastLocation: CGPoint

    init(displayBounds: CGRect) {
        self.displayBounds = displayBounds
        self.lastLocation = CGPoint(x: displayBounds.midX, y: displayBounds.midY)
        // Do not let macOS suppress our synthetic events right after a real local one.
        let permitEverything: CGEventFilterMask = [.permitLocalMouseEvents, .permitLocalKeyboardEvents, .permitSystemDefinedEvents]
        source?.setLocalEventsFilterDuringSuppressionState(permitEverything, state: .eventSuppressionStateSuppressionInterval)
    }

    func updateBounds(_ bounds: CGRect) { displayBounds = bounds }

    static var isPermitted: Bool {
        AXIsProcessTrusted()
    }

    static func requestPermission() {
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        _ = AXIsProcessTrustedWithOptions(options)
    }

    func handle(_ event: InputEvent) {
        currentFlags = CGEventFlags(rawValue: UInt64(event.modifiers))

        switch event.kind {
        case .mouseMove:   moveMouse(event)
        case .mouseDown:   mouseButton(event, down: true)
        case .mouseUp:     mouseButton(event, down: false)
        case .scroll:      scroll(event)
        case .keyDown:     key(event, down: true)
        case .keyUp:       key(event, down: false)
        case .flagsChanged: break   // captured above; nothing to post on its own
        }
    }

    /// Releases anything still held when the link drops, so the host is not left with a
    /// stuck modifier or mouse button.
    func releaseAll() {
        for button in pressedButtons {
            var event = InputEvent(kind: .mouseUp)
            event.button = button
            mouseButton(event, down: false)
        }
        pressedButtons.removeAll()
        currentFlags = []
    }

    // MARK: -

    private func point(for event: InputEvent) -> CGPoint {
        CGPoint(x: displayBounds.origin.x + CGFloat(event.x) * displayBounds.width,
                y: displayBounds.origin.y + CGFloat(event.y) * displayBounds.height)
    }

    private func moveMouse(_ event: InputEvent) {
        let location = point(for: event)
        lastLocation = location
        let type: CGEventType
        if pressedButtons.contains(MouseButton.left.rawValue)      { type = .leftMouseDragged }
        else if pressedButtons.contains(MouseButton.right.rawValue) { type = .rightMouseDragged }
        else if pressedButtons.contains(MouseButton.middle.rawValue) { type = .otherMouseDragged }
        else                                                        { type = .mouseMoved }

        guard let cgEvent = CGEvent(mouseEventSource: source, mouseType: type,
                                    mouseCursorPosition: location,
                                    mouseButton: cgButton(for: pressedButtons.first ?? 0)) else { return }
        cgEvent.flags = currentFlags
        cgEvent.setDoubleValueField(.mouseEventDeltaX, value: Double(event.deltaX))
        cgEvent.setDoubleValueField(.mouseEventDeltaY, value: Double(event.deltaY))
        cgEvent.post(tap: .cghidEventTap)
    }

    private func mouseButton(_ event: InputEvent, down: Bool) {
        let location = down ? point(for: event) : lastLocation
        lastLocation = location
        if down { pressedButtons.insert(event.button) } else { pressedButtons.remove(event.button) }

        let type: CGEventType
        switch MouseButton(rawValue: event.button) ?? .left {
        case .left:   type = down ? .leftMouseDown : .leftMouseUp
        case .right:  type = down ? .rightMouseDown : .rightMouseUp
        case .middle: type = down ? .otherMouseDown : .otherMouseUp
        }

        guard let cgEvent = CGEvent(mouseEventSource: source, mouseType: type,
                                    mouseCursorPosition: location,
                                    mouseButton: cgButton(for: event.button)) else { return }
        cgEvent.flags = currentFlags
        cgEvent.setIntegerValueField(.mouseEventClickState, value: Int64(max(1, event.clickCount)))
        cgEvent.post(tap: .cghidEventTap)
    }

    private func scroll(_ event: InputEvent) {
        let unit: CGScrollEventUnit = event.isPreciseScroll ? .pixel : .line
        guard let cgEvent = CGEvent(scrollWheelEvent2Source: source, units: unit,
                                    wheelCount: 2,
                                    wheel1: Int32(event.deltaY.rounded()),
                                    wheel2: Int32(event.deltaX.rounded()),
                                    wheel3: 0) else { return }
        cgEvent.flags = currentFlags
        cgEvent.post(tap: .cghidEventTap)
    }

    private func key(_ event: InputEvent, down: Bool) {
        guard let cgEvent = CGEvent(keyboardEventSource: source,
                                    virtualKey: CGKeyCode(event.keyCode),
                                    keyDown: down) else { return }
        cgEvent.flags = currentFlags
        cgEvent.post(tap: .cghidEventTap)
    }

    private func cgButton(for raw: UInt8) -> CGMouseButton {
        switch MouseButton(rawValue: raw) ?? .left {
        case .left:   return .left
        case .right:  return .right
        case .middle: return .center
        }
    }
}
