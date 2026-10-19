import Foundation
import AudioToolbox
import CoreAudio

/// The packets waiting to play on this Mac, and the decision of which sample goes out when.
///
/// The rule is the spec's: a sample captured at host time T leaves the speaker cone at
/// `T + playoutDelay + syncOffset`. The render callback knows when its buffer will reach the
/// hardware (`mHostTime`) and the device knows how long it takes from there to the speaker
/// (`outputLatency`), so the sample due at render frame i is the one captured at
///
///     renderTime + outputLatency + i / 48 kHz − playoutDelay − syncOffset
///
/// Kept apart from the AudioUnit so the harness can drive it with a fake clock.
final class PlayoutBuffer {
    static let packetFrames = AudioPacketizer.packetFrames
    static let channels = 2
    static let frameMicros = 1_000_000.0 / AudioPacketizer.outputRate
    /// Within this the stream is "in sync" and plays straight on; beyond it the buffer jumps.
    /// Two clocks (tap device vs. output device) can drift apart by a few ppm; a jump of under
    /// a millisecond is the price of not running a second resampler here. When the default
    /// output is the tapped device — the normal case — there is no drift at all.
    static let resyncThresholdMicros = 1_000.0

    private let ring: SPSCCursors
    private let times: UnsafeMutablePointer<UInt64>
    private let samples: UnsafeMutablePointer<Int16>
    private let samplesPerPacket = packetFrames * channels

    private let playoutDelayMicros: Int64
    private let syncOffset: RealtimeValue
    private let outputLatencyMicros: RealtimeValue

    // Consumer (render thread) state.
    private var offsetInPacket = 0
    private(set) var underruns: UInt64 = 0
    private(set) var droppedFrames: UInt64 = 0
    private(set) var resyncs: UInt64 = 0

    init(capacityPackets: Int = 128, playoutDelayMs: Int, syncOffsetMs: Int, outputLatencyMicros: Int64 = 0) {
        ring = SPSCCursors(capacity: capacityPackets)
        times = .allocate(capacity: capacityPackets)
        samples = .allocate(capacity: capacityPackets * samplesPerPacket)
        playoutDelayMicros = Int64(playoutDelayMs) * 1_000
        syncOffset = RealtimeValue(Int64(syncOffsetMs) * 1_000)
        self.outputLatencyMicros = RealtimeValue(outputLatencyMicros)
    }

    deinit {
        times.deallocate()
        samples.deallocate()
    }

    func setSyncOffset(ms: Int) { syncOffset.set(Int64(ms) * 1_000) }
    func setOutputLatency(micros: Int64) { outputLatencyMicros.set(micros) }

    /// Capture time of the sample that should be handed to the hardware at `renderMicros`,
    /// i.e. inverse of `playAt = capture + delay + offset − latency`.
    static func targetCaptureMicros(renderMicros: Int64, outputLatencyMicros: Int64,
                                    playoutDelayMicros: Int64, syncOffsetMicros: Int64) -> Int64 {
        renderMicros + outputLatencyMicros - playoutDelayMicros - syncOffsetMicros
    }

    // MARK: Producer (pump thread)

    /// Returns false when full; the packet is dropped (it would be too late to play anyway).
    @discardableResult
    func enqueue(hostTimeMicros: UInt64, pcm: UnsafeBufferPointer<Int16>) -> Bool {
        guard ring.writableCount > 0, let source = pcm.baseAddress else { return false }
        let slot = Int(ring.producerCursor & ring.mask)
        times[slot] = hostTimeMicros
        (samples + slot * samplesPerPacket).update(from: source, count: min(pcm.count, samplesPerPacket))
        ring.commitWrite(1)
        return true
    }

    // MARK: Consumer (real-time render thread)

