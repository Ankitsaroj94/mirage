import Foundation
import os

enum Log {
    private static let networkLogger = Logger(subsystem: "com.voxturr.mirage", category: "network")
    private static let videoLogger = Logger(subsystem: "com.voxturr.mirage", category: "video")
    private static let appLogger = Logger(subsystem: "com.voxturr.mirage", category: "app")

    static func net(_ message: String)   { networkLogger.info("\(message, privacy: .public)") }
    static func video(_ message: String) { videoLogger.info("\(message, privacy: .public)") }
    static func app(_ message: String)   { appLogger.info("\(message, privacy: .public)") }
}
