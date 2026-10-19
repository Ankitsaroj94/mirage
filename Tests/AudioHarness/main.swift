// Audio pipeline checks that need no permission and no audio hardware:
//
//   1. ring buffer      — SPSC capture ring under a real concurrent producer/consumer
//   2. wire vector      — packet bytes match spec/vectors.json handshake.audioPacket exactly
//   3. s16              — float → s16 scaling, rounding, clipping
//   4. packetizer       — 48 kHz pass-through is bit-exact, 44.1 kHz resamples, timestamps hold
//   5. host clock       — mach host time ↔ MonotonicClock mapping
//   6. scheduler        — a sample captured at T plays at T + delay + offset − latency (fake clock)
//   7. UDP loopback     — AudioSender → local socket → parse, clear and encrypted
//
//   --live  also taps the real system audio for two seconds (asks for System Audio Recording).
import Foundation
import CryptoKit
import Network

var failures = 0
func expect(_ condition: Bool, _ message: @autoclosure () -> String) {
    if !condition { failures += 1; print("  FAIL: \(message())") }
}
func section(_ name: String) { print("• \(name)") }

func hexData(_ s: String) -> Data {
    var data = Data()
    var i = s.startIndex
    while i < s.endIndex {
        let j = s.index(i, offsetBy: 2)
        data.append(UInt8(s[i..<j], radix: 16)!)
        i = j
    }
    return data
}
func hex(_ d: Data) -> String { d.map { String(format: "%02x", $0) }.joined() }

// MARK: - 1. Ring buffer

section("ring buffer: concurrent producer/consumer")
do {
    let ring = CaptureRing(capacityFrames: 1 << 10, anchorCapacity: 64)
    let total = 2_000_000
    let done = DispatchSemaphore(value: 0)
    var consumerErrors = 0, received = 0, anchorsSeen = 0, anchorErrors = 0

    // Frame k carries (k mod 2^20, -(k mod 2^20)); every chunk's anchor says "frame N at N µs".
    Thread {
        let chunk = UnsafeMutablePointer<Float>.allocate(capacity: 600 * 2)
        var next = 0
        var rng = SystemRandomNumberGenerator()
        while next < total {
            let n = min(Int.random(in: 1...600, using: &rng), total - next)
            for i in 0..<n {
                let v = Float((next + i) & 0xFFFFF)
                chunk[i * 2] = v; chunk[i * 2 + 1] = -v
            }
            var offset = 0
            while offset < n {
                let wrote = ring.write(left: chunk + offset * 2, right: chunk + offset * 2 + 1, stride: 2,
                                       count: n - offset, hostMicros: UInt64(next + offset))
                offset += wrote
                if wrote == 0 { sched_yield() }
            }
            next += n
        }
        chunk.deallocate()
    }.start()

    Thread {
        let out = UnsafeMutablePointer<Float>.allocate(capacity: 333 * 2)
        while received < total {
            while let anchor = ring.popAnchor() {
                anchorsSeen += 1
                if anchor.frame != anchor.micros { anchorErrors += 1 }
            }
            let (count, first) = ring.read(into: out, maxFrames: 333)
            if count == 0 { sched_yield(); continue }
            if first != UInt64(received) { consumerErrors += 1 }
            for i in 0..<count {
                let v = Float((received + i) & 0xFFFFF)
                if out[i * 2] != v || out[i * 2 + 1] != -v { consumerErrors += 1 }
            }
            received += count
        }
        out.deallocate()
        done.signal()
    }.start()

    let ok = done.wait(timeout: .now() + 30) == .success
    expect(ok, "consumer did not finish (deadlock?)")
    expect(consumerErrors == 0, "\(consumerErrors) out-of-order or corrupt frames")
    expect(anchorErrors == 0, "\(anchorErrors) anchors with wrong frame/time pairing")
    expect(anchorsSeen > 0, "no anchors delivered")
    print("  \(received) frames, \(anchorsSeen) anchors, overflow retries absorbed")
}

