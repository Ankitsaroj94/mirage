import Foundation
import CoreAudio
import AudioToolbox

/// Captures everything the Mac is playing — except Mirage itself — with a Core Audio process
/// tap (macOS 14.2+).
///
/// A tap is not a device on its own; it has to be wrapped in a private aggregate device, and
/// the aggregate's IOProc is where the samples show up. The aggregate is built around the
/// default output device so the tap runs on that device's clock, which is also the clock
/// `LocalPlayout` renders against — that is what keeps the Mac side drift-free.
///
/// `mutesSystemOutput` (`muteBehavior = .mutedWhenTapped`) silences the original output while
/// the tap is alive, so the only thing coming out of the speakers is the delayed copy (mode
/// `.both`) or nothing (mode `.tabletOnly`). Destroying the tap in `stop()` unmutes it.
@available(macOS 14.2, *)
final class SystemAudioTap {

    /// What the IOProc needs, behind one pointer the C callback can borrow.
    final class IOContext {
        let ring: CaptureRing
        let wake: DispatchSemaphore
        /// Planar (one buffer per channel) or interleaved, from the tap's format.
        var planar = false
        var channels = 2
        init(ring: CaptureRing, wake: DispatchSemaphore) { self.ring = ring; self.wake = wake }
    }

    let context: IOContext
    private let mutesSystemOutput: Bool
    private(set) var sampleRate: Double = 48_000

    private var tapID = AudioObjectID(kAudioObjectUnknown)
    private let tapUUID = UUID()
    private var aggregateID = AudioObjectID(kAudioObjectUnknown)
    private var procID: AudioDeviceIOProcID?

    init(ring: CaptureRing, wake: DispatchSemaphore, mutesSystemOutput: Bool) {
        self.context = IOContext(ring: ring, wake: wake)
        self.mutesSystemOutput = mutesSystemOutput
    }

    deinit { stop() }

    func start() throws {
        HostClock.prepare()
        if SystemAudioPermission.preflight() == .denied { throw SystemAudioPermission.deniedError }

        do {
            try createTap()
            try createAggregate()
            try startIO()
        } catch {
            stop()
            throw error
        }
    }

    /// Tears down in reverse. Every step is safe to repeat, so this doubles as error cleanup.
    func stop() {
        if aggregateID != kAudioObjectUnknown, let procID {
            AudioDeviceStop(aggregateID, procID)
            // Waits for an IOProc already in flight, so the context can be released after this.
            AudioDeviceDestroyIOProcID(aggregateID, procID)
        }
        procID = nil
        if aggregateID != kAudioObjectUnknown {
            AudioHardwareDestroyAggregateDevice(aggregateID)
            aggregateID = AudioObjectID(kAudioObjectUnknown)
        }
        if tapID != kAudioObjectUnknown {
            AudioHardwareDestroyProcessTap(tapID)
            tapID = AudioObjectID(kAudioObjectUnknown)
        }
    }

    // MARK: Setup

    private func createTap() throws {
        // Exclude ourselves, or the delayed local copy would be captured and fed back in.
        var excluded: [AudioObjectID] = []
        var pid = getpid()
        var address = CoreAudioProperty.address(kAudioHardwarePropertyTranslatePIDToProcessObject)
        var own = AudioObjectID(kAudioObjectUnknown)
        var size = UInt32(MemoryLayout<AudioObjectID>.size)
        if AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address,
                                      UInt32(MemoryLayout<pid_t>.size), &pid, &size, &own) == noErr,
           own != kAudioObjectUnknown {
            excluded = [own]
        }
        // When Mirage has not played anything yet it has no process object, and there is
        // nothing of ours to exclude — LocalPlayout starts before the tap for exactly this
        // reason, so in mode .both the object does exist by now.

        let description = CATapDescription(stereoGlobalTapButExcludeProcesses: excluded)
        description.uuid = tapUUID
        description.name = "Mirage system audio"
        description.isPrivate = true
        description.muteBehavior = mutesSystemOutput ? .mutedWhenTapped : .unmuted

        var tap = AudioObjectID(kAudioObjectUnknown)
        let status = AudioHardwareCreateProcessTap(description, &tap)
        guard status == noErr, tap != kAudioObjectUnknown else {
            throw MirageError("Could not tap the Mac's system audio (\(CoreAudioProperty.describe(status))). "
                              + SystemAudioPermission.advice)
        }
        tapID = tap

