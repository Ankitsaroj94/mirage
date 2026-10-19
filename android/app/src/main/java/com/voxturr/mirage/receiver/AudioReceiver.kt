package com.voxturr.mirage.receiver

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.voxturr.mirage.core.AudioConfig
import com.voxturr.mirage.core.SecureChannel
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Plays the host's audio in sync with the host's own speakers (spec §7).
 *
 * The UDP socket is bound for the whole receiver lifetime (its port goes into ClientHello);
 * playback starts on audioConfig and stops on audioStop / disconnect.
 *  - "mirage-audio-rx" (URGENT_AUDIO): receive → (decrypt) → [JitterBuffer] keyed by seq.
 *  - "mirage-audio-out" (URGENT_AUDIO): for each packet, computes when the next written frame
 *    will reach the speaker (AudioTrack.getTimestamp) vs. when the packet should play
 *    (hostTime + playoutDelay mapped through ClockSync), lets [PlayoutController] choose
 *    silence / trim / drop / resample, and writes to a LOW_LATENCY AudioTrack (blocking writes pace the loop).
 */
class AudioReceiver {

    /** Where buffer depth / sync error are reported (the current session's stats). */
    @Volatile var stats: ReceiverStats = ReceiverStats()

    /** Host clock → this device; supplied per session (PeerLink.clock in production). */
    interface HostClock {
        val ready: Boolean
        /** Host monotonic µs → local System.nanoTime() ns. */
        fun hostMicrosToLocalNanos(hostMicros: Long): Long
    }

    private val socket = DatagramSocket(null as java.net.SocketAddress?).apply {
        reuseAddress = true
        try { trafficClass = 0xB8 } catch (_: Exception) {}
        receiveBufferSize = 256 * 1024
        bind(InetSocketAddress(0))
    }
    val port: Int = socket.localPort

    private val jitter = JitterBuffer()

    @Volatile private var config: AudioConfig? = null
    @Volatile private var clock: HostClock? = null
    @Volatile private var channel: (() -> SecureChannel?)? = null
    @Volatile private var source: InetAddress? = null
    @Volatile private var generation = 0
    @Volatile private var closed = false
    private var writer: Thread? = null
    private val writerLock = Object()

    // Diagnostics, logged every 5 s while playing: where packets go missing is otherwise invisible.
    @Volatile private var rxPackets = 0
    @Volatile private var rxRejected = 0
    @Volatile private var rxJitterRefused = 0
    @Volatile private var rxReordered = 0
    @Volatile private var rxDuplicate = 0
    @Volatile private var rxGapPackets = 0
    private var maxSeenSeq = -1L
    private val arrivalAges = DoubleArray(1000)
    @Volatile private var arrivalCount = 0
    @Volatile private var lastSeq = -1L
    @Volatile private var rxWrongSource = 0
    @Volatile private var playedPackets = 0
    @Volatile private var silencePackets = 0

    private val rxThread = Thread({ receiveLoop() }, "mirage-audio-rx").apply { isDaemon = true; start() }

    /** Starts (or restarts) playback. [source] = the host's address; packets from elsewhere are ignored. */
    fun start(cfg: AudioConfig, hostClock: HostClock, source: InetAddress?, channel: () -> SecureChannel?) = synchronized(writerLock) {
        stopPlaybackLocked()
        this.clock = hostClock
        this.channel = channel
        this.source = source
        jitter.clear()
        this.config = cfg
        val gen = ++generation
        writer = Thread({ writeLoop(cfg, hostClock, gen) }, "mirage-audio-out").apply { start() }
        Log.i(TAG, "audio start: ${cfg.sampleRate} Hz ×${cfg.channels}, ${cfg.packetFrames} frames/packet, delay ${cfg.playoutDelayMs} ms, enc=${cfg.encrypted}")
    }

    fun stopPlayback() = synchronized(writerLock) { stopPlaybackLocked() }

    private fun stopPlaybackLocked() {
        config = null
        generation++
        writer?.let { it.interrupt(); it.join(500) }
        writer = null
        jitter.clear()
        stats.audioBufferedMs = 0.0
    }

    fun close() {
        stopPlayback()
        closed = true
        socket.close()
    }

    // ---------------------------------------------------------------------------------------
    // Receive
    // ---------------------------------------------------------------------------------------