section("ring buffer: playout packets across threads")
do {
    let buffer = PlayoutBuffer(capacityPackets: 8, playoutDelayMs: 0, syncOffsetMs: 0)
    let packets = 20_000
    let done = DispatchSemaphore(value: 0)
    var errors = 0, got = 0
    Thread {
        var pcm = [Int16](repeating: 0, count: 480)
        for p in 0..<packets {
            for i in 0..<480 { pcm[i] = Int16(truncatingIfNeeded: p &* 7 &+ i) }
            while !pcm.withUnsafeBufferPointer({ buffer.enqueue(hostTimeMicros: UInt64(p) * 5_000, pcm: $0) }) {
                sched_yield()
            }
        }
    }.start()
    Thread {
        // Render exactly on schedule (fake clock), 240 frames at a time: output must be the
        // packets in order with nothing skipped or invented.
        let out = UnsafeMutablePointer<Int16>.allocate(capacity: 480)
        var p = 0
        while p < packets {
            // Latency 0 and delay 0 still leave the 5 ms minimum shift, hence the + 5 ms.
            let underrunsBefore = buffer.underruns
            buffer.render(into: out, frames: 240, renderMicros: Int64(p) * 5_000 + 5_000)
            if buffer.underruns != underrunsBefore {
                sched_yield()                         // producer behind: same slot again
                continue
            }
            for i in 0..<480 where out[i] != Int16(truncatingIfNeeded: p &* 7 &+ i) { errors += 1 }
            p += 1; got += 1
        }
        out.deallocate()
        done.signal()
    }.start()
    let ok = done.wait(timeout: .now() + 30) == .success
    expect(ok, "playout consumer did not finish")
    expect(errors == 0, "\(errors) wrong samples in playout ring")
    print("  \(got) packets through an 8-slot ring")
}

// MARK: - 2. Wire vector

section("packet bytes vs spec/vectors.json")
do {
    let root = URL(fileURLWithPath: CommandLine.arguments[0]).deletingLastPathComponent()
    let candidates = [URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("spec/vectors.json"),
                      root.appendingPathComponent("../../spec/vectors.json")]
    guard let url = candidates.first(where: { FileManager.default.fileExists(atPath: $0.path) }),
          let json = try? JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
          let handshake = json["handshake"] as? [String: Any],
          let vector = handshake["audioPacket"] as? [String: Any],
          let keyHex = handshake["sessionKeyHex"] as? String else {
        expect(false, "spec/vectors.json not found (run from the repo root)")
        exit(1)
    }
    let key = SymmetricKey(data: hexData(keyHex))
    let seq = UInt32(vector["seq"] as! Int)
    let time = UInt64(vector["hostTimeMicros"] as! String)!
    let pcm = hexData(vector["pcmHex"] as! String)

    let header = AudioPacket.header(seq: seq, hostTimeMicros: time, encrypted: true)
    expect(hex(header) == vector["headerHex"] as! String, "header \(hex(header)) != \(vector["headerHex"]!)")

    let wire = try! AudioPacket.make(seq: seq, hostTimeMicros: time, pcm: pcm, key: key)
    expect(hex(wire) == vector["wireHex"] as! String, "wire bytes differ:\n    got  \(hex(wire))\n    want \(vector["wireHex"]!)")

    let parsed = try? AudioPacket.parse(wire, key: key)
    expect(parsed?.pcm == pcm && parsed?.seq == seq && parsed?.hostTimeMicros == time && parsed?.encrypted == true,
           "encrypted packet does not round-trip")

    var tampered = wire; tampered[8] ^= 1                      // timestamp is authenticated
    expect((try? AudioPacket.parse(tampered, key: key)) == nil, "tampered header still opened")
    expect((try? AudioPacket.parse(wire, key: SymmetricKey(size: .bits256))) == nil, "wrong key still opened")

    let clear = try! AudioPacket.make(seq: seq, hostTimeMicros: time, pcm: pcm, key: nil)
    expect(hex(clear) == "0100000007000000404b4c0000000000" + hex(pcm), "clear packet \(hex(clear))")
    print("  wire \(hex(wire).prefix(32))… matches")
}

// MARK: - 3. s16

section("float → s16")
do {
    let cases: [(Float, Int16)] = [(0, 0), (1, 32767), (-1, -32767), (0.5, 16384), (-0.5, -16384),
                                   (1.5, 32767), (-2, -32768), (.infinity, 32767), (-.infinity, -32768),
                                   (.nan, 0), (1e-6, 0), (0.00002, 1)]
    for (x, want) in cases {
        let got = AudioPacket.s16(x)
        expect(got == want, "s16(\(x)) = \(got), want \(want)")
    }
}

// MARK: - 4. Packetizer