        let format = try CoreAudioProperty.get(tapID, kAudioTapPropertyFormat, AudioStreamBasicDescription())
        guard format.mFormatID == kAudioFormatLinearPCM,
              format.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              format.mBitsPerChannel == 32,
              format.mChannelsPerFrame >= 1 else {
            throw MirageError("The system audio tap uses a format Mirage cannot read.")
        }
        sampleRate = format.mSampleRate
        context.planar = format.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        context.channels = Int(format.mChannelsPerFrame)
    }

    private func createAggregate() throws {
        let output = try CoreAudioProperty.defaultOutputDevice()
        let outputUID = try CoreAudioProperty.string(output, kAudioDevicePropertyDeviceUID)
        let tapUID = tapUUID.uuidString

        let description: [String: Any] = [
            kAudioAggregateDeviceNameKey: "Mirage tap",
            kAudioAggregateDeviceUIDKey: "com.voxturr.mirage.tap.\(UUID().uuidString)",
            kAudioAggregateDeviceMainSubDeviceKey: outputUID,
            kAudioAggregateDeviceIsPrivateKey: true,
            kAudioAggregateDeviceIsStackedKey: false,
            kAudioAggregateDeviceTapAutoStartKey: true,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: outputUID]],
            kAudioAggregateDeviceTapListKey: [[kAudioSubTapDriftCompensationKey: true,
                                               kAudioSubTapUIDKey: tapUID]],
        ]
        var aggregate = AudioObjectID(kAudioObjectUnknown)
        let status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &aggregate)
        guard status == noErr, aggregate != kAudioObjectUnknown else {
            throw MirageError("Could not create the capture device for system audio (\(CoreAudioProperty.describe(status))).")
        }
        aggregateID = aggregate

        // The IOProc runs at the aggregate's rate; prefer it over the tap's if they differ.
        if let rate = try? CoreAudioProperty.get(aggregateID, kAudioDevicePropertyNominalSampleRate, Float64(0)), rate > 0 {
            sampleRate = rate
        }
    }

    private func startIO() throws {
        var proc: AudioDeviceIOProcID?
        var status = AudioDeviceCreateIOProcID(aggregateID, tapIOProc,
                                               Unmanaged.passUnretained(context).toOpaque(), &proc)
        guard status == noErr, let proc else {
            throw MirageError("Could not start capturing system audio (\(CoreAudioProperty.describe(status))).")
        }
        procID = proc
        status = AudioDeviceStart(aggregateID, proc)
        guard status == noErr else {
            throw MirageError("Could not start capturing system audio (\(CoreAudioProperty.describe(status))). "
                              + SystemAudioPermission.advice)
        }
    }
}

/// The aggregate's IOProc, on Core Audio's real-time thread: copy into the ring, poke the
/// pump, return. No allocation, no locks that can wait, no Objective-C.
///
/// The tap's streams come after any input streams of the main sub-device (a USB headset has
/// a mic, for instance), so the tap is taken from the *end* of the input buffer list.
@available(macOS 14.2, *)
private let tapIOProc: AudioDeviceIOProc = { _, _, inputData, inputTime, _, _, clientData in
    guard let clientData else { return noErr }
    Unmanaged<SystemAudioTap.IOContext>.fromOpaque(clientData)._withUnsafeGuaranteedRef { context in
        let buffers = UnsafeMutableAudioBufferListPointer(UnsafeMutablePointer(mutating: inputData))
        let count = buffers.count
        guard count > 0 else { return }

        let left: UnsafePointer<Float>, right: UnsafePointer<Float>
        let stride: Int, frames: Int
        if context.planar {
            let needed = min(context.channels, 2)
            guard count >= needed,
                  let l = buffers[count - needed].mData,
                  let r = buffers[count - 1].mData else { return }
            left = UnsafePointer(l.assumingMemoryBound(to: Float.self))
            right = UnsafePointer(r.assumingMemoryBound(to: Float.self))
            stride = 1
            frames = Int(buffers[count - 1].mDataByteSize) / MemoryLayout<Float>.size
        } else {
            let buffer = buffers[count - 1]
            guard let data = buffer.mData, buffer.mNumberChannels > 0 else { return }
            let channels = Int(buffer.mNumberChannels)
            let base = UnsafePointer(data.assumingMemoryBound(to: Float.self))
            left = base
            right = channels > 1 ? base + 1 : base
            stride = channels
            frames = Int(buffer.mDataByteSize) / (MemoryLayout<Float>.size * channels)
        }
        guard frames > 0 else { return }

        let stamp = inputTime.pointee
        let hostTime = stamp.mFlags.contains(.hostTimeValid) ? stamp.mHostTime : mach_absolute_time()
        let micros = UInt64(max(0, HostClock.micros(hostTime: hostTime)))
        context.ring.write(left: left, right: right, stride: stride, count: frames, hostMicros: micros)
        context.wake.signal()
    }
    return noErr
}

/// The "System Audio Recording" privacy permission.
///
/// There is no public API to ask about it ahead of time: without it the tap is still created
/// and simply delivers silence. TCC's own preflight call is looked up at run time — if it is
/// not there, Mirage just carries on and lets the system prompt on first capture.
enum SystemAudioPermission {
    enum Status { case authorized, denied, unknown }

    static let advice = "Mirage needs permission to record system audio. Allow it in System Settings › "
        + "Privacy & Security › Screen & System Audio Recording (System Audio Recording Only), then reconnect."

    static var deniedError: MirageError { MirageError(advice) }

    static func preflight() -> Status {
        typealias Preflight = @convention(c) (CFString, CFDictionary?) -> Int
        guard let handle = dlopen("/System/Library/PrivateFrameworks/TCC.framework/Versions/A/TCC", RTLD_NOW),
              let symbol = dlsym(handle, "TCCAccessPreflight") else { return .unknown }
        let call = unsafeBitCast(symbol, to: Preflight.self)
        switch call("kTCCServiceAudioCapture" as CFString, nil) {
        case 0: return .authorized
        case 1: return .denied
        default: return .unknown
        }
    }
}
