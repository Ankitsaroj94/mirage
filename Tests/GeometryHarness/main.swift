// Table test for DisplayGeometry (spec/PROTOCOL.md §5): the virtual display and stream sizes
// a host picks for each client, panel and scale preference. Pure functions, no permissions.
import Foundation

var failures = 0
func expect(_ name: String, _ plan: DisplayPlan,
            virtualPx: (Int, Int), virtualPt: (Int, Int), stream: (Int, Int), scaled: Bool) {
    let ok = plan.virtualPixelWidth == virtualPx.0 && plan.virtualPixelHeight == virtualPx.1
        && plan.virtualPointWidth == virtualPt.0 && plan.virtualPointHeight == virtualPt.1
        && plan.streamPixelWidth == stream.0 && plan.streamPixelHeight == stream.1
        && plan.scalesToFit == scaled
    let got = "\(plan.virtualPixelWidth)×\(plan.virtualPixelHeight) px / \(plan.virtualPointWidth)×\(plan.virtualPointHeight) pt"
        + " → stream \(plan.streamPixelWidth)×\(plan.streamPixelHeight)\(plan.scalesToFit ? " (scaled)" : "")"
    print("  \(ok ? "✓" : "✗") \(name.padding(toLength: 44, withPad: " ", startingAt: 0)) \(got)")
    if !ok {
        failures += 1
        print("      expected \(virtualPx.0)×\(virtualPx.1) px / \(virtualPt.0)×\(virtualPt.1) pt → stream \(stream.0)×\(stream.1)\(scaled ? " (scaled)" : "")")
    }
}

func hello(_ w: Int, _ h: Int, pt: (Int, Int)? = nil, platform: String? = "android",
           preference: String? = nil, dpi: Double? = 249, version: Int = 2) -> ClientHello {
    var hello = ClientHello(deviceName: "Galaxy Tab S9 FE", pixelWidth: w, pixelHeight: h,
                            pointWidth: pt?.0 ?? w / 2, pointHeight: pt?.1 ?? h / 2, refreshHz: 90,
                            supportsHEVC: true, supportsH264: true, wantsInputForwarding: true)
    hello.protocolVersion = version
    hello.platform = platform
    hello.scalePreference = preference
    hello.physicalDPI = dpi
    return hello
}

print("Galaxy Tab S9 FE (SM-X510), 2304×1440 @ 249 dpi")
expect("landscape crisp", DisplayGeometry.plan(for: hello(2304, 1440, preference: "crisp")),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)
expect("portrait crisp", DisplayGeometry.plan(for: hello(1440, 2304, preference: "crisp")),
       virtualPx: (1440, 2304), virtualPt: (720, 1152), stream: (1440, 2304), scaled: false)
expect("landscape, no preference sent = crisp", DisplayGeometry.plan(for: hello(2304, 1440)),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)
expect("landscape larger (×0.85)", DisplayGeometry.plan(for: hello(2304, 1440, preference: "larger")),
       virtualPx: (1960, 1224), virtualPt: (980, 612), stream: (2304, 1440), scaled: true)
expect("landscape more (×1.15)", DisplayGeometry.plan(for: hello(2304, 1440, preference: "more")),
       virtualPx: (2648, 1656), virtualPt: (1324, 828), stream: (2304, 1440), scaled: true)
expect("landscape most (×1.30)", DisplayGeometry.plan(for: hello(2304, 1440, preference: "most")),
       virtualPx: (2996, 1872), virtualPt: (1498, 936), stream: (2304, 1440), scaled: true)
expect("portrait more", DisplayGeometry.plan(for: hello(1440, 2304, preference: "more")),
       virtualPx: (1656, 2648), virtualPt: (828, 1324), stream: (1440, 2304), scaled: true)
expect("host override crisp beats client \"most\"", DisplayGeometry.plan(for: hello(2304, 1440, preference: "most"), override: .crisp),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)
expect("host override more beats client \"crisp\"", DisplayGeometry.plan(for: hello(2304, 1440, preference: "crisp"), override: .more),
       virtualPx: (2648, 1656), virtualPt: (1324, 828), stream: (2304, 1440), scaled: true)
expect("unknown preference string = crisp", DisplayGeometry.plan(for: hello(2304, 1440, preference: "huge")),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)
expect("client point suggestion is ignored", DisplayGeometry.plan(for: hello(2304, 1440, pt: (1000, 625))),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)

print("Other tablets (plan.md §1.3)")
expect("Tab S10/S11 11\" 2560×1600 crisp", DisplayGeometry.plan(for: hello(2560, 1600)),
       virtualPx: (2560, 1600), virtualPt: (1280, 800), stream: (2560, 1600), scaled: false)