section("packetizer: 48 kHz pass-through")
do {
    let p = AudioPacketizer(sourceRate: 48_000)
    var packets: [(UInt64, [Int16])] = []
    p.onPacket = { t, pcm in packets.append((t, Array(pcm))) }
    p.addAnchor(frame: 1_000, micros: 2_000_000)
    let frames = 48_000
    var src = [Float](repeating: 0, count: frames * 2)
    for i in 0..<frames {
        src[i * 2] = Float(i % 2000 - 1000) / 32767
        src[i * 2 + 1] = -Float(i % 2000 - 1000) / 32767
    }
    // Feed in uneven chunks, the way IOProc buffers arrive.
    var offset = 0
    while offset < frames {
        let n = min(Int.random(in: 1...700), frames - offset)
        src.withUnsafeBufferPointer { p.process($0.baseAddress! + offset * 2, frames: n, firstFrame: 1_000 + UInt64(offset)) }
        offset += n
    }
    expect(packets.count >= 199 && packets.count <= 200, "\(packets.count) packets from 1 s")
    var exact = true, timesOK = true
    for (k, (t, pcm)) in packets.enumerated() {
        if t != 2_000_000 + UInt64(k) * 5_000 { timesOK = false }
        for f in 0..<240 {
            let i = k * 240 + f
            let v = Int16(i % 2000 - 1000)
            if pcm[f * 2] != v || pcm[f * 2 + 1] != -v { exact = false }
        }
    }
    expect(exact, "48 kHz samples were altered")
    expect(timesOK, "packet times are not 5 ms apart from the anchor")
}

section("packetizer: 44.1 kHz → 48 kHz")
do {
    let p = AudioPacketizer(sourceRate: 44_100)
    var packets: [(UInt64, [Int16])] = []
    p.onPacket = { t, pcm in packets.append((t, Array(pcm))) }
    p.addAnchor(frame: 0, micros: 10_000_000)
    let frames = 44_100
    let freq = 997.0
    var src = [Float](repeating: 0, count: frames * 2)
    for i in 0..<frames {
        let v = Float(0.5 * sin(2 * Double.pi * freq * Double(i) / 44_100))
        src[i * 2] = v; src[i * 2 + 1] = v
    }
    src.withUnsafeBufferPointer { p.process($0.baseAddress!, frames: frames, firstFrame: 0) }
    expect(packets.count >= 199 && packets.count <= 200, "\(packets.count) packets from 1 s at 44.1 kHz")
    var maxTimeError = 0.0, maxSampleError = 0.0
    for (k, (t, pcm)) in packets.enumerated() {
        maxTimeError = max(maxTimeError, abs(Double(t) - (10_000_000 + Double(k) * 5_000)))
        for f in 0..<240 {
            let seconds = Double(k * 240 + f) / 48_000
            let want = 0.5 * sin(2 * Double.pi * freq * seconds) * 32767
            maxSampleError = max(maxSampleError, abs(Double(pcm[f * 2]) - want))
        }
    }
    expect(maxTimeError <= 1, "timestamps off by \(maxTimeError) µs")
    // Catmull-Rom on a 1 kHz tone at 44.1 kHz: error well under 0.2 % of full scale.
    expect(maxSampleError < 60, "resampled sine off by \(maxSampleError) LSB")
    print("  max time error \(maxTimeError) µs, max sample error \(Int(maxSampleError)) LSB")
}

section("packetizer: a capture gap shows up in the timestamps")
do {
    let p = AudioPacketizer(sourceRate: 48_000)
    var times: [UInt64] = []
    p.onPacket = { t, _ in times.append(t) }
    let zeros = [Float](repeating: 0, count: 480 * 2)
    p.addAnchor(frame: 0, micros: 0)
    zeros.withUnsafeBufferPointer { p.process($0.baseAddress!, frames: 480, firstFrame: 0) }
    // Next frames (index 480 on) were captured 50 ms later — the ring dropped what was between.
    p.addAnchor(frame: 480, micros: 60_000)
    zeros.withUnsafeBufferPointer { p.process($0.baseAddress!, frames: 480, firstFrame: 480) }
    expect(times.prefix(3) == [0, 5_000, 60_000], "times \(times)")
}

// MARK: - 5. Host clock

section("host clock mapping")
do {
    let m = HostClock.Mapping(numer: 125, denom: 3, offsetMicros: -7_000)
    expect(m.micros(hostTime: 24_000_000) == 1_000_000 - 7_000, "24 M ticks → \(m.micros(hostTime: 24_000_000))")
    let ticks: UInt64 = 123_456_789_012
    expect(abs(Int64(m.hostTime(micros: m.micros(hostTime: ticks))) - Int64(ticks)) <= 24, "round trip drifted")
    let huge: UInt64 = 1 << 58                      // ~380 years of uptime: ticks × numer overflows 64 bits
    expect(m.micros(hostTime: huge) > 0, "overflow on large host time")

    HostClock.prepare()
    let live = HostClock.micros(hostTime: mach_absolute_time())
    let mono = Int64(MonotonicClock.micros())
    expect(abs(live - mono) < 200, "host clock and MonotonicClock disagree by \(live - mono) µs")
}

