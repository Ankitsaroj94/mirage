import Foundation

/// The three sizes a connection runs at.
///
/// On a Mac client they are all the same thing — the receiver's own framebuffer — which is
/// what keeps that path free of any resampling. A tablet's panel is denser than any Mac's,
/// though, and mapping it 1:1 at 2× would give a display that looks like a 1152×720 Mac at
/// best; the "more space" settings need a virtual display bigger than the stream.
struct DisplayPlan: Equatable {
    /// Backing store of the virtual display: what macOS renders.
    var virtualPixelWidth: Int
    var virtualPixelHeight: Int
    /// Its UI size ("looks like").
    var virtualPointWidth: Int
    var virtualPointHeight: Int
    /// What is captured, encoded and sent — always the client panel's pixel size.
    var streamPixelWidth: Int
    var streamPixelHeight: Int

    /// When true, ScreenCaptureKit resamples the virtual display to the stream size on the
    /// GPU — the same trade-off macOS makes for its own scaled modes.
    var scalesToFit: Bool {
        virtualPixelWidth != streamPixelWidth || virtualPixelHeight != streamPixelHeight
    }
}

/// Decides the virtual display and stream geometry for a client. spec/PROTOCOL.md §5.
///
/// Pure and deterministic, so the table in Tests/GeometryHarness pins it down exactly.
enum DisplayGeometry {

    /// UI-size factor applied to the crisp (panel ÷ 2) point size.
    static func factor(for preference: ScalePreference) -> Double {
        switch preference {
        case .crisp:  return 1.0
        case .larger: return 0.85
        case .more:   return 1.15
        case .most:   return 1.30
        }
    }

    /// - Parameters:
    ///   - panel: the client's physical pixels, in its current orientation.
    ///   - requestedPoints: the client's own point suggestion; only honoured for Macs.
    ///   - physicalDPI: informative for now (0 = unknown). The crisp default already lands a
    ///     tablet near Mac UI density, so no preference is derived from it yet.
    ///   - platform: nil / "macos" keeps the exact v1 behaviour.
    static func plan(panelPixelWidth: Int, panelPixelHeight: Int,
                     requestedPointWidth: Int, requestedPointHeight: Int,
                     physicalDPI: Double = 0,
                     preference: ScalePreference = .crisp,
                     platform: String? = nil) -> DisplayPlan {

        // A Mac asks for its own framebuffer and gets exactly that: v1 behaviour, untouched.
        if (platform ?? "macos") == "macos" {
            return DisplayPlan(virtualPixelWidth: panelPixelWidth, virtualPixelHeight: panelPixelHeight,
                               virtualPointWidth: requestedPointWidth, virtualPointHeight: requestedPointHeight,
                               streamPixelWidth: panelPixelWidth, streamPixelHeight: panelPixelHeight)
        }

        // The private API only synthesises a Retina mode for even pixel sizes, and the
        // encoders want even dimensions anyway.
        let panelWidth = max(2, evenFloor(panelPixelWidth))
        let panelHeight = max(2, evenFloor(panelPixelHeight))

        let pointWidth: Int
        let pointHeight: Int
        if preference == .crisp {
            // Exactly half the panel: virtual == stream == panel, zero resampling. Not forced
            // even — doing so would break the 1:1 mapping on a panel that is 2 mod 4.
            pointWidth = panelWidth / 2
            pointHeight = panelHeight / 2
        } else {
            let f = factor(for: preference)
            pointWidth = max(2, evenRound(Double(panelWidth) / 2 * f))
            pointHeight = max(2, evenRound(Double(panelHeight) / 2 * f))
        }

        return DisplayPlan(virtualPixelWidth: pointWidth * 2, virtualPixelHeight: pointHeight * 2,
                           virtualPointWidth: pointWidth, virtualPointHeight: pointHeight,
                           streamPixelWidth: panelWidth, streamPixelHeight: panelHeight)
    }

    /// The plan for a hello. `override` is the host's per-device setting, which wins over the
    /// client's own `scalePreference`.
    static func plan(for hello: ClientHello, override: ScalePreference? = nil) -> DisplayPlan {
        let preference = override
            ?? hello.scalePreference.flatMap(ScalePreference.init(rawValue:))
            ?? .crisp
        return plan(panelPixelWidth: hello.pixelWidth, panelPixelHeight: hello.pixelHeight,
                    requestedPointWidth: hello.pointWidth, requestedPointHeight: hello.pointHeight,
                    physicalDPI: hello.physicalDPI ?? 0,
                    preference: preference,
                    platform: hello.platform)
    }

    /// Capture/encode rate: never above the client's panel or the host's frame-rate setting,
    /// and above 60 only when 120 Hz is switched on. The 30 fps setting still wins.
    static func frameRate(clientRefreshHz: Int, maxFPS: Int, allow120Hz: Bool) -> Int {
        let ceiling = (allow120Hz && maxFPS >= 60) ? 120 : min(maxFPS, 60)
        return max(1, min(ceiling, clientRefreshHz > 0 ? clientRefreshHz : 60))
    }

    // MARK: Rounding

    static func evenFloor(_ value: Int) -> Int { value - (value & 1) }

    /// Nearest even integer; halfway cases round away from zero, so it never depends on the
    /// platform's tie-breaking.
    static func evenRound(_ value: Double) -> Int { Int((value / 2).rounded(.toNearestOrAwayFromZero)) * 2 }
}
