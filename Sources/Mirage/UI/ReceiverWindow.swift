import AppKit
import AVFoundation

/// Borderless, always-on-top window that fills the receiving Mac's screen.
///
/// It is deliberately not an AppKit fullscreen space: a plain borderless window at
/// screen-saver level comes up instantly and has no space-switch animation, which matters
/// when you are toggling the extended display on and off.
@MainActor
final class ReceiverWindowController {

    var onExit: (() -> Void)?
    var statsProvider: (() -> SessionStats)?

    private let window: NSWindow
    private let videoView: VideoHostingView
    private let renderer: VideoRenderer
    private var previousPresentationOptions: NSApplication.PresentationOptions?
    private var overlayTimer: Timer?

    var contentView: NSView { videoView }

    init(renderer: VideoRenderer, screen: NSScreen) {
        self.renderer = renderer
        self.videoView = VideoHostingView(renderer: renderer)

        window = NSWindow(contentRect: screen.frame,
                          styleMask: [.borderless],
                          backing: .buffered,
                          defer: false,
                          screen: screen)
        window.level = .screenSaver
        window.backgroundColor = .black
        window.isOpaque = true
        window.hasShadow = false
        window.collectionBehavior = [.canJoinAllSpaces, .stationary, .fullScreenNone, .ignoresCycle]
        window.acceptsMouseMovedEvents = true
        window.contentView = videoView
        window.setFrame(screen.frame, display: true)

        videoView.onExit = { [weak self] in self?.onExit?() }
        videoView.onToggleOverlay = { [weak self] in self?.toggleOverlay() }

        // The hint is a waiting state, not a permanent caption. Once real video is on
        // screen it lingers just long enough to be read, then fades out.
        renderer.onFirstFrame = { [weak self] in
            Task { @MainActor in self?.videoView.dismissHint(after: 2.5) }
        }
    }

    func show() {
        videoView.showWaitingHint()
        previousPresentationOptions = NSApp.presentationOptions
        NSApp.presentationOptions = [.hideDock, .hideMenuBar, .disableProcessSwitching]
        NSApp.activate(ignoringOtherApps: true)
        window.makeKeyAndOrderFront(nil)
        window.makeFirstResponder(videoView)
        NSCursor.hide()
    }

    func close() {
        overlayTimer?.invalidate(); overlayTimer = nil
        NSCursor.unhide()
        if let previous = previousPresentationOptions {
            NSApp.presentationOptions = previous
        }
        window.orderOut(nil)
    }

    private func toggleOverlay() {
        if overlayTimer != nil {
            overlayTimer?.invalidate(); overlayTimer = nil
            videoView.setOverlayText(nil)
            return
        }
        let update: () -> Void = { [weak self] in
            guard let self, let stats = self.statsProvider?() else { return }
            self.videoView.setOverlayText(stats.overlayDescription)
        }
        update()
        overlayTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { _ in update() }
    }
}

/// Hosts the display layer and owns keyboard handling for the fullscreen surface.
@MainActor
final class VideoHostingView: NSView {

    var onExit: (() -> Void)?
    var onToggleOverlay: (() -> Void)?

    private let renderer: VideoRenderer
    private let overlay = NSTextField(labelWithString: "")
    private let hint = NSTextField(labelWithString: "Waiting for the other Mac…")
    private var hintDismissWorkItem: DispatchWorkItem?

    init(renderer: VideoRenderer) {
        self.renderer = renderer
        super.init(frame: .zero)

        wantsLayer = true
        layer?.backgroundColor = CGColor(red: 0, green: 0, blue: 0, alpha: 1)
        layer?.addSublayer(renderer.layer)

        for label in [overlay, hint] {
            label.font = .monospacedSystemFont(ofSize: 12, weight: .medium)
            label.textColor = .white
            label.backgroundColor = NSColor.black.withAlphaComponent(0.55)
            label.drawsBackground = true
            label.translatesAutoresizingMaskIntoConstraints = false
            label.maximumNumberOfLines = 0
            addSubview(label)
        }
        overlay.isHidden = true

        NSLayoutConstraint.activate([
            overlay.topAnchor.constraint(equalTo: topAnchor, constant: 16),
            overlay.leadingAnchor.constraint(equalTo: leadingAnchor, constant: 16),
            hint.centerXAnchor.constraint(equalTo: centerXAnchor),
            hint.centerYAnchor.constraint(equalTo: centerYAnchor),
        ])
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override var isFlipped: Bool { false }
    override var acceptsFirstResponder: Bool { true }
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func layout() {
        super.layout()
        // Match the layer's scale to the screen so the decoded buffer maps 1:1 to pixels.
        let scale = window?.screen?.backingScaleFactor ?? 2
        renderer.layer.contentsScale = scale
        CATransaction.begin()
        CATransaction.setDisableActions(true)   // no implicit animation on resize
        renderer.layer.frame = bounds
        CATransaction.commit()
    }

    override func keyDown(with event: NSEvent) {
        switch event.keyCode {
        case 53:                     // esc
            onExit?()
        case 1 where event.modifierFlags.isDisjoint(with: [.command, .control, .option]):  // s
            onToggleOverlay?()
        default:
            super.keyDown(with: event)
        }
    }

    func setOverlayText(_ text: String?) {
        overlay.isHidden = text == nil
        overlay.stringValue = text ?? ""
    }

    /// Swaps the waiting message for the shortcut reminder, then fades it away.
    func dismissHint(after delay: TimeInterval) {
        hintDismissWorkItem?.cancel()
        guard !hint.isHidden else { return }
        hint.stringValue = "esc to disconnect · s for stats"

        let work = DispatchWorkItem { [weak self] in
            guard let self else { return }
            NSAnimationContext.runAnimationGroup({ context in
                context.duration = 0.5
                self.hint.animator().alphaValue = 0
            }, completionHandler: { [weak self] in
                self?.hint.isHidden = true
                self?.hint.alphaValue = 1
            })
        }
        hintDismissWorkItem = work
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
    }

    /// Brings the waiting message back if the stream stops.
    func showWaitingHint() {
        hintDismissWorkItem?.cancel()
        hint.stringValue = "Waiting for the other Mac…"
        hint.alphaValue = 1
        hint.isHidden = false
    }
}