// MARK: - 6. Scheduler

section("playout scheduler (fake clock)")
do {
    expect(PlayoutBuffer.targetCaptureMicros(renderMicros: 1_000_000, outputLatencyMicros: 10_000,
                                             playoutDelayMicros: 60_000, syncOffsetMicros: 5_000) == 945_000,
           "target capture formula")

    let delay = 60, offset = 5
    let latency: Int64 = 10_000
    let base: UInt64 = 5_000_000
    func makeBuffer() -> PlayoutBuffer {
        let b = PlayoutBuffer(capacityPackets: 64, playoutDelayMs: delay, syncOffsetMs: offset, outputLatencyMicros: latency)
        var pcm = [Int16](repeating: 0, count: 480)
        for k in 0..<40 {
            // Sample value = 1 + global frame index, so the output says which frame played.
            for f in 0..<240 { pcm[f * 2] = Int16(1 + k * 240 + f); pcm[f * 2 + 1] = pcm[f * 2] }
            pcm.withUnsafeBufferPointer { _ = b.enqueue(hostTimeMicros: base + UInt64(k) * 5_000, pcm: $0) }
        }
        return b
    }
    let out = UnsafeMutablePointer<Int16>.allocate(capacity: 4096)
    defer { out.deallocate() }

    // Captured at T → plays at T + delay + offset; the render that reaches the speaker then is
    // the one with renderTime = T + delay + offset − latency.
    let onTime = Int64(base) + Int64(delay + offset) * 1_000 - latency
    var b = makeBuffer()
    b.render(into: out, frames: 512, renderMicros: onTime)
    expect(out[0] == 1 && out[511 * 2] == 512, "on-time render starts at frame \(out[0]), ends \(out[511 * 2])")
    // The next render continues seamlessly.
    b.render(into: out, frames: 256, renderMicros: onTime + Int64((512.0 * 1_000_000 / 48_000).rounded()))
    expect(out[0] == 513 && out[255 * 2] == 768, "continuation starts at \(out[0])")

    // 10 ms early: 480 frames of silence, then frame 1.
    b = makeBuffer()
    b.render(into: out, frames: 1024, renderMicros: onTime - 10_000)
    expect(out[479 * 2] == 0 && out[480 * 2] == 1, "early render: silence then frame 1 (got \(out[479 * 2]), \(out[480 * 2]))")

    // 20 ms late: frames 1…960 are skipped, playback starts at frame 961.
    b = makeBuffer()
    b.render(into: out, frames: 256, renderMicros: onTime + 20_000)
    expect(out[0] == 961, "late render starts at frame \(out[0]), want 961")
    expect(b.droppedFrames == 960, "dropped \(b.droppedFrames)")

    // Sync offset is live: +5 ms more means the same render time plays 240 frames earlier audio.
    b = makeBuffer()
    b.setSyncOffset(ms: offset + 5)
    b.render(into: out, frames: 256, renderMicros: onTime + 5_000)
    expect(out[0] == 1, "after +5 ms offset, render at +5 ms starts at frame \(out[0])")

    // Jitter below the threshold does not cause jumps.
    b = makeBuffer()
    b.render(into: out, frames: 240, renderMicros: onTime)
    b.render(into: out, frames: 240, renderMicros: onTime + 5_000 + 400)
    expect(out[0] == 241, "0.4 ms jitter caused a jump to frame \(out[0])")

    // Underrun: an empty buffer renders silence and does not block.
    let empty = PlayoutBuffer(playoutDelayMs: delay, syncOffsetMs: 0)
    out.update(repeating: 7, count: 512)
    empty.render(into: out, frames: 256, renderMicros: 0)
    expect(out[0] == 0 && out[511] == 0 && empty.underruns == 1, "underrun was not silence")

    // A device slower than the whole budget still plays, as early as it can.
    let slow = PlayoutBuffer(playoutDelayMs: 20, syncOffsetMs: 0, outputLatencyMicros: 150_000)
    let pcm = [Int16](repeating: 3, count: 480)
    pcm.withUnsafeBufferPointer { _ = slow.enqueue(hostTimeMicros: 1_000_000, pcm: $0) }
    slow.render(into: out, frames: 240, renderMicros: 1_005_000)
    expect(out[0] == 3, "slow device never plays")
}

// MARK: - 7. UDP loopback

