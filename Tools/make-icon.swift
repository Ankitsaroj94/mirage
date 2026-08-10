// Draws the Mirage app icon and writes Resources/Mirage.icns.
//
// Generated rather than checked in as a binary so the artwork stays reviewable and
// tweakable. Run with `make icon`.
//
// The mark: two screens, one solid and one lighter behind it, offset the way an extended
// display sits beside a main one — with the trailing screen partly translucent, since the
// second Mac is showing something that is not really there.

import AppKit
import CoreGraphics
import Foundation

let outputDirectory = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "Resources"

// macOS icons are drawn inside a squircle that occupies ~80% of the canvas.
func drawIcon(size: CGFloat, into context: CGContext) {
    let scale = size / 1024.0
    context.saveGState()
    context.scaleBy(x: scale, y: scale)

    let canvas = CGRect(x: 0, y: 0, width: 1024, height: 1024)
    context.setShouldAntialias(true)
    context.interpolationQuality = .high
    context.clear(canvas)

    // Rounded-square plate, inset like a system icon.
    let plate = canvas.insetBy(dx: 100, dy: 100)
    let platePath = CGPath(roundedRect: plate, cornerWidth: 190, cornerHeight: 190, transform: nil)

    context.saveGState()
    context.addPath(platePath)
    context.clip()
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    let gradient = CGGradient(colorsSpace: colorSpace, colors: [
        CGColor(red: 0.32, green: 0.44, blue: 0.98, alpha: 1),
        CGColor(red: 0.42, green: 0.26, blue: 0.86, alpha: 1),
    ] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(gradient,
                               start: CGPoint(x: plate.minX, y: plate.maxY),
                               end: CGPoint(x: plate.maxX, y: plate.minY),
                               options: [])
    // Soft highlight across the top edge.
    let sheen = CGGradient(colorsSpace: colorSpace, colors: [
        CGColor(red: 1, green: 1, blue: 1, alpha: 0.22),
        CGColor(red: 1, green: 1, blue: 1, alpha: 0),
    ] as CFArray, locations: [0, 1])!
    context.drawLinearGradient(sheen,
                               start: CGPoint(x: plate.midX, y: plate.maxY),
                               end: CGPoint(x: plate.midX, y: plate.midY),
                               options: [])
    context.restoreGState()

    // Secondary screen: the borrowed one, sitting behind and to the upper right.
    let secondary = CGRect(x: 452, y: 452, width: 356, height: 226)
    let secondaryPath = CGPath(roundedRect: secondary, cornerWidth: 26, cornerHeight: 26, transform: nil)
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.30))
    context.addPath(secondaryPath)
    context.fillPath()
    context.setStrokeColor(CGColor(red: 1, green: 1, blue: 1, alpha: 0.85))
    context.setLineWidth(14)
    context.addPath(secondaryPath)
    context.strokePath()

    // Primary screen: solid, in front, lower left.
    let primary = CGRect(x: 216, y: 330, width: 400, height: 254)
    let primaryPath = CGPath(roundedRect: primary, cornerWidth: 30, cornerHeight: 30, transform: nil)

    // Separate the two screens by re-laying the plate gradient in a slightly larger
    // footprint around the front one. Painting the same gradient over the top is exact,
    // and avoids the seam that clearing first would leave behind from double antialiasing.
    context.saveGState()
    context.addPath(CGPath(roundedRect: primary.insetBy(dx: -16, dy: -16),
                           cornerWidth: 44, cornerHeight: 44, transform: nil))
    context.clip()
    context.drawLinearGradient(gradient,
                               start: CGPoint(x: plate.minX, y: plate.maxY),
                               end: CGPoint(x: plate.maxX, y: plate.minY),
                               options: [])
    context.restoreGState()

    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    context.addPath(primaryPath)
    context.fillPath()

    // Stand, so it reads as a display rather than a card.
    context.setFillColor(CGColor(red: 1, green: 1, blue: 1, alpha: 1))
    let neck = CGRect(x: primary.midX - 34, y: primary.minY - 54, width: 68, height: 58)
    context.addPath(CGPath(roundedRect: neck, cornerWidth: 10, cornerHeight: 10, transform: nil))
    context.fillPath()
    let foot = CGRect(x: primary.midX - 116, y: primary.minY - 78, width: 232, height: 30)
    context.addPath(CGPath(roundedRect: foot, cornerWidth: 15, cornerHeight: 15, transform: nil))
    context.fillPath()

    // Text lines on the primary screen — the whole point of the app is legible text.
    context.setFillColor(CGColor(red: 0.36, green: 0.33, blue: 0.86, alpha: 1))
    let lineWidths: [CGFloat] = [250, 190, 218, 150]
    for (index, width) in lineWidths.enumerated() {
        let y = primary.maxY - 66 - CGFloat(index) * 46
        context.addPath(CGPath(roundedRect: CGRect(x: primary.minX + 46, y: y, width: width, height: 22),
                               cornerWidth: 11, cornerHeight: 11, transform: nil))
        context.fillPath()
    }

    context.restoreGState()
}

func writePNG(size: Int, to url: URL) throws {
    let colorSpace = CGColorSpaceCreateDeviceRGB()
    guard let context = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8,
                                  bytesPerRow: 0, space: colorSpace,
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else {
        throw NSError(domain: "icon", code: 1)
    }
    drawIcon(size: CGFloat(size), into: context)
    guard let image = context.makeImage() else { throw NSError(domain: "icon", code: 2) }
    let rep = NSBitmapImageRep(cgImage: image)
    guard let data = rep.representation(using: .png, properties: [:]) else {
        throw NSError(domain: "icon", code: 3)
    }
    try data.write(to: url)
}

let iconset = URL(fileURLWithPath: outputDirectory).appendingPathComponent("Mirage.iconset")
try? FileManager.default.removeItem(at: iconset)
try FileManager.default.createDirectory(at: iconset, withIntermediateDirectories: true)

// The exact set iconutil expects.
let variants: [(String, Int)] = [
    ("icon_16x16", 16), ("icon_16x16@2x", 32),
    ("icon_32x32", 32), ("icon_32x32@2x", 64),
    ("icon_128x128", 128), ("icon_128x128@2x", 256),
    ("icon_256x256", 256), ("icon_256x256@2x", 512),
    ("icon_512x512", 512), ("icon_512x512@2x", 1024),
]
for (name, size) in variants {
    try writePNG(size: size, to: iconset.appendingPathComponent("\(name).png"))
}

let iconutil = Process()
iconutil.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
iconutil.arguments = ["-c", "icns", iconset.path,
                      "-o", URL(fileURLWithPath: outputDirectory).appendingPathComponent("Mirage.icns").path]
try iconutil.run()
iconutil.waitUntilExit()
guard iconutil.terminationStatus == 0 else {
    FileHandle.standardError.write(Data("iconutil failed\n".utf8))
    exit(1)
}
try? FileManager.default.removeItem(at: iconset)
print("wrote \(outputDirectory)/Mirage.icns")