    private fun receiveLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = ByteArray(8192)
        val packet = DatagramPacket(buf, buf.size)
        val header = ByteArray(AudioPacket.HEADER_BYTES) // reused for the encrypted path's AAD
        while (!closed) {
            try {
                packet.setData(buf, 0, buf.size)
                socket.receive(packet)
            } catch (e: Exception) {
                if (closed) return
                continue
            }
            rxPackets++
            val cfg = config ?: continue
            val len = packet.length
            if (len <= AudioPacket.HEADER_BYTES || AudioPacket.version(buf) != AudioPacket.VERSION) { rxRejected++; continue }
            val src = source
            if (src != null && packet.address != src) { rxWrongSource++; continue }
            val seq = AudioPacket.seq(buf)
            val hostTime = AudioPacket.hostTimeMicros(buf)
            clock?.takeIf { it.ready }?.let { c ->
                val ageMs = (System.nanoTime() - c.hostMicrosToLocalNanos(hostTime)) / 1e6
                if (arrivalCount < arrivalAges.size) arrivalAges[arrivalCount++] = ageMs
            }
            if (seq < maxSeenSeq) rxReordered++
            if (seq == maxSeenSeq) rxDuplicate++
            if (seq > maxSeenSeq) { if (maxSeenSeq >= 0 && seq > maxSeenSeq + 1) rxGapPackets += (seq - maxSeenSeq - 1).toInt(); maxSeenSeq = seq }
            if (AudioPacket.flags(buf) and AudioPacket.FLAG_ENCRYPTED != 0) {
                val ch = channel?.invoke() ?: continue
                System.arraycopy(buf, 0, header, 0, AudioPacket.HEADER_BYTES)
                val sealed = buf.copyOfRange(AudioPacket.HEADER_BYTES, len)
                val pcm = try { ch.openAudio(seq, header, sealed) } catch (e: Exception) { continue }
                jitter.put(seq, hostTime, pcm, 0, pcm.size)
            } else if (!cfg.encrypted) {
                if (!jitter.put(seq, hostTime, buf, AudioPacket.HEADER_BYTES, len - AudioPacket.HEADER_BYTES)) rxJitterRefused++
                else lastSeq = seq
            } else rxRejected++
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playout
    // ---------------------------------------------------------------------------------------

    private fun writeLoop(cfg: AudioConfig, hostClock: HostClock, gen: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val sr = cfg.sampleRate
        val ch = cfg.channels.coerceIn(1, 2)
        val channelMask = if (ch == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBuf = AudioTrack.getMinBufferSize(sr, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val track = try {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build())
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack creation failed", e)
            return
        }
        // Samsung did not grant the fast track here: getMinBufferSize was 80 ms and the track
        // came up holding 160 ms, which alone blew the whole playout budget (measured 175 ms
        // to the speaker). The blocking writes keep that buffer full, so its size *is* our
        // output latency — cut it to two mixer bursts.
        val burst = (track.bufferSizeInFrames / 8).coerceAtLeast(MIN_BURST_FRAMES)
        try { track.setBufferSizeInFrames(burst * 2) } catch (_: Exception) {}

        val packetFrames = cfg.packetFrames.coerceAtLeast(1)
        val packetUs = packetFrames * 1_000_000L / sr
        val delayUs = cfg.playoutDelayMs * 1000L
        val pcm = ShortArray(4096)
        val resampler = LinearResampler(ch)
        val out = ShortArray(resampler.maxOutFrames(4096 / ch, 1.0 - PlayoutController.MAX_DEVIATION) * ch)
        val silence = ShortArray(sr / 100 * ch) // 10 ms
        val controller = PlayoutController(sr)
        val ts = AudioTimestamp()
        var written = 0L
        var nextSeq = -1L
        var lastHostTime = 0L
        val assumedLatencyNs = 20_000_000L

        fun write(buf: ShortArray, frames: Int): Boolean {
            var off = 0
            val total = frames * ch
            while (off < total) {
                val n = track.write(buf, off, total - off, AudioTrack.WRITE_BLOCKING)
                if (n <= 0) return false
                off += n
            }
            written += frames
            return true
        }
        fun writeSilence(frames: Int): Boolean {
            var left = frames
            while (left > 0) {
                val n = minOf(left, silence.size / ch)
                if (!write(silence, n)) return false
                left -= n
            }
            return true
        }
        fun nextOutNanos(): Long =
            if (track.getTimestamp(ts) && ts.nanoTime > 0) {
                SpeakerClock.nextFrameNanos(ts.framePosition, ts.nanoTime, written, sr)
            } else {
                SpeakerClock.estimateNanos(System.nanoTime(), track.playbackHeadPosition.toLong() and 0xffffffffL,
                    written, sr, assumedLatencyNs)
            }

        var lastReport = System.nanoTime()
        try {
            track.play()
            writeSilence(packetFrames) // prime
            while (gen == generation && !Thread.currentThread().isInterrupted) {
                if (System.nanoTime() - lastReport > 5_000_000_000L) {
                    lastReport = System.nanoTime()
                    val n = arrivalCount
                    if (n > 10) {
                        val a = arrivalAges.copyOf(n).sorted()
                        val p99 = a[n * 99 / 100]
                        val outMs = (nextOutNanos() - System.nanoTime()) / 1e6
                        // Every packet must be here before it is written, i.e. one output latency
                        // ahead of its play time. Margin covers the next window being a bit worse.
                        val needed = ((p99 + outMs + NEEDED_MARGIN_MS) / 10).toInt() * 10 + 10
                        stats.audioDelayNeededMs = needed
                        Log.i(TAG, "audio arrival age (capture→here): p50=${"%.1f".format(a[n / 2])} p90=${"%.1f".format(a[n * 9 / 10])} " +
                            "p99=${"%.1f".format(p99)} max=${"%.1f".format(a[n - 1])} ms (n=$n), output ${"%.1f".format(outMs)} ms → need ${needed} ms")
                    }
                    arrivalCount = 0
                    Log.i(TAG, "audio: rx=$rxPackets rejected=$rxRejected wrongSource=$rxWrongSource " +
                        "reordered=$rxReordered dup=$rxDuplicate gaps=$rxGapPackets clockReady=${hostClock.ready} refused=$rxJitterRefused lastSeq=$lastSeq newest=${jitter.newestSeq} jitter=${jitter.size} played=$playedPackets " +
                        "silence=$silencePackets error=${"%.1f".format(stats.audioErrorMs)}ms " +
                        "trackBuf=${track.bufferSizeInFrames}/${track.bufferCapacityInFrames} minBuf=${minBuf / 4} " +
                        "outLatency=${"%.1f".format((nextOutNanos() - System.nanoTime()) / 1e6)}ms")
                }
                // Nothing to play yet (stream not started, gap, or no clock estimate): keep the
                // track running on silence so restarting costs no extra latency.
                if (nextSeq < 0) {
                    if (!hostClock.ready || jitter.size == 0) {
                        if (!hostClock.ready) jitter.discardThrough(jitter.newestSeq)
                        silencePackets++
                        if (!writeSilence(packetFrames)) break
                        continue
                    }
                    nextSeq = jitter.oldestSeq()
                    if (nextSeq < 0) continue
                    lastHostTime = jitter.hostTimeOf(nextSeq, 0)
                    controller.reset(); resampler.reset()
                }
                // Stream went quiet for a while: drop back to idle and relock when it resumes.
                if (jitter.size == 0 && jitter.newestSeq < nextSeq - IDLE_PACKETS) {
                    nextSeq = -1
                    continue
                }

                val hostTime = jitter.hostTimeOf(nextSeq, lastHostTime)
                val target = hostClock.hostMicrosToLocalNanos(hostTime + delayUs)
                val error = nextOutNanos() - target
                stats.audioErrorMs = error / 1e6
                stats.audioBufferedMs = jitter.size * packetUs / 1000.0
                val d = controller.decide(error, packetFrames)
                when (d.action) {
                    PlayoutController.Action.SILENCE -> {
                        if (!writeSilence(minOf(d.frames, sr / 50))) break // ≤20 ms at a time, then re-check
                    }
                    PlayoutController.Action.DROP -> {
                        // Dropping only helps if something newer is already here. Dropping the
                        // newest packet when the whole path is slower than the playout delay just
                        // spins, discarding everything — play late instead of not at all.
                        if (jitter.newestSeq <= nextSeq) {
                            controller.acceptLate(error)
                            continue
                        }
                        jitter.discardThrough(nextSeq)
                        nextSeq++; lastHostTime = hostTime + packetUs
                    }
                    PlayoutController.Action.TRIM, PlayoutController.Action.PLAY -> {
                        val n = jitter.take(nextSeq, pcm)
                        nextSeq++; lastHostTime = hostTime + packetUs
                        if (n < 0) {
                            // Lost or late: conceal with silence for its slot, keep the timeline.
                            if (!writeSilence(packetFrames)) break
                            continue
                        }
                        playedPackets++
                        val frames = n / ch
                        if (d.action == PlayoutController.Action.TRIM) {
                            val skip = d.frames.coerceAtMost(frames)
                            if (skip < frames) {
                                System.arraycopy(pcm, skip * ch, pcm, 0, (frames - skip) * ch)
                                if (!write(pcm, frames - skip)) break
                            }
                            resampler.reset()
                        } else {
                            // Always through the resampler (exact copy at ratio 1) so its phase
                            // stays continuous when the controller moves on and off 1.0.
                            val o = resampler.process(pcm, frames, d.ratio, out)
                            if (!write(out, o)) break
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "audio playout stopped", e)
        } finally {
            try { track.pause(); track.flush() } catch (_: Exception) {}
            track.release()
        }
    }

    private companion object {
        /** One HAL mixer period on the Tab S9 FE (960 frames = 20 ms at 48 kHz). */
        const val MIN_BURST_FRAMES = 960
        const val NEEDED_MARGIN_MS = 15.0
        const val TAG = "Mirage"
        /** ~100 ms of 5 ms packets with nothing arriving → idle. */
        const val IDLE_PACKETS = 20
    }
}