section("UDP loopback")
func loopback(key: SymmetricKey?) {
    let fd = socket(AF_INET, SOCK_DGRAM, 0)
    defer { close(fd) }
    var addr = sockaddr_in()
    addr.sin_family = sa_family_t(AF_INET)
    addr.sin_addr.s_addr = inet_addr("127.0.0.1")
    addr.sin_port = 0
    _ = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
    var len = socklen_t(MemoryLayout<sockaddr_in>.size)
    _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
    let port = UInt16(bigEndian: addr.sin_port)
    var timeout = timeval(tv_sec: 2, tv_usec: 0)
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

    let sender = AudioSender(host: "127.0.0.1", port: port, key: key)
    sender.start()
    let count = 50
    var pcm = [Int16](repeating: 0, count: 480)
    for k in 0..<count {
        for i in 0..<480 { pcm[i] = Int16(k * 100 + i % 100) }
        pcm.withUnsafeBufferPointer { sender.send(hostTimeMicros: 1_000 + UInt64(k) * 5_000, pcm: $0) }
    }

    var buffer = [UInt8](repeating: 0, count: 2048)
    var received = 0, bad = 0
    while received < count {
        let n = recv(fd, &buffer, buffer.count, 0)
        if n <= 0 { break }
        let data = Data(buffer[0..<n])
        guard let packet = try? AudioPacket.parse(data, key: key) else { bad += 1; continue }
        let k = Int(packet.seq)
        let samples = packet.pcm.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
        if n != 16 + 960 + (key == nil ? 0 : 16) || packet.encrypted != (key != nil)
            || packet.hostTimeMicros != 1_000 + UInt64(k) * 5_000
            || samples.count != 480 || samples[0] != Int16(k * 100) || samples[479] != Int16(k * 100 + 79) {
            bad += 1
        }
        received += 1
    }
    sender.stop()
    expect(received == count, "\(key == nil ? "clear" : "encrypted"): received \(received)/\(count)")
    expect(bad == 0, "\(bad) bad packets")
    print("  \(key == nil ? "clear" : "encrypted"): \(received) packets, \(16 + 960 + (key == nil ? 0 : 16)) bytes each")
}
loopback(key: nil)
loopback(key: SymmetricKey(size: .bits256))

// MARK: - Live

if CommandLine.arguments.contains("--live") {
    section("live system audio tap (2 s, play something)")
    if !AudioStreamer.isSupported {
        print("  skipped: needs macOS 14.2")
    } else {
        let fd = socket(AF_INET, SOCK_DGRAM, 0)
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET); addr.sin_addr.s_addr = inet_addr("127.0.0.1")
        _ = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        _ = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
        var timeout = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))

        let mode: AudioMode = CommandLine.arguments.contains("--both") ? .both : .tabletOnly
        let streamer = AudioStreamer(mode: mode, playoutDelayMs: 60, syncOffsetMs: 0)
        do {
            let config = try streamer.start(host: "127.0.0.1", port: UInt16(bigEndian: addr.sin_port), sessionKey: nil)
            print("  config \(config)")
            var packets = 0, loud = 0, lastSeq: UInt32?, gaps = 0, maxAgeMs = 0.0
            var buffer = [UInt8](repeating: 0, count: 2048)
            let end = Date().addingTimeInterval(2)
            while Date() < end {
                let n = recv(fd, &buffer, buffer.count, 0)
                guard n > 0, let p = try? AudioPacket.parse(Data(buffer[0..<n]), key: nil) else { continue }
                packets += 1
                if let lastSeq, p.seq != lastSeq &+ 1 { gaps += 1 }
                lastSeq = p.seq
                maxAgeMs = max(maxAgeMs, Double(Int64(MonotonicClock.micros()) - Int64(p.hostTimeMicros)) / 1000)
                if p.pcm.withUnsafeBytes({ $0.bindMemory(to: Int16.self).contains { abs(Int32($0)) > 64 } }) { loud += 1 }
            }
            streamer.stop()
            print("  \(packets) packets (\(loud) non-silent), \(gaps) seq gaps, capture→socket age ≤ \(String(format: "%.1f", maxAgeMs)) ms")
            expect(packets > 300, "only \(packets) packets in 2 s")
            expect(gaps == 0, "\(gaps) sequence gaps")
        } catch {
            expect(false, "live start failed: \(error.localizedDescription)")
        }
        close(fd)
    }
}

if failures == 0 {
    print("AUDIO OK")
    exit(0)
} else {
    print("AUDIO FAILED (\(failures))")
    exit(1)
}