    /// Fills `frames` interleaved s16 frames for a buffer that reaches the hardware at
    /// `renderMicros` (MonotonicClock µs). Never blocks; anything missing is silence.
    func render(into out: UnsafeMutablePointer<Int16>, frames: Int, renderMicros: Int64) {
        let latency = outputLatencyMicros.get()
        // If the device is slower than the whole playout budget (Bluetooth can be 150 ms+)
        // perfect sync is impossible; play as early as we can rather than not at all.
        let shift = max(playoutDelayMicros + syncOffset.get(), latency + 5_000)
        let target0 = Double(renderMicros + latency - shift)

        var written = 0
        while written < frames {
            let available = ring.readableCount
            guard available > 0 else {
                (out + written * Self.channels).update(repeating: 0, count: (frames - written) * Self.channels)
                underruns &+= 1
                return
            }
            let slot = Int(ring.consumerCursor & ring.mask)
            let headTime = Double(times[slot]) + Double(offsetInPacket) * Self.frameMicros
            let wantTime = target0 + Double(written) * Self.frameMicros
            let diff = headTime - wantTime

            if diff > Self.resyncThresholdMicros {
                // Not due yet (start-up, or a gap in the stream): silence until it is.
                let wait = min(frames - written, max(1, Int((diff / Self.frameMicros).rounded())))
                (out + written * Self.channels).update(repeating: 0, count: wait * Self.channels)
                written += wait
                continue
            }
            if diff < -Self.resyncThresholdMicros {
                // Already late: skip ahead to the sample that is due now.
                var skip = Int((-diff / Self.frameMicros).rounded())
                droppedFrames &+= UInt64(skip)
                resyncs &+= 1
                while skip > 0 && ring.readableCount > 0 {
                    let left = Self.packetFrames - offsetInPacket
                    if skip >= left {
                        skip -= left
                        offsetInPacket = 0
                        ring.commitRead(1)
                    } else {
                        offsetInPacket += skip
                        skip = 0
                    }
                }
                continue
            }

            let count = min(frames - written, Self.packetFrames - offsetInPacket)
            (out + written * Self.channels).update(
                from: samples + slot * samplesPerPacket + offsetInPacket * Self.channels,
                count: count * Self.channels)
            written += count
            offsetInPacket += count
            if offsetInPacket == Self.packetFrames {
                offsetInPacket = 0
                ring.commitRead(1)
            }
        }
    }
}

/// Plays the stream on this Mac's default output, delayed to line up with the client.
///
/// A bare AUHAL output unit rather than AVAudioEngine: the render callback gets the
/// `AudioTimeStamp` of the exact moment its buffer reaches the device, which is what the sync
/// is built on, and nothing in between adds its own buffering. The unit's input format is set
/// to the wire format (48 kHz s16 stereo) so AUHAL does any rate conversion to the device.
///
/// This process is excluded from the tap, so what plays here is never captured again.
final class LocalPlayout {
    let buffer: PlayoutBuffer
    private var unit: AudioUnit?
    private var deviceID = AudioObjectID(kAudioObjectUnknown)

    init(playoutDelayMs: Int, syncOffsetMs: Int) {
        buffer = PlayoutBuffer(playoutDelayMs: playoutDelayMs, syncOffsetMs: syncOffsetMs)
    }

    deinit { stop() }

    func start() throws {
        HostClock.prepare()
        deviceID = try CoreAudioProperty.defaultOutputDevice()

        var description = AudioComponentDescription(componentType: kAudioUnitType_Output,
                                                    componentSubType: kAudioUnitSubType_HALOutput,
                                                    componentManufacturer: kAudioUnitManufacturer_Apple,
                                                    componentFlags: 0, componentFlagsMask: 0)
        guard let component = AudioComponentFindNext(nil, &description) else {
            throw MirageError("This Mac has no audio output unit.")
        }
        var created: AudioUnit?
        try check(AudioComponentInstanceNew(component, &created), "open the audio output")
        guard let unit = created else { throw MirageError("Could not open the audio output.") }
        self.unit = unit

        var device = deviceID
        try check(AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0,
                                       &device, UInt32(MemoryLayout<AudioObjectID>.size)), "select the output device")

