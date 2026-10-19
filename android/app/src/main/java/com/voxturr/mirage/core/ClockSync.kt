package com.voxturr.mirage.core

/** NTP-style offset estimate from ping/pong — spec/PROTOCOL.md §8. Thread-safe. */
class ClockSync {
    /** t0 = local ping send, t1 = remote clock in pong, t2 = local pong receive (all µs). */
    fun addSample(t0: Long, t1: Long, t2: Long): Unit = TODO("agent 4")
    /** remote − local, µs. 0 until the first sample. */
    val offsetMicros: Long get() = TODO("agent 4")
    val hasEstimate: Boolean get() = TODO("agent 4")
    val bestRoundTripMicros: Long get() = TODO("agent 4")
    fun remoteToLocal(remoteMicros: Long): Long = TODO("agent 4")
    fun localToRemote(localMicros: Long): Long = TODO("agent 4")
}