expect("Tab S10+ 12.4\" 2800×1752 crisp", DisplayGeometry.plan(for: hello(2800, 1752)),
       virtualPx: (2800, 1752), virtualPt: (1400, 876), stream: (2800, 1752), scaled: false)
expect("Tab Ultra 14.6\" 2960×1848 crisp", DisplayGeometry.plan(for: hello(2960, 1848)),
       virtualPx: (2960, 1848), virtualPt: (1480, 924), stream: (2960, 1848), scaled: false)

print("Rounding")
expect("odd panel 2305×1441 → even", DisplayGeometry.plan(for: hello(2305, 1441)),
       virtualPx: (2304, 1440), virtualPt: (1152, 720), stream: (2304, 1440), scaled: false)
expect("crisp keeps 1:1 on a 2-mod-4 panel", DisplayGeometry.plan(for: hello(2004, 1202)),
       virtualPx: (2004, 1202), virtualPt: (1002, 601), stream: (2004, 1202), scaled: false)
expect("scaled points round to even", DisplayGeometry.plan(for: hello(2004, 1202, preference: "more")),
       virtualPx: (2304, 1384), virtualPt: (1152, 692), stream: (2004, 1202), scaled: true)
for pref in ScalePreference.allCases where pref != .crisp {
    for (w, h) in [(2304, 1440), (1440, 2304), (2560, 1600), (2000, 1202), (1080, 2340)] {
        let p = DisplayGeometry.plan(for: hello(w, h, preference: pref.rawValue))
        if p.virtualPointWidth % 2 != 0 || p.virtualPointHeight % 2 != 0
            || p.virtualPixelWidth != 2 * p.virtualPointWidth || p.virtualPixelHeight != 2 * p.virtualPointHeight
            || p.streamPixelWidth % 2 != 0 || p.streamPixelHeight % 2 != 0 {
            print("  ✗ \(pref) \(w)×\(h): not even / not 2×: \(p)"); failures += 1
        }
        if p != DisplayGeometry.plan(for: hello(w, h, preference: pref.rawValue)) {
            print("  ✗ \(pref) \(w)×\(h): not deterministic"); failures += 1
        }
    }
}
print("  ✓ every scaled plan: even points, pixels = 2 × points, even stream, deterministic")

print("Mac clients — exactly what they ask for (v1 behaviour)")
let macbook = hello(2940, 1912, pt: (1470, 956), platform: nil, dpi: nil, version: 1)
expect("MacBook Pro 14\" v1 hello", DisplayGeometry.plan(for: macbook),
       virtualPx: (2940, 1912), virtualPt: (1470, 956), stream: (2940, 1912), scaled: false)
expect("same panel, v2 hello platform macos", DisplayGeometry.plan(for: hello(2940, 1912, pt: (1470, 956), platform: "macos", dpi: 254)),
       virtualPx: (2940, 1912), virtualPt: (1470, 956), stream: (2940, 1912), scaled: false)
expect("macos ignores a scale preference", DisplayGeometry.plan(for: hello(2940, 1912, pt: (1470, 956), platform: "macos", preference: "most")),
       virtualPx: (2940, 1912), virtualPt: (1470, 956), stream: (2940, 1912), scaled: false)
expect("non-Retina Mac (1:1 points)", DisplayGeometry.plan(for: hello(1920, 1080, pt: (1920, 1080), platform: nil, version: 1)),
       virtualPx: (1920, 1080), virtualPt: (1920, 1080), stream: (1920, 1080), scaled: false)

print("Frame rate")
let rates: [(Int, Int, Bool, Int)] = [
    (60, 60, false, 60), (90, 60, false, 60), (120, 60, false, 60), (120, 60, true, 120),
    (90, 60, true, 90), (120, 30, true, 30), (120, 30, false, 30), (144, 60, true, 120),
    (0, 60, false, 60), (120, 120, false, 60),
]
for (clientHz, maxFPS, allow120, expected) in rates {
    let got = DisplayGeometry.frameRate(clientRefreshHz: clientHz, maxFPS: maxFPS, allow120Hz: allow120)
    if got != expected { print("  ✗ client \(clientHz) Hz, max \(maxFPS), 120 Hz \(allow120): \(got) != \(expected)"); failures += 1 }
}
print("  ✓ \(rates.count) frame-rate cases")

if failures > 0 {
    print("✗ geometry: \(failures) failure(s)")
    exit(1)
}
print("✓ geometry: all cases match spec §5")
exit(0)
