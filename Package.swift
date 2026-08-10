// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "Mirage",
    platforms: [.macOS(.v13)],
    targets: [
        // Objective-C shim around the private CoreGraphics virtual-display classes.
        // Kept in its own target so the Swift side never touches the ObjC runtime directly.
        .target(
            name: "MirageBridge",
            path: "Sources/MirageBridge"
        ),
        // Owns one virtual display, in its own process. See its main.swift for why.
        .executableTarget(
            name: "MirageDisplayHelper",
            dependencies: ["MirageBridge"],
            path: "Sources/MirageDisplayHelper"
        ),
        .executableTarget(
            name: "Mirage",
            dependencies: ["MirageBridge"],
            path: "Sources/Mirage"
        ),
    ]
)