        var format = AudioStreamBasicDescription(
            mSampleRate: AudioPacketizer.outputRate, mFormatID: kAudioFormatLinearPCM,
            mFormatFlags: kAudioFormatFlagIsSignedInteger | kAudioFormatFlagIsPacked,
            mBytesPerPacket: 4, mFramesPerPacket: 1, mBytesPerFrame: 4,
            mChannelsPerFrame: 2, mBitsPerChannel: 16, mReserved: 0)
        try check(AudioUnitSetProperty(unit, kAudioUnitProperty_StreamFormat, kAudioUnitScope_Input, 0,
                                       &format, UInt32(MemoryLayout<AudioStreamBasicDescription>.size)), "set the playback format")

        var callback = AURenderCallbackStruct(inputProc: playoutRenderCallback,
                                              inputProcRefCon: Unmanaged.passUnretained(buffer).toOpaque())
        try check(AudioUnitSetProperty(unit, kAudioUnitProperty_SetRenderCallback, kAudioUnitScope_Input, 0,
                                       &callback, UInt32(MemoryLayout<AURenderCallbackStruct>.size)), "install the render callback")

        try check(AudioUnitInitialize(unit), "initialise the audio output")
        buffer.setOutputLatency(micros: outputLatencyMicros(unit: unit))
        try check(AudioOutputUnitStart(unit), "start the audio output")
        Log.app("local playout on device \(deviceID), latency \(outputLatencyMicros(unit: unit)) µs")
    }

    func stop() {
        guard let unit else { return }
        AudioOutputUnitStop(unit)
        AudioUnitUninitialize(unit)
        AudioComponentInstanceDispose(unit)
        self.unit = nil
    }

    func updateSyncOffset(ms: Int) { buffer.setSyncOffset(ms: ms) }

    /// Time from the render timestamp to the speaker.
    ///
    /// `mHostTime` in the render timestamp is when the HAL hands the first frame to the
    /// hardware; the IO cycle already runs a buffer plus the safety offset ahead of that
    /// moment, so neither of those is added again. What remains is downstream of it: the
    /// device's own latency (DAC, DSP, Bluetooth link), the stream's, and the unit's resampler.
    private func outputLatencyMicros(unit: AudioUnit) -> Int64 {
        let rate = (try? CoreAudioProperty.get(deviceID, kAudioDevicePropertyNominalSampleRate, Float64(0))) ?? 48_000
        let deviceFrames = (try? CoreAudioProperty.get(deviceID, kAudioDevicePropertyLatency, UInt32(0),
                                                       scope: kAudioObjectPropertyScopeOutput)) ?? 0
        var streamFrames: UInt32 = 0
        if let streams = try? CoreAudioProperty.array(deviceID, kAudioDevicePropertyStreams, AudioObjectID.self,
                                                      scope: kAudioObjectPropertyScopeOutput),
           let first = streams.first {
            streamFrames = (try? CoreAudioProperty.get(first, kAudioStreamPropertyLatency, UInt32(0))) ?? 0
        }
        var unitSeconds: Float64 = 0
        var size = UInt32(MemoryLayout<Float64>.size)
        AudioUnitGetProperty(unit, kAudioUnitProperty_Latency, kAudioUnitScope_Global, 0, &unitSeconds, &size)

        let seconds = Double(deviceFrames + streamFrames) / max(rate, 1) + unitSeconds
        return Int64((seconds * 1_000_000).rounded())
    }

    private func check(_ status: OSStatus, _ what: String) throws {
        guard status == noErr else {
            stop()
            throw MirageError("Could not \(what) for local audio playback (\(CoreAudioProperty.describe(status))).")
        }
    }
}

