import Foundation

/// Maps Core Audio host times (`mach_absolute_time` ticks, as in `AudioTimeStamp.mHostTime`)
/// onto `MonotonicClock`, the clock every Mirage timestamp and the spec's `hostTimeMicros` use.
///
/// `MonotonicClock` is `DispatchTime.uptimeNanoseconds`, which is the same mach clock scaled
/// by the timebase and shifted by a private base. So the mapping is exact apart from that
/// shift, which is measured once by reading both clocks back to back.
///
/// Call `prepare()` before any real-time thread uses this: the statics initialise lazily, and
/// that first touch must not happen on an audio thread.
enum HostClock {
    struct Mapping {
        var numer: UInt64
        var denom: UInt64
        /// `MonotonicClock` µs minus host-time µs.
        var offsetMicros: Int64

        func micros(hostTime: UInt64) -> Int64 {
            Int64(bitPattern: Self.nanos(hostTime, numer, denom) / 1_000) &+ offsetMicros
        }

        func hostTime(micros: Int64) -> UInt64 {
            let nanos = UInt64(max(0, micros &- offsetMicros)) &* 1_000
            let (high, low) = nanos.multipliedFullWidth(by: denom)
            return numer.dividingFullWidth((high, low)).quotient
        }

        /// Overflow-safe ticks × numer / denom.
        static func nanos(_ ticks: UInt64, _ numer: UInt64, _ denom: UInt64) -> UInt64 {
            if numer == denom { return ticks }
            let (high, low) = ticks.multipliedFullWidth(by: numer)
            return denom.dividingFullWidth((high, low)).quotient
        }
    }

    static let mapping: Mapping = {
        var info = mach_timebase_info_data_t()
        mach_timebase_info(&info)
        let numer = UInt64(info.numer), denom = UInt64(info.denom)

        // Bracket a MonotonicClock read between two mach reads and keep the tightest bracket,
        // so a preemption in the middle of one sample cannot skew the offset.
        _ = MonotonicClock.micros()
        var best = (span: UInt64.max, offset: Int64(0))
        for _ in 0..<16 {
            let before = mach_absolute_time()
            let mono = MonotonicClock.micros()
            let after = mach_absolute_time()
            let span = after &- before
            if span < best.span {
                let mid = before &+ span / 2
                let hostMicros = Int64(bitPattern: Mapping.nanos(mid, numer, denom) / 1_000)
                best = (span, Int64(bitPattern: mono) &- hostMicros)
            }
        }
        return Mapping(numer: numer, denom: denom, offsetMicros: best.offset)
    }()

    static func prepare() { _ = mapping }

    /// Real-time safe once `prepare()` has run.
    static func micros(hostTime: UInt64) -> Int64 { mapping.micros(hostTime: hostTime) }

    static func hostTime(micros: Int64) -> UInt64 { mapping.hostTime(micros: micros) }
}