/// Render callback. Runs on the device's real-time IO thread: no allocation, no locks, no
/// Objective-C, no retain/release on the buffer (it is borrowed through the refcon and
/// outlives the unit).
private let playoutRenderCallback: AURenderCallback = { refCon, _, timeStamp, _, frameCount, ioData in
    guard let ioData else { return noErr }
    let buffers = UnsafeMutableAudioBufferListPointer(ioData)
    guard let first = buffers.first, let data = first.mData else { return noErr }

    let stamp = timeStamp.pointee
    let hostTime = stamp.mFlags.contains(.hostTimeValid) ? stamp.mHostTime : mach_absolute_time()
    let micros = HostClock.micros(hostTime: hostTime)
    let out = data.assumingMemoryBound(to: Int16.self)
    let frames = min(Int(frameCount), Int(first.mDataByteSize) / 4)

    Unmanaged<PlayoutBuffer>.fromOpaque(refCon)._withUnsafeGuaranteedRef {
        $0.render(into: out, frames: frames, renderMicros: micros)
    }
    return noErr
}

/// Small typed wrappers over `AudioObjectGetPropertyData`, shared by the tap and the playout.
enum CoreAudioProperty {
    static func address(_ selector: AudioObjectPropertySelector,
                        scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) -> AudioObjectPropertyAddress {
        AudioObjectPropertyAddress(mSelector: selector, mScope: scope, mElement: kAudioObjectPropertyElementMain)
    }

    static func get<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ initial: T,
                       scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) throws -> T {
        var address = address(selector, scope: scope)
        var value = initial
        var size = UInt32(MemoryLayout<T>.size)
        let status = withUnsafeMutablePointer(to: &value) { AudioObjectGetPropertyData(object, &address, 0, nil, &size, $0) }
        guard status == noErr else { throw MirageError("Core Audio property query failed (\(describe(status))).") }
        return value
    }

    static func array<T>(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector, _ type: T.Type,
                         scope: AudioObjectPropertyScope = kAudioObjectPropertyScopeGlobal) throws -> [T] {
        var address = address(selector, scope: scope)
        var size: UInt32 = 0
        var status = AudioObjectGetPropertyDataSize(object, &address, 0, nil, &size)
        guard status == noErr else { throw MirageError("Core Audio property query failed (\(describe(status))).") }
        let count = Int(size) / MemoryLayout<T>.stride
        guard count > 0 else { return [] }
        let storage = UnsafeMutableRawPointer.allocate(byteCount: Int(size), alignment: MemoryLayout<T>.alignment)
        defer { storage.deallocate() }
        status = AudioObjectGetPropertyData(object, &address, 0, nil, &size, storage)
        guard status == noErr else { throw MirageError("Core Audio property query failed (\(describe(status))).") }
        return Array(UnsafeBufferPointer(start: storage.assumingMemoryBound(to: T.self), count: count))
    }

    static func string(_ object: AudioObjectID, _ selector: AudioObjectPropertySelector) throws -> String {
        var address = address(selector)
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        let status = withUnsafeMutablePointer(to: &value) { AudioObjectGetPropertyData(object, &address, 0, nil, &size, $0) }
        guard status == noErr, let value else { throw MirageError("Core Audio property query failed (\(describe(status))).") }
        return value.takeRetainedValue() as String
    }

    static func defaultOutputDevice() throws -> AudioObjectID {
        let device = try get(AudioObjectID(kAudioObjectSystemObject), kAudioHardwarePropertyDefaultOutputDevice,
                             AudioObjectID(kAudioObjectUnknown))
        guard device != kAudioObjectUnknown else { throw MirageError("This Mac has no audio output device.") }
        return device
    }

    /// OSStatus values in Core Audio are usually four-character codes; show them that way.
    static func describe(_ status: OSStatus) -> String {
        let bytes = withUnsafeBytes(of: UInt32(bitPattern: status).bigEndian) { Array($0) }
        if bytes.allSatisfy({ $0 >= 0x20 && $0 < 0x7F }) {
            return "'" + String(decoding: bytes, as: UTF8.self) + "'"
        }
        return String(status)
    }
}
